package com.silentinput.app.model

import java.nio.charset.StandardCharsets

object PairingTokenValidator {
    const val TOKEN_PREFIX = "si_"
    const val STRONG_TOKEN_LEN = 46

    fun isStrongToken(raw: String?): Boolean {
        val clean = raw?.trim().orEmpty()
        if (clean.length != STRONG_TOKEN_LEN || !clean.startsWith(TOKEN_PREFIX)) {
            return false
        }
        for (i in TOKEN_PREFIX.length until clean.length) {
            val c = clean[i]
            val valid = (c in 'A'..'Z') ||
                (c in 'a'..'z') ||
                (c in '0'..'9') ||
                c == '-' ||
                c == '_'
            if (!valid) return false
        }
        return true
    }

    fun sanitizeMigratedToken(raw: String?): String {
        val clean = raw?.trim().orEmpty()
        return if (isStrongToken(clean)) clean else ""
    }
}

data class PairingQrPayload(
    val name: String,
    val ips: List<String>,
    val port: Int,
    val token: String,
    val tls: Boolean,
    val fingerprint: String?
)

object PairingQrParser {
    private const val URI_PREFIX = "silentinput://pair?"

    fun parse(rawUri: String?): PairingQrPayload? {
        val trimmed = rawUri?.trim().orEmpty()
        if (!trimmed.startsWith(URI_PREFIX)) return null
        val query = trimmed.removePrefix(URI_PREFIX)
        if (query.isEmpty()) return null

        var version: String? = null
        var name = ""
        var ips: List<String> = emptyList()
        var port: Int? = null
        var token = ""
        var tls = true
        var fingerprint: String? = null

        for (pair in query.split('&')) {
            val eqIdx = pair.indexOf('=')
            if (eqIdx <= 0) return null
            val key = pair.substring(0, eqIdx)
            val decoded = percentDecode(pair.substring(eqIdx + 1)) ?: return null
            when (key) {
                "v" -> version = decoded
                "name" -> name = decoded.trim()
                "ips" -> {
                    ips = decoded.split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && !isLoopbackLiteral(it) }
                        .distinct()
                }
                "port" -> {
                    val p = decoded.toIntOrNull()
                    port = if (p != null && p in 1..65535) p else null
                }
                "token" -> token = decoded.trim()
                "tls" -> tls = decoded == "1" || decoded.equals("true", ignoreCase = true)
                "fp" -> {
                    val cleanFp = decoded.trim().lowercase()
                    if (cleanFp.isNotEmpty()) {
                        if (cleanFp.length != 64 || !cleanFp.all { it in '0'..'9' || it in 'a'..'f' }) {
                            return null
                        }
                        fingerprint = cleanFp
                    }
                }
            }
        }

        if (version != "1") return null
        val validPort = port ?: return null
        if (!PairingTokenValidator.isStrongToken(token)) return null

        return PairingQrPayload(
            name = name.ifBlank { ips.firstOrNull().orEmpty() },
            ips = ips,
            port = validPort,
            token = token,
            tls = tls,
            fingerprint = fingerprint
        )
    }

    fun formatEndpoint(ip: String, port: Int, defaultPort: Int = 53318): String {
        val cleanIp = ip.trim()
        return when {
            port == defaultPort -> cleanIp
            cleanIp.contains(':') && !cleanIp.startsWith("[") -> "[$cleanIp]:$port"
            else -> "$cleanIp:$port"
        }
    }

    private fun isLoopbackLiteral(rawHost: String): Boolean {
        val lower = rawHost.trim().lowercase()
        return lower == "localhost" ||
            lower == "::1" ||
            lower == "0:0:0:0:0:0:0:1" ||
            lower.startsWith("127.")
    }

    private fun percentDecode(raw: String): String? {
        val out = ArrayList<Byte>(raw.length)
        var i = 0
        while (i < raw.length) {
            when (val ch = raw[i]) {
                '%' -> {
                    if (i + 2 >= raw.length) return null
                    val hi = raw[i + 1].digitToIntOrNull(16) ?: return null
                    val lo = raw[i + 2].digitToIntOrNull(16) ?: return null
                    out.add(((hi shl 4) or lo).toByte())
                    i += 3
                }
                '+' -> {
                    out.add(' '.code.toByte())
                    i++
                }
                else -> {
                    val bytes = ch.toString().toByteArray(StandardCharsets.UTF_8)
                    for (b in bytes) out.add(b)
                    i++
                }
            }
        }
        return String(out.toByteArray(), StandardCharsets.UTF_8)
    }
}
