package moe.majsoulmax.app.ui.web

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import moe.majsoulmax.app.R
import moe.majsoulmax.app.core.CertManager
import moe.majsoulmax.app.data.ConfigRepository
import moe.majsoulmax.app.data.TunnelStatus
import moe.majsoulmax.app.service.TunnelController

/**
 * Built-in browser for the web client.
 *
 * This exists because the VPN excludes our own package to prevent the proxy
 * looping back on itself — which means a WebView we host is *never* routed
 * through the tun, and has to reach the MITM proxy directly. A WebView proxy
 * override does exactly that, and as a bonus this path works with no VPN consent
 * at all: if nothing is running, the service is started in proxy-only mode.
 *
 * It is also the fallback when a game client refuses user-installed CAs, since a
 * WebView honours them (see `network_security_config.xml`).
 */
class GameActivity : ComponentActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                useWideViewPort = true
                loadWithOverviewMode = true
                cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            }
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
        }
        CookieManager.getInstance().setAcceptCookie(true)
        setContentView(webView)

        // Back should walk the page history before leaving the game.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )

        lifecycleScope.launch { prepareAndLoad() }
    }

    private suspend fun prepareAndLoad() {
        if (!CertManager.isTrusted(this)) {
            toast(getString(R.string.web_needs_cert))
        }

        val status = TunnelStatus.read(this)
        if (status.stage != TunnelStatus.Stage.RUNNING) {
            toast(getString(R.string.web_starting_core))
            TunnelController.startProxyOnly(this)
            awaitRunning()
        }

        if (!applyProxyOverride()) {
            toast(getString(R.string.web_proxy_unsupported))
        }

        webView.loadUrl(GAME_URL)
    }

    /** Polls status.json; the service publishes RUNNING once the proxy is bound. */
    private suspend fun awaitRunning() {
        withTimeoutOrNull(STARTUP_TIMEOUT_MS) {
            while (TunnelStatus.read(this@GameActivity).stage != TunnelStatus.Stage.RUNNING) {
                kotlinx.coroutines.delay(300)
            }
        }
    }

    /**
     * Points every WebView in this process at the MITM proxy. The endpoint is read
     * from upstream's own `settings.json`, so changing `proxyAddr` in the editor
     * moves both the proxy and this override together.
     */
    private suspend fun applyProxyOverride(): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return false

        val general = runCatching {
            ConfigRepository(this).load(ConfigRepository.Which.GENERAL)
        }.getOrNull()
        val (host, port) = general
            ?.let { ConfigRepository.mitmEndpoint(it) }
            ?: ("127.0.0.1" to ConfigRepository.DEFAULT_MITM_PORT)

        return runCatching {
            val config = ProxyConfig.Builder()
                .addProxyRule("$host:$port")
                // Loopback would otherwise be sent to the proxy as well, which
                // cannot resolve it.
                .addBypassRule("localhost")
                .addBypassRule("127.0.0.1")
                .build()
            ProxyController.getInstance().setProxyOverride(config, { it.run() }, {})
            true
        }.getOrDefault(false)
    }

    override fun onDestroy() {
        // Leaving the override in place would break any other WebView the app
        // shows after the proxy stops.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            runCatching { ProxyController.getInstance().clearProxyOverride({ it.run() }, {}) }
        }
        webView.destroy()
        super.onDestroy()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        /** Line 1, the only one upstream reports as working on Android. */
        const val GAME_URL = "https://game.maj-soul.com/1/"
        const val STARTUP_TIMEOUT_MS = 60_000L
    }
}
