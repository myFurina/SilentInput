package com.silentinput.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.silentinput.app.network.UdpMouseCodec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher

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
        assertEquals(0x5A5A1234, ByteBuffer.wrap(openedProbe!!).order(ByteOrder.BIG_ENDIAN).int)

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

    @Test
    fun `sealMoveInto zero-alloc path encodes move and wheel identically to sealPacket`() {
        val key = UdpMouseCodec.decodeAes256Key("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")!!
        val sessionId = 0x0102030405060708L
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val payloadScratch = ByteArray(UdpMouseCodec.MOVE_PAYLOAD_LEN)
        val nonceScratch = ByteArray(UdpMouseCodec.NONCE_LEN)
        val wireBuf = ByteArray(UdpMouseCodec.MOVE_WIRE_LEN)

        val totalLen = UdpMouseCodec.sealMoveInto(
            secretKey = key,
            sessionId = sessionId,
            dir = UdpMouseCodec.DIR_CLIENT_TO_SERVER,
            seq = 42L,
            dx = -12,
            dy = 34,
            wheel = -3,
            flags = UdpMouseCodec.FLAG_HI_RES_WHEEL,
            payloadScratch = payloadScratch,
            nonceScratch = nonceScratch,
            wireOut = wireBuf,
            cipher = cipher
        )
        assertEquals(UdpMouseCodec.MOVE_WIRE_LEN, totalLen)

        val opened = UdpMouseCodec.openPacket(
            secretKey = key,
            expectedSessionId = sessionId,
            expectedDir = UdpMouseCodec.DIR_CLIENT_TO_SERVER,
            expectedPktType = UdpMouseCodec.PKT_MOUSE_MOVE,
            packet = wireBuf,
            length = totalLen,
            cipher = cipher
        )!!
        val bb = ByteBuffer.wrap(opened).order(ByteOrder.BIG_ENDIAN)
        assertEquals((-12).toShort(), bb.short)
        assertEquals(34.toShort(), bb.short)
        assertEquals((-3).toShort(), bb.short)
        assertEquals(UdpMouseCodec.FLAG_HI_RES_WHEEL.toShort(), bb.short)
    }

    @Test
    fun `sealMoveWithFecInto embeds prev1 and prev2 history within 60-byte wire packet`() {
        val key = UdpMouseCodec.decodeAes256Key("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")!!
        val sessionId = 0x0102030405060708L
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val payloadScratch = ByteArray(UdpMouseCodec.MOVE_FEC_PAYLOAD_LEN)
        val nonceScratch = ByteArray(UdpMouseCodec.NONCE_LEN)
        val wireBuf = ByteArray(UdpMouseCodec.MOVE_FEC_WIRE_LEN)

        val totalLen = UdpMouseCodec.sealMoveWithFecInto(
            secretKey = key,
            sessionId = sessionId,
            dir = UdpMouseCodec.DIR_CLIENT_TO_SERVER,
            seq = 10L,
            dx = 9,
            dy = -5,
            wheel = 0,
            flags = 0,
            hasPrev1 = true,
            prev1Dx = 7,
            prev1Dy = -3,
            prev1Wheel = 60,
            prev1Flags = UdpMouseCodec.FLAG_HI_RES_WHEEL,
            hasPrev2 = true,
            prev2Dx = 4,
            prev2Dy = 2,
            prev2Wheel = 0,
            prev2Flags = 0,
            payloadScratch = payloadScratch,
            nonceScratch = nonceScratch,
            wireOut = wireBuf,
            cipher = cipher
        )
        assertEquals(UdpMouseCodec.MOVE_FEC_WIRE_LEN, totalLen)

        val opened = UdpMouseCodec.openPacket(
            secretKey = key,
            expectedSessionId = sessionId,
            expectedDir = UdpMouseCodec.DIR_CLIENT_TO_SERVER,
            expectedPktType = UdpMouseCodec.PKT_MOUSE_MOVE,
            packet = wireBuf,
            length = totalLen,
            cipher = cipher
        )!!
        assertEquals(24, opened.size)
        val bb = ByteBuffer.wrap(opened).order(ByteOrder.BIG_ENDIAN)
        assertEquals(9.toShort(), bb.short)
        assertEquals((-5).toShort(), bb.short)
        assertEquals(0.toShort(), bb.short)
        assertEquals((UdpMouseCodec.FLAG_FEC_PREV1 or UdpMouseCodec.FLAG_FEC_PREV2).toShort(), bb.short)
        assertEquals(7.toShort(), bb.short)
        assertEquals((-3).toShort(), bb.short)
        assertEquals(60.toShort(), bb.short)
        assertEquals(UdpMouseCodec.FLAG_HI_RES_WHEEL.toShort(), bb.short)
        assertEquals(4.toShort(), bb.short)
        assertEquals(2.toShort(), bb.short)
        assertEquals(0.toShort(), bb.short)
        assertEquals(0.toShort(), bb.short)
    }

    @Test
    fun `sealMoveWithFecInto preserves FLAG_HWHEEL across current and FEC history frames`() {
        val key = UdpMouseCodec.decodeAes256Key("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")!!
        val sessionId = 0x0102030405060708L
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val payloadScratch = ByteArray(UdpMouseCodec.MOVE_FEC_PAYLOAD_LEN)
        val nonceScratch = ByteArray(UdpMouseCodec.NONCE_LEN)
        val wireBuf = ByteArray(UdpMouseCodec.MOVE_FEC_WIRE_LEN)

        val curFlags = UdpMouseCodec.FLAG_HI_RES_WHEEL or UdpMouseCodec.FLAG_HWHEEL
        val prev1Flags = UdpMouseCodec.FLAG_HWHEEL
        val prev2Flags = UdpMouseCodec.FLAG_HI_RES_WHEEL or UdpMouseCodec.FLAG_HWHEEL
        val totalLen = UdpMouseCodec.sealMoveWithFecInto(
            secretKey = key,
            sessionId = sessionId,
            dir = UdpMouseCodec.DIR_CLIENT_TO_SERVER,
            seq = 12L,
            dx = 0,
            dy = 0,
            wheel = -40,
            flags = curFlags,
            hasPrev1 = true,
            prev1Dx = 0,
            prev1Dy = 0,
            prev1Wheel = 1,
            prev1Flags = prev1Flags,
            hasPrev2 = true,
            prev2Dx = 0,
            prev2Dy = 0,
            prev2Wheel = 80,
            prev2Flags = prev2Flags,
            payloadScratch = payloadScratch,
            nonceScratch = nonceScratch,
            wireOut = wireBuf,
            cipher = cipher
        )

        val opened = UdpMouseCodec.openPacket(
            secretKey = key,
            expectedSessionId = sessionId,
            expectedDir = UdpMouseCodec.DIR_CLIENT_TO_SERVER,
            expectedPktType = UdpMouseCodec.PKT_MOUSE_MOVE,
            packet = wireBuf,
            length = totalLen,
            cipher = cipher
        )!!
        val bb = ByteBuffer.wrap(opened).order(ByteOrder.BIG_ENDIAN)
        // Frame 0 (cur)
        bb.short; bb.short; assertEquals((-40).toShort(), bb.short)
        assertEquals(
            (curFlags or UdpMouseCodec.FLAG_FEC_PREV1 or UdpMouseCodec.FLAG_FEC_PREV2).toShort(),
            bb.short
        )
        // Frame 1 (prev1)
        bb.short; bb.short; assertEquals(1.toShort(), bb.short)
        assertEquals(prev1Flags.toShort(), bb.short)
        // Frame 2 (prev2)
        bb.short; bb.short; assertEquals(80.toShort(), bb.short)
        assertEquals(prev2Flags.toShort(), bb.short)
    }

    @Test
    fun `sealTouchpadInto encodes multi-touch snapshots and empty liftoff accurately`() {
        val key = UdpMouseCodec.decodeAes256Key("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")!!
        val sessionId = 0x1122334455667788L
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val payloadScratch = ByteArray(UdpMouseCodec.TOUCHPAD_MAX_PAYLOAD_LEN)
        val nonceScratch = ByteArray(UdpMouseCodec.NONCE_LEN)
        val wireBuf = ByteArray(UdpMouseCodec.TOUCHPAD_MAX_WIRE_LEN)

        val contacts = listOf(
            TouchpadContact(pointerId = 3, x = 4000, y = 4100),
            TouchpadContact(pointerId = 7, x = 5200, y = 4800)
        )
        val len = UdpMouseCodec.sealTouchpadInto(
            secretKey = key,
            sessionId = sessionId,
            dir = UdpMouseCodec.DIR_CLIENT_TO_SERVER,
            seq = 21L,
            contacts = contacts,
            payloadScratch = payloadScratch,
            nonceScratch = nonceScratch,
            wireOut = wireBuf,
            cipher = cipher
        )
        assertEquals(UdpMouseCodec.HEADER_LEN + 1 + 12 + UdpMouseCodec.GCM_TAG_LEN, len)

        val opened = UdpMouseCodec.openPacket(
            secretKey = key,
            expectedSessionId = sessionId,
            expectedDir = UdpMouseCodec.DIR_CLIENT_TO_SERVER,
            expectedPktType = UdpMouseCodec.PKT_TOUCHPAD,
            packet = wireBuf,
            length = len,
            cipher = cipher
        )!!
        assertEquals(13, opened.size)
        val bb = ByteBuffer.wrap(opened).order(ByteOrder.BIG_ENDIAN)
        assertEquals(2, bb.get().toInt() and 0xFF)
        assertEquals(3, bb.short.toInt() and 0xFFFF)
        assertEquals(4000, bb.short.toInt() and 0xFFFF)
        assertEquals(4100, bb.short.toInt() and 0xFFFF)
        assertEquals(7, bb.short.toInt() and 0xFFFF)
        assertEquals(5200, bb.short.toInt() and 0xFFFF)
        assertEquals(4800, bb.short.toInt() and 0xFFFF)

        val emptyLen = UdpMouseCodec.sealTouchpadInto(
            secretKey = key,
            sessionId = sessionId,
            dir = UdpMouseCodec.DIR_CLIENT_TO_SERVER,
            seq = 22L,
            contacts = emptyList(),
            payloadScratch = payloadScratch,
            nonceScratch = nonceScratch,
            wireOut = wireBuf,
            cipher = cipher
        )
        val openedEmpty = UdpMouseCodec.openPacket(
            secretKey = key,
            expectedSessionId = sessionId,
            expectedDir = UdpMouseCodec.DIR_CLIENT_TO_SERVER,
            expectedPktType = UdpMouseCodec.PKT_TOUCHPAD,
            packet = wireBuf,
            length = emptyLen,
            cipher = cipher
        )!!
        assertEquals(1, openedEmpty.size)
        assertEquals(0, openedEmpty[0].toInt() and 0xFF)
    }
}
