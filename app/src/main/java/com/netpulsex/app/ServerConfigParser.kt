package com.netpulsex.app

import android.net.Uri
import android.util.Base64
import org.json.JSONObject

data class ParsedServerConfig(
    val protocol: String,
    val host: String,
    val port: Int,
    val secret: String = "",
    val sni: String = "",
    val security: String = "none",
    val network: String = "tcp",
    val rawInput: String
)

object ServerConfigParser {

    fun parse(selectedProtocol: String, rawInput: String): Result<ParsedServerConfig> {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("يرجى إدخال رابط السيرفر أو العنوان (IP:PORT) أولاً"))
        }

        if (!trimmed.contains("://")) {
            val hostPort = parseHostPort(trimmed)
                ?: return Result.failure(IllegalArgumentException("صيغة العنوان غير صحيحة. استخدم host:port أو رابط بروكسي"))
            return Result.success(
                ParsedServerConfig(
                    protocol = selectedProtocol.uppercase(),
                    host = hostPort.first,
                    port = hostPort.second,
                    rawInput = trimmed
                )
            )
        }

        return try {
            when {
                trimmed.startsWith("vmess://", ignoreCase = true) -> parseVmess(trimmed, selectedProtocol)
                trimmed.startsWith("vless://", ignoreCase = true) -> parseStandardUri(trimmed, "VLESS")
                trimmed.startsWith("trojan://", ignoreCase = true) -> parseStandardUri(trimmed, "TROJAN")
                trimmed.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(trimmed)
                trimmed.startsWith("socks5://", ignoreCase = true) || trimmed.startsWith("socks://", ignoreCase = true) ->
                    parseStandardUri(trimmed, "SOCKS5")
                trimmed.startsWith("wireguard://", ignoreCase = true) || trimmed.startsWith("wg://", ignoreCase = true) ->
                    parseStandardUri(trimmed, "WIREGUARD")
                else -> {
                    val uri = Uri.parse(trimmed)
                    val host = uri.host
                    val port = if (uri.port > 0) uri.port else 443
                    if (host.isNullOrBlank()) {
                        Result.failure(IllegalArgumentException("تعذر استخراج المضيف (Host) من الرابط"))
                    } else {
                        Result.success(
                            ParsedServerConfig(
                                protocol = selectedProtocol.uppercase(),
                                host = host,
                                port = port,
                                sni = uri.getQueryParameter("sni") ?: "",
                                security = uri.getQueryParameter("security") ?: "none",
                                network = uri.getQueryParameter("type") ?: "tcp",
                                rawInput = trimmed
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("فشل تحليل رابط السيرفر: ${e.message ?: "صيغة غير مدعومة"}"))
        }
    }

    private fun parseHostPort(input: String): Pair<String, Int>? {
        val clean = input.substringBefore("#").trim()
        val lastColon = clean.lastIndexOf(':')
        if (lastColon <= 0) return null
        val host = clean.substring(0, lastColon).removeSurrounding("[", "]").trim()
        val port = clean.substring(lastColon + 1).trim().toIntOrNull() ?: return null
        if (host.isEmpty() || port !in 1..65535) return null
        return Pair(host, port)
    }

    private fun parseStandardUri(raw: String, protocol: String): Result<ParsedServerConfig> {
        val uri = Uri.parse(raw)
        val host = uri.host?.trim().orEmpty()
        val port = if (uri.port in 1..65535) uri.port else 443
        if (host.isEmpty()) {
            return Result.failure(IllegalArgumentException("رابط $protocol يفتقر إلى عنوان المضيف أو المنفذ"))
        }
        val secret = uri.userInfo ?: ""
        val sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("peer") ?: uri.getQueryParameter("host") ?: ""
        val security = uri.getQueryParameter("security") ?: if (protocol == "TROJAN") "tls" else "none"
        val network = uri.getQueryParameter("type") ?: uri.getQueryParameter("network") ?: "tcp"

        return Result.success(
            ParsedServerConfig(
                protocol = protocol,
                host = host,
                port = port,
                secret = secret,
                sni = sni,
                security = security,
                network = network,
                rawInput = raw
            )
        )
    }

    private fun parseVmess(raw: String, fallbackProtocol: String): Result<ParsedServerConfig> {
        val payload = raw.removePrefix("vmess://").removePrefix("VMESS://").substringBefore("#").trim()
        val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
        val decoded = String(Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
        val json = JSONObject(decoded)
        val host = json.optString("add").trim()
        val port = json.optString("port").toIntOrNull() ?: json.optInt("port", 443)
        if (host.isEmpty() || port !in 1..65535) {
            return Result.failure(IllegalArgumentException("بيانات VMess غير مكتملة (add/port)"))
        }
        return Result.success(
            ParsedServerConfig(
                protocol = fallbackProtocol.uppercase(),
                host = host,
                port = port,
                secret = json.optString("id"),
                sni = json.optString("sni").ifEmpty { json.optString("host") },
                security = json.optString("tls").ifEmpty { "none" },
                network = json.optString("net").ifEmpty { "tcp" },
                rawInput = raw
            )
        )
    }

    private fun parseShadowsocks(raw: String): Result<ParsedServerConfig> {
        val withoutScheme = raw.removePrefix("ss://").removePrefix("SS://").substringBefore("#")
        if (withoutScheme.contains("@")) {
            val parts = withoutScheme.split("@", limit = 2)
            val hostPort = parseHostPort(parts[1])
                ?: return Result.failure(IllegalArgumentException("منفذ أو مضيف Shadowsocks غير صالح"))
            return Result.success(
                ParsedServerConfig(
                    protocol = "SHADOWSOCKS",
                    host = hostPort.first,
                    port = hostPort.second,
                    secret = parts[0],
                    rawInput = raw
                )
            )
        }
        val padded = withoutScheme + "=".repeat((4 - withoutScheme.length % 4) % 4)
        val decoded = String(Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
        val atIdx = decoded.lastIndexOf('@')
        if (atIdx <= 0) {
            return Result.failure(IllegalArgumentException("صيغة Shadowsocks غير صالحة"))
        }
        val methodPass = decoded.substring(0, atIdx)
        val hostPort = parseHostPort(decoded.substring(atIdx + 1))
            ?: return Result.failure(IllegalArgumentException("عنوان Shadowsocks غير صالح"))
        return Result.success(
            ParsedServerConfig(
                protocol = "SHADOWSOCKS",
                host = hostPort.first,
                port = hostPort.second,
                secret = methodPass,
                rawInput = raw
            )
        )
    }
}
