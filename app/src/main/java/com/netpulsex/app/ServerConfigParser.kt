package com.netpulsex.app

import java.net.URI

data class ParsedServerConfig(
    val protocol: String,
    val host: String,
    val port: Int,
    val security: String = "",
    val rawInput: String = ""
)

object ServerConfigParser {

    fun parse(protocol: String, rawInput: String): Result<ParsedServerConfig> {
        val clean = rawInput.trim()
        if (clean.isEmpty()) {
            return Result.failure(IllegalArgumentException("حقل السيرفر فارغ"))
        }

        val normalizedProto = protocol.trim().uppercase()
        if (normalizedProto == "IP_PROXY" || normalizedProto == "IP PROXY" || normalizedProto == "IP") {
            val noScheme = clean.replace(Regex("^[a-zA-Z0-9+.-]+://"), "")
                .split("#").first()
                .split("?").first()
                .split("/").first()
                .trim()
            val hostPort = if (noScheme.contains("@")) noScheme.substringAfterLast("@") else noScheme
            val colonIdx = hostPort.lastIndexOf(':')
            if (colonIdx <= 0) {
                return Result.failure(IllegalArgumentException("أدخل عنوان IP والمنفذ بصيغة IP:PORT مثل 197.232.158.189:41890"))
            }
            val host = hostPort.substring(0, colonIdx).trim().removeSurrounding("[", "]")
            val port = hostPort.substring(colonIdx + 1).trim().toIntOrNull()
            if (host.isEmpty() || port == null || port !in 1..65535) {
                return Result.failure(IllegalArgumentException("عنوان IP أو المنفذ غير صالح في $clean"))
            }
            return Result.success(
                ParsedServerConfig(
                    protocol = "IP_PROXY",
                    host = host,
                    port = port,
                    security = "none",
                    rawInput = clean
                )
            )
        }

        val uriCandidate = if (clean.contains("://")) clean else "${normalizedProto.lowercase()}://$clean"
        return try {
            val uri = URI(uriCandidate)
            val host = uri.host?.trim().orEmpty()
            val port = if (uri.port in 1..65535) uri.port else defaultPortFor(normalizedProto)
            if (host.isEmpty() || port !in 1..65535) {
                Result.failure(IllegalArgumentException("تعذر استخراج المضيف أو المنفذ من الإعداد"))
            } else {
                val query = uri.rawQuery.orEmpty()
                val sec = Regex("(?:security|tls)=([^&]+)").find(query)?.groupValues?.get(1).orEmpty()
                Result.success(
                    ParsedServerConfig(
                        protocol = normalizedProto,
                        host = host,
                        port = port,
                        security = sec,
                        rawInput = clean
                    )
                )
            }
        } catch (_: Exception) {
            val noScheme = clean.replace(Regex("^[a-zA-Z0-9+.-]+://"), "")
                .split("#").first()
                .split("?").first()
                .split("/").first()
            val hostPort = if (noScheme.contains("@")) noScheme.substringAfterLast("@") else noScheme
            val parts = hostPort.split(":")
            if (parts.size == 2) {
                val host = parts[0].trim()
                val port = parts[1].trim().toIntOrNull()
                if (host.isNotEmpty() && port != null && port in 1..65535) {
                    return Result.success(
                        ParsedServerConfig(
                            protocol = normalizedProto,
                            host = host,
                            port = port,
                            rawInput = clean
                        )
                    )
                }
            }
            Result.failure(IllegalArgumentException("صيغة رابط السيرفر غير مدعومة أو ناقصة"))
        }
    }

    private fun defaultPortFor(proto: String): Int = when (proto) {
        "VLESS", "VMESS", "TROJAN" -> 443
        "SHADOWSOCKS" -> 8388
        "SOCKS5", "IP_PROXY" -> 1080
        "WIREGUARD" -> 51820
        else -> 443
    }
}
