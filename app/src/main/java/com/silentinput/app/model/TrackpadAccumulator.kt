package com.silentinput.app.model

import kotlin.math.exp
import kotlin.math.hypot

/**
 * Sub-pixel movement, velocity-based pointer acceleration, and two-finger scroll
 * delta accumulator for the touch trackpad.
 */
class TrackpadAccumulator {
    var accX: Float = 0f
        private set
    var accY: Float = 0f
        private set
    var accWheel: Float = 0f
        private set
    var accWheelHiRes: Float = 0f
        private set
    private var smoothedVelocityDpPerMs: Float = 0f

    fun addMoveDelta(dx: Float, dy: Float, sensitivity: Float): Pair<Int, Int> {
        if (!dx.isFinite() || !dy.isFinite() || !sensitivity.isFinite()) {
            return 0 to 0
        }
        accX += dx * sensitivity
        accY += dy * sensitivity
        return extractIntegerDelta()
    }

    /**
     * DPI-normalized, velocity-aware pointer ballistics calibrated for host OS acceleration (libinput):
     * normalizes raw pixels to a 3.0x (~480ppi) baseline with a 4ms floor, EMA filter, and
     * gentle [0.95x..1.18x] curve so host acceleration never stacks into muddy starts or sudden jumps.
     */
    fun addAcceleratedMoveDelta(
        dx: Float,
        dy: Float,
        dtMs: Float,
        density: Float,
        sensitivity: Float
    ): Pair<Int, Int> {
        if (!dx.isFinite() || !dy.isFinite() || !dtMs.isFinite() || !density.isFinite() || !sensitivity.isFinite()) {
            return 0 to 0
        }
        val safeDensity = density.coerceAtLeast(1f)
        val safeDt = dtMs.coerceIn(2f, 40f)
        val distDp = hypot(dx, dy) / safeDensity
        val instVelocity = (distDp / safeDt).coerceAtMost(5.0f)
        val alpha = 1f - exp(-safeDt / VELOCITY_EMA_TAU_MS)
        smoothedVelocityDpPerMs = if (smoothedVelocityDpPerMs == 0f) {
            instVelocity
        } else {
            smoothedVelocityDpPerMs * (1f - alpha) + instVelocity * alpha
        }
        val gain = computePointerGain(smoothedVelocityDpPerMs)
        val dpiScale = REF_DENSITY / safeDensity.coerceIn(1.0f, 5.0f)

        accX += dx * dpiScale * sensitivity * gain
        accY += dy * dpiScale * sensitivity * gain
        return extractIntegerDelta()
    }

    private fun extractIntegerDelta(): Pair<Int, Int> {
        val intX = accX.toInt()
        val intY = accY.toInt()
        if (intX != 0 || intY != 0) {
            accX -= intX
            accY -= intY
        }
        return intX to intY
    }

    fun addScrollDelta(
        avgDy: Float,
        sensitivity: Float,
        stepDivisor: Float = 28f,
        naturalScrolling: Boolean = true,
        density: Float = REF_DENSITY
    ): Int {
        if (!avgDy.isFinite() || !sensitivity.isFinite() || !stepDivisor.isFinite() || !density.isFinite() || stepDivisor == 0f) {
            return 0
        }
        val directionSign = if (naturalScrolling) 1f else -1f
        val dpiScale = REF_DENSITY / density.coerceAtLeast(1f)
        accWheel += (directionSign * avgDy * dpiScale * sensitivity) / stepDivisor
        val intWheel = accWheel.toInt()
        if (intWheel != 0) {
            accWheel -= intWheel
        }
        return intWheel
    }

    fun addHiResScrollDelta(
        avgDy: Float,
        density: Float,
        sensitivity: Float,
        naturalScrolling: Boolean = true
    ): Int {
        if (!avgDy.isFinite() || !density.isFinite() || !sensitivity.isFinite()) {
            return 0
        }
        val safeDensity = density.coerceAtLeast(1f)
        val directionSign = if (naturalScrolling) 1f else -1f
        val dpDy = avgDy / safeDensity
        // 10dp swipe = 120 REL_WHEEL_HI_RES units (= 1 standard detent), emitted smoothly per-pixel
        accWheelHiRes += directionSign * dpDy * sensitivity * HI_RES_UNITS_PER_DP
        val intHiRes = accWheelHiRes.toInt()
        if (intHiRes != 0) {
            accWheelHiRes -= intHiRes
        }
        return intHiRes
    }

    fun reset() {
        accX = 0f
        accY = 0f
        accWheel = 0f
        accWheelHiRes = 0f
        smoothedVelocityDpPerMs = 0f
    }

    companion object {
        const val REF_DENSITY = 3.0f
        const val HI_RES_UNITS_PER_DP = 12.0f
        const val VELOCITY_EMA_TAU_MS = 12.0f

        internal fun computePointerGain(velocityDpPerMs: Float): Float {
            val v = velocityDpPerMs.coerceAtLeast(0f)
            return when {
                v < 0.12f -> 0.95f + (v / 0.12f) * 0.05f
                v < 1.2f -> 1.00f + ((v - 0.12f) / 1.08f) * 0.12f
                else -> {
                    val extra = ((v - 1.2f) / (v - 1.2f + 1.5f)) * 0.06f
                    1.12f + extra
                }
            }
        }
    }
}

/**
 * Pure Kotlin state machine for low-latency trackpad gesture handling.
 * Uses a tiny micro-jitter threshold (~1.5dp instead of Android's 30-60px touchSlop)
 * and flushes accumulated initial displacement with zero lost pixels on drag start.
 */
class TrackpadGestureProcessor(
    private val accumulator: TrackpadAccumulator = TrackpadAccumulator()
) {
    enum class TapAction {
        NONE,
        LEFT_CLICK,
        RIGHT_CLICK
    }

    data class FrameOutput(
        val moveDx: Int = 0,
        val moveDy: Int = 0,
        val wheelDelta: Int = 0,
        val dragStartedNow: Boolean = false
    )

    private data class PointerPos(var x: Float, var y: Float)

    private val pointers = LinkedHashMap<Int, PointerPos>()
    private var primaryPointerId: Int = -1
    private var downTimeMs: Long = 0L
    private var lastSampleTimeMs: Long = 0L
    private var totalTravelPx: Float = 0f
    private var pendingRawX: Float = 0f
    private var pendingRawY: Float = 0f
    private var pendingDtMs: Float = 0f

    var isDragging: Boolean = false
        private set
    var hadMultiTouch: Boolean = false
        private set
    var longPressFired: Boolean = false
        private set

    fun onPrimaryDown(pointerId: Int, x: Float, y: Float, timeMs: Long) {
        reset()
        primaryPointerId = pointerId
        pointers[pointerId] = PointerPos(x, y)
        downTimeMs = timeMs
        lastSampleTimeMs = timeMs
    }

    fun onPointerDown(pointerId: Int, x: Float, y: Float) {
        pointers[pointerId] = PointerPos(x, y)
        if (pointers.size >= 2) {
            hadMultiTouch = true
            isDragging = true
            pendingRawX = 0f
            pendingRawY = 0f
            pendingDtMs = 0f
        }
    }

    fun onMoveSample(
        pointerIds: IntArray,
        xs: FloatArray,
        ys: FloatArray,
        count: Int,
        sampleTimeMs: Long,
        density: Float,
        sensitivity: Float,
        naturalScrolling: Boolean,
        hiResScroll: Boolean = false
    ): FrameOutput {
        if (count <= 0 || pointers.isEmpty()) return FrameOutput()
        val dtMs = (sampleTimeMs - lastSampleTimeMs).coerceAtLeast(1L).toFloat()
        lastSampleTimeMs = sampleTimeMs

        if (hadMultiTouch && pointers.size < 2) {
            // Sync coordinates and ignore residual single-finger motion after a two-finger scroll
            for (i in 0 until count) {
                pointers[pointerIds[i]]?.let {
                    it.x = xs[i]
                    it.y = ys[i]
                }
            }
            return FrameOutput()
        }
        if (hadMultiTouch || count >= 2 || pointers.size >= 2) {
            hadMultiTouch = true
            isDragging = true
            var dySum = 0f
            var movedCount = 0
            for (i in 0 until count) {
                val id = pointerIds[i]
                val pos = pointers[id]
                if (pos != null) {
                    dySum += ys[i] - pos.y
                    pos.x = xs[i]
                    pos.y = ys[i]
                    movedCount++
                }
            }
            if (movedCount == 0) return FrameOutput()
            val avgDy = dySum / movedCount
            val wheel = if (hiResScroll) {
                accumulator.addHiResScrollDelta(
                    avgDy = avgDy,
                    density = density,
                    sensitivity = sensitivity,
                    naturalScrolling = naturalScrolling
                )
            } else {
                accumulator.addScrollDelta(
                    avgDy = avgDy,
                    sensitivity = sensitivity,
                    naturalScrolling = naturalScrolling,
                    density = density
                )
            }
            return FrameOutput(wheelDelta = wheel)
        }

        // Single finger tracking
        var idx = -1
        for (i in 0 until count) {
            if (pointerIds[i] == primaryPointerId) {
                idx = i
                break
            }
        }
        if (idx < 0) idx = 0
        val id = pointerIds[idx]
        val pos = pointers[id] ?: return FrameOutput()
        val dx = xs[idx] - pos.x
        val dy = ys[idx] - pos.y
        pos.x = xs[idx]
        pos.y = ys[idx]

        if (dx == 0f && dy == 0f) return FrameOutput()

        if (longPressFired) return FrameOutput()

        if (!isDragging) {
            totalTravelPx += hypot(dx, dy)
            pendingRawX += dx
            pendingRawY += dy
            pendingDtMs += dtMs
            val microThresholdPx = 1.5f * density.coerceAtLeast(1f)
            if (totalTravelPx < microThresholdPx) {
                return FrameOutput()
            }
            isDragging = true
            val (mx, my) = accumulator.addAcceleratedMoveDelta(
                dx = pendingRawX,
                dy = pendingRawY,
                dtMs = pendingDtMs.coerceIn(4f, 16f),
                density = density,
                sensitivity = sensitivity
            )
            pendingRawX = 0f
            pendingRawY = 0f
            pendingDtMs = 0f
            return FrameOutput(moveDx = mx, moveDy = my, dragStartedNow = true)
        }

        val (mx, my) = accumulator.addAcceleratedMoveDelta(
            dx = dx,
            dy = dy,
            dtMs = dtMs,
            density = density,
            sensitivity = sensitivity
        )
        return FrameOutput(moveDx = mx, moveDy = my)
    }

    fun onPointerUp(pointerId: Int, @Suppress("UNUSED_PARAMETER") x: Float, @Suppress("UNUSED_PARAMETER") y: Float) {
        pointers.remove(pointerId)
        if (pointerId == primaryPointerId && pointers.isNotEmpty()) {
            val next = pointers.entries.first()
            primaryPointerId = next.key
        }
    }

    fun onLongPressTimeout(): TapAction {
        if (pointers.isEmpty() || hadMultiTouch || isDragging || longPressFired) {
            return TapAction.NONE
        }
        longPressFired = true
        return TapAction.RIGHT_CLICK
    }

    fun onAllUp(timeMs: Long, tapTimeoutMs: Long = 260L): TapAction {
        val elapsed = timeMs - downTimeMs
        val action = if (!hadMultiTouch && !isDragging && !longPressFired && elapsed in 0..tapTimeoutMs) {
            TapAction.LEFT_CLICK
        } else {
            TapAction.NONE
        }
        reset()
        return action
    }

    fun reset() {
        pointers.clear()
        primaryPointerId = -1
        downTimeMs = 0L
        lastSampleTimeMs = 0L
        totalTravelPx = 0f
        pendingRawX = 0f
        pendingRawY = 0f
        pendingDtMs = 0f
        isDragging = false
        hadMultiTouch = false
        longPressFired = false
        accumulator.reset()
    }
}
