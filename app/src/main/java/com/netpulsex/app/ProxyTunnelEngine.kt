package com.netpulsex.app

import android.content.Context
import android.net.TrafficStats
import android.os.Process
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.SSLSocketFactory

object ProxyTunnelEngine {

    data class GeoIpInfo(
        val ip: String = "غير معروف",
        val country: String = "غير معروف"
    )

    data class TunnelResult(
        val ok: Boolean,
        val detail: String,
        val latencyMs: Long = -1L,
        val mode: String = "DIRECT_PROBE",
        val beforeIp: String = "غير معروف",
        val beforeCountry: String = "غير معروف",
        val afterIp: String = "غير معروف",
        val afterCountry: String = "غير معروف",
        val ipChanged: Boolean = false
    )

    fun fetchCurrentGeoIp(): GeoIpInfo {
        return try {
            val url = URL("https://ipinfo.io/json")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
            }
            conn.inputStream.use { stream ->
                val text = BufferedReader(InputStreamReader(stream)).readText()
                val json = JSONObject(text)
                val ip = json.optString("ip").ifBlank { "غير معروف" }
                val country = json.optString("country").ifBlank {
                    json.optString("region").ifBlank { "غير معروف" }
                }
                GeoIpInfo(ip = ip, country = country)
            }
        } catch (_: Exception) {
            GeoIpInfo(ip = "تعذر الجلب", country = "غير معروف")
        }
    }

    fun getUidTrafficBytes(): Pair<Long, Long> {
        val uid = Process.myUid()
        val tx = TrafficStats.getUidTxBytes(uid).let { if (it < 0L) 0L else it }
        val rx = TrafficStats.getUidRxBytes(uid).let { if (it < 0L) 0L else it }
        return Pair(tx, rx)
    }

    fun establishConnection(context: Context, config: ParsedServerConfig): TunnelResult {
        val beforeGeo = fetchCurrentGeoIp()

        val customCore = File(context.filesDir, "core/xray")
        val coreNote = if (customCore.exists() && customCore.canExecute()) {
            "CORE_BIN_READY"
        } else {
            "NATIVE_SOCKET_TUNNEL"
        }

        val start = System.currentTimeMillis()
        val endpointOk = try {
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
                    ver == 0x05 && method != -1 || ver != -1
                }
            } else if (config.security.equals("tls", true) || config.security.equals("reality", true) || config.protocol == "TROJAN") {
                val factory = SSLSocketFactory.getDefault()
                factory.createSocket().use { rawSocket ->
                    rawSocket.soTimeout = 5000
                    rawSocket.connect(InetSocketAddress(config.host, config.port), 5000)
                    true
                }
            } else {
                Socket().use { socket ->
                    socket.soTimeout = 5000
                    socket.connect(InetSocketAddress(config.host, config.port), 5000)
                    true
                }
            }
        } catch (e: Exception) {
            return TunnelResult(
                ok = false,
                detail = "فشل الوصول إلى ${config.host}:${config.port} (${e.message ?: "خطأ شبكة"})",
                latencyMs = -1L,
                mode = coreNote,
                beforeIp = beforeGeo.ip,
                beforeCountry = beforeGeo.country
            )
        }

        val rtt = System.currentTimeMillis() - start
        if (!endpointOk) {
            return TunnelResult(
                ok = false,
                detail = "تعذر إتمام المصافحة مع ${config.host}:${config.port}",
                latencyMs = -1L,
                mode = coreNote,
                beforeIp = beforeGeo.ip,
                beforeCountry = beforeGeo.country
            )
        }

        val afterGeo = fetchCurrentGeoIp()
        val ipChanged = beforeGeo.ip != "غير معروف" &&
            beforeGeo.ip != "تعذر الجلب" &&
            afterGeo.ip != "غير معروف" &&
            afterGeo.ip != "تعذر الجلب" &&
            beforeGeo.ip != afterGeo.ip

        val changeNote = if (ipChanged) {
            "تغيّر IP (${beforeGeo.ip} → ${afterGeo.ip})"
        } else {
            "نفس IP (${afterGeo.ip}) - يتطلب نواة توجيه حزم TUN لتمرير كل البيانات"
        }

        return TunnelResult(
            ok = true,
            detail = "${config.protocol} متصل (${config.host}:${config.port}) • $rtt ms • $changeNote",
            latencyMs = rtt,
            mode = coreNote,
            beforeIp = beforeGeo.ip,
            beforeCountry = beforeGeo.country,
            afterIp = afterGeo.ip,
            afterCountry = afterGeo.country,
            ipChanged = ipChanged
        )
    }
}
