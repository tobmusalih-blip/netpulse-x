package com.netpulsex.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var pendingProtocol: String = "VLESS"
    private var pendingServer: String = ""
    private var pendingRouting: String = "افتراضي"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                    notifyJsStatus(state, "الجسر الأصلي جاهز")
                }
            }
            webChromeClient = WebChromeClient()
            addJavascriptInterface(ProxyXBridge(), "ProxyXBridge")
            loadUrl("file:///android_asset/index.html")
        }

        setContentView(webView)
    }

    private fun requestOrStartVpn(protocol: String, server: String, routing: String) {
        pendingProtocol = protocol
        pendingServer = server
        pendingRouting = routing

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
        }
        startService(intent)
        notifyJsStatus("connected", "تم تفعيل واجهة TUN الحقيقية عبر $pendingProtocol")
    }

    private fun stopVpnService() {
        val intent = Intent(this, ProxyXVpnService::class.java).apply {
            action = ProxyXVpnService.ACTION_STOP
        }
        startService(intent)
        notifyJsStatus("disconnected", "تم إيقاف خدمة VPN")
    }

    private fun notifyJsStatus(state: String, message: String) {
        val safeMsg = message.replace("\\", "\\\\").replace("'", "\\'")
        val js = "if (window.onNativeVpnState) { window.onNativeVpnState('$state', '$safeMsg'); }"
        runOnUiThread {
            webView.evaluateJavascript(js, null)
        }
    }

    inner class ProxyXBridge {
        @JavascriptInterface
        fun connectVpn(protocol: String, server: String, routing: String) {
            runOnUiThread {
                requestOrStartVpn(protocol, server, routing)
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
    }
}
