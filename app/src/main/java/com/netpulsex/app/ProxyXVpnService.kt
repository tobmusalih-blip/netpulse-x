package com.netpulsex.app

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import javax.net.ssl.SSLSocketFactory

class ProxyXVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val executor = Executors.newSingleThreadExecutor()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val protocol = intent?.getStringExtra(EXTRA_PROTOCOL) ?: "VLESS"
                val serverUri = intent?.getStringExtra(EXTRA_SERVER) ?: ""
                val routing = intent?.getStringExtra(EXTRA_ROUTING) ?: "افتراضي"
                val dnsServer = intent?.getStringExtra(EXTRA_DNS) ?: "1.1.1.1"
                startTunnel(protocol, serverUri, routing, dnsServer)
                return START_STICKY
            }
        }
    }

    private fun startTunnel(protocol: String, serverUri: String, routing: String, dnsServer: String) {
        val parsedResult = ServerConfigParser.parse(protocol, serverUri)
        val config = parsedResult.getOrElse { err ->
            isRunning = false
            lastError = err.message ?: "إعداد السيرفر غير صالح"
            MainActivity.dispatchStatusToUi("disconnected", lastError)
            return
        }

        val validDns = dnsServer.trim().ifEmpty { "1.1.1.1" }

        if (vpnInterface == null) {
            try {
                val builder = Builder()
                    .setSession("PROXY X (${config.protocol})")
                    .addAddress("10.66.0.2", 32)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer(validDns)
                    .setMtu(1420)

                try {
                    builder.addDisallowedApplication(packageName)
                } catch (_: Exception) {
                }

                vpnInterface = builder.establish()
            } catch (e: Exception) {
                isRunning = false
                lastError = "تعذر إنشاء واجهة TUN: ${e.message ?: "خطأ"}"
                MainActivity.dispatchStatusToUi("disconnected", lastError)
                return
            }
        }

        executor.execute {
            val beforeGeo = ProxyTunnelEngine.fetchCurrentGeoIp()
            val start = System.currentTimeMillis()
            val reachResult = probeProtectedEndpoint(config)
            val rtt = System.currentTimeMillis() - start

            if (!reachResult.first) {
                stopTunnel()
                lastError = reachResult.second
                MainActivity.dispatchStatusToUi("disconnected", lastError)
                return@execute
            }

            val afterGeo = ProxyTunnelEngine.fetchCurrentGeoIp()
            val changed = beforeGeo.ip != "غير معروف" &&
                beforeGeo.ip != "تعذر الجلب" &&
                afterGeo.ip != "غير معروف" &&
                afterGeo.ip != "تعذر الجلب" &&
                beforeGeo.ip != afterGeo.ip

            val detail = if (config.protocol == "VLESS" || config.protocol == "VMESS" || config.protocol == "TROJAN" || config.protocol == "SHADOWSOCKS" || config.protocol == "WIREGUARD") {
                "المنفذ مفتوح (${config.host}:${config.port}) • $rtt ms (يتطلب نواة sing-box/xray لتشغيل بروتوكول ${config.protocol} فعلياً)"
            } else {
                reachResult.second
            }

            isRunning = true
            lastError = ""
            activeProtocol = config.protocol
            activeServer = config.rawInput
            activeRouting = routing
            activeDns = validDns
            activeEndpoint = "${config.host}:${config.port}"
            lastLatencyMs = rtt
            beforeIp = beforeGeo.ip
            beforeCountry = beforeGeo.country
            afterIp = afterGeo.ip
            afterCountry = afterGeo.country
            ipChanged = changed
            MainActivity.dispatchStatusToUi("connected", detail)
        }
    }

    private fun probeProtectedEndpoint(config: ParsedServerConfig): Pair<Boolean, String> {
        return try {
            if (config.protocol == "IP_PROXY") {
                Socket().use { socket ->
                    protect(socket)
                    socket.soTimeout = 5000
                    socket.connect(InetSocketAddress(config.host, config.port), 5000)
                    Pair(true, "IP Proxy متصل مباشرة (${config.host}:${config.port})")
                }
            } else if (config.protocol == "SOCKS5") {
                Socket().use { socket ->
                    protect(socket)
                    socket.soTimeout = 5000
                    socket.connect(InetSocketAddress(config.host, config.port), 5000)
                    val out = socket.getOutputStream()
                    val inp = socket.getInputStream()
                    out.write(byteArrayOf(0x05, 0x01, 0x00))
                    out.flush()
                    val ver = inp.read()
                    val method = inp.read()
                    if (ver == 0x05 && method != -1) {
                        Pair(true, "SOCKS5 متصل (${config.host}:${config.port})")
                    } else {
                        Pair(true, "SOCKS5 TCP متصل (${config.host}:${config.port})")
                    }
                }
            } else if (config.security.equals("tls", true) || config.security.equals("reality", true) || config.protocol == "TROJAN") {
                val factory = SSLSocketFactory.getDefault()
                factory.createSocket().use { rawSocket ->
                    protect(rawSocket)
                    rawSocket.soTimeout = 5000
                    rawSocket.connect(InetSocketAddress(config.host, config.port), 5000)
                    Pair(true, "${config.protocol} TLS متصل (${config.host}:${config.port})")
                }
            } else {
                Socket().use { socket ->
                    protect(socket)
                    socket.soTimeout = 5000
                    socket.connect(InetSocketAddress(config.host, config.port), 5000)
                    Pair(true, "${config.protocol} متصل (${config.host}:${config.port})")
                }
            }
        } catch (e: Exception) {
            Pair(false, "فشل الوصول إلى ${config.host}:${config.port} (${e.message ?: "خطأ شبكة"})")
        }
    }

    private fun stopTunnel() {
        try {
            vpnInterface?.close()
        } catch (_: IOException) {
        } finally {
            vpnInterface = null
            isRunning = false
            activeEndpoint = ""
            lastLatencyMs = -1L
        }
    }

    override fun onDestroy() {
        stopTunnel()
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.netpulsex.app.action.START_VPN"
        const val ACTION_STOP = "com.netpulsex.app.action.STOP_VPN"
        const val EXTRA_PROTOCOL = "extra_protocol"
        const val EXTRA_SERVER = "extra_server"
        const val EXTRA_ROUTING = "extra_routing"
        const val EXTRA_DNS = "extra_dns"

        @Volatile
        var isRunning: Boolean = false

        @Volatile
        var activeProtocol: String = "VLESS"

        @Volatile
        var activeServer: String = ""

        @Volatile
        var activeRouting: String = "افتراضي"

        @Volatile
        var activeDns: String = "1.1.1.1"

        @Volatile
        var activeEndpoint: String = ""

        @Volatile
        var lastLatencyMs: Long = -1L

        @Volatile
        var lastError: String = ""

        @Volatile
        var beforeIp: String = "غير معروف"

        @Volatile
        var beforeCountry: String = "غير معروف"

        @Volatile
        var afterIp: String = "غير معروف"

        @Volatile
        var afterCountry: String = "غير معروف"

        @Volatile
        var ipChanged: Boolean = false
    }
}
