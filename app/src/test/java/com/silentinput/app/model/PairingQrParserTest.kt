package com.silentinput.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingQrParserTest {

    private val validToken = "si_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef0123456789-"
    private val validFp = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun `strong token validator accepts si 256-bit format and rejects legacy or uuid`() {
        assertEquals(46, validToken.length)
        assertTrue(PairingTokenValidator.isStrongToken(validToken))
        assertTrue(PairingTokenValidator.isStrongToken("  $validToken \n"))

        // Reject legacy 8-char hex, UUIDv4, wrong prefix, or invalid Base64URL chars
        assertFalse(PairingTokenValidator.isStrongToken(""))
        assertFalse(PairingTokenValidator.isStrongToken("ab12cd34"))
        assertFalse(PairingTokenValidator.isStrongToken("550e8400-e29b-41d4-a716-446655440000"))
        assertFalse(PairingTokenValidator.isStrongToken("xx_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef0123456789-"))
        assertFalse(PairingTokenValidator.isStrongToken("si_short"))
        assertFalse(PairingTokenValidator.isStrongToken("si_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef0123456789+"))

        assertEquals(validToken, PairingTokenValidator.sanitizeMigratedToken(validToken))
        assertEquals("", PairingTokenValidator.sanitizeMigratedToken("ab12cd34"))
    }

    @Test
    fun `parse extracts host ips port strong token tls and fingerprint`() {
        val uri = "silentinput://pair?v=1&name=My%20Workstation&ips=192.168.1.10%2C100.64.0.5%2C127.0.0.1&port=53318&token=$validToken&tls=1&fp=${validFp.uppercase()}"
        val parsed = PairingQrParser.parse(uri)
        assertNotNull(parsed)
        assertEquals("My Workstation", parsed!!.name)
        // Loopback IP 127.0.0.1 is filtered out automatically
        assertEquals(listOf("192.168.1.10", "100.64.0.5"), parsed.ips)
        assertEquals(53318, parsed.port)
        assertEquals(validToken, parsed.token)
        assertTrue(parsed.tls)
        assertEquals(validFp, parsed.fingerprint)
    }

    @Test
    fun `parse rejects unsupported version weak token bad port or corrupted fingerprint`() {
        assertNull(PairingQrParser.parse("https://example.com"))
        assertNull(PairingQrParser.parse("silentinput://pair?v=2&name=pc&ips=192.168.1.2&port=53318&token=$validToken&tls=1"))
        assertNull(PairingQrParser.parse("silentinput://pair?v=1&name=pc&ips=192.168.1.2&port=53318&token=deadbeef&tls=1"))
        assertNull(PairingQrParser.parse("silentinput://pair?v=1&name=pc&ips=192.168.1.2&port=0&token=$validToken&tls=1"))
        assertNull(PairingQrParser.parse("silentinput://pair?v=1&name=pc&ips=192.168.1.2&port=53318&token=$validToken&tls=1&fp=not_64_hex"))
    }

    @Test
    fun `formatEndpoint omits default port and keeps custom port`() {
        assertEquals("192.168.1.20", PairingQrParser.formatEndpoint("192.168.1.20", 53318))
        assertEquals("192.168.1.20:54000", PairingQrParser.formatEndpoint("192.168.1.20", 54000))
    }
}
