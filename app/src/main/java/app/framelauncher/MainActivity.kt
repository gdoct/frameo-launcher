package app.framelauncher

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    companion object {
        const val EXTRA_FROM_ENGINE = "fromEngine"
        /**
         * Testing aid: `adb shell am start -n app.framelauncher/.MainActivity --es preview WIRE`
         * shows that mode type for 10 minutes (optional --es url ..., --ei interval ...).
         * `--es preview none` ends it (any name that is not a mode type).
         */
        const val EXTRA_PREVIEW = "preview"
        private const val PREVIEW_MS = 10 * 60_000L
        private const val PEEK_MS = 60_000L
        private const val CHECK_MS = 30_000L
        private const val HOT_CORNER_PX = 140
        private const val HOT_CORNER_HOLD_MS = 2500L
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var clock: ClockView
    private lateinit var showHost: FrameLayout
    private lateinit var black: View
    private lateinit var banner: TextView
    private lateinit var hud: TextView

    private var shownPresetId: String? = null
    private var shownUrl: String? = null
    private var shownType: ModeType? = null
    private var shownKey: String? = null
    private var currentShow: Show? = null
    private var advanceMs = 0L
    private var previewPreset: Preset? = null
    private var previewUntil = 0L
    /** Settings revision when the preview started; any later save in settings ends it. */
    private var previewRev = 0L
    private var shownBrightness = 200
    private var shownIntervalSec = 30
    private var peeking = false
    private var resumed = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_ON && shownType == ModeType.OFF) startPeek()
        }
    }

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        buildUi()
        handlePreview(intent)
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        // Make sure an alarm is armed even if we were started by hand.
        Engine.sync(this, bringToFront = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePreview(intent)
        apply(force = false)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        hideSystemUi()
        web.onResume()
        apply(force = false)
        restartAdvance()
        ui.removeCallbacks(periodicCheck)
        ui.postDelayed(periodicCheck, CHECK_MS)
    }

    override fun onPause() {
        resumed = false
        ui.removeCallbacks(periodicCheck)
        ui.removeCallbacks(advance)
        if (shownType != ModeType.WEB) web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        ui.removeCallbacksAndMessages(null)
        stopShow()
        web.destroy()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (shownType == ModeType.WEB && web.canGoBack()) web.goBack()
        // Otherwise swallow: this is the home screen.
    }

    private val periodicCheck = object : Runnable {
        override fun run() {
            apply(force = false)
            ui.postDelayed(this, CHECK_MS)
        }
    }

    // ---------- UI ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
            webViewClient = FrameWebClient()
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            // No long-press text selection: it cancels the touch stream, which breaks the
            // hidden settings hold in the top-left corner.
            setOnLongClickListener { true }
            isLongClickable = false
            isHapticFeedbackEnabled = false
        }
        clock = ClockView(this)
        showHost = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        black = View(this).apply { setBackgroundColor(Color.BLACK) }
        banner = TextView(this).apply {
            setTextColor(0xFFB0BEC5.toInt())
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        val match = FrameLayout.LayoutParams.MATCH_PARENT
        root.addView(web, FrameLayout.LayoutParams(match, match))
        root.addView(clock, FrameLayout.LayoutParams(match, match))
        root.addView(showHost, FrameLayout.LayoutParams(match, match))
        root.addView(black, FrameLayout.LayoutParams(match, match))
        root.addView(banner, FrameLayout.LayoutParams(match, match))
        hud = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 22f
            setPadding(40, 14, 40, 14)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x8C000000.toInt()); cornerRadius = 999f
            }
            alpha = 0f
        }
        root.addView(hud, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 32 })
        setContentView(root)
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    private fun setBrightness(level: Int) {
        val lp = window.attributes
        lp.screenBrightness = (level.coerceIn(1, 255)) / 255f
        window.attributes = lp
    }

    private fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun showOnly(v: View?) {
        for (x in listOf(web, clock, showHost, black, banner)) x.visibility = if (x === v) View.VISIBLE else View.GONE
    }

    // ---------- mode switching ----------

    private fun apply(force: Boolean) {
        if (peeking && !force) return
        val cfg = Store.load(this)
        Net.allowInsecureSsl = cfg.allowInsecureSsl
        if (previewPreset != null &&
            (System.currentTimeMillis() >= previewUntil || Store.revision(this) != previewRev)
        ) previewPreset = null
        val p = previewPreset ?: Schedule.state(cfg).active
        if (p == null) {
            stopShow()
            showOnly(banner)
            banner.text = "No modes configured.\n\nHold the top-left corner to open settings."
            keepScreenOn(true); setBrightness(150)
            shownPresetId = null; shownType = null
            return
        }
        val changed = force || p.id != shownPresetId || p.type != shownType ||
            (p.type == ModeType.WEB && p.url != shownUrl) ||
            (currentShow != null && p.showKey() != shownKey)
        if (!changed) {
            setBrightness(p.brightness) // brightness edits apply without a reload
            return
        }
        Log.i(TAG, "showing ${p.name} (${p.type})")
        shownPresetId = p.id; shownType = p.type
        shownBrightness = p.brightness; shownIntervalSec = p.intervalSec
        ui.removeCallbacks(reloadRunnable)

        if (p.type != ModeType.WEB) web.onPause()
        when (p.type) {
            ModeType.PHOTOS, ModeType.PICSUM, ModeType.FLIP_CLOCK, ModeType.WIRE -> {
                if (p.type == ModeType.PHOTOS && p.url.isBlank()) {
                    stopShow()
                    showOnly(banner)
                    banner.text = "\"${p.name}\" has no photo service URL yet.\n\nHold the top-left corner to open settings."
                } else {
                    startShow(p)
                }
                keepScreenOn(true); setBrightness(p.brightness)
                Engine.wakeScreen(this)
            }
            ModeType.WEB -> {
                stopShow()
                if (p.url.isBlank()) {
                    showOnly(banner)
                    banner.text = "\"${p.name}\" has no URL yet.\n\nHold the top-left corner to open settings."
                    shownUrl = p.url
                } else {
                    showOnly(web)
                    web.onResume()
                    if (p.url != shownUrl || force) web.loadUrl(p.url)
                    shownUrl = p.url
                    if (p.reloadMinutes > 0) ui.postDelayed(reloadRunnable, p.reloadMinutes * 60_000L)
                }
                keepScreenOn(true); setBrightness(p.brightness)
                Engine.wakeScreen(this)
            }
            ModeType.CLOCK -> {
                stopShow()
                showOnly(clock)
                keepScreenOn(true); setBrightness(p.brightness)
                Engine.wakeScreen(this)
            }
            ModeType.OFF -> {
                stopShow()
                showOnly(black)
                keepScreenOn(false); setBrightness(1)
                // Let the black frame draw, then cut the display.
                ui.postDelayed({ goDark() }, 1500)
            }
        }
    }

    // ---------- native shows ----------

    private fun startShow(p: Preset) {
        stopShow()
        val interval = p.intervalSec * 1000L
        val show: Show = when (p.type) {
            ModeType.PHOTOS -> PhotoShowView(this, NasSource(p.url), interval)
            ModeType.PICSUM -> PhotoShowView(this, PicsumSource(), interval)
            ModeType.FLIP_CLOCK -> FlipClockView(this, p.lat, p.lon)
            else -> WireView(this)
        }
        val match = FrameLayout.LayoutParams.MATCH_PARENT
        showHost.addView(show.view, FrameLayout.LayoutParams(match, match))
        currentShow = show
        shownKey = p.showKey()
        advanceMs = if (p.type.timed) interval else 0
        showOnly(showHost)
        restartAdvance()
    }

    private fun stopShow() {
        ui.removeCallbacks(advance)
        currentShow?.stop()
        currentShow = null
        shownKey = null
        advanceMs = 0
        showHost.removeAllViews()
    }

    private val advance = object : Runnable {
        override fun run() {
            currentShow?.next()
            ui.postDelayed(this, advanceMs)
        }
    }

    private fun restartAdvance() {
        ui.removeCallbacks(advance)
        if (advanceMs > 0 && resumed && currentShow != null) ui.postDelayed(advance, advanceMs)
    }

    private fun handlePreview(i: Intent?) {
        val type = i?.getStringExtra(EXTRA_PREVIEW) ?: return
        i.removeExtra(EXTRA_PREVIEW)
        val t = runCatching { ModeType.valueOf(type.uppercase(Locale.ROOT)) }.getOrNull()
        previewPreset = t?.let {
            Preset(name = "Preview", type = it, url = i.getStringExtra("url") ?: "",
                intervalSec = i.getIntExtra("interval", 30).coerceAtLeast(5), brightness = 200)
        }
        previewUntil = System.currentTimeMillis() + PREVIEW_MS
        previewRev = Store.revision(this)
        Log.i(TAG, "preview: ${previewPreset?.type ?: "off"}")
        if (::showHost.isInitialized) apply(force = true)
    }

    private fun goDark() {
        if (shownType != ModeType.OFF) return
        if (!Engine.screenOff(this)) {
            Log.w(TAG, "device admin not active; relying on screen timeout")
        }
    }

    private val reloadRunnable = object : Runnable {
        override fun run() {
            if (shownType == ModeType.WEB && resumed) web.reload()
            val p = Store.load(this@MainActivity).preset(shownPresetId) ?: return
            if (p.reloadMinutes > 0) ui.postDelayed(this, p.reloadMinutes * 60_000L)
        }
    }

    /** Someone woke the screen during "off": show a dim clock briefly, then go dark again. */
    private fun startPeek() {
        if (peeking) return
        peeking = true
        showOnly(clock)
        keepScreenOn(true); setBrightness(40)
        ui.postDelayed(endPeek, PEEK_MS)
    }

    private val endPeek = Runnable {
        peeking = false
        apply(force = true)
    }

    // ---------- hidden settings gesture ----------

    private var holdStart: Runnable? = null
    private var swipeX = 0f
    private var swipeY = 0f

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (peeking && ev.action == MotionEvent.ACTION_DOWN) {
            // Touch during a peek extends it.
            ui.removeCallbacks(endPeek)
            ui.postDelayed(endPeek, PEEK_MS)
        }
        if (currentShow != null && !peeking) when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { swipeX = ev.x; swipeY = ev.y }
            MotionEvent.ACTION_UP -> onSwipe(ev.x - swipeX, ev.y - swipeY)
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (ev.x < HOT_CORNER_PX && ev.y < HOT_CORNER_PX) {
                    val r = Runnable { holdStart = null; openSettings() }
                    holdStart = r
                    ui.postDelayed(r, HOT_CORNER_HOLD_MS)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (holdStart != null && (ev.x > HOT_CORNER_PX * 1.5 || ev.y > HOT_CORNER_PX * 1.5)) cancelHold()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> cancelHold()
        }
        return super.dispatchTouchEvent(ev)
    }

    /** Left/right: next/previous photo or scene. Up: next screensaver. Down: restart the show. */
    private fun onSwipe(dx: Float, dy: Float) {
        val show = currentShow ?: return
        if (maxOf(Math.abs(dx), Math.abs(dy)) < 50 * resources.displayMetrics.density) return
        when {
            Math.abs(dx) > Math.abs(dy) -> if (dx < 0) show.next() else show.prev()
            dy < 0 -> { nextScreensaver(); return }
            else -> apply(force = true)
        }
        restartAdvance()
    }

    /**
     * Swipe up: switch to the next native screensaver until the next scheduled switch.
     * Uses the settings of a configured mode of that type when there is one.
     */
    private fun nextScreensaver() {
        val cfg = Store.load(this)
        val order = listOf(ModeType.PHOTOS, ModeType.PICSUM, ModeType.FLIP_CLOCK, ModeType.WIRE)
        val from = order.indexOf(shownType)
        for (k in 1..order.size) {
            val t = order[(from + k) % order.size]
            val configured = cfg.presets.firstOrNull { it.type == t && (t != ModeType.PHOTOS || it.url.isNotBlank()) }
            if (t == ModeType.PHOTOS && configured == null) continue   // no photo service to ask
            val p = configured ?: Preset(name = t.label, type = t, brightness = shownBrightness, intervalSec = shownIntervalSec)
            previewPreset = p
            previewUntil = Schedule.state(cfg).nextAt ?: Long.MAX_VALUE
            previewRev = Store.revision(this)
            Log.i(TAG, "swipe up: ${p.name} (${p.type}) until next switch")
            apply(force = true)
            flashHud(p.name)
            return
        }
    }

    private fun flashHud(text: String) {
        hud.text = text
        hud.animate().cancel()
        hud.alpha = 1f
        hud.animate().alpha(0f).setStartDelay(1800).setDuration(400).start()
    }

    private fun cancelHold() {
        holdStart?.let { ui.removeCallbacks(it) }
        holdStart = null
    }

    private fun openSettings() {
        val pin = Store.load(this).pin
        if (pin.isBlank()) {
            startActivity(Intent(this, SettingsActivity::class.java)); return
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            gravity = Gravity.CENTER
        }
        AlertDialog.Builder(this)
            .setTitle("PIN")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                if (input.text.toString() == pin) startActivity(Intent(this, SettingsActivity::class.java))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- web ----------

    private inner class FrameWebClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            if (Store.load(this@MainActivity).allowInsecureSsl) handler.proceed() else handler.cancel()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            Log.w(TAG, "page error ${error.errorCode} ${error.description}; retrying in 30s")
            ui.postDelayed({
                if (shownType == ModeType.WEB && shownUrl != null) view.loadUrl(shownUrl!!)
            }, 30_000)
        }
    }
}

/** Large, simple clock with date. */
class ClockView(ctx: Context) : LinearLayout(ctx) {
    private val time = TextView(ctx).apply {
        setTextColor(0xFFE0E0E0.toInt()); textSize = 160f; gravity = Gravity.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-thin", android.graphics.Typeface.NORMAL)
    }
    private val date = TextView(ctx).apply {
        setTextColor(0xFF9E9E9E.toInt()); textSize = 34f; gravity = Gravity.CENTER
    }
    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            time.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
            date.text = SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now)
            postDelayed(this, 1000)
        }
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Color.BLACK)
        addView(time); addView(date)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }
}
