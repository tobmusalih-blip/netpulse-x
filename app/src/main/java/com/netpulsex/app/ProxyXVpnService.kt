package com.netpulsex.app

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.IOException

class ProxyXVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null

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
        if (vpnInterface != null) return
        try {
            val builder = Builder()
                .setSession("PROXY X ($protocol)")
                .addAddress("10.66.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .setMtu(1420)

            // في التطوير المستقبلي هنا يتم تمرير fileDescriptor و serverUri/routing إلى نواة sing-box أو xray-core
            vpnInterface = builder.establish()
            isRunning = true
            activeProtocol = protocol
            activeServer = serverUri
            activeRouting = routing
        } catch (_: Exception) {
            isRunning = false
        }
    }

    private fun stopTunnel() {
        try {
            vpnInterface?.close()
        } catch (_: IOException) {
        } finally {
            vpnInterface = null
            isRunning = false
        }
    }

    override fun onDestroy() {
        stopTunnel()
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
    }
}
