package com.silentinput.app.bluetooth

import com.silentinput.app.model.ProtocolPayloads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BluetoothOutboundQueueTest {

    @Test
    fun `consecutive moves coalesce into single payload`() {
        val queue = BluetoothOutboundQueue()
        queue.enqueueMove(2.5f, -1.0f)
        queue.enqueueMove(1.5f, 3.0f)

        assertEquals(
            ProtocolPayloads.buildMouseJson(4.0f, 2.0f, action = "move"),
            queue.pollNext()
        )
        assertNull(queue.pollNext())
    }

    @Test
    fun `control frame flushes pending move first to preserve order`() {
        val queue = BluetoothOutboundQueue()
        val downLeft = ProtocolPayloads.buildMouseJson(0f, 0f, action = "down_left")
        val upLeft = ProtocolPayloads.buildMouseJson(0f, 0f, action = "up_left")

        queue.enqueueControl(downLeft)
        queue.enqueueMove(10f, 5f)
        queue.enqueueMove(4f, -2f)
        queue.enqueueControl(upLeft)

        assertEquals(downLeft, queue.pollNext())
        assertEquals(ProtocolPayloads.buildMouseJson(14f, 3f, action = "move"), queue.pollNext())
        assertEquals(upLeft, queue.pollNext())
        assertNull(queue.pollNext())
    }

    @Test
    fun `clear discards both control frames and pending moves`() {
        val queue = BluetoothOutboundQueue()
        queue.enqueueControl(ProtocolPayloads.buildKeyJson("enter"))
        queue.enqueueMove(5f, 5f)
        queue.clear()

        assertNull(queue.pollNext())
    }
}
