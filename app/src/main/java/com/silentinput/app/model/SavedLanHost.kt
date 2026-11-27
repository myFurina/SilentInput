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
            val objects = SimpleJson.parseObjectArray(rawJson) ?: return emptyList()
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
            sb.append("\"id\":").append(SimpleJson.quoteString(host.id)).append(',')
            sb.append("\"name\":").append(SimpleJson.quoteString(host.name)).append(',')
            sb.append("\"endpoint\":").append(SimpleJson.quoteString(host.endpoint)).append(',')
            sb.append("\"token\":").append(SimpleJson.quoteString(host.token)).append(',')
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
}
