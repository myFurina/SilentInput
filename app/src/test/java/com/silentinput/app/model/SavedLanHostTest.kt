package com.silentinput.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedLanHostTest {

    private val tokenA = "si_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef0123456789-"
    private val tokenB = "si_0123456789-_abcdefghijklmnopqrstuvwxyzABCDEF"

    @Test
    fun `upsert sorts favorites first then most recently connected and deduplicates endpoints`() {
        var hosts = SavedLanHostsStore.upsert(
            hosts = emptyList(),
            name = "Office PC",
            endpoint = "10.0.0.5",
            token = tokenA,
            tlsEnabled = true,
            nowMs = 100L,
            markConnected = true
        )
        hosts = SavedLanHostsStore.upsert(
            hosts = hosts,
            name = "Home PC",
            endpoint = "192.168.1.10",
            token = tokenB,
            tlsEnabled = false,
            nowMs = 200L,
            markConnected = true
        )
        assertEquals(listOf("Home PC", "Office PC"), hosts.map { it.name })

        // Pinning Office PC brings it to the top ahead of more recent Home PC
        val officeId = hosts.first { it.endpoint == "10.0.0.5" }.id
        hosts = SavedLanHostsStore.toggleFavorite(hosts, officeId)
        assertTrue(hosts.first().isFavorite)
        assertEquals("Office PC", hosts.first().name)

        // Updating same endpoint preserves favorite & id, updates token/time
        hosts = SavedLanHostsStore.upsert(
            hosts = hosts,
            name = "Office PC 2",
            endpoint = "10.0.0.5",
            token = tokenB,
            tlsEnabled = true,
            nowMs = 300L,
            markConnected = true
        )
        assertEquals(2, hosts.size)
        assertEquals(officeId, hosts.first().id)
        assertTrue(hosts.first().isFavorite)
        assertEquals(tokenB, hosts.first().token)

        // JSON roundtrip preserves host list and drops any weak tokens on reload
        val json = SavedLanHostsStore.toJson(hosts)
        val reloaded = SavedLanHostsStore.fromJson(json)
        assertEquals(2, reloaded.size)
        assertEquals("Office PC 2", reloaded.first().name)
        assertEquals(tokenB, reloaded.first().token)

        val injectedWeakJson = json.replace(tokenB, "deadbeef")
        val reloadedWeak = SavedLanHostsStore.fromJson(injectedWeakJson)
        assertEquals("", reloadedWeak.first().token)
    }

    @Test
    fun `migrateLegacyHost preserves ip and drops weak 8-char legacy token`() {
        val migratedWeak = SavedLanHostsStore.migrateLegacyHost(
            existingHosts = emptyList(),
            legacyIp = "192.168.1.50",
            legacyToken = "ab12cd34",
            tlsEnabled = true
        )
        assertEquals(1, migratedWeak.size)
        assertEquals("192.168.1.50", migratedWeak.first().endpoint)
        assertEquals("", migratedWeak.first().token)
        assertFalse(migratedWeak.first().isFavorite)

        // Strong token survives migration
        val migratedStrong = SavedLanHostsStore.migrateLegacyHost(
            existingHosts = emptyList(),
            legacyIp = "192.168.1.50",
            legacyToken = tokenA,
            tlsEnabled = true
        )
        assertEquals(tokenA, migratedStrong.first().token)

        // Loopback legacy IP is ignored
        assertTrue(
            SavedLanHostsStore.migrateLegacyHost(
                existingHosts = emptyList(),
                legacyIp = "127.0.0.1",
                legacyToken = tokenA,
                tlsEnabled = true
            ).isEmpty()
        )
    }

    @Test
    fun `rename and remove update list properly`() {
        var hosts = SavedLanHostsStore.upsert(
            hosts = emptyList(),
            name = "pc-1",
            endpoint = "192.168.1.88",
            token = tokenA,
            tlsEnabled = true,
            nowMs = 100L,
            markConnected = true
        )
        val id = hosts.first().id
        hosts = SavedLanHostsStore.rename(hosts, id, "Work Laptop")
        assertEquals("Work Laptop", hosts.first().name)
        hosts = SavedLanHostsStore.remove(hosts, id)
        assertTrue(hosts.isEmpty())
    }
}
