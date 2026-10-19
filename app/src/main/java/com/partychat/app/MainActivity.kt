package com.partychat.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private var pocketOverlay: android.view.View? = null
    private var pendingWebPermission: PermissionRequest? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            val micOk = hasMic()
            pendingWebPermission?.let { req ->
                if (micOk) req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else req.deny()
                pendingWebPermission = null
            }
            if (micOk) {
                startParty()
                PartyService.reroute(this) // Bluetooth permission may have just been granted
            } else {
                Toast.makeText(this, "Microphone permission is needed for party chat", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.subtitle = "Audio: —"

        WebView.setWebContentsDebuggingEnabled(true)
        webView = buildWebView()
        setContentView(webView)

        PartyService.routeListener = { desc -> runOnUiThread { actionBar?.subtitle = "Audio: $desc" } }
        PartyService.stopRequestedListener = {
            runOnUiThread {
                if (!isDestroyed) {
                    webView.loadUrl("about:blank")
                    finishAndRemoveTask()
                }
            }
        }

        // Back = go back in the page, or minimize (never accidentally leave the party).
        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) webView.goBack() else moveTaskToBack(true)
        }

        requestNeededPermissions()

        if (savedInstanceState == null) webView.loadUrl(Prefs.home(this))
        else webView.restoreState(savedInstanceState)
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView {
        // Always tell the browser engine it's on screen. Otherwise, when the screen turns
        // off, Chromium throttles the page and your outgoing voice gets choppy.
        val wv = object : WebView(this) {
            override fun onWindowVisibilityChanged(visibility: Int) {
                super.onWindowVisibilityChanged(android.view.View.VISIBLE)
            }
        }

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false) // sign-in popups open in the same view
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        applyUserAgent(wv)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true) // Microsoft sign-in spans several domains
        }

        // Keep the page's renderer at full priority even when the app isn't visible.
        wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)

        // Make the page believe it's always visible, so it never mutes / tears down the
        // voice connection when you switch apps or turn the screen off.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(wv, ALWAYS_VISIBLE_JS, setOf("*"))
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val scheme = uri.scheme ?: return false
                if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "blob" || scheme == "data") {
                    return false // keep everything inside the app
                }
                // intent://, mailto:, etc. -> hand to the system
                try {
                    val intent = if (scheme == "intent") Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                                 else Intent(Intent.ACTION_VIEW, uri)
                    startActivity(intent)
                } catch (_: Exception) { }
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    view.evaluateJavascript(ALWAYS_VISIBLE_JS, null)
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // The page's process crashed/was killed: rebuild the WebView instead of crashing the app.
                val url = view.url ?: Prefs.home(this@MainActivity)
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.destroy()
                webView = buildWebView()
                setContentView(webView)
                webView.loadUrl(url)
                return true
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { handleWebPermission(request) }
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                actionBar?.title = title?.takeIf { it.isNotBlank() } ?: getString(R.string.app_name)
            }
        }

        return wv
    }

    private fun applyUserAgent(wv: WebView) {
        val default = WebSettings.getDefaultUserAgent(this)
        val chromeVersion = Regex("Chrome/([\\d.]+)").find(default)?.groupValues?.get(1) ?: "130.0.0.0"
        wv.settings.userAgentString = if (Prefs.desktopMode(this)) {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/$chromeVersion Safari/537.36 Edg/$chromeVersion"
        } else {
            // Look like normal Chrome for Android (some sites refuse embedded browsers).
            default.replace("; wv", "").replace("Version/4.0 ", "")
        }
    }

    private fun handleWebPermission(request: PermissionRequest) {
        val host = request.origin?.host.orEmpty()
        val wantsMic = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        if (!wantsMic || !isTrustedHost(host)) {
            request.deny()
            return
        }
        if (hasMic()) {
            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            startParty()
        } else {
            pendingWebPermission?.deny()
            pendingWebPermission = request
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        }
    }

    private fun isTrustedHost(host: String): Boolean =
        TRUSTED_DOMAINS.any { host == it || host.endsWith(".$it") }

    // ---------------------------------------------------------------- Party / permissions

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestNeededPermissions() {
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) wanted += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startParty() else permissionLauncher.launch(missing.toTypedArray())
    }

    /** Starts the background-mic service. Only works while the app is on screen. */
    private fun startParty() {
        if (!hasMic() || PartyService.running) return
        try {
            PartyService.start(this)
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't start background mic: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun leaveParty() {
        PartyService.stop(this)
        webView.loadUrl("about:blank")
        finishAndRemoveTask()
    }

    // ---------------------------------------------------------------- Lifecycle
    // Deliberately NOT calling webView.onPause()/pauseTimers() so audio keeps flowing.

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
        startParty() // self-heal if the service was stopped
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        PartyService.routeListener = null
        PartyService.stopRequestedListener = null
        if (isFinishing) PartyService.stop(this)
        webView.destroy()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Pocket mode
    // Android starves the page when the screen is really off, which makes your voice
    // choppy. Pocket mode keeps the screen "on" but fully black at minimum brightness
    // (black pixels are off on OLED screens, so it costs very little battery).

    private fun enterPocketMode() {
        if (pocketOverlay != null) return
        val overlay = android.widget.FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            isClickable = true
            isFocusable = true
            val hint = android.widget.TextView(this@MainActivity).apply {
                text = "Pocket mode — double-tap to exit"
                setTextColor(0xFF202020.toInt())
                textSize = 14f
            }
            addView(hint, android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER))
            val detector = android.view.GestureDetector(this@MainActivity,
                object : android.view.GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: android.view.MotionEvent) = true
                    override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                        exitPocketMode(); return true
                    }
                })
            setOnTouchListener { _, ev -> detector.onTouchEvent(ev) }
        }
        addContentView(overlay, android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        pocketOverlay = overlay
        actionBar?.hide()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 0.01f }
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun exitPocketMode() {
        val overlay = pocketOverlay ?: return
        (overlay.parent as? android.view.ViewGroup)?.removeView(overlay)
        pocketOverlay = null
        actionBar?.show()
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }

    // ---------------------------------------------------------------- Menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, M_HOME, 0, "Home")
        menu.add(0, M_RELOAD, 1, "Reload")
        menu.add(0, M_SET_HOME, 2, "Set this page as home")
        menu.add(0, M_DESKTOP, 3, "Desktop site").setCheckable(true).isChecked = Prefs.desktopMode(this)
        menu.add(0, M_SPEAKER, 4, "Speaker when no headset").setCheckable(true).isChecked = Prefs.speakerFallback(this)
        menu.add(0, M_REROUTE, 5, "Fix audio routing now")
        menu.add(0, M_BATTERY, 6, "Battery optimization settings")
        menu.add(0, M_POCKET, 7, "Pocket mode (black screen)")
        menu.add(0, M_LEAVE, 8, "Leave & close")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            M_HOME -> webView.loadUrl(Prefs.home(this))
            M_RELOAD -> webView.reload()
            M_SET_HOME -> webView.url?.let {
                Prefs.setHome(this, it)
                Toast.makeText(this, "Home page saved", Toast.LENGTH_SHORT).show()
            }
            M_DESKTOP -> {
                val on = !item.isChecked
                item.isChecked = on
                Prefs.setDesktopMode(this, on)
                applyUserAgent(webView)
                webView.reload()
            }
            M_SPEAKER -> {
                val on = !item.isChecked
                item.isChecked = on
                Prefs.setSpeakerFallback(this, on)
                PartyService.reroute(this)
            }
            M_REROUTE -> {
                PartyService.reroute(this)
                Toast.makeText(this, "Re-routing audio", Toast.LENGTH_SHORT).show()
            }
            M_BATTERY -> try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            M_POCKET -> enterPocketMode()
            M_LEAVE -> leaveParty()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    companion object {
        private const val M_HOME = 1
        private const val M_RELOAD = 2
        private const val M_SET_HOME = 3
        private const val M_DESKTOP = 4
        private const val M_SPEAKER = 5
        private const val M_REROUTE = 6
        private const val M_BATTERY = 7
        private const val M_LEAVE = 8
        private const val M_POCKET = 9

        /** Sites allowed to use the microphone. */
        private val TRUSTED_DOMAINS = listOf(
            "xbox.com", "xboxlive.com", "microsoft.com", "live.com", "xboxservices.com"
        )

        /** Pretends the page is always in the foreground and blocks "you left" events. */
        private const val ALWAYS_VISIBLE_JS = """
(function () {
  if (window.__partyChatAlwaysVisible) return;
  window.__partyChatAlwaysVisible = true;
  try {
    var def = function (name, value) {
      try { Object.defineProperty(Document.prototype, name, { get: function () { return value; }, configurable: true }); } catch (e) {}
    };
    def('hidden', false);
    def('webkitHidden', false);
    def('visibilityState', 'visible');
    def('webkitVisibilityState', 'visible');
    Document.prototype.hasFocus = function () { return true; };
    try {
      if (navigator.permissions && navigator.permissions.query) {
        var origQuery = navigator.permissions.query.bind(navigator.permissions);
        navigator.permissions.query = function (desc) {
          if (desc && (desc.name === 'microphone' || desc.name === 'audio_capture')) {
            return Promise.resolve({ state: 'granted', status: 'granted', name: desc.name, onchange: null,
              addEventListener: function () {}, removeEventListener: function () {} });
          }
          return origQuery(desc);
        };
      }
    } catch (e) {}
    var block = function (e) { e.stopImmediatePropagation(); };
    ['visibilitychange', 'webkitvisibilitychange', 'freeze'].forEach(function (t) {
      document.addEventListener(t, block, true);
    });
    ['pagehide', 'blur'].forEach(function (t) { window.addEventListener(t, block, true); });
  } catch (e) {}
})();
"""
    }
}
