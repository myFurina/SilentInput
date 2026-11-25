package com.silentinput.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.silentinput.app.network.UdpMouseCodec
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UdpMouseCodecTest {

    @Test
    fun `parseSessionIdHex and decodeAes256Key validate inputs strictly`() {
        assertNull(UdpMouseCodec.parseSessionIdHex(""))
        assertNull(UdpMouseCodec.parseSessionIdHex("0000000000000000"))
        assertNull(UdpMouseCodec.parseSessionIdHex("zzzz"))
        assertEquals(-1L, UdpMouseCodec.parseSessionIdHex("ffffffffffffffff"))
        assertEquals(0x0123456789abcdefL, UdpMouseCodec.parseSessionIdHex("0123456789abcdef"))

        assertNull(UdpMouseCodec.decodeAes256Key(""))
        assertNull(UdpMouseCodec.decodeAes256Key("Zm9v")) // 3 bytes != 32 bytes
        val validKeyB64 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" // 32 zero bytes
        val key = UdpMouseCodec.decodeAes256Key(validKeyB64)
        assertEquals("AES", key?.algorithm)
        assertEquals(32, key?.encoded?.size)
    }

    @Test
    fun `sealPacket and openPacket roundtrip probe and move payloads with AAD integrity`() {
        val key = UdpMouseCodec.decodeAes256Key("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")!!
        val sessionId = 0x1122334455667788L

        // 1. PROBE -> PROBE_ACK roundtrip
        val probePt = UdpMouseCodec.encodeProbePayload(0x5A5A1234)
        val probeWire = UdpMouseCodec.sealPacket(
            secretKey = key,
            pktType = UdpMouseCodec.PKT_PROBE,
            sessionId = sessionId,
            dir = UdpMouseCodec.DIR_CLIENT_TO_SERVER,
            seq = 1L,
            plaintext = probePt
        )
        assertEquals(20 + 4 + 16, probeWire.size)
        val openedProbe = UdpMouseCodec.openPacket(
            secretKey = key,
            expectedSessionId = sessionId,
            expectedDir = UdpMouseCodec.DIR_CLIENT_TO_SERVER,
            expectedPktType = UdpMouseCodec.PKT_PROBE,
            packet = probeWire
        )
        assertEquals(0x5A5A1234, ByteBuffer.wrap(openedProbe).order(ByteOrder.BIG_ENDIAN).int)

        // 2. MOUSE_MOVE payload roundtrip (44 bytes total)
        val movePt = UdpMouseCodec.encodeMovePayload(dx = 18, dy = -24, wheel = 1, flags = 0)
        val moveWire = UdpMouseCodec.sealPacket(
            secretKey = key,
            pktType = UdpMouseCodec.PKT_MOUSE_MOVE,
            sessionId = sessionId,
            dir = UdpMouseCodec.DIR_CLIENT_TO_SERVER,
            seq = 2L,
            plaintext = movePt
        )
        assertEquals(44, moveWire.size)
        val openedMove = UdpMouseCodec.openPacket(
            secretKey = key,
            expectedSessionId = sessionId,
            expectedDir = UdpMouseCodec.DIR_CLIENT_TO_SERVER,
            expectedPktType = UdpMouseCodec.PKT_MOUSE_MOVE,
            packet = moveWire
        )!!
        val bb = ByteBuffer.wrap(openedMove).order(ByteOrder.BIG_ENDIAN)
        assertEquals(18.toShort(), bb.short)
        assertEquals((-24).toShort(), bb.short)
        assertEquals(1.toShort(), bb.short)

        // 3. Tampering with header (AAD) or direction or sessionId must fail decryption
        val tampered = moveWire.copyOf()
        tampered[19] = (tampered[19].toInt() xor 0x01).toByte() // flip last bit of seq
        assertNull(
            UdpMouseCodec.openPacket(
                secretKey = key,
                expectedSessionId = sessionId,
                expectedDir = UdpMouseCodec.DIR_CLIENT_TO_SERVER,
                expectedPktType = UdpMouseCodec.PKT_MOUSE_MOVE,
                packet = tampered
            )
        )
        assertNull(
            UdpMouseCodec.openPacket(
                secretKey = key,
                expectedSessionId = sessionId,
                expectedDir = UdpMouseCodec.DIR_SERVER_TO_CLIENT,
                expectedPktType = UdpMouseCodec.PKT_MOUSE_MOVE,
                packet = moveWire
            )
        )
    }
}
