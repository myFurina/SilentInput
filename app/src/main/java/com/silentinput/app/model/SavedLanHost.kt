package com.silentinput.app.model

import java.util.Locale
import java.util.UUID

data class SavedLanHost(
    val id: String,
    val name: String,
    val endpoint: String,
    val token: String,
    val tlsEnabled: Boolean,
    val isFavorite: Boolean,
    val lastConnectedAtMs: Long
)

object SavedLanHostsStore {
    const val MAX_HOSTS = 32

    fun fromJson(rawJson: String?): List<SavedLanHost> {
        if (rawJson.isNullOrBlank()) return emptyList()
        return try {
            val objects = parseJsonObjectArray(rawJson) ?: return emptyList()
            val seenEndpoints = HashSet<String>()
            val out = ArrayList<SavedLanHost>(objects.size)
            for (obj in objects) {
                val endpoint = (obj["endpoint"] as? String)?.trim().orEmpty()
                if (endpoint.isEmpty()) continue
                val key = endpoint.lowercase(Locale.ROOT)
                if (!seenEndpoints.add(key)) continue
                val id = (obj["id"] as? String)?.trim().orEmpty().ifEmpty { UUID.randomUUID().toString() }
                val name = (obj["name"] as? String)?.trim().orEmpty().ifEmpty { endpoint }
                val token = PairingTokenValidator.sanitizeMigratedToken(obj["token"] as? String)
                val tlsEnabled = (obj["tlsEnabled"] as? Boolean) ?: true
                val isFavorite = (obj["isFavorite"] as? Boolean) ?: false
                val lastConnectedAtMs = ((obj["lastConnectedAtMs"] as? Number)?.toLong() ?: 0L).coerceAtLeast(0L)
                out.add(
                    SavedLanHost(
                        id = id,
                        name = name,
                        endpoint = endpoint,
                        token = token,
                        tlsEnabled = tlsEnabled,
                        isFavorite = isFavorite,
                        lastConnectedAtMs = lastConnectedAtMs
                    )
                )
            }
            sorted(out)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun toJson(hosts: List<SavedLanHost>): String {
        val sb = StringBuilder()
        sb.append('[')
        val list = sorted(hosts)
        for ((idx, host) in list.withIndex()) {
            if (idx > 0) sb.append(',')
            sb.append('{')
            sb.append("\"id\":").append(jsonString(host.id)).append(',')
            sb.append("\"name\":").append(jsonString(host.name)).append(',')
            sb.append("\"endpoint\":").append(jsonString(host.endpoint)).append(',')
            sb.append("\"token\":").append(jsonString(host.token)).append(',')
            sb.append("\"tlsEnabled\":").append(host.tlsEnabled).append(',')
            sb.append("\"isFavorite\":").append(host.isFavorite).append(',')
            sb.append("\"lastConnectedAtMs\":").append(host.lastConnectedAtMs)
            sb.append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    fun sorted(hosts: List<SavedLanHost>): List<SavedLanHost> {
        return hosts.sortedWith(
            compareByDescending<SavedLanHost> { it.isFavorite }
                .thenByDescending { it.lastConnectedAtMs }
                .thenBy { it.name.lowercase(Locale.ROOT) }
        )
    }

    fun upsert(
        hosts: List<SavedLanHost>,
        name: String?,
        endpoint: String,
        token: String,
        tlsEnabled: Boolean,
        nowMs: Long,
        markConnected: Boolean,
        preserveFavorite: Boolean = true
    ): List<SavedLanHost> {
        val cleanEndpoint = endpoint.trim()
        if (cleanEndpoint.isEmpty()) return sorted(hosts)
        val cleanToken = PairingTokenValidator.sanitizeMigratedToken(token)
        val endpointKey = cleanEndpoint.lowercase(Locale.ROOT)
        val existing = hosts.firstOrNull { it.endpoint.trim().lowercase(Locale.ROOT) == endpointKey }

        val resolvedName = name?.trim().orEmpty().ifEmpty {
            existing?.name ?: cleanEndpoint
        }
        val updatedEntry = if (existing != null) {
            existing.copy(
                name = resolvedName,
                endpoint = cleanEndpoint,
                token = cleanToken.ifEmpty { existing.token },
                tlsEnabled = tlsEnabled,
                isFavorite = if (preserveFavorite) existing.isFavorite else false,
                lastConnectedAtMs = if (markConnected) nowMs else existing.lastConnectedAtMs
            )
        } else {
            SavedLanHost(
                id = UUID.randomUUID().toString(),
                name = resolvedName,
                endpoint = cleanEndpoint,
                token = cleanToken,
                tlsEnabled = tlsEnabled,
                isFavorite = false,
                lastConnectedAtMs = if (markConnected) nowMs else 0L
            )
        }

        val combined = hosts.filterNot {
            it.endpoint.trim().lowercase(Locale.ROOT) == endpointKey
        } + updatedEntry

        val trimmed = if (combined.size > MAX_HOSTS) {
            sorted(combined).take(MAX_HOSTS)
        } else {
            sorted(combined)
        }
        return trimmed
    }

    fun toggleFavorite(hosts: List<SavedLanHost>, id: String): List<SavedLanHost> {
        return sorted(
            hosts.map { if (it.id == id) it.copy(isFavorite = !it.isFavorite) else it }
        )
    }

    fun rename(hosts: List<SavedLanHost>, id: String, newName: String): List<SavedLanHost> {
        val clean = newName.trim()
        if (clean.isEmpty()) return sorted(hosts)
        return sorted(
            hosts.map { if (it.id == id) it.copy(name = clean) else it }
        )
    }

    fun remove(hosts: List<SavedLanHost>, id: String): List<SavedLanHost> {
        return sorted(hosts.filterNot { it.id == id })
    }

    fun migrateLegacyHost(
        existingHosts: List<SavedLanHost>,
        legacyIp: String?,
        legacyToken: String?,
        tlsEnabled: Boolean
    ): List<SavedLanHost> {
        if (existingHosts.isNotEmpty()) return sorted(existingHosts)
        val cleanIp = legacyIp?.trim().orEmpty()
        if (cleanIp.isEmpty()) return emptyList()
        val lower = cleanIp.lowercase(Locale.ROOT)
        if (lower == "localhost" || lower == "::1" || lower.startsWith("127.")) {
            return emptyList()
        }
        return listOf(
            SavedLanHost(
                id = UUID.randomUUID().toString(),
                name = cleanIp,
                endpoint = cleanIp,
                token = PairingTokenValidator.sanitizeMigratedToken(legacyToken),
                tlsEnabled = tlsEnabled,
                isFavorite = false,
                lastConnectedAtMs = 0L
            )
        )
    }

    private fun jsonString(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun parseJsonObjectArray(raw: String): List<Map<String, Any?>>? {
        val parser = MiniJsonParser(raw)
        parser.skipWs()
        if (!parser.consume('[')) return null
        val list = ArrayList<Map<String, Any?>>()
        parser.skipWs()
        if (parser.consume(']')) return list
        while (true) {
            parser.skipWs()
            val obj = parser.parseObject() ?: return null
            list.add(obj)
            parser.skipWs()
            when {
                parser.consume(',') -> continue
                parser.consume(']') -> return list
                else -> return null
            }
        }
    }

    private class MiniJsonParser(private val src: String) {
        private var pos = 0

        fun skipWs() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        fun consume(ch: Char): Boolean {
            if (pos < src.length && src[pos] == ch) {
                pos++
                return true
            }
            return false
        }

        fun parseObject(): Map<String, Any?>? {
            if (!consume('{')) return null
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (consume('}')) return map
            while (true) {
                skipWs()
                val key = parseString() ?: return null
                skipWs()
                if (!consume(':')) return null
                skipWs()
                val value = parseValue()
                map[key] = value
                skipWs()
                when {
                    consume(',') -> continue
                    consume('}') -> return map
                    else -> return null
                }
            }
        }

        private fun parseValue(): Any? {
            if (pos >= src.length) return null
            return when (src[pos]) {
                '"' -> parseString()
                't' -> if (src.startsWith("true", pos)) {
                    pos += 4
                    true
                } else {
                    null
                }
                'f' -> if (src.startsWith("false", pos)) {
                    pos += 5
                    false
                } else {
                    null
                }
                'n' -> if (src.startsWith("null", pos)) {
                    pos += 4
                    null
                } else {
                    null
                }
                '-', in '0'..'9' -> parseNumber()
                else -> null
            }
        }

        private fun parseString(): String? {
            if (!consume('"')) return null
            val sb = StringBuilder()
            while (pos < src.length) {
                val c = src[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= src.length) return null
                        when (val esc = src[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > src.length) return null
                                val code = src.substring(pos, pos + 4).toIntOrNull(16) ?: return null
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> sb.append(esc)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            return null
        }

        private fun parseNumber(): Long? {
            val start = pos
            if (pos < src.length && src[pos] == '-') pos++
            while (pos < src.length && src[pos] in '0'..'9') pos++
            return src.substring(start, pos).toLongOrNull()
        }
    }
}
