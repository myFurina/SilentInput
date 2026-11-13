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
    fun `two finger scroll accumulates with divisor and resets cleanly`() {
        val acc = TrackpadAccumulator()
        // Negative avgDy scrolls up (+1 wheel when >= 28 effective px)
        assertEquals(0, acc.addScrollDelta(avgDy = -14f, sensitivity = 1.0f))
        assertEquals(1, acc.addScrollDelta(avgDy = -14f, sensitivity = 1.0f))

        acc.addMoveDelta(0.5f, 0.5f, sensitivity = 1.0f)
        acc.reset()
        assertEquals(0f, acc.accX, 0.0f)
        assertEquals(0f, acc.accY, 0.0f)
        assertEquals(0f, acc.accWheel, 0.0f)
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
