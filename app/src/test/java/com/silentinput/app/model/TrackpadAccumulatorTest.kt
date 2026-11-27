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

        assertTrue(slow in 0.95f..1.00f)
        assertTrue(medium > 1.0f && medium < fast)
        assertTrue(fast > 1.12f && fast <= 1.18f)
    }

    @Test
    fun `accelerated move delta normalizes physical distance across screen densities`() {
        val lowDpiAcc = TrackpadAccumulator()
        val highDpiAcc = TrackpadAccumulator()
        // Same physical 10dp swipe over 20ms at 2.0x density (20px) vs 4.0x density (40px)
        val (lowDx, _) = lowDpiAcc.addAcceleratedMoveDelta(dx = 20f, dy = 0f, dtMs = 20f, density = 2.0f, sensitivity = 1.0f)
        val (highDx, _) = highDpiAcc.addAcceleratedMoveDelta(dx = 40f, dy = 0f, dtMs = 20f, density = 4.0f, sensitivity = 1.0f)
        // Both represent 10dp at 0.5 dp/ms -> identical cursor displacement despite 2x pixel density
        assertEquals(lowDx, highDx)
    }

    @Test
    fun `hi-res scroll emits smooth fractional 1-120th units across devices`() {
        val acc = TrackpadAccumulator()
        // 5dp downward swipe (15px at 3.0 density) = 60 hi-res units (= half a detent)
        assertEquals(60, acc.addHiResScrollDelta(avgDy = 15f, density = 3.0f, sensitivity = 1.0f))
        // Sub-detent movement (2px at 3.0 density = 8 hi-res units) still scrolls immediately without waiting 28px
        assertEquals(8, acc.addHiResScrollDelta(avgDy = 2f, density = 3.0f, sensitivity = 1.0f))
        // Traditional mode flips direction
        assertEquals(-60, acc.addHiResScrollDelta(avgDy = 15f, density = 3.0f, sensitivity = 1.0f, naturalScrolling = false))
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
    fun `gesture processor distinguishes single tap double click and tap and drag`() {
        val processor = TrackpadGestureProcessor(TrackpadAccumulator())
        // 1. Clean single tap -> PENDING_LEFT_CLICK -> timeout emits LEFT_CLICK
        assertFalse(processor.onPrimaryDown(pointerId = 1, x = 50f, y = 50f, timeMs = 2000L, density = 3.0f))
        assertEquals(
            TrackpadGestureProcessor.TapAction.PENDING_LEFT_CLICK,
            processor.onAllUp(timeMs = 2100L)
        )
        assertEquals(
            TrackpadGestureProcessor.TapAction.LEFT_CLICK,
            processor.onPendingTapTimeout()
        )

        // 2. Fast double tap -> DOUBLE_LEFT_CLICK
        processor.onPrimaryDown(pointerId = 2, x = 50f, y = 50f, timeMs = 3000L, density = 3.0f)
        assertEquals(
            TrackpadGestureProcessor.TapAction.PENDING_LEFT_CLICK,
            processor.onAllUp(timeMs = 3060L)
        )
        assertFalse(processor.onPrimaryDown(pointerId = 3, x = 54f, y = 52f, timeMs = 3140L, density = 3.0f))
        assertEquals(
            TrackpadGestureProcessor.TapAction.DOUBLE_LEFT_CLICK,
            processor.onAllUp(timeMs = 3210L)
        )

        // 3. Double tap and hold + move -> Tap-and-Drag starts left hold and releases on lift
        processor.onPrimaryDown(pointerId = 4, x = 100f, y = 100f, timeMs = 4000L, density = 3.0f)
        assertEquals(
            TrackpadGestureProcessor.TapAction.PENDING_LEFT_CLICK,
            processor.onAllUp(timeMs = 4070L)
        )
        assertFalse(processor.onPrimaryDown(pointerId = 5, x = 102f, y = 100f, timeMs = 4150L, density = 3.0f))
        val dragOut = processor.onMoveSample(
            pointerIds = intArrayOf(5),
            xs = floatArrayOf(120f),
            ys = floatArrayOf(100f),
            count = 1,
            sampleTimeMs = 4166L,
            density = 3.0f,
            sensitivity = 1.0f,
            naturalScrolling = true
        )
        assertTrue(dragOut.tapDragStarted)
        assertTrue(processor.isTapDragging)
        assertEquals(
            TrackpadGestureProcessor.TapAction.TAP_DRAG_END,
            processor.onAllUp(timeMs = 4300L)
        )
    }

    @Test
    fun `legacy scroll delta normalizes across screen densities`() {
        val accLow = TrackpadAccumulator()
        val accHigh = TrackpadAccumulator()
        // 18px at 2.0x density vs 36px at 4.0x density represent identical physical swipe distance
        val lowWheel = accLow.addScrollDelta(avgDy = 18f, sensitivity = 1.0f, density = 2.0f)
        val highWheel = accHigh.addScrollDelta(avgDy = 36f, sensitivity = 1.0f, density = 4.0f)
        assertEquals(lowWheel, highWheel)
    }

    @Test
    fun `gesture processor two finger scroll ignores residual single finger motion after lift`() {
        val processor = TrackpadGestureProcessor(TrackpadAccumulator())
        val ids = intArrayOf(0, 1)
        val xs = floatArrayOf(100f, 160f)
        val ys = floatArrayOf(100f, 100f)

        processor.onPrimaryDown(pointerId = 0, x = 100f, y = 100f, timeMs = 1000L)
        processor.onPointerDown(pointerId = 1, x = 160f, y = 100f)

        ys[0] = 130f
        ys[1] = 130f
        val scrollOut = processor.onMoveSample(
            pointerIds = ids,
            xs = xs,
            ys = ys,
            count = 2,
            sampleTimeMs = 1016L,
            density = 3.0f,
            sensitivity = 1.0f,
            naturalScrolling = true,
            hiResScroll = true
        )
        assertEquals(120, scrollOut.wheelDelta)

        // Lift second finger and keep moving primary finger -> must NOT jump cursor
        processor.onPointerUp(pointerId = 1, x = 160f, y = 130f)
        xs[0] = 140f
        ys[0] = 150f
        val residualOut = processor.onMoveSample(
            pointerIds = intArrayOf(0),
            xs = floatArrayOf(140f),
            ys = floatArrayOf(150f),
            count = 1,
            sampleTimeMs = 1024L,
            density = 3.0f,
            sensitivity = 1.0f,
            naturalScrolling = true
        )
        assertEquals(0, residualOut.moveDx)
        assertEquals(0, residualOut.moveDy)
        assertEquals(0, residualOut.wheelDelta)
    }

    @Test
    fun `ema velocity smoothing decays consistently across 60hz and 240hz sample rates`() {
        val acc60 = TrackpadAccumulator()
        val acc240 = TrackpadAccumulator()

        // Prime both at 1.0 dp/ms
        acc60.addAcceleratedMoveDelta(dx = 48f, dy = 0f, dtMs = 16f, density = 3.0f, sensitivity = 1.0f)
        acc240.addAcceleratedMoveDelta(dx = 12f, dy = 0f, dtMs = 4f, density = 3.0f, sensitivity = 1.0f)

        // Over 16ms at 0.25 dp/ms: 1x16ms step vs 4x4ms steps should yield nearly identical displacement
        val (dx60, _) = acc60.addAcceleratedMoveDelta(dx = 12f, dy = 0f, dtMs = 16f, density = 3.0f, sensitivity = 1.0f)
        var dx240 = 0
        repeat(4) {
            dx240 += acc240.addAcceleratedMoveDelta(dx = 3f, dy = 0f, dtMs = 4f, density = 3.0f, sensitivity = 1.0f).first
        }
        assertTrue("Expected similar 60Hz ($dx60) vs 240Hz ($dx240) output", kotlin.math.abs(dx60 - dx240) <= 1)
    }

    @Test
    fun `gesture processor triggers right click on clean two finger tap without scroll`() {
        val processor = TrackpadGestureProcessor(TrackpadAccumulator())
        processor.onPrimaryDown(pointerId = 0, x = 100f, y = 100f, timeMs = 1000L)
        processor.onPointerDown(pointerId = 1, x = 160f, y = 105f)
        // Micro-jitter < 3dp stays below scroll slop
        val out = processor.onMoveSample(
            pointerIds = intArrayOf(0, 1),
            xs = floatArrayOf(102f, 162f),
            ys = floatArrayOf(102f, 107f),
            count = 2,
            sampleTimeMs = 1020L,
            density = 3.0f,
            sensitivity = 1.0f,
            naturalScrolling = true
        )
        assertEquals(0, out.wheelDelta)
        assertEquals(0, out.hwheelDelta)

        processor.onPointerUp(pointerId = 1, x = 162f, y = 107f)
        assertEquals(
            TrackpadGestureProcessor.TapAction.RIGHT_CLICK,
            processor.onAllUp(timeMs = 1120L)
        )
    }

    @Test
    fun `two finger scroll emits horizontal wheel respects natural sign and avoids right click`() {
        val processor = TrackpadGestureProcessor(TrackpadAccumulator())
        val ids = intArrayOf(0, 1)
        val xs = floatArrayOf(100f, 160f)
        val ys = floatArrayOf(200f, 200f)

        processor.onPrimaryDown(pointerId = 0, x = xs[0], y = ys[0], timeMs = 1000L)
        processor.onPointerDown(pointerId = 1, x = xs[1], y = ys[1])

        // Swipe right 30px (10dp at 3.0x): natural scrolling emits -120 hi-res hwheel
        xs[0] = 130f
        xs[1] = 190f
        val outNatural = processor.onMoveSample(
            pointerIds = ids,
            xs = xs,
            ys = ys,
            count = 2,
            sampleTimeMs = 1016L,
            density = 3.0f,
            sensitivity = 1.0f,
            naturalScrolling = true,
            hiResScroll = true
        )
        assertEquals(0, outNatural.wheelDelta)
        assertEquals(-120, outNatural.hwheelDelta)
        assertEquals(TrackpadGestureProcessor.TapAction.NONE, processor.onAllUp(timeMs = 1060L))

        val acc = TrackpadAccumulator()
        // Traditional horizontal mode: swiping right (+30px at 3.0 density) -> +120
        assertEquals(
            120,
            acc.addHiResHScrollDelta(avgDx = 30f, density = 3.0f, sensitivity = 1.0f, naturalScrolling = false)
        )
        // Legacy detent horizontal scroll
        assertEquals(
            -1,
            acc.addHScrollDelta(avgDx = 28f, sensitivity = 1.0f, naturalScrolling = true, density = 3.0f)
        )
    }

    @Test
    fun `scroll fling engine decays smoothly and stops below threshold`() {
        val processor = TrackpadGestureProcessor(TrackpadAccumulator())
        processor.onPrimaryDown(pointerId = 0, x = 100f, y = 400f, timeMs = 1000L)
        processor.onPointerDown(pointerId = 1, x = 160f, y = 400f)
        processor.onMoveSample(
            pointerIds = intArrayOf(0, 1),
            xs = floatArrayOf(100f, 160f),
            ys = floatArrayOf(340f, 340f),
            count = 2,
            sampleTimeMs = 1016L,
            density = 3.0f,
            sensitivity = 1.0f,
            naturalScrolling = true,
            hiResScroll = true
        )
        assertEquals(TrackpadGestureProcessor.TapAction.NONE, processor.onAllUp(timeMs = 1030L))
        val vel = processor.consumeFlingVelocity()
        assertTrue("Expected vertical-only fling velocity after fast vertical swipe", vel != null && vel.vxPxPerMs == 0f && vel.vyPxPerMs < 0f)

        val engine = ScrollFlingEngine()
        val flingAcc = TrackpadAccumulator()
        engine.start(vel!!)
        assertTrue(engine.isActive)
        var totalV = 0
        var ticks = 0
        while (engine.isActive && ticks < 200) {
            val (wv, _) = engine.step(
                dtMs = 16f,
                density = 3.0f,
                sensitivity = 1.0f,
                naturalScrolling = true,
                hiResScroll = true,
                accumulator = flingAcc
            )
            totalV += wv
            ticks++
        }
        assertFalse(engine.isActive)
        assertTrue("Expected negative momentum wheel output, got $totalV", totalV < 0)
    }

    @Test
    fun `horizontal axis lock suppresses vertical wobble and emits no horizontal fling`() {
        val processor = TrackpadGestureProcessor(TrackpadAccumulator())
        val ids = intArrayOf(0, 1)
        val xs = floatArrayOf(100f, 160f)
        val ys = floatArrayOf(200f, 200f)

        processor.onPrimaryDown(pointerId = 0, x = xs[0], y = ys[0], timeMs = 1000L)
        processor.onPointerDown(pointerId = 1, x = xs[1], y = ys[1])

        // Dominant horizontal start (dx=+30, dy=+6) locks gesture horizontally
        xs[0] = 130f; xs[1] = 190f
        ys[0] = 206f; ys[1] = 206f
        val first = processor.onMoveSample(
            pointerIds = ids,
            xs = xs,
            ys = ys,
            count = 2,
            sampleTimeMs = 1012L,
            density = 3.0f,
            sensitivity = 1.0f,
            naturalScrolling = true,
            hiResScroll = true
        )
        assertEquals(0, first.wheelDelta)
        assertEquals(-120, first.hwheelDelta)

        // Subsequent diagonal drift (dx=+30, dy=+25) stays locked to horizontal!
        xs[0] = 160f; xs[1] = 220f
        ys[0] = 231f; ys[1] = 231f
        val second = processor.onMoveSample(
            pointerIds = ids,
            xs = xs,
            ys = ys,
            count = 2,
            sampleTimeMs = 1024L,
            density = 3.0f,
            sensitivity = 1.0f,
            naturalScrolling = true,
            hiResScroll = true
        )
        assertEquals(0, second.wheelDelta)
        assertEquals(-120, second.hwheelDelta)

        // Lifting fast after horizontal swipe produces NO fling so paged views do not skip pages
        assertEquals(TrackpadGestureProcessor.TapAction.NONE, processor.onAllUp(timeMs = 1030L))
        assertEquals(null, processor.consumeFlingVelocity())
    }

    @Test
    fun `three finger swipe emits workspace or overview gesture once without scroll or click`() {
        val cases = listOf(
            (90f to 0f) to TrackpadGestureProcessor.ThreeFingerGesture.SWIPE_RIGHT,
            (-90f to 0f) to TrackpadGestureProcessor.ThreeFingerGesture.SWIPE_LEFT,
            (0f to -90f) to TrackpadGestureProcessor.ThreeFingerGesture.SWIPE_UP,
            (0f to 90f) to TrackpadGestureProcessor.ThreeFingerGesture.SWIPE_DOWN
        )
        for ((delta, expected) in cases) {
            val processor = TrackpadGestureProcessor(TrackpadAccumulator())
            val ids = intArrayOf(0, 1, 2)
            val xs = floatArrayOf(100f, 150f, 200f)
            val ys = floatArrayOf(200f, 200f, 200f)
            processor.onPrimaryDown(pointerId = 0, x = xs[0], y = ys[0], timeMs = 1000L)
            processor.onPointerDown(pointerId = 1, x = xs[1], y = ys[1])
            processor.onPointerDown(pointerId = 2, x = xs[2], y = ys[2])

            for (i in 0..2) {
                xs[i] += delta.first
                ys[i] += delta.second
            }
            val firstOut = processor.onMoveSample(
                pointerIds = ids,
                xs = xs,
                ys = ys,
                count = 3,
                sampleTimeMs = 1020L,
                density = 3.0f,
                sensitivity = 1.0f,
                naturalScrolling = true
            )
            assertEquals(expected, firstOut.threeFingerGesture)
            assertEquals(0, firstOut.wheelDelta)
            assertEquals(0, firstOut.hwheelDelta)

            // Continued movement in the same gesture must NOT re-trigger
            for (i in 0..2) {
                xs[i] += delta.first
                ys[i] += delta.second
            }
            val secondOut = processor.onMoveSample(
                pointerIds = ids,
                xs = xs,
                ys = ys,
                count = 3,
                sampleTimeMs = 1040L,
                density = 3.0f,
                sensitivity = 1.0f,
                naturalScrolling = true
            )
            assertEquals(null, secondOut.threeFingerGesture)
            assertEquals(TrackpadGestureProcessor.TapAction.NONE, processor.onAllUp(timeMs = 1080L))
        }
    }

    @Test
    fun `resolvePhysicalDensityFactor uses physical dpi and falls back safely`() {
        // 480 dpi -> 3.0x regardless of OS display zoom fallback
        assertEquals(3.0f, resolvePhysicalDensityFactor(xdpi = 480f, ydpi = 480f, fallbackDensity = 2.55f), 0.001f)
        // Bogus/emulator 0 dpi falls back cleanly
        assertEquals(2.75f, resolvePhysicalDensityFactor(xdpi = 0f, ydpi = -1f, fallbackDensity = 2.75f), 0.001f)
        // Single valid axis still works
        assertEquals(2.5f, resolvePhysicalDensityFactor(xdpi = 400f, ydpi = 10f, fallbackDensity = 3.0f), 0.001f)
    }
}
