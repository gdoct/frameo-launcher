package app.framelauncher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URL
import java.util.Random
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** A photo to show. [url] is null for photos that only exist in the offline cache. */
class Photo(val key: String, val url: String?)

interface PhotoSource {
    /** Cache key prefix, so each source can fall back to its own cached photos. */
    val prefix: String
    /** The next few photos. Throws IOException when the source can't be reached. */
    fun batch(width: Int, height: Int): List<Photo>
}

/** The NAS photo service (slideshow/frameo-slideshow): GET <base>/api/next. */
class NasSource(base: String) : PhotoSource {
    private val base = base.trim().trimEnd('/') + "/"
    override val prefix = "nas-"

    override fun batch(width: Int, height: Int): List<Photo> {
        val arr = JSONObject(Net.getString("${base}api/next?count=12")).getJSONArray("photos")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Photo(prefix + o.getString("id"), URL(URL(base), o.getString("url")).toString())
        }
    }
}

/** Random photos from picsum.photos at screen size. */
class PicsumSource : PhotoSource {
    private val rnd = Random()
    override val prefix = "picsum-"

    override fun batch(width: Int, height: Int) = List(12) {
        val seed = rnd.nextInt(1_000_000)
        Photo("$prefix$seed-${width}x$height", "https://picsum.photos/seed/$seed/$width/$height")
    }
}

/** Downloaded photos on disk; least recently used beyond [max] files are deleted. */
class PhotoCache(private val dir: File, private val max: Int = 200) {
    init { dir.mkdirs() }

    private fun file(key: String) = File(dir, key.replace(Regex("[^A-Za-z0-9._-]"), "_"))

    fun get(key: String): File? = file(key).takeIf { it.isFile && it.length() > 0 }
        ?.also { it.setLastModified(System.currentTimeMillis()) }

    fun put(key: String, bytes: ByteArray): File {
        val f = file(key)
        val tmp = File(dir, f.name + ".part")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { tmp.delete(); throw IOException("cannot write ${f.name}") }
        trim()
        return f
    }

    fun remove(key: String) { file(key).delete() }

    fun keys(prefix: String): List<String> =
        dir.listFiles()?.map { it.name }?.filter { it.startsWith(prefix) && !it.endsWith(".part") } ?: emptyList()

    private fun trim() {
        val files = dir.listFiles() ?: return
        if (files.size <= max) return
        files.sortedBy { it.lastModified() }.take(files.size - max).forEach { it.delete() }
    }
}

/**
 * Full-screen photo slideshow: crossfade with a slow Ken Burns zoom, blurred copy
 * of the photo behind it, and two portrait photos side by side when they follow
 * each other. Photos are downloaded ahead and kept in [PhotoCache]; when the source
 * is unreachable the show keeps cycling through cached photos.
 */
class PhotoShowView(
    ctx: Context,
    private val source: PhotoSource,
    private val intervalMs: Long,
) : FrameLayout(ctx), Show {
    override val view: View get() = this

    private val layers = Array(2) {
        ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_XY; alpha = 0f }
    }
    private val kenBurns = arrayOfNulls<Animator>(2)
    private val fades = arrayOfNulls<Animator>(2)
    private var front = 0
    private val message = TextView(ctx).apply {
        setTextColor(0xFF888888.toInt()); textSize = 24f; gravity = Gravity.CENTER
        setPadding(48, 48, 48, 48); visibility = GONE
    }
    private val ui = Handler(Looper.getMainLooper())
    private val rnd = Random()

    // Worker-thread state. One thread, so no locking.
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val cache = PhotoCache(File(ctx.cacheDir, "photos"))
    private val history = ArrayList<Photo>()     // photos in show order; index = position
    private val backStack = ArrayList<Int>()     // positions of earlier slides, for "previous"
    private val missing = HashSet<String>()      // keys the source answered 404 for; not retried
    private var pos = -1                         // first photo of the slide on screen
    private var slideSize = 1                    // 2 when showing a portrait pair
    private val screen: Point

    @Volatile private var token = 0
    @Volatile private var stopped = false

    init {
        setBackgroundColor(Color.BLACK)
        layers.forEach { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
        addView(message, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        screen = Point().also {
            (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealSize(it)
        }
        if (screen.x < screen.y) screen.set(screen.y, screen.x)   // the frame is always landscape
        go(0)
    }

    override fun next() = go(1)
    override fun prev() = go(-1)
    override fun skip() {
        val t = ++token
        worker.execute {
            // Drop what was queued and fetch a fresh batch.
            if (pos >= 0 && history.size > pos + slideSize) history.subList(pos + slideSize, history.size).clear()
            step(1, t)
        }
    }

    override fun stop() {
        stopped = true
        worker.shutdownNow()
        ui.removeCallbacksAndMessages(null)
        (kenBurns + fades).forEach { it?.cancel() }
    }

    private fun go(dir: Int) {
        if (stopped) return
        val t = ++token
        worker.execute { step(dir, t) }
    }

    // ---------- worker ----------

    private class Slide(val start: Int, val size: Int, val bitmap: Bitmap)

    private fun step(dir: Int, t: Int) {
        if (t != token || stopped) return          // a newer swipe or tick superseded this one
        val w = screen.x; val h = screen.y
        var slide: Slide? = null
        if (dir < 0) {
            while (slide == null && backStack.isNotEmpty()) slide = build(backStack.removeAt(backStack.size - 1), w, h)
            if (slide == null) return
        } else {
            var start = if (dir == 0 || pos < 0) maxOf(pos, 0) else pos + slideSize
            for (attempt in 0 until 15) {
                if (stopped) return
                if (!ensure(start + 1, w, h)) break
                slide = build(start, w, h)
                if (slide != null) break
                start++                                   // broken or unavailable photo: skip it
            }
            if (slide == null) { showMessage("Geen foto's beschikbaar"); return }
            if (dir > 0 && pos >= 0) {
                backStack.add(pos)
                if (backStack.size > 200) backStack.removeAt(0)
            }
        }
        pos = slide.start
        slideSize = slide.size
        trimHistory()
        val s = slide
        ui.post { if (t == token && !stopped) display(s.bitmap) }
        prefetch(pos + slideSize, 4, w, h)
    }

    /** Make sure history has at least [n] entries. False when nothing more can be found. */
    private fun ensure(n: Int, w: Int, h: Int): Boolean {
        while (history.size < n) {
            val more = try {
                source.batch(w, h)
            } catch (e: Exception) {
                Log.w(TAG, "photo source unreachable (${e.message}); using cache")
                cache.keys(source.prefix).shuffled().take(12).map { Photo(it, null) }
            }
            if (more.isEmpty()) return false
            history.addAll(more)
        }
        return true
    }

    private fun build(start: Int, w: Int, h: Int): Slide? {
        val a = load(history[start], w, h) ?: return null
        var b: Bitmap? = null
        if (a.height > a.width && ensure(start + 2, w, h)) {
            b = load(history[start + 1], w / 2, h)?.takeIf { it.height > it.width }
        }
        return Slide(start, if (b != null) 2 else 1, compose(a, b, w, h))
    }

    private fun file(p: Photo): File? {
        cache.get(p.key)?.let { return it }
        val url = p.url ?: return null
        if (p.key in missing) return null
        return try { cache.put(p.key, Net.get(url)) } catch (e: Exception) {
            if (e is HttpStatusException && e.code == 404) missing += p.key
            Log.w(TAG, "download failed: ${e.message}"); null
        }
    }

    private fun load(p: Photo, w: Int, h: Int): Bitmap? {
        val f = file(p) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0) { cache.remove(p.key); return null }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        val bmp = try {
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: OutOfMemoryError) { null }
        if (bmp == null) cache.remove(p.key)
        return bmp
    }

    private fun prefetch(from: Int, count: Int, w: Int, h: Int) {
        val t = token
        for (i in from until from + count) {
            if (t != token || stopped || !ensure(i + 1, w, h)) return
            file(history[i])
        }
    }

    private fun trimHistory() {
        if (pos < 500) return
        val drop = 250
        history.subList(0, drop).clear()
        pos -= drop
        val kept = backStack.filter { it >= drop }.map { it - drop }
        backStack.clear(); backStack.addAll(kept)
    }

    // ---------- composing ----------

    private val filter = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** Screen-sized bitmap: blurred background, then one photo fitted, or two side by side. */
    private fun compose(a: Bitmap, b: Bitmap?, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        drawBlur(c, a, w, h)
        if (b == null) {
            c.drawBitmap(a, null, fit(a, RectF(0f, 0f, w.toFloat(), h.toFloat())), filter)
        } else {
            val gap = h / 80f
            c.drawBitmap(a, null, fit(a, RectF(0f, 0f, w / 2f - gap / 2, h.toFloat())), filter)
            c.drawBitmap(b, null, fit(b, RectF(w / 2f + gap / 2, 0f, w.toFloat(), h.toFloat())), filter)
        }
        return out
    }

    /** Cheap blur: shrink to a few dozen pixels and scale back up with filtering, then dim. */
    private fun drawBlur(c: Canvas, src: Bitmap, w: Int, h: Int) {
        val tiny = Bitmap.createScaledBitmap(src, 24, maxOf(1, 24 * src.height / src.width), true)
        val small = Bitmap.createScaledBitmap(tiny, 160, maxOf(1, 160 * src.height / src.width), true)
        // Cover the screen with it.
        val scale = maxOf(w / small.width.toFloat(), h / small.height.toFloat())
        val dw = small.width * scale; val dh = small.height * scale
        c.drawBitmap(small, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), filter)
        c.drawColor(0x8C000000.toInt())
        tiny.recycle(); small.recycle()
    }

    private fun fit(bmp: Bitmap, box: RectF): RectF {
        val s = minOf(box.width() / bmp.width, box.height() / bmp.height)
        val dw = bmp.width * s; val dh = bmp.height * s
        return RectF(box.centerX() - dw / 2, box.centerY() - dh / 2, box.centerX() + dw / 2, box.centerY() + dh / 2)
    }

    // ---------- UI thread ----------

    private fun display(bmp: Bitmap) {
        message.visibility = GONE
        val backIdx = 1 - front
        val back = layers[backIdx]
        back.setImageBitmap(bmp)
        startKenBurns(backIdx)
        fade(backIdx, 1f)
        val oldIdx = front
        fade(oldIdx, 0f) {
            // Free the old bitmap once it's invisible, unless it has become the front again.
            if (front != oldIdx) layers[oldIdx].setImageDrawable(null)
        }
        front = backIdx
    }

    private fun fade(i: Int, to: Float, end: (() -> Unit)? = null) {
        fades[i]?.cancel()
        fades[i] = ObjectAnimator.ofFloat(layers[i], View.ALPHA, to).setDuration(1000).apply {
            if (end != null) addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: Animator) { cancelled = true }
                override fun onAnimationEnd(a: Animator) { if (!cancelled) end() }
            })
            start()
        }
    }

    /** Very slow zoom in or out around a random point, lasting as long as the photo is shown. */
    private fun startKenBurns(i: Int) {
        val v = layers[i]
        kenBurns[i]?.cancel()
        v.pivotX = v.width * (0.2f + rnd.nextFloat() * 0.6f)
        v.pivotY = v.height * (0.2f + rnd.nextFloat() * 0.6f)
        val (from, to) = if (rnd.nextBoolean()) 1f to ZOOM else ZOOM to 1f
        kenBurns[i] = ObjectAnimator.ofPropertyValuesHolder(
            v,
            PropertyValuesHolder.ofFloat(View.SCALE_X, from, to),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, from, to),
        ).apply {
            duration = intervalMs + 2000
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun showMessage(text: String) = ui.post {
        if (stopped) return@post
        message.text = text
        message.visibility = VISIBLE
        ui.postDelayed({ go(0) }, 60_000)   // try again in a minute
    }

    companion object {
        private const val ZOOM = 1.06f
    }
}
