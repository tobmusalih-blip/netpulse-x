package com.netpulsex.app

import android.content.Context
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocketFactory

object ProxyTunnelEngine {

    data class TunnelResult(
        val ok: Boolean,
        val detail: String,
        val latencyMs: Long = -1L,
        val mode: String = "DIRECT_PROBE"
    )

    fun establishConnection(context: Context, config: ParsedServerConfig): TunnelResult {
        val customCore = File(context.filesDir, "core/xray")
        val coreNote = if (customCore.exists() && customCore.canExecute()) {
            "CORE_BIN_READY"
        } else {
            "NATIVE_SOCKET_TUNNEL"
        }

        val start = System.currentTimeMillis()
        return try {
            if (config.protocol == "SOCKS5") {
                Socket().use { socket ->
                    socket.soTimeout = 5000
                    socket.connect(InetSocketAddress(config.host, config.port), 5000)
                    val out = socket.getOutputStream()
                    val inp = socket.getInputStream()
                    out.write(byteArrayOf(0x05, 0x01, 0x00))
                    out.flush()
                    val ver = inp.read()
                    val method = inp.read()
                    val rtt = System.currentTimeMillis() - start
                    if (ver == 0x05 && method != -1) {
                        TunnelResult(
                            ok = true,
                            detail = "SOCKS5 handshake OK (${config.host}:${config.port}) • $rtt ms",
                            latencyMs = rtt,
                            mode = coreNote
                        )
                    } else {
                        TunnelResult(
                            ok = true,
                            detail = "SOCKS5 TCP connected (${config.host}:${config.port}) • $rtt ms",
                            latencyMs = rtt,
                            mode = coreNote
                        )
                    }
                }
            } else if (config.security.equals("tls", true) || config.security.equals("reality", true) || config.protocol == "TROJAN") {
                val factory = SSLSocketFactory.getDefault()
                factory.createSocket().use { rawSocket ->
                    rawSocket.soTimeout = 5000
                    rawSocket.connect(InetSocketAddress(config.host, config.port), 5000)
                    val rtt = System.currentTimeMillis() - start
                    TunnelResult(
                        ok = true,
                        detail = "${config.protocol} TLS endpoint reachable (${config.host}:${config.port}) • $rtt ms",
                        latencyMs = rtt,
                        mode = coreNote
                    )
                }
            } else {
                Socket().use { socket ->
                    socket.soTimeout = 5000
                    socket.connect(InetSocketAddress(config.host, config.port), 5000)
                    val rtt = System.currentTimeMillis() - start
                    TunnelResult(
                        ok = true,
                        detail = "${config.protocol} endpoint reachable (${config.host}:${config.port}) • $rtt ms",
                        latencyMs = rtt,
                        mode = coreNote
                    )
                }
            }
        } catch (e: Exception) {
            TunnelResult(
                ok = false,
                detail = "فشل الوصول إلى ${config.host}:${config.port} (${e.message ?: "خطأ شبكة"})",
                latencyMs = -1L,
                mode = coreNote
            )
        }
    }
}
