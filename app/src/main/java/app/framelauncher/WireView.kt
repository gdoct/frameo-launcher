package app.framelauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Wireframe animations in the spirit of early-1980s computer-generated TV
 * interstitials: glowing vector lines on black, slowly turning geometry, tunnels
 * and grids. [next]/[prev] fade to another scene.
 */
class WireView(ctx: Context) : View(ctx), Show {
    override val view: View get() = this

    private val r = WireRenderer()
    private var scene = (Math.random() * SCENES.size).toInt()
    private var pending = -1
    private var fade = 0f
    private var t0 = SystemClock.uptimeMillis()
    private var last = t0
    private var running = true

    private val overlay = Paint()
    private val vignette = Paint()

    override fun next() = go(1)
    override fun prev() = go(-1)
    override fun skip() = go(1)
    override fun stop() { running = false }

    private fun go(d: Int) {
        val n = SCENES.size
        pending = (((if (pending >= 0) pending else scene) + d) % n + n) % n
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        r.resize(w.toFloat(), h.toFloat())
        // Faint scanlines (1 dark px every 3) and a vignette, like an old TV.
        val lines = Bitmap.createBitmap(1, 3, Bitmap.Config.ARGB_8888)
        lines.setPixel(0, 0, Color.argb(56, 0, 0, 0))
        overlay.shader = BitmapShader(lines, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        val g = RadialGradient(0f, 0f, 1f, intArrayOf(0, 0, Color.argb(140, 0, 0, 0)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        g.setLocalMatrix(Matrix().apply {
            setScale(w / 2f * SQRT2, h / 2f * SQRT2)
            postTranslate(w / 2f, h / 2f)
        })
        vignette.shader = g
    }

    override fun onDraw(canvas: Canvas) {
        if (!running) return
        val now = SystemClock.uptimeMillis()
        val dt = min(0.1f, (now - last) / 1000f)
        last = now
        if (pending >= 0) {                       // fade out, switch, fade in
            fade -= dt / 0.5f
            if (fade <= 0f) { fade = 0f; scene = pending; pending = -1; t0 = now }
        } else fade = min(1f, fade + dt / 0.8f)

        r.begin()
        SCENES[scene](r, (now - t0) / 1000f)
        r.end(canvas, fade)
        canvas.drawPaint(overlay)
        canvas.drawPaint(vignette)
        if (windowVisibility == VISIBLE) postInvalidateOnAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) { last = SystemClock.uptimeMillis(); invalidate() }
    }

    private companion object {
        val SQRT2 = sqrt(2f)
        val SCENES: Array<(WireRenderer, Float) -> Unit> = arrayOf(
            Scenes::solids, Scenes::gridFlight, Scenes::tunnel, Scenes::torus,
            Scenes::stringArt, Scenes::globe, Scenes::ripple, Scenes::cubeSphere,
        )
    }
}

/** Collects camera-space line segments, buckets them by colour and strokes them with a glow. */
class WireRenderer {
    private var w = 1f
    private var h = 1f
    private var f = 1f
    private var cx = 0.5f
    private var cy = 0.5f
    private var lw = 1f

    private var fogOn = false
    private var fogNear = 0f
    private var fogScale = 1f
    private var fogMin = 0f

    private val bufs = arrayOfNulls<FloatArray>(HUES * (LEVELS + 1))
    private val counts = IntArray(HUES * (LEVELS + 1))
    private val glowColor = IntArray(HUES)
    private val coreColor = IntArray(HUES)
    // Flat caps: round caps overlap at joints and show as beads under additive blending.
    // The wide, faint glow pass doesn't need anti-aliasing.
    private val glow = Paint().apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }

    init {
        for (i in 0 until HUES) {
            glowColor[i] = hsl(i * HUE_STEP, 0.55f)
            coreColor[i] = hsl(i * HUE_STEP, 0.68f)
        }
    }

    fun resize(width: Float, height: Float) {
        w = width; h = height
        f = min(w, h) * 0.9f
        cx = w / 2; cy = h / 2
        lw = max(1f, min(w, h) / 650f)
    }

    fun begin() {
        counts.fill(0)
        fogOn = false
    }

    /** Alpha falls from 1 to [min] between camera distance [near] and [far]. */
    fun fog(near: Float, far: Float, min: Float) {
        fogOn = true; fogNear = near; fogScale = 1 / (far - near); fogMin = min
    }

    fun line(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, hue: Float, alpha: Float = 1f) {
        var x1 = ax; var y1 = ay; var z1 = az
        var x2 = bx; var y2 = by; var z2 = bz
        if (z1 < NEAR && z2 < NEAR) return
        if (z1 < NEAR) {
            val k = (NEAR - z1) / (z2 - z1); x1 += (x2 - x1) * k; y1 += (y2 - y1) * k; z1 = NEAR
        } else if (z2 < NEAR) {
            val k = (NEAR - z2) / (z1 - z2); x2 += (x1 - x2) * k; y2 += (y1 - y2) * k; z2 = NEAR
        }
        var a = alpha
        if (fogOn) a *= 1 - (1 - fogMin) * clamp01(((z1 + z2) / 2 - fogNear) * fogScale)
        val lvl = (clamp01(a) * LEVELS + 0.5f).toInt()
        if (lvl == 0) return
        var hueIdx = (hue / HUE_STEP + 0.5f).toInt() % HUES
        if (hueIdx < 0) hueIdx += HUES
        val b = hueIdx * (LEVELS + 1) + lvl
        var buf = bufs[b]
        val n = counts[b]
        if (buf == null || n + 4 > buf.size) {
            buf = (buf ?: FloatArray(0)).copyOf(max(64, (buf?.size ?: 0) * 2))
            bufs[b] = buf
        }
        val k1 = f / z1
        val k2 = f / z2
        buf[n] = cx + x1 * k1
        buf[n + 1] = cy + y1 * k1
        buf[n + 2] = cx + x2 * k2
        buf[n + 3] = cy + y2 * k2
        counts[b] = n + 4
    }

    /** Line between points [i] and [j] of a flat xyz array. */
    fun seg(p: FloatArray, i: Int, j: Int, hue: Float, a: Float = 1f) =
        line(p[i * 3], p[i * 3 + 1], p[i * 3 + 2], p[j * 3], p[j * 3 + 1], p[j * 3 + 2], hue, a)

    /** Polyline through [n] points of a flat xyz array. */
    fun poly(p: FloatArray, n: Int, hue: Float, a: Float = 1f, closed: Boolean = false) {
        for (i in 1 until n) seg(p, i - 1, i, hue, a)
        if (closed) seg(p, n - 1, 0, hue, a)
    }

    fun end(canvas: Canvas, level: Float) {
        canvas.drawColor(Color.BLACK)
        glow.strokeWidth = lw * 6
        core.strokeWidth = lw * 1.4f
        for (b in counts.indices) {
            val n = counts[b]
            if (n == 0) continue
            val hueIdx = b / (LEVELS + 1)
            val a = (b % (LEVELS + 1)) / LEVELS.toFloat() * level
            glow.color = withAlpha(glowColor[hueIdx], a * 0.16f)
            canvas.drawLines(bufs[b]!!, 0, n, glow)
            core.color = withAlpha(coreColor[hueIdx], a)
            canvas.drawLines(bufs[b]!!, 0, n, core)
        }
    }

    private companion object {
        const val NEAR = 0.05f
        const val LEVELS = 6
        const val HUE_STEP = 15f            // hue quantised to 15° steps: fewer draw calls
        const val HUES = 24

        fun withAlpha(c: Int, a: Float) = (c and 0xFFFFFF) or ((clamp01(a) * 255).roundToInt() shl 24)

        /** HSL with full saturation to opaque RGB. */
        fun hsl(hue: Float, l: Float): Int {
            val c = 1 - abs(2 * l - 1)
            val hp = hue / 60f
            val x = c * (1 - abs(hp % 2 - 1))
            val (r, g, b) = when (hp.toInt()) {
                0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
                3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
            }
            val m = l - c / 2
            return Color.rgb(((r + m) * 255).roundToInt(), ((g + m) * 255).roundToInt(), ((b + m) * 255).roundToInt())
        }
    }
}

private fun clamp01(x: Float) = min(1f, max(0f, x))
private fun smooth(x: Float) = x * x * (3 - 2 * x)
private val TAU = (2 * PI).toFloat()
private fun s(x: Float) = sin(x)
private fun c(x: Float) = cos(x)

/** Rotates (x, y, z) about x, then y, then z, and writes the result to out[o..o+2]. */
private fun rot(x: Float, y: Float, z: Float, ax: Float, ay: Float, az: Float, out: FloatArray, o: Int) {
    var cs = c(ax); var sn = s(ax)
    val y1 = y * cs - z * sn
    val z1 = y * sn + z * cs
    cs = c(ay); sn = s(ay)
    val x2 = x * cs + z1 * sn
    val z2 = -x * sn + z1 * cs
    cs = c(az); sn = s(az)
    out[o] = x2 * cs - y1 * sn
    out[o + 1] = x2 * sn + y1 * cs
    out[o + 2] = z2
}

/** A regular solid: vertices normalised to radius 1, edges = vertex pairs at the minimum distance. */
private class Solid(raw: List<FloatArray>) {
    val verts: FloatArray
    val edges: IntArray
    val n = raw.size

    init {
        fun d(a: FloatArray, b: FloatArray) = sqrt((a[0] - b[0]).pow(2) + (a[1] - b[1]).pow(2) + (a[2] - b[2]).pow(2))
        var m = Float.MAX_VALUE
        for (i in raw.indices) for (j in i + 1 until raw.size) m = min(m, d(raw[i], raw[j]))
        val e = ArrayList<Int>()
        for (i in raw.indices) for (j in i + 1 until raw.size) if (d(raw[i], raw[j]) < m * 1.01f) { e += i; e += j }
        edges = e.toIntArray()
        val r = raw.maxOf { sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2]) }
        verts = FloatArray(raw.size * 3) { raw[it / 3][it % 3] / r }
    }

    companion object {
        private val PHI = ((1 + sqrt(5.0)) / 2).toFloat()
        private val SIGNS = floatArrayOf(-1f, 1f)
        val CUBE = Solid(SIGNS.flatMap { x -> SIGNS.flatMap { y -> SIGNS.map { z -> floatArrayOf(x, y, z) } } })
        val OCTA = Solid(listOf(
            floatArrayOf(1f, 0f, 0f), floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 1f, 0f),
            floatArrayOf(0f, -1f, 0f), floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, -1f)))
        val ICOSA = Solid(SIGNS.flatMap { a ->
            SIGNS.flatMap { b ->
                listOf(floatArrayOf(0f, a, b * PHI), floatArrayOf(a, b * PHI, 0f), floatArrayOf(b * PHI, 0f, a))
            }
        })
    }
}

/** Affine transform out = M·v + T, so a whole shape is rotated with 9 multiplies per point. */
private class Xf {
    private val m = FloatArray(9)          // row-major
    private var tx = 0f
    private var ty = 0f
    private var tz = 0f
    private val tmp = FloatArray(3)
    private val r = FloatArray(9)

    /** Rotation about x, then y, then z (as [rot]), followed by translation (dx, dy, dz). */
    fun setRot(ax: Float, ay: Float, az: Float, dx: Float = 0f, dy: Float = 0f, dz: Float = 0f): Xf {
        for (col in 0 until 3) {
            rot(if (col == 0) 1f else 0f, if (col == 1) 1f else 0f, if (col == 2) 1f else 0f, ax, ay, az, tmp, 0)
            m[col] = tmp[0]; m[3 + col] = tmp[1]; m[6 + col] = tmp[2]
        }
        tx = dx; ty = dy; tz = dz
        return this
    }

    /** this = a after b (b is applied first). */
    fun compose(a: Xf, b: Xf): Xf {
        for (i in 0 until 3) for (j in 0 until 3) {
            r[i * 3 + j] = a.m[i * 3] * b.m[j] + a.m[i * 3 + 1] * b.m[3 + j] + a.m[i * 3 + 2] * b.m[6 + j]
        }
        val nx = a.m[0] * b.tx + a.m[1] * b.ty + a.m[2] * b.tz + a.tx
        val ny = a.m[3] * b.tx + a.m[4] * b.ty + a.m[5] * b.tz + a.ty
        val nz = a.m[6] * b.tx + a.m[7] * b.ty + a.m[8] * b.tz + a.tz
        r.copyInto(m)
        tx = nx; ty = ny; tz = nz
        return this
    }

    fun apply(x: Float, y: Float, z: Float, out: FloatArray, o: Int) {
        out[o] = m[0] * x + m[1] * y + m[2] * z + tx
        out[o + 1] = m[3] * x + m[4] * y + m[5] * z + ty
        out[o + 2] = m[6] * x + m[7] * y + m[8] * z + tz
    }
}

/**
 * Scenes: draw(r, t) with t = seconds since the scene started; camera looks down +z, y is down.
 * Fixed geometry is precomputed so a frame needs almost no trigonometry.
 */
private object Scenes {
    private val P = FloatArray(4096 * 3)     // scratch points, reused every frame
    private val A = Xf()
    private val B = Xf()
    private val C = Xf()

    private fun drawSolid(r: WireRenderer, sol: Solid, scale: Float, xf: Xf, hue: Float) {
        val v = sol.verts
        for (i in 0 until sol.n) xf.apply(v[i * 3] * scale, v[i * 3 + 1] * scale, v[i * 3 + 2] * scale, P, i * 3)
        val e = sol.edges
        var k = 0
        while (k < e.size) { r.seg(P, e[k], e[k + 1], hue); k += 2 }
    }

    // Nested platonic solids, each turning its own way.
    fun solids(r: WireRenderer, t: Float) {
        r.fog(4f, 8f, 0.25f)
        val pulse = 1 + 0.08f * s(t * 1.3f)
        drawSolid(r, Solid.ICOSA, 2.1f * pulse, A.setRot(t * 0.23f, t * 0.31f, t * 0.07f, dz = 6f), 190 + 40 * s(t * 0.2f))
        drawSolid(r, Solid.CUBE, 1.25f, A.setRot(-t * 0.41f, t * 0.17f, t * 0.29f, dz = 6f), 310 + 30 * s(t * 0.3f))
        drawSolid(r, Solid.OCTA, 0.75f / pulse, A.setRot(t * 0.6f, -t * 0.5f, 0f, dz = 6f), 50f)
    }

    // Flight over an endless rolling grid landscape.
    fun gridFlight(r: WireRenderer, t: Float) {
        r.fog(4f, 36f, 0f)
        val sz = 1.5f; val n = 26; val xs = 14; val cols = 2 * xs + 1
        val travel = t * 4f
        val off = travel % sz
        A.setRot(-0.05f, s(t * 0.13f) * 0.25f, s(t * 0.21f) * 0.18f)
        for (k in 0 until n) for (i in -xs..xs) {
            val x = i * sz; val z = k * sz - off; val zw = z + travel
            val m = max(0f, s(x * 0.35f + zw * 0.11f) * s(zw * 0.17f - x * 0.08f))
            A.apply(x, 1.6f - 1.4f * m * m, z, P, (k * cols + i + xs) * 3)
        }
        for (k in 0 until n) for (i in 1 until cols) r.seg(P, k * cols + i - 1, k * cols + i, 300f - k * 3)   // rows across
        for (i in 0 until cols) for (k in 1 until n) r.seg(P, (k - 1) * cols + i, k * cols + i, 190f, 0.8f)    // rails
    }

    // Tunnel of rotating squares, rainbow rings rushing past.
    fun tunnel(r: WireRenderer, t: Float) {
        val n = 30; val g = 1.1f; val rad = 1.8f
        val travel = t * 5f
        r.fog(2f, n * g, 0f)
        val off = travel % g
        val base = floor(travel / g)
        val c0x = s(travel * 0.07f) * 2.2f + s(travel * 0.023f) * 1.5f
        val c0y = c(travel * 0.05f) * 1.6f
        for (k in 0 until n) {
            val z = k * g - off + 0.3f
            val zw = z + travel
            val cx = s(zw * 0.07f) * 2.2f + s(zw * 0.023f) * 1.5f
            val cy = c(zw * 0.05f) * 1.6f
            val ang = zw * 0.06f + t * 0.4f
            for (i in 0 until 4) {
                val a = ang + i * TAU / 4
                val o = (k * 4 + i) * 3
                P[o] = cx - c0x + c(a) * rad; P[o + 1] = cy - c0y + s(a) * rad; P[o + 2] = z
            }
            val hue = (base + k) * 23
            for (i in 0 until 4) r.seg(P, k * 4 + i, k * 4 + (i + 1) % 4, hue)
            if (k > 0) for (i in 0 until 4) r.seg(P, (k - 1) * 4 + i, k * 4 + i, hue, 0.5f)
        }
    }

    // Torus whose meridians flow around the tube.
    private const val TU = 30
    private const val TV = 14
    private const val TS = 3
    private val ringCosU = FloatArray(TU) { c(it.toFloat() / TU * TAU) }
    private val ringSinU = FloatArray(TU) { s(it.toFloat() / TU * TAU) }
    private val ringCosV = FloatArray(TV * TS + 1) { c(it.toFloat() / (TV * TS) * TAU) }
    private val ringSinV = FloatArray(TV * TS + 1) { s(it.toFloat() / (TV * TS) * TAU) }
    private val spCosU = FloatArray(TU * TS + 1) { c(it.toFloat() / (TU * TS) * TAU) }
    private val spSinU = FloatArray(TU * TS + 1) { s(it.toFloat() / (TU * TS) * TAU) }
    /** Angle around the tube of spiral j at step i, before the flow offset. */
    private val spCosB = FloatArray(TV * (TU * TS + 1)) { val j = it / (TU * TS + 1); val i = it % (TU * TS + 1); c(j.toFloat() / TV * TAU + 2 * i.toFloat() / (TU * TS) * TAU) }
    private val spSinB = FloatArray(TV * (TU * TS + 1)) { val j = it / (TU * TS + 1); val i = it % (TU * TS + 1); s(j.toFloat() / TV * TAU + 2 * i.toFloat() / (TU * TS) * TAU) }

    fun torus(r: WireRenderer, t: Float) {
        r.fog(4f, 8.5f, 0.2f)
        val big = 1.7f; val rr = 0.65f
        A.setRot(0.9f + 0.3f * s(t * 0.2f), t * 0.25f, t * 0.1f, dz = 6.3f)
        val ringPts = TV * TS + 1
        for (i in 0 until TU) {                               // tube rings
            for (j in 0 until ringPts) {
                val ring = big + rr * ringCosV[j]
                A.apply(ring * ringCosU[i], rr * ringSinV[j], ring * ringSinU[i], P, j * 3)
            }
            r.poly(P, ringPts, 160 + 60 * s(i.toFloat() / TU * TAU + t * 0.5f), 0.7f)
        }
        val cf = c(t * 0.8f); val sf = s(t * 0.8f)
        val spiralPts = TU * TS + 1
        for (j in 0 until TV) {                               // spiralling lines along the torus
            for (i in 0 until spiralPts) {
                val k = j * spiralPts + i
                val cv = spCosB[k] * cf - spSinB[k] * sf     // cos/sin of (base angle + flow)
                val sv = spSinB[k] * cf + spCosB[k] * sf
                val ring = big + rr * cv
                A.apply(ring * spCosU[i], rr * sv, ring * spSinU[i], P, i * 3)
            }
            r.poly(P, spiralPts, 320f, 0.9f)
        }
    }

    // String art: a line between two points on Lissajous paths, with its recent history.
    fun stringArt(r: WireRenderer, t: Float) {
        r.fog(4f, 8f, 0.4f)
        A.setRot(t * 0.05f, t * 0.11f, 0f, dz = 6f)
        val n = 110
        for (i in 0 until n) {
            val sv = t * 0.9f - i * 0.035f
            val a = (1 - i.toFloat() / n).pow(1.5f)
            val hue = t * 20 + i * 1.6f
            val px = s(sv * 1.13f) * 2.2f; val py = s(sv * 1.71f + 1) * 1.5f; val pz = s(sv * 0.67f) * 1.4f
            val qx = s(sv * 0.83f + 2) * 2.2f; val qy = s(sv * 1.29f) * 1.5f; val qz = c(sv * 1.07f) * 1.4f
            A.apply(px, py, pz, P, 0); A.apply(qx, qy, qz, P, 3)
            A.apply(-px, py, pz, P, 6); A.apply(-qx, qy, qz, P, 9)
            r.seg(P, 0, 1, hue, a)
            r.seg(P, 2, 3, hue + 180, a * 0.6f)
        }
    }

    // Globe with two orbiting rings and their satellites.
    private const val SEG = 48
    private val latitudes = FloatArray(8 * SEG * 3).also { out ->
        for (i in 1 until 9) {
            val lat = (-PI / 2 + i * PI / 9).toFloat()
            for (j in 0 until SEG) {
                val a = j.toFloat() / SEG * TAU
                val o = ((i - 1) * SEG + j) * 3
                out[o] = c(lat) * c(a) * 1.5f; out[o + 1] = s(lat) * 1.5f; out[o + 2] = c(lat) * s(a) * 1.5f
            }
        }
    }
    private val longitudes = FloatArray(12 * (SEG / 2 + 1) * 3).also { out ->
        for (i in 0 until 12) {
            val a = i / 12f * TAU
            for (j in 0..SEG / 2) {
                val lat = (-PI / 2 + j.toFloat() / (SEG / 2) * PI).toFloat()
                val o = (i * (SEG / 2 + 1) + j) * 3
                out[o] = c(lat) * c(a) * 1.5f; out[o + 1] = s(lat) * 1.5f; out[o + 2] = c(lat) * s(a) * 1.5f
            }
        }
    }
    private val circle = FloatArray(SEG * 2 * 2).also { out ->
        for (j in 0 until SEG * 2) { val a = j.toFloat() / (SEG * 2) * TAU; out[j * 2] = c(a); out[j * 2 + 1] = s(a) }
    }
    private val orbits = arrayOf(
        floatArrayOf(2.3f, 1.2f, 0.2f, 0.7f, 40f),
        floatArrayOf(2.7f, -0.5f, -0.6f, -0.45f, 330f),
    )

    private fun applyAll(xf: Xf, src: FloatArray, from: Int, count: Int) {
        for (k in 0 until count) {
            val o = (from + k) * 3
            xf.apply(src[o], src[o + 1], src[o + 2], P, k * 3)
        }
    }

    fun globe(r: WireRenderer, t: Float) {
        r.fog(4.5f, 7.5f, 0.15f)
        val d = 6f
        A.compose(B.setRot(0.4f, 0f, 0.3f, dz = d), C.setRot(0f, t * 0.3f, 0f))   // spin, then tilt
        for (i in 0 until 8) {
            applyAll(A, latitudes, i * SEG, SEG)
            r.poly(P, SEG, 140f, 0.8f, closed = true)
        }
        for (i in 0 until 12) {
            applyAll(A, longitudes, i * (SEG / 2 + 1), SEG / 2 + 1)
            r.poly(P, SEG / 2 + 1, 170f, 0.8f)
        }
        val sat = Solid.OCTA
        for (orbit in orbits) {
            val rad = orbit[0]; val ax = orbit[1]; val az = orbit[2]; val speed = orbit[3]; val hue = orbit[4]
            B.setRot(ax, t * 0.05f, az, dz = d)
            for (j in 0 until SEG * 2) B.apply(circle[j * 2] * rad, 0f, circle[j * 2 + 1] * rad, P, j * 3)
            r.poly(P, SEG * 2, hue, 0.6f, closed = true)
            val a = t * speed
            // Satellite: spin it, move it onto the orbit, then apply the orbit's own rotation.
            A.compose(B, C.setRot(t * 2, t * 3, 0f, c(a) * rad, 0f, s(a) * rad))
            drawSolid(r, sat, 0.18f, A, hue)
        }
    }

    // Rippling surface, seen from above at an angle.
    private const val RN = 34
    private const val RS = 3.2f
    private const val RSIDE = RN + 1
    private fun rippleCoord(i: Int) = -RS + i.toFloat() / RN * 2 * RS
    private val rippleD = FloatArray(RSIDE * RSIDE) { hypot(rippleCoord(it / RSIDE), rippleCoord(it % RSIDE)) }
    private val rippleSin = FloatArray(RSIDE * RSIDE) { s(rippleD[it] * 3) }
    private val rippleCos = FloatArray(RSIDE * RSIDE) { c(rippleD[it] * 3) }
    private val rippleDamp = FloatArray(RSIDE * RSIDE) { -0.45f / (1 + rippleD[it] * 0.6f) }
    private val rippleY = FloatArray(RSIDE * RSIDE)

    fun ripple(r: WireRenderer, t: Float) {
        r.fog(5f, 12f, 0.1f)
        A.setRot(-0.75f, t * 0.12f, 0f, dy = 0.2f, dz = 8.5f)
        val ct = c(t * 2.2f); val st = s(t * 2.2f)
        for (k in 0 until RSIDE * RSIDE) {
            val y = rippleDamp[k] * (rippleSin[k] * ct - rippleCos[k] * st)   // sin(3d - 2.2t)
            rippleY[k] = y
            A.apply(rippleCoord(k / RSIDE), y, rippleCoord(k % RSIDE), P, k * 3)
        }
        for (a in 0..RN) for (b in 0 until RN) {
            // Lines along x at z = coord(a), then along z at x = coord(a); colour by height.
            val p = b * RSIDE + a; val q = (b + 1) * RSIDE + a
            r.seg(P, p, q, 200 - (rippleY[p] + rippleY[q]) * 150, 0.85f)
            val u = a * RSIDE + b; val v = a * RSIDE + b + 1
            r.seg(P, u, v, 200 - (rippleY[u] + rippleY[v]) * 150, 0.85f)
        }
    }

    // Cube made of grid lines, breathing into a sphere and back.
    private const val CN = 6
    private const val CSUB = 12
    private const val CLINES = 3 * 2 * 2 * (CN + 1)
    private val cubePts = FloatArray(CLINES * (CSUB + 1) * 3)
    private val cubeInvLen = FloatArray(CLINES * (CSUB + 1))
    private val cubeHue = FloatArray(CLINES)

    init {
        var line = 0
        val p = FloatArray(3)
        for (axis in 0 until 3) for (side in intArrayOf(1, -1)) for (dir in 0 until 2) for (i in 0..CN) {
            for (j in 0..CSUB) {
                p[axis] = side.toFloat()
                p[(axis + 1 + dir) % 3] = -1 + 2f * i / CN
                p[(axis + 2 - dir) % 3] = -1 + 2f * j / CSUB
                val k = line * (CSUB + 1) + j
                p.copyInto(cubePts, k * 3)
                cubeInvLen[k] = 1 / sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2])
            }
            cubeHue[line] = axis * 120f + (if (side > 0) 0 else 40)
            line++
        }
    }

    fun cubeSphere(r: WireRenderer, t: Float) {
        r.fog(4f, 8f, 0.2f)
        val m = smooth(clamp01(0.5f + 0.8f * s(t * 0.6f)))
        A.setRot(t * 0.3f, t * 0.21f, t * 0.13f, dz = 6f)
        for (line in 0 until CLINES) {
            for (j in 0..CSUB) {
                val k = line * (CSUB + 1) + j
                val f = (1 - m) + m * 1.45f * cubeInvLen[k]
                A.apply(cubePts[k * 3] * f, cubePts[k * 3 + 1] * f, cubePts[k * 3 + 2] * f, P, j * 3)
            }
            r.poly(P, CSUB + 1, cubeHue[line] + t * 15, 0.85f)
        }
    }
}
