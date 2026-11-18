package app.framelauncher

import android.content.Context
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Split-flap clock with date and current weather (open-meteo.com, no API key).
 * Each card flips like a real split-flap display when its character changes.
 */
class FlipClockView(ctx: Context, private val lat: Double, private val lon: Double) : View(ctx), Show {
    override val view: View get() = this

    private class Card {
        var value = ' '
        var old = ' '
        var flipStart = -1L          // uptime ms; -1 = not flipping
        val rect = RectF()
        var topShader: Shader? = null
        var bottomShader: Shader? = null
    }

    private class Row(len: Int) {
        val cards = List(len) { Card() }
        var first = true
        var em = 0f                  // font size; card is 0.72em x 1.15em (0.82em wide for small rows)

        fun set(text: String, now: Long) {
            val s = text.padEnd(cards.size).take(cards.size)
            cards.forEachIndexed { i, c ->
                val ch = s[i]
                if (ch == c.value) return@forEachIndexed
                c.old = c.value
                c.value = ch
                c.flipStart = if (first) -1 else now
            }
            first = false
        }
    }

    private val hours = Row(2)
    private val minutes = Row(2)
    private val date = Row(9)        // "MA 16 NOV"
    private val temp = Row(3)        // "12°"
    private var weatherText = ""
    private val colonPos = RectF()
    private var colonSize = 0f
    private var weatherY = 0f
    private var weatherSize = 0f

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var stopped = false
    private var lastMinute = -1

    private val bgPaint = Paint()
    private val cardTop = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardBottom = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = 0xFFF2F2F2.toInt(); typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val hinge = Paint().apply { color = 0xFF000000.toInt() }
    private val colonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF555555.toInt(); typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val weatherPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF999999.toInt(); textAlign = Paint.Align.CENTER
    }
    private val camera = Camera()
    private val matrix = Matrix()

    private val tick = object : Runnable {
        override fun run() {
            updateTime()
            ui.postDelayed(this, 1000)
        }
    }
    private val weatherTick = object : Runnable {
        override fun run() {
            fetchWeather()
            ui.postDelayed(this, WEATHER_EVERY_MS)
        }
    }

    init {
        temp.set(" --", 0)
        updateTime()
        ui.post(weatherTick)
        ui.postDelayed(tick, 1000)
    }

    override fun stop() {
        stopped = true
        ui.removeCallbacksAndMessages(null)
        worker.shutdownNow()
    }

    // ---------- data ----------

    private fun updateTime() {
        val c = Calendar.getInstance()
        val m = c.get(Calendar.MINUTE)
        if (m == lastMinute) return
        lastMinute = m
        val now = SystemClock.uptimeMillis()
        hours.set(String.format(Locale.ROOT, "%02d", c.get(Calendar.HOUR_OF_DAY)), now)
        minutes.set(String.format(Locale.ROOT, "%02d", m), now)
        date.set(String.format(Locale.ROOT, "%s %2d %s",
            DAYS[c.get(Calendar.DAY_OF_WEEK) - 1], c.get(Calendar.DAY_OF_MONTH), MONTHS[c.get(Calendar.MONTH)]), now)
        invalidate()
    }

    private fun fetchWeather() = worker.execute {
        val text: String
        val t: String?
        try {
            val d = JSONObject(Net.getString(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code,wind_speed_10m" +
                    "&daily=temperature_2m_min,temperature_2m_max&forecast_days=1&timezone=auto"))
            val cur = d.getJSONObject("current")
            val daily = d.getJSONObject("daily")
            t = "${cur.getDouble("temperature_2m").roundToInt()}°".padStart(3)
            val lo = daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt()
            val hi = daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt()
            val desc = WMO[cur.getInt("weather_code")] ?: ""
            text = "$desc · $lo° / $hi° · wind ${cur.getDouble("wind_speed_10m").roundToInt()} km/u"
        } catch (e: Exception) {
            if (!stopped) ui.post { weatherText = "Weer niet beschikbaar"; invalidate() }
            return@execute
        }
        ui.post {
            if (stopped) return@post
            temp.set(t, SystemClock.uptimeMillis())
            weatherText = text
            invalidate()
        }
    }

    // ---------- layout ----------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val vmin = min(w, h) / 100f
        val big = min(w * 0.26f, h * 0.42f)
        val small = min(w * 0.07f, h * 0.10f)
        hours.em = big; minutes.em = big; date.em = small; temp.em = small
        weatherSize = (3 * vmin).coerceIn(14 * resources.displayMetrics.scaledDensity, 30 * resources.displayMetrics.scaledDensity)
        val gap = 4 * vmin

        // Vertical stack: time, date, temperature, weather text; centred as a block.
        val timeH = 1.15f * big
        val smallH = 1.15f * small
        val total = timeH + gap + smallH + gap + smallH + 1.5f * vmin + weatherSize * 1.2f
        var y = (h - total) / 2

        colonSize = 0.6f * big
        val colonW = colonSize * 0.6f + 0.16f * big
        val timeW = rowWidth(hours) + colonW + rowWidth(minutes)
        var x = (w - timeW) / 2
        x = layoutRow(hours, x, y)
        colonPos.set(x, y, x + colonW, y + timeH)
        layoutRow(minutes, x + colonW, y)
        y += timeH + gap
        layoutRow(date, (w - rowWidth(date)) / 2, y)
        y += smallH + gap
        layoutRow(temp, (w - rowWidth(temp)) / 2, y)
        y += smallH + 1.5f * vmin
        weatherY = y + weatherSize

        val r = hypot(w / 2f, h * 0.6f) * 0.75f
        bgPaint.shader = RadialGradient(w / 2f, h * 0.4f, r, 0xFF1B1B1B.toInt(), 0xFF000000.toInt(), Shader.TileMode.CLAMP)
        camera.setLocation(0f, 0f, -8f * resources.displayMetrics.density)
    }

    private fun cardWidth(r: Row) = r.em * (if (r === date || r === temp) 0.82f else 0.72f)
    private fun rowWidth(r: Row) = r.cards.size * cardWidth(r) + (r.cards.size - 1) * 0.06f * r.em

    /** Positions the cards of [r] from [x0]; returns the x just after the row. */
    private fun layoutRow(r: Row, x0: Float, y: Float): Float {
        var x = x0
        val cw = cardWidth(r)
        for (c in r.cards) {
            c.rect.set(x, y, x + cw, y + 1.15f * r.em)
            val mid = c.rect.centerY()
            c.topShader = LinearGradient(0f, c.rect.top, 0f, mid, 0xFF2A2A2A.toInt(), 0xFF222222.toInt(), Shader.TileMode.CLAMP)
            c.bottomShader = LinearGradient(0f, mid, 0f, c.rect.bottom, 0xFF1E1E1E.toInt(), 0xFF262626.toInt(), Shader.TileMode.CLAMP)
            x += cw + 0.06f * r.em
        }
        return x - 0.06f * r.em
    }

    // ---------- drawing ----------

    override fun onDraw(canvas: Canvas) {
        canvas.drawPaint(bgPaint)
        val now = SystemClock.uptimeMillis()
        var animating = false
        for (r in listOf(hours, minutes, date, temp)) {
            glyph.textSize = r.em
            for (c in r.cards) animating = drawCard(canvas, c, r.em, now) or animating
        }
        colonPaint.textSize = colonSize
        val cy = colonPos.centerY() - (colonPaint.descent() + colonPaint.ascent()) / 2 - colonSize * 0.1f
        canvas.drawText(":", colonPos.centerX(), cy, colonPaint)
        weatherPaint.textSize = weatherSize
        canvas.drawText(weatherText, width / 2f, weatherY, weatherPaint)
        if (animating) postInvalidateOnAnimation()
    }

    /** Draws one card; returns true while it is still flipping. */
    private fun drawCard(canvas: Canvas, c: Card, em: Float, now: Long): Boolean {
        val r = c.rect
        val mid = r.centerY()
        val radius = 0.06f * em
        cardTop.shader = c.topShader
        cardBottom.shader = c.bottomShader

        val t = if (c.flipStart < 0) 1f else (now - c.flipStart) / FLIP_MS.toFloat()
        val flipping = t < 1f
        if (!flipping) c.flipStart = -1

        // Static halves: the top already shows the new character, the bottom the old one until the end.
        drawHalf(canvas, c, c.value, top = true, radius)
        drawHalf(canvas, c, if (flipping) c.old else c.value, top = false, radius)

        if (flipping) {
            if (t < 0.5f) {
                // Top leaf with the old character falls forward (ease-in).
                val p = t / 0.5f
                drawLeaf(canvas, c, c.old, top = true, degrees = -90f * p * p, radius = radius)
            } else {
                // Bottom leaf with the new character comes down into place (ease-out).
                val p = (t - 0.5f) / 0.5f
                drawLeaf(canvas, c, c.value, top = false, degrees = 90f * (1 - p) * (1 - p), radius = radius)
            }
        }
        canvas.drawRect(r.left, mid - 0.01f * em, r.right, mid + 0.01f * em, hinge)
        return flipping
    }

    /**
     * Draws the top or bottom half of a card. With [transform] (a leaf rotating about the
     * hinge) the clip is applied in screen space first: rotation about the hinge keeps each
     * half on its own side of the hinge line, so that clip still cuts the card in half.
     */
    private fun drawHalf(canvas: Canvas, c: Card, ch: Char, top: Boolean, radius: Float, transform: Matrix? = null) {
        val r = c.rect
        val mid = r.centerY()
        val ext = if (transform != null) r.width() else 0f    // perspective widens the near edge
        canvas.save()
        if (top) canvas.clipRect(r.left - ext, r.top - ext, r.right + ext, mid)
        else canvas.clipRect(r.left - ext, mid, r.right + ext, r.bottom + ext)
        if (transform != null) canvas.concat(transform)
        canvas.drawRoundRect(r, radius, radius, if (top) cardTop else cardBottom)
        if (ch != ' ') {
            val baseline = mid - (glyph.descent() + glyph.ascent()) / 2
            canvas.drawText(ch.toString(), r.centerX(), baseline, glyph)
        }
        canvas.restore()
    }

    private fun drawLeaf(canvas: Canvas, c: Card, ch: Char, top: Boolean, degrees: Float, radius: Float) {
        val r = c.rect
        val mid = r.centerY()
        camera.save()
        camera.rotateX(degrees)
        camera.getMatrix(matrix)
        camera.restore()
        matrix.preTranslate(-r.centerX(), -mid)
        matrix.postTranslate(r.centerX(), mid)
        drawHalf(canvas, c, ch, top, radius, matrix)
    }

    companion object {
        private const val FLIP_MS = 500L
        private const val WEATHER_EVERY_MS = 15 * 60_000L
        private val DAYS = arrayOf("ZO", "MA", "DI", "WO", "DO", "VR", "ZA")
        private val MONTHS = arrayOf("JAN", "FEB", "MRT", "APR", "MEI", "JUN", "JUL", "AUG", "SEP", "OKT", "NOV", "DEC")

        /** WMO weather codes in Dutch. */
        private val WMO = mapOf(
            0 to "Onbewolkt", 1 to "Overwegend helder", 2 to "Half bewolkt", 3 to "Bewolkt",
            45 to "Mist", 48 to "Rijpende mist",
            51 to "Lichte motregen", 53 to "Motregen", 55 to "Dichte motregen", 56 to "IJzel", 57 to "IJzel",
            61 to "Lichte regen", 63 to "Regen", 65 to "Zware regen", 66 to "IJzel", 67 to "IJzel",
            71 to "Lichte sneeuw", 73 to "Sneeuw", 75 to "Zware sneeuw", 77 to "Korrelsneeuw",
            80 to "Lichte buien", 81 to "Buien", 82 to "Zware buien", 85 to "Sneeuwbuien", 86 to "Zware sneeuwbuien",
            95 to "Onweer", 96 to "Onweer met hagel", 99 to "Onweer met hagel",
        )
    }
}
