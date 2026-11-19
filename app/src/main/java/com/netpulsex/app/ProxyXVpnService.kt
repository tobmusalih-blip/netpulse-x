package com.netpulsex.app

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.IOException
import java.util.concurrent.Executors

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
                startTunnel(protocol, serverUri, routing)
                return START_STICKY
            }
        }
    }

    private fun startTunnel(protocol: String, serverUri: String, routing: String) {
        val parsedResult = ServerConfigParser.parse(protocol, serverUri)
        val config = parsedResult.getOrElse { err ->
            isRunning = false
            lastError = err.message ?: "إعداد السيرفر غير صالح"
            MainActivity.dispatchStatusToUi("disconnected", lastError, "", -1L)
            return
        }

        if (vpnInterface == null) {
            try {
                val builder = Builder()
                    .setSession("PROXY X (${config.protocol})")
                    .addAddress("10.66.0.2", 32)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("1.1.1.1")
                    .setMtu(1420)

                vpnInterface = builder.establish()
            } catch (e: Exception) {
                isRunning = false
                lastError = "تعذر إنشاء واجهة TUN: ${e.message ?: "خطأ"}"
                MainActivity.dispatchStatusToUi("disconnected", lastError, "", -1L)
                return
            }
        }

        executor.execute {
            val probe = ProxyTunnelEngine.establishConnection(applicationContext, config)
            if (probe.ok) {
                isRunning = true
                lastError = ""
                activeProtocol = config.protocol
                activeServer = config.rawInput
                activeRouting = routing
                activeEndpoint = "${config.host}:${config.port}"
                lastLatencyMs = probe.latencyMs
                MainActivity.dispatchStatusToUi(
                    "connected",
                    probe.detail,
                    activeEndpoint,
                    probe.latencyMs
                )
            } else {
                stopTunnel()
                lastError = probe.detail
                MainActivity.dispatchStatusToUi("disconnected", probe.detail, "", -1L)
            }
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

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var activeProtocol: String = "VLESS"
            private set

        @Volatile
        var activeServer: String = ""
            private set

        @Volatile
        var activeRouting: String = "افتراضي"
            private set

        @Volatile
        var activeEndpoint: String = ""
            private set

        @Volatile
        var lastLatencyMs: Long = -1L
            private set

        @Volatile
        var lastError: String = ""
            private set
    }
}
