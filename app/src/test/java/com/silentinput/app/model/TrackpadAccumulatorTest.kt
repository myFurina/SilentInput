package com.silentinput.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackpadAccumulatorTest {

    @Test
    fun `subpixel move deltas accumulate across events and preserve fractional remainder`() {
        val acc = TrackpadAccumulator()

        // 0.6 * 0.6 = 0.36 per tick -> stays subpixel for first 2 ticks
        assertEquals(0 to 0, acc.addMoveDelta(0.6f, 0f, sensitivity = 0.6f))
        assertEquals(0 to 0, acc.addMoveDelta(0.6f, 0f, sensitivity = 0.6f))
        // 3rd tick reaches 1.08 -> emits 1 px and leaves ~0.08
        assertEquals(1 to 0, acc.addMoveDelta(0.6f, 0f, sensitivity = 0.6f))
        // Next tick reaches 0.44 -> 0 px
        assertEquals(0 to 0, acc.addMoveDelta(0.6f, 0f, sensitivity = 0.6f))
    }

    @Test
    fun `negative move deltas accumulate symmetrically`() {
        val acc = TrackpadAccumulator()
        assertEquals(0 to 0, acc.addMoveDelta(0f, -0.6f, sensitivity = 1.0f))
        assertEquals(0 to -1, acc.addMoveDelta(0f, -0.6f, sensitivity = 1.0f))
        assertEquals(-0.2f, acc.accY, 0.001f)
    }

    @Test
    fun `two finger scroll accumulates with divisor, respects natural scrolling, and resets cleanly`() {
        val naturalAcc = TrackpadAccumulator()
        // Natural scrolling (default = true): swiping fingers up (avgDy < 0) scrolls wheel down (-1)
        assertEquals(0, naturalAcc.addScrollDelta(avgDy = -14f, sensitivity = 1.0f))
        assertEquals(-1, naturalAcc.addScrollDelta(avgDy = -14f, sensitivity = 1.0f))
        assertEquals(0, naturalAcc.addScrollDelta(avgDy = 14f, sensitivity = 1.0f))
        assertEquals(1, naturalAcc.addScrollDelta(avgDy = 14f, sensitivity = 1.0f))

        val traditionalAcc = TrackpadAccumulator()
        // Traditional wheel mode (naturalScrolling = false): swiping up (avgDy < 0) emits +1 wheel
        assertEquals(0, traditionalAcc.addScrollDelta(avgDy = -14f, sensitivity = 1.0f, naturalScrolling = false))
        assertEquals(1, traditionalAcc.addScrollDelta(avgDy = -14f, sensitivity = 1.0f, naturalScrolling = false))

        traditionalAcc.addMoveDelta(0.5f, 0.5f, sensitivity = 1.0f)
        traditionalAcc.reset()
        assertEquals(0f, traditionalAcc.accX, 0.0f)
        assertEquals(0f, traditionalAcc.accY, 0.0f)
        assertEquals(0f, traditionalAcc.accWheel, 0.0f)
    }

    @Test
    fun `non-finite deltas are ignored without corrupting accumulator`() {
        val acc = TrackpadAccumulator()
        assertEquals(1 to 0, acc.addMoveDelta(1.5f, 0f, sensitivity = 1.0f))
        assertEquals(0 to 0, acc.addMoveDelta(Float.NaN, Float.POSITIVE_INFINITY, sensitivity = 1.0f))
        // Remainder 0.5f must remain intact after NaN input
        assertEquals(1 to 0, acc.addMoveDelta(0.6f, 0f, sensitivity = 1.0f))
    }
}
