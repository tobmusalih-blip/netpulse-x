package com.netpulsex.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.lang.ref.WeakReference

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var pendingProtocol: String = "VLESS"
    private var pendingServer: String = ""
    private var pendingRouting: String = "افتراضي"
    private var pendingDns: String = "1.1.1.1"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeActivityRef = WeakReference(this)

        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#06040d"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val state = if (ProxyXVpnService.isRunning) "connected" else "disconnected"
                    val msg = if (ProxyXVpnService.isRunning) {
                        "النفق نشط عبر ${ProxyXVpnService.activeProtocol} (${ProxyXVpnService.activeEndpoint})"
                    } else {
                        "المحرك جاهز لفحص البلد والـ IP والاتصال"
                    }
                    notifyJsStatus(state, msg)
                }
            }
            webChromeClient = WebChromeClient()
            addJavascriptInterface(ProxyXBridge(), "ProxyXBridge")
            loadUrl("file:///android_asset/index.html")
        }

        setContentView(webView)
    }

    private fun readClipboardText(): String {
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip: ClipData? = cm?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
            } else {
                ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun requestOrStartVpn(protocol: String, server: String, routing: String, dns: String) {
        val parsed = ServerConfigParser.parse(protocol, server)
        if (parsed.isFailure) {
            val errMsg = parsed.exceptionOrNull()?.message ?: "رابط السيرفر غير صالح"
            notifyJsStatus("disconnected", errMsg)
            return
        }

        pendingProtocol = protocol
        pendingServer = server
        pendingRouting = routing
        pendingDns = dns.trim().ifEmpty { "1.1.1.1" }

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            @Suppress("DEPRECATION")
            startActivityForResult(prepareIntent, VPN_REQUEST_CODE)
        } else {
            launchVpnService()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                launchVpnService()
            } else {
                notifyJsStatus("disconnected", "تم رفض إذن VPN من النظام")
            }
        }
    }

    private fun launchVpnService() {
        val intent = Intent(this, ProxyXVpnService::class.java).apply {
            action = ProxyXVpnService.ACTION_START
            putExtra(ProxyXVpnService.EXTRA_PROTOCOL, pendingProtocol)
            putExtra(ProxyXVpnService.EXTRA_SERVER, pendingServer)
            putExtra(ProxyXVpnService.EXTRA_ROUTING, pendingRouting)
            putExtra(ProxyXVpnService.EXTRA_DNS, pendingDns)
        }
        startService(intent)
    }

    private fun stopVpnService() {
        val intent = Intent(this, ProxyXVpnService::class.java).apply {
            action = ProxyXVpnService.ACTION_STOP
        }
        startService(intent)
        notifyJsStatus("disconnected", "تم إيقاف خدمة VPN")
    }

    fun notifyJsStatus(state: String, message: String) {
        val payload = JSONObject().apply {
            put("state", state)
            put("message", message)
            put("endpoint", ProxyXVpnService.activeEndpoint)
            put("latencyMs", ProxyXVpnService.lastLatencyMs)
            put("beforeIp", ProxyXVpnService.beforeIp)
            put("beforeCountry", ProxyXVpnService.beforeCountry)
            put("afterIp", ProxyXVpnService.afterIp)
            put("afterCountry", ProxyXVpnService.afterCountry)
            put("ipChanged", ProxyXVpnService.ipChanged)
            put("dns", ProxyXVpnService.activeDns)
        }
        val safeJson = JSONObject.quote(payload.toString())
        val js = "if (window.onNativeVpnState) { window.onNativeVpnState($safeJson); }"
        runOnUiThread {
            if (this::webView.isInitialized) {
                webView.evaluateJavascript(js, null)
            }
        }
    }

    inner class ProxyXBridge {
        @JavascriptInterface
        fun connectVpn(protocol: String, server: String, routing: String, dns: String) {
            runOnUiThread {
                requestOrStartVpn(protocol, server, routing, dns)
            }
        }

        @JavascriptInterface
        fun disconnectVpn() {
            runOnUiThread {
                stopVpnService()
            }
        }

        @JavascriptInterface
        fun getStatus(): String {
            return if (ProxyXVpnService.isRunning) "connected" else "disconnected"
        }

        @JavascriptInterface
        fun pasteFromClipboard(): String {
            return readClipboardText()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (this::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (this::webView.isInitialized) {
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val VPN_REQUEST_CODE = 1001
        private var activeActivityRef: WeakReference<MainActivity>? = null

        fun dispatchStatusToUi(state: String, message: String) {
            activeActivityRef?.get()?.notifyJsStatus(state, message)
        }
    }
}
