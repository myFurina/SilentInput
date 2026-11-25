package com.silentinput.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `pointer acceleration curve scales monotonically within gentle host-friendly bounds`() {
        val slow = TrackpadAccumulator.computePointerGain(0.02f)
        val medium = TrackpadAccumulator.computePointerGain(0.4f)
        val fast = TrackpadAccumulator.computePointerGain(2.0f)

        assertTrue(slow in 0.80f..0.95f)
        assertTrue(medium > 1.0f && medium < fast)
        assertTrue(fast > 1.30f && fast <= 1.45f)
    }

    @Test
    fun `accelerated move delta resists 1ms timestamp quantization spikes`() {
        val acc = TrackpadAccumulator()
        // Establish steady 0.3 dp/ms baseline
        acc.addAcceleratedMoveDelta(dx = 7.2f, dy = 0f, dtMs = 8f, density = 3f, sensitivity = 1f)
        // Suddenly a sample arrives with dtMs=1ms (timestamp quantization); 4ms floor + EMA prevents huge jump
        val (spikeDx, _) = acc.addAcceleratedMoveDelta(dx = 7.2f, dy = 0f, dtMs = 1f, density = 3f, sensitivity = 1f)
        assertTrue("Expected smoothed delta <= 9, got $spikeDx", spikeDx in 7..9)
    }

    @Test
    fun `gesture processor eliminates touchSlop deadzone and flushes initial delta cleanly`() {
        val processor = TrackpadGestureProcessor(TrackpadAccumulator())
        val ids = intArrayOf(0)
        val xs = floatArrayOf(0f)
        val ys = floatArrayOf(0f)
        val density = 3.0f // micro threshold = 4.5 px (much smaller than Android 24-48px touchSlop)

        processor.onPrimaryDown(pointerId = 0, x = 100f, y = 100f, timeMs = 1000L)
        assertFalse(processor.isDragging)

        // Small micro-movement within 1.5dp (2px) stays pending so taps do not jitter
        xs[0] = 102f
        ys[0] = 100f
        val preDrag = processor.onMoveSample(
            pointerIds = ids,
            xs = xs,
            ys = ys,
            count = 1,
            sampleTimeMs = 1008L,
            density = density,
            sensitivity = 1.0f,
            naturalScrolling = true
        )
        assertFalse(processor.isDragging)
        assertEquals(0, preDrag.moveDx)

        // Moving past 4.5px flushes ALL accumulated raw displacement (no eaten pixels!)
        xs[0] = 106f
        ys[0] = 100f
        val dragStart = processor.onMoveSample(
            pointerIds = ids,
            xs = xs,
            ys = ys,
            count = 1,
            sampleTimeMs = 1016L,
            density = density,
            sensitivity = 1.0f,
            naturalScrolling = true
        )
        assertTrue(processor.isDragging)
        assertTrue(dragStart.dragStartedNow)
        assertTrue(dragStart.moveDx >= 4)

        // Lifting after a drag must NOT fire a left click
        assertEquals(
            TrackpadGestureProcessor.TapAction.NONE,
            processor.onAllUp(timeMs = 1040L)
        )
    }

    @Test
    fun `gesture processor distinguishes clean tap and long press`() {
        val processor = TrackpadGestureProcessor(TrackpadAccumulator())
        processor.onPrimaryDown(pointerId = 1, x = 50f, y = 50f, timeMs = 2000L)
        assertEquals(
            TrackpadGestureProcessor.TapAction.LEFT_CLICK,
            processor.onAllUp(timeMs = 2120L)
        )

        processor.onPrimaryDown(pointerId = 2, x = 50f, y = 50f, timeMs = 3000L)
        assertEquals(
            TrackpadGestureProcessor.TapAction.RIGHT_CLICK,
            processor.onLongPressTimeout()
        )
        assertEquals(
            TrackpadGestureProcessor.TapAction.NONE,
            processor.onAllUp(timeMs = 3500L)
        )
    }
}
