package com.silentinput.app.bluetooth

import com.silentinput.app.model.ProtocolPayloads
import com.silentinput.app.model.TouchpadContact
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

    @Test
    fun `consecutive scrolls coalesce and preserve order across moves and control frames`() {
        val queue = BluetoothOutboundQueue()
        val clickLeft = ProtocolPayloads.buildMouseJson(0f, 0f, action = "click_left")

        queue.enqueueMove(3f, 1f)
        queue.enqueueScroll(40f, hiRes = true, hwheel = -10f)
        queue.enqueueScroll(35f, hiRes = true, hwheel = -15f)
        queue.enqueueControl(clickLeft)
        queue.enqueueScroll(-2f, hiRes = false, hwheel = 1f)
        queue.enqueueScroll(-1f, hiRes = false, hwheel = 2f)

        val batch = queue.drainBatch(16)
        assertEquals(
            listOf(
                ProtocolPayloads.buildMouseJson(3f, 1f, action = "move"),
                ProtocolPayloads.buildMouseJson(0f, 0f, action = "scroll", wheel = 75f, hiResWheel = true, hwheel = -25f),
                clickLeft,
                ProtocolPayloads.buildMouseJson(0f, 0f, action = "scroll", wheel = -3f, hiResWheel = false, hwheel = 3f)
            ),
            batch
        )
        assertNull(queue.pollNext())
    }

    @Test
    fun `touchpad frames coalesce non-transition moves and preserve transition and control order`() {
        val queue = BluetoothOutboundQueue()
        val fDown = listOf(TouchpadContact(0, 4000, 4000))
        val fMove1 = listOf(TouchpadContact(0, 4100, 4050))
        val fMove2 = listOf(TouchpadContact(0, 4250, 4120))
        val fLiftoff = emptyList<TouchpadContact>()
        val keyEnter = ProtocolPayloads.buildKeyJson("enter")

        queue.enqueueTouchpad(fDown, isTransition = true)
        queue.enqueueTouchpad(fMove1, isTransition = false)
        queue.enqueueTouchpad(fMove2, isTransition = false)
        queue.enqueueTouchpad(fLiftoff, isTransition = true)
        queue.enqueueControl(keyEnter)

        val batch = queue.drainBatch(16)
        assertEquals(
            listOf(
                ProtocolPayloads.buildTouchpadJson(fDown),
                ProtocolPayloads.buildTouchpadJson(fMove2),
                ProtocolPayloads.buildTouchpadJson(fLiftoff),
                keyEnter
            ),
            batch
        )
        assertNull(queue.pollNext())
    }
}
