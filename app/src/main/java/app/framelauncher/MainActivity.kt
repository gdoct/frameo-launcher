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
        private const val PEEK_MS = 60_000L
        private const val CHECK_MS = 30_000L
        private const val HOT_CORNER_PX = 140
        private const val HOT_CORNER_HOLD_MS = 2500L
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var clock: ClockView
    private lateinit var black: View
    private lateinit var banner: TextView

    private var shownPresetId: String? = null
    private var shownUrl: String? = null
    private var shownType: ModeType? = null
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
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        // Make sure an alarm is armed even if we were started by hand.
        Engine.sync(this, bringToFront = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        apply(force = false)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        hideSystemUi()
        web.onResume()
        apply(force = false)
        ui.removeCallbacks(periodicCheck)
        ui.postDelayed(periodicCheck, CHECK_MS)
    }

    override fun onPause() {
        resumed = false
        ui.removeCallbacks(periodicCheck)
        if (shownType != ModeType.WEB) web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        ui.removeCallbacksAndMessages(null)
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
        }
        clock = ClockView(this)
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
        root.addView(black, FrameLayout.LayoutParams(match, match))
        root.addView(banner, FrameLayout.LayoutParams(match, match))
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
        for (x in listOf(web, clock, black, banner)) x.visibility = if (x === v) View.VISIBLE else View.GONE
    }

    // ---------- mode switching ----------

    private fun apply(force: Boolean) {
        if (peeking && !force) return
        val cfg = Store.load(this)
        val st = Schedule.state(cfg)
        val p = st.active
        if (p == null) {
            showOnly(banner)
            banner.text = "No modes configured.\n\nHold the top-left corner to open settings."
            keepScreenOn(true); setBrightness(150)
            shownPresetId = null; shownType = null
            return
        }
        val changed = force || p.id != shownPresetId || p.type != shownType ||
            (p.type == ModeType.WEB && p.url != shownUrl)
        if (!changed) {
            setBrightness(p.brightness) // brightness edits apply without a reload
            return
        }
        Log.i(TAG, "showing ${p.name} (${p.type})")
        shownPresetId = p.id; shownType = p.type
        ui.removeCallbacks(reloadRunnable)

        when (p.type) {
            ModeType.WEB -> {
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
                showOnly(clock)
                web.onPause()
                keepScreenOn(true); setBrightness(p.brightness)
                Engine.wakeScreen(this)
            }
            ModeType.OFF -> {
                showOnly(black)
                web.onPause()
                keepScreenOn(false); setBrightness(1)
                // Let the black frame draw, then cut the display.
                ui.postDelayed({ goDark() }, 1500)
            }
        }
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

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (peeking && ev.action == MotionEvent.ACTION_DOWN) {
            // Touch during a peek extends it.
            ui.removeCallbacks(endPeek)
            ui.postDelayed(endPeek, PEEK_MS)
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
