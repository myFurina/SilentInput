package com.silentinput.app.model

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow

fun resolvePhysicalDensityFactor(
    xdpi: Float,
    ydpi: Float,
    fallbackDensity: Float
): Float {
    val validX = xdpi.takeIf { it.isFinite() && it in 72f..1000f }
    val validY = ydpi.takeIf { it.isFinite() && it in 72f..1000f }
    val physicalDpi = when {
        validX != null && validY != null -> (validX + validY) * 0.5f
        validX != null -> validX
        validY != null -> validY
        else -> null
    }
    return physicalDpi?.let { (it / 160f).coerceIn(1.0f, 5.0f) }
        ?: fallbackDensity.coerceAtLeast(1f)
}

data class FlingVelocity(
    val vxPxPerMs: Float,
    val vyPxPerMs: Float
)

data class CoastingVelocity(
    val vxPerMs: Float,
    val vyPerMs: Float
)

/**
 * Decaying inertia coasting generator used for single-finger flick coasting in both
 * Native Touchpad Mode (units/ms) and Mouse Fallback Mode (px/ms).
 */
class PointerCoastingEngine(
    private val stopSpeedPerMs: Float = 0.04f
) {
    private var vxPerMs: Float = 0f
    private var vyPerMs: Float = 0f
    private var tauMs: Float = 80f

    var isActive: Boolean = false
        private set

    fun start(vxPerMs: Float, vyPerMs: Float, inertia: Float) {
        if (!vxPerMs.isFinite() || !vyPerMs.isFinite() || inertia <= 0f) {
            stop()
            return
        }
        val clampedInertia = inertia.coerceIn(0f, 1f)
        this.vxPerMs = vxPerMs
        this.vyPerMs = vyPerMs
        this.tauMs = 40f + clampedInertia * 200f
        isActive = hypot(vxPerMs, vyPerMs) >= stopSpeedPerMs
    }

    fun stop() {
        vxPerMs = 0f
        vyPerMs = 0f
        isActive = false
    }

    fun step(dtMs: Float): Pair<Float, Float> {
        if (!isActive || !dtMs.isFinite() || dtMs <= 0f) return 0f to 0f
        val safeDt = dtMs.coerceIn(1f, 50f)
        val dx = vxPerMs * safeDt
        val dy = vyPerMs * safeDt
        val decay = exp(-safeDt / tauMs)
        vxPerMs *= decay
        vyPerMs *= decay
        if (hypot(vxPerMs, vyPerMs) < stopSpeedPerMs) {
            stop()
        }
        return dx to dy
    }
}

/**
 * Decaying two-finger scroll momentum generator (half-life / time constant ~220ms at 1.0x friction).
 */
class ScrollFlingEngine {
    private var vxPxPerMs: Float = 0f
    private var vyPxPerMs: Float = 0f

    var isActive: Boolean = false
        private set

    fun start(velocity: FlingVelocity) {
        if (!velocity.vxPxPerMs.isFinite() || !velocity.vyPxPerMs.isFinite()) {
            stop()
            return
        }
        vxPxPerMs = velocity.vxPxPerMs
        vyPxPerMs = velocity.vyPxPerMs
        isActive = vxPxPerMs != 0f || vyPxPerMs != 0f
    }

    fun stop() {
        vxPxPerMs = 0f
        vyPxPerMs = 0f
        isActive = false
    }

    fun step(
        dtMs: Float,
        density: Float,
        sensitivity: Float,
        naturalScrolling: Boolean,
        hiResScroll: Boolean,
        accumulator: TrackpadAccumulator,
        friction: Float = 1.0f
    ): Pair<Int, Int> {
        if (!isActive || !dtMs.isFinite() || dtMs <= 0f) return 0 to 0
        val safeDensity = density.coerceAtLeast(1f)
        val safeDt = dtMs.coerceIn(1f, 50f)

        val avgDx = vxPxPerMs * safeDt
        val avgDy = vyPxPerMs * safeDt
        val wheelV = if (hiResScroll) {
            accumulator.addHiResScrollDelta(
                avgDy = avgDy,
                density = safeDensity,
                sensitivity = sensitivity,
                naturalScrolling = naturalScrolling
            )
        } else {
            accumulator.addScrollDelta(
                avgDy = avgDy,
                sensitivity = sensitivity,
                naturalScrolling = naturalScrolling,
                density = safeDensity
            )
        }
        val wheelH = if (hiResScroll) {
            accumulator.addHiResHScrollDelta(
                avgDx = avgDx,
                density = safeDensity,
                sensitivity = sensitivity,
                naturalScrolling = naturalScrolling
            )
        } else {
            accumulator.addHScrollDelta(
                avgDx = avgDx,
                sensitivity = sensitivity,
                naturalScrolling = naturalScrolling,
                density = safeDensity
            )
        }

        val safeFriction = if (friction.isFinite()) friction.coerceIn(0.20f, 2.00f) else 1.0f
        val effectiveTauMs = FLING_DECAY_TAU_MS / safeFriction
        val decay = exp(-safeDt / effectiveTauMs)
        vxPxPerMs *= decay
        vyPxPerMs *= decay
        if (hypot(vxPxPerMs, vyPxPerMs) / safeDensity < STOP_SPEED_DP_PER_MS) {
            stop()
        }
        return wheelV to wheelH
    }

    companion object {
        private const val FLING_DECAY_TAU_MS = 220f
        private const val STOP_SPEED_DP_PER_MS = 0.03f
    }
}

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
    var accHWheel: Float = 0f
        private set
    var accHWheelHiRes: Float = 0f
        private set
    private var smoothedVelocityDpPerMs: Float = 0f
    private var smoothedMoveDx: Float = 0f
    private var smoothedMoveDy: Float = 0f
    private var smoothedOutVx: Float = 0f
    private var smoothedOutVy: Float = 0f
    private var prevPredX: Float = 0f
    private var prevPredY: Float = 0f
    private var hasOutVelocity: Boolean = false

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
     * tunable acceleration/smoothing curves.
     */
    fun addAcceleratedMoveDelta(
        dx: Float,
        dy: Float,
        dtMs: Float,
        density: Float,
        sensitivity: Float,
        accelStrength: Float = DEFAULT_ACCEL_STRENGTH,
        smoothing: Float = 0f,
        tuning: TrackpadTuningConfig? = null,
        extraRotationDeg: Float = 0f,
        rotationAlreadyApplied: Boolean = false
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
        val effectiveSmoothing = tuning?.smoothing ?: smoothing
        val safeSmoothing = effectiveSmoothing.coerceIn(0f, 1f)
        val (effectiveDx, effectiveDy) = if (safeSmoothing <= 0f) {
            dx to dy
        } else {
            val tauSmooth = (safeSmoothing * 14f) / (1f + smoothedVelocityDpPerMs * 3.5f)
            val alphaSmooth = if (tauSmooth <= 0.1f) 1f else (1f - exp(-safeDt / tauSmooth)).coerceIn(0.20f, 1f)
            smoothedMoveDx = smoothedMoveDx + (dx - smoothedMoveDx) * alphaSmooth
            smoothedMoveDy = smoothedMoveDy + (dy - smoothedMoveDy) * alphaSmooth
            smoothedMoveDx to smoothedMoveDy
        }

        val effectiveTuning = tuning ?: TrackpadTuningConfig(
            pointerAccel = accelStrength,
            smoothing = effectiveSmoothing
        )
        val (transformedDx, transformedDy) = TrackpadVectorMath.transformMoveDelta(
            dx = effectiveDx,
            dy = effectiveDy,
            tuning = effectiveTuning,
            extraRotationDeg = extraRotationDeg,
            applyRotation = !rotationAlreadyApplied
        )
        val gain = computePointerGain(
            velocityDpPerMs = smoothedVelocityDpPerMs,
            accelStrength = effectiveTuning.pointerAccel,
            accelThreshold = effectiveTuning.accelThreshold,
            accelCurve = effectiveTuning.accelCurve,
            speedCap = effectiveTuning.speedCap,
            lowSpeedPrecision = effectiveTuning.lowSpeedPrecision
        )
        val dpiScale = REF_DENSITY / safeDensity.coerceIn(1.0f, 5.0f)
        val rawStepX = transformedDx * dpiScale * sensitivity * gain
        val rawStepY = transformedDy * dpiScale * sensitivity * gain

        val predMs = effectiveTuning.predictionMs.coerceIn(0, 16)
        val (predDeltaX, predDeltaY) = if (predMs > 0) {
            val alphaVel = 1f - exp(-safeDt / 14f)
            val instOutVx = rawStepX / safeDt
            val instOutVy = rawStepY / safeDt
            if (!hasOutVelocity) {
                smoothedOutVx = instOutVx
                smoothedOutVy = instOutVy
                hasOutVelocity = true
            } else {
                smoothedOutVx = smoothedOutVx * (1f - alphaVel) + instOutVx * alphaVel
                smoothedOutVy = smoothedOutVy * (1f - alphaVel) + instOutVy * alphaVel
            }
            val (leadX, leadY) = TrackpadVectorMath.clampLeadVector(
                vx = smoothedOutVx,
                vy = smoothedOutVy,
                predictionMs = predMs,
                stepDx = rawStepX,
                stepDy = rawStepY
            )
            val dpx = leadX - prevPredX
            val dpy = leadY - prevPredY
            prevPredX = leadX
            prevPredY = leadY
            dpx to dpy
        } else {
            val dpx = -prevPredX
            val dpy = -prevPredY
            prevPredX = 0f
            prevPredY = 0f
            hasOutVelocity = false
            dpx to dpy
        }

        accX += rawStepX + predDeltaX
        accY += rawStepY + predDeltaY
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

    fun addHScrollDelta(
        avgDx: Float,
        sensitivity: Float,
        stepDivisor: Float = 28f,
        naturalScrolling: Boolean = true,
        density: Float = REF_DENSITY
    ): Int {
        if (!avgDx.isFinite() || !sensitivity.isFinite() || !stepDivisor.isFinite() || !density.isFinite() || stepDivisor == 0f) {
            return 0
        }
        // Linux REL_HWHEEL > 0 scrolls right; natural right swipe moves content right (viewport left => negative)
        val directionSign = if (naturalScrolling) -1f else 1f
        val dpiScale = REF_DENSITY / density.coerceAtLeast(1f)
        accHWheel += (directionSign * avgDx * dpiScale * sensitivity) / stepDivisor
        val intHWheel = accHWheel.toInt()
        if (intHWheel != 0) {
            accHWheel -= intHWheel
        }
        return intHWheel
    }

    fun addHiResHScrollDelta(
        avgDx: Float,
        density: Float,
        sensitivity: Float,
        naturalScrolling: Boolean = true
    ): Int {
        if (!avgDx.isFinite() || !density.isFinite() || !sensitivity.isFinite()) {
            return 0
        }
        val safeDensity = density.coerceAtLeast(1f)
        val directionSign = if (naturalScrolling) -1f else 1f
        val dpDx = avgDx / safeDensity
        accHWheelHiRes += directionSign * dpDx * sensitivity * HI_RES_UNITS_PER_DP
        val intHiRes = accHWheelHiRes.toInt()
        if (intHiRes != 0) {
            accHWheelHiRes -= intHiRes
        }
        return intHiRes
    }

    fun reset() {
        accX = 0f
        accY = 0f
        accWheel = 0f
        accWheelHiRes = 0f
        accHWheel = 0f
        accHWheelHiRes = 0f
        smoothedVelocityDpPerMs = 0f
        smoothedMoveDx = 0f
        smoothedMoveDy = 0f
        smoothedOutVx = 0f
        smoothedOutVy = 0f
        prevPredX = 0f
        prevPredY = 0f
        hasOutVelocity = false
    }

    companion object {
        const val REF_DENSITY = 3.0f
        const val HI_RES_UNITS_PER_DP = 12.0f
        const val VELOCITY_EMA_TAU_MS = 12.0f
        const val DEFAULT_ACCEL_STRENGTH = 0.40f

        internal fun computePointerGain(
            velocityDpPerMs: Float,
            accelStrength: Float = DEFAULT_ACCEL_STRENGTH,
            accelThreshold: Float = TrackpadTuningConfig.DEFAULT_ACCEL_THRESHOLD,
            accelCurve: Float = TrackpadTuningConfig.DEFAULT_ACCEL_CURVE,
            speedCap: Float = TrackpadTuningConfig.DEFAULT_SPEED_CAP,
            lowSpeedPrecision: Float = TrackpadTuningConfig.DEFAULT_LOW_SPEED_PRECISION
        ): Float {
            val v = velocityDpPerMs.coerceAtLeast(0f)
            val cap = speedCap.takeIf { it.isFinite() }?.coerceIn(1.00f, 5.00f) ?: TrackpadTuningConfig.DEFAULT_SPEED_CAP
            val strength = accelStrength.takeIf { it.isFinite() }?.coerceIn(0f, 2f) ?: DEFAULT_ACCEL_STRENGTH
            val lowGain = TrackpadVectorMath.computeLowSpeedGain(
                speed = v,
                tuning = TrackpadTuningConfig(
                    lowSpeedPrecision = lowSpeedPrecision,
                    accelThreshold = accelThreshold
                )
            )
            val isDefaultShape = abs(accelThreshold - TrackpadTuningConfig.DEFAULT_ACCEL_THRESHOLD) < 1e-4f &&
                abs(accelCurve - TrackpadTuningConfig.DEFAULT_ACCEL_CURVE) < 1e-4f &&
                lowSpeedPrecision >= 0.999f

            if (isDefaultShape) {
                val baseGain = when {
                    v < 0.12f -> 0.95f + (v / 0.12f) * 0.05f
                    v < 1.2f -> 1.00f + ((v - 0.12f) / 1.08f) * 0.12f
                    else -> {
                        val extra = ((v - 1.2f) / (v - 1.2f + 1.5f)) * 0.06f
                        1.12f + extra
                    }
                }
                val rawGain = if (strength <= DEFAULT_ACCEL_STRENGTH) {
                    val t = strength / DEFAULT_ACCEL_STRENGTH
                    1.0f + (baseGain - 1.0f) * t
                } else {
                    val boost = (strength - DEFAULT_ACCEL_STRENGTH) / (2f - DEFAULT_ACCEL_STRENGTH)
                    val highVelRatio = ((v - 0.12f) / 1.0f).coerceIn(0f, 1.5f)
                    baseGain * (1f + boost * highVelRatio * 0.45f)
                }
                return rawGain.coerceAtMost(cap)
            }

            if (strength <= 0f) {
                return lowGain.coerceAtMost(cap)
            }
            val safeThreshold = accelThreshold.coerceIn(0f, 0.60f)
            val safeCurve = accelCurve.coerceIn(0.50f, 2.50f)
            val excess = ((v - safeThreshold) / 0.90f).coerceIn(0f, 2.5f)
            val curved = if (excess <= 0f) 0f else excess.toDouble().pow(safeCurve.toDouble()).toFloat()
            val accelMult = (1f + strength * 0.35f * curved).coerceAtMost(cap)
            return (lowGain * accelMult).coerceAtMost(cap)
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
        PENDING_LEFT_CLICK,
        DOUBLE_LEFT_CLICK,
        RIGHT_CLICK,
        TAP_DRAG_END
    }

    enum class ThreeFingerGesture {
        SWIPE_LEFT,
        SWIPE_RIGHT,
        SWIPE_UP,
        SWIPE_DOWN
    }

    private enum class ScrollAxisLock {
        UNDECIDED,
        VERTICAL,
        HORIZONTAL,
        FREE
    }

    data class FrameOutput(
        val moveDx: Int = 0,
        val moveDy: Int = 0,
        val wheelDelta: Int = 0,
        val dragStartedNow: Boolean = false,
        val hwheelDelta: Int = 0,
        val tapDragStarted: Boolean = false,
        val threeFingerGesture: ThreeFingerGesture? = null
    )

    private data class PointerPos(var x: Float, var y: Float)

    private val pointers = LinkedHashMap<Int, PointerPos>()
    private var primaryPointerId: Int = -1
    private var maxPointerCount: Int = 0
    private var downTimeMs: Long = 0L
    private var lastSampleTimeMs: Long = 0L
    private var lastPrimaryX: Float = 0f
    private var lastPrimaryY: Float = 0f
    private var totalTravelPx: Float = 0f
    private var pendingRawX: Float = 0f
    private var pendingRawY: Float = 0f
    private var pendingDtMs: Float = 0f

    private var didScroll: Boolean = false
    private var twoFingerTravelPx: Float = 0f
    private var pendingScrollRawX: Float = 0f
    private var pendingScrollRawY: Float = 0f
    private var scrollAxisLock: ScrollAxisLock = ScrollAxisLock.UNDECIDED
    private var scrollVelX: Float = 0f
    private var scrollVelY: Float = 0f
    private var lastScrollSampleTimeMs: Long = 0L
    private var lastDensity: Float = TrackpadAccumulator.REF_DENSITY
    private var lastFlingVelocity: FlingVelocity? = null
    private var pointerOutVxPxPerMs: Float = 0f
    private var pointerOutVyPxPerMs: Float = 0f
    private var lastPointerMoveTimeMs: Long = 0L
    private var lastPointerInertia: Float = 0f
    private var lastPointerCoastVelocity: CoastingVelocity? = null

    private var threeFingerAccumX: Float = 0f
    private var threeFingerAccumY: Float = 0f
    private var threeFingerTriggered: Boolean = false

    private var pendingTapUpTimeMs: Long = 0L
    private var pendingTapX: Float = 0f
    private var pendingTapY: Float = 0f
    private var hasPendingTap: Boolean = false
    private var isTapDragArmed: Boolean = false

    var isTapDragging: Boolean = false
        private set
    var isDragging: Boolean = false
        private set
    var hadMultiTouch: Boolean = false
        private set

    fun onPrimaryDown(
        pointerId: Int,
        x: Float,
        y: Float,
        timeMs: Long,
        density: Float = lastDensity
    ): Boolean {
        val safeDensity = density.coerceAtLeast(1f)
        lastDensity = safeDensity
        var flushPreviousTap = false
        var armTapDrag = false
        if (hasPendingTap) {
            val gap = timeMs - pendingTapUpTimeMs
            val dist = hypot(x - pendingTapX, y - pendingTapY)
            if (gap in 0L..TAP_DRAG_WINDOW_MS && dist <= TAP_DRAG_MAX_DIST_DP * safeDensity) {
                armTapDrag = true
            } else {
                flushPreviousTap = true
            }
            hasPendingTap = false
        }
        resetGestureOnly()
        isTapDragArmed = armTapDrag
        primaryPointerId = pointerId
        pointers[pointerId] = PointerPos(x, y)
        lastPrimaryX = x
        lastPrimaryY = y
        maxPointerCount = 1
        downTimeMs = timeMs
        lastSampleTimeMs = timeMs
        return flushPreviousTap
    }

    fun onPointerDown(pointerId: Int, x: Float, y: Float) {
        pointers[pointerId] = PointerPos(x, y)
        maxPointerCount = maxOf(maxPointerCount, pointers.size)
        if (pointers.size >= 2) {
            hadMultiTouch = true
            isDragging = true
            pendingRawX = 0f
            pendingRawY = 0f
            pendingDtMs = 0f
            isTapDragArmed = false
            hasPendingTap = false
            isTapDragging = false
            if (pointers.size >= 3) {
                threeFingerAccumX = 0f
                threeFingerAccumY = 0f
                threeFingerTriggered = false
            }
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
        hiResScroll: Boolean = false,
        scrollSensitivityOverride: Float? = null,
        pointerAccel: Float = TrackpadAccumulator.DEFAULT_ACCEL_STRENGTH,
        smoothing: Float = 0f,
        deadzoneMm: Float = 0.2f,
        tuning: TrackpadTuningConfig? = null,
        extraRotationDeg: Float = 0f,
        rotationAlreadyApplied: Boolean = false
    ): FrameOutput {
        if (count <= 0 || pointers.isEmpty()) return FrameOutput()
        val safeDensity = density.coerceAtLeast(1f)
        lastDensity = safeDensity
        lastPointerInertia = tuning?.pointerInertia ?: 0f
        val effectiveAxisLock = tuning?.scrollAxisLock ?: true
        val dtMs = (sampleTimeMs - lastSampleTimeMs).coerceAtLeast(1L).toFloat()
        lastSampleTimeMs = sampleTimeMs

        if (maxPointerCount >= 3 || pointers.size >= 3 || count >= 3) {
            maxPointerCount = maxOf(maxPointerCount, pointers.size, count)
            hadMultiTouch = true
            isDragging = true
            var dxSum = 0f
            var dySum = 0f
            var movedCount = 0
            for (i in 0 until count) {
                val id = pointerIds[i]
                val pos = pointers[id]
                if (pos != null) {
                    dxSum += xs[i] - pos.x
                    dySum += ys[i] - pos.y
                    pos.x = xs[i]
                    pos.y = ys[i]
                    movedCount++
                }
            }
            if (!threeFingerTriggered && count >= 3 && movedCount > 0) {
                threeFingerAccumX += dxSum / movedCount
                threeFingerAccumY += dySum / movedCount
                val travel = hypot(threeFingerAccumX, threeFingerAccumY)
                if (travel >= THREE_FINGER_SWIPE_THRESHOLD_DP * safeDensity) {
                    threeFingerTriggered = true
                    val gesture = if (abs(threeFingerAccumX) >= abs(threeFingerAccumY)) {
                        if (threeFingerAccumX > 0f) {
                            ThreeFingerGesture.SWIPE_RIGHT
                        } else {
                            ThreeFingerGesture.SWIPE_LEFT
                        }
                    } else {
                        if (threeFingerAccumY > 0f) {
                            ThreeFingerGesture.SWIPE_DOWN
                        } else {
                            ThreeFingerGesture.SWIPE_UP
                        }
                    }
                    return FrameOutput(threeFingerGesture = gesture)
                }
            }
            return FrameOutput()
        }

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
            var dxSum = 0f
            var dySum = 0f
            var movedCount = 0
            for (i in 0 until count) {
                val id = pointerIds[i]
                val pos = pointers[id]
                if (pos != null) {
                    dxSum += xs[i] - pos.x
                    dySum += ys[i] - pos.y
                    pos.x = xs[i]
                    pos.y = ys[i]
                    movedCount++
                }
            }
            if (movedCount == 0) return FrameOutput()
            val avgDx = dxSum / movedCount
            val avgDy = dySum / movedCount

            var emitDx = avgDx
            var emitDy = avgDy
            if (!didScroll) {
                twoFingerTravelPx += hypot(avgDx, avgDy)
                pendingScrollRawX += avgDx
                pendingScrollRawY += avgDy
                if (twoFingerTravelPx < TWO_FINGER_TAP_SLOP_DP * safeDensity) {
                    return FrameOutput()
                }
                didScroll = true
                emitDx = pendingScrollRawX
                emitDy = pendingScrollRawY
                pendingScrollRawX = 0f
                pendingScrollRawY = 0f
                scrollAxisLock = if (!effectiveAxisLock) {
                    ScrollAxisLock.FREE
                } else when {
                    abs(emitDx) >= AXIS_LOCK_DECIDE_RATIO * abs(emitDy) -> ScrollAxisLock.HORIZONTAL
                    abs(emitDy) >= AXIS_LOCK_DECIDE_RATIO * abs(emitDx) -> ScrollAxisLock.VERTICAL
                    else -> ScrollAxisLock.FREE
                }
            }

            when (scrollAxisLock) {
                ScrollAxisLock.HORIZONTAL -> emitDy = 0f
                ScrollAxisLock.VERTICAL -> emitDx = 0f
                ScrollAxisLock.FREE -> if (effectiveAxisLock) {
                    if (abs(emitDy) > AXIS_LOCK_RATIO * abs(emitDx)) {
                        emitDx = 0f
                    } else if (abs(emitDx) > AXIS_LOCK_RATIO * abs(emitDy)) {
                        emitDy = 0f
                    }
                }
                ScrollAxisLock.UNDECIDED -> {}
            }

            val safeDt = dtMs.coerceIn(2f, 40f)
            val instVx = (emitDx / safeDt).coerceIn(-MAX_SCROLL_VEL_PX_PER_MS, MAX_SCROLL_VEL_PX_PER_MS)
            val instVy = (emitDy / safeDt).coerceIn(-MAX_SCROLL_VEL_PX_PER_MS, MAX_SCROLL_VEL_PX_PER_MS)
            val alpha = 1f - exp(-safeDt / SCROLL_VEL_EMA_TAU_MS)
            if (lastScrollSampleTimeMs == 0L) {
                scrollVelX = instVx
                scrollVelY = instVy
            } else {
                scrollVelX = scrollVelX * (1f - alpha) + instVx * alpha
                scrollVelY = scrollVelY * (1f - alpha) + instVy * alpha
            }
            lastScrollSampleTimeMs = sampleTimeMs

            val scrollSens = scrollSensitivityOverride?.takeIf { it.isFinite() }?.coerceIn(0.2f, 3.0f)
                ?: computeScrollSensitivity(sensitivity)
            val wheel = if (hiResScroll) {
                accumulator.addHiResScrollDelta(
                    avgDy = emitDy,
                    density = safeDensity,
                    sensitivity = scrollSens,
                    naturalScrolling = naturalScrolling
                )
            } else {
                accumulator.addScrollDelta(
                    avgDy = emitDy,
                    sensitivity = scrollSens,
                    naturalScrolling = naturalScrolling,
                    density = safeDensity
                )
            }
            val hwheel = if (hiResScroll) {
                accumulator.addHiResHScrollDelta(
                    avgDx = emitDx,
                    density = safeDensity,
                    sensitivity = scrollSens,
                    naturalScrolling = naturalScrolling
                )
            } else {
                accumulator.addHScrollDelta(
                    avgDx = emitDx,
                    sensitivity = scrollSens,
                    naturalScrolling = naturalScrolling,
                    density = safeDensity
                )
            }
            return FrameOutput(wheelDelta = wheel, hwheelDelta = hwheel)
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
        lastPrimaryX = pos.x
        lastPrimaryY = pos.y

        if (dx == 0f && dy == 0f) return FrameOutput()

        if (!isDragging) {
            totalTravelPx += hypot(dx, dy)
            pendingRawX += dx
            pendingRawY += dy
            pendingDtMs += dtMs
            val effectiveDeadzoneMm = if (tuning != null) {
                maxOf(tuning.deadzoneMm, tuning.tapToleranceMm)
            } else {
                deadzoneMm
            }
            val deadzoneDp = (effectiveDeadzoneMm.coerceIn(0f, 3f) * 6.3f).coerceAtLeast(0.4f)
            val microThresholdPx = deadzoneDp * safeDensity
            if (totalTravelPx < microThresholdPx) {
                return FrameOutput()
            }
            isDragging = true
            var startedTapDrag = false
            if (isTapDragArmed) {
                isTapDragArmed = false
                isTapDragging = true
                startedTapDrag = true
            }
            val frameDtMs = pendingDtMs.coerceIn(4f, 16f)
            val (mx, my) = accumulator.addAcceleratedMoveDelta(
                dx = pendingRawX,
                dy = pendingRawY,
                dtMs = frameDtMs,
                density = safeDensity,
                sensitivity = sensitivity,
                accelStrength = pointerAccel,
                smoothing = smoothing,
                tuning = tuning,
                extraRotationDeg = extraRotationDeg,
                rotationAlreadyApplied = rotationAlreadyApplied
            )
            pendingRawX = 0f
            pendingRawY = 0f
            pendingDtMs = 0f
            recordPointerVelocity(mx, my, frameDtMs, sampleTimeMs)
            return FrameOutput(
                moveDx = mx,
                moveDy = my,
                dragStartedNow = true,
                tapDragStarted = startedTapDrag
            )
        }

        val frameDtMs = dtMs.coerceIn(2f, 40f)
        val (mx, my) = accumulator.addAcceleratedMoveDelta(
            dx = dx,
            dy = dy,
            dtMs = dtMs,
            density = safeDensity,
            sensitivity = sensitivity,
            accelStrength = pointerAccel,
            smoothing = smoothing,
            tuning = tuning,
            extraRotationDeg = extraRotationDeg,
            rotationAlreadyApplied = rotationAlreadyApplied
        )
        recordPointerVelocity(mx, my, frameDtMs, sampleTimeMs)
        return FrameOutput(moveDx = mx, moveDy = my)
    }

    private fun recordPointerVelocity(mx: Int, my: Int, dtMs: Float, sampleTimeMs: Long) {
        val alphaVel = 1f - exp(-dtMs / 16f)
        val instVx = mx / dtMs
        val instVy = my / dtMs
        if (lastPointerMoveTimeMs == 0L) {
            pointerOutVxPxPerMs = instVx
            pointerOutVyPxPerMs = instVy
        } else {
            pointerOutVxPxPerMs = pointerOutVxPxPerMs * (1f - alphaVel) + instVx * alphaVel
            pointerOutVyPxPerMs = pointerOutVyPxPerMs * (1f - alphaVel) + instVy * alphaVel
        }
        lastPointerMoveTimeMs = sampleTimeMs
    }

    fun onPointerUp(pointerId: Int, @Suppress("UNUSED_PARAMETER") x: Float, @Suppress("UNUSED_PARAMETER") y: Float) {
        pointers.remove(pointerId)
        if (pointerId == primaryPointerId && pointers.isNotEmpty()) {
            val next = pointers.entries.first()
            primaryPointerId = next.key
            lastPrimaryX = next.value.x
            lastPrimaryY = next.value.y
        }
    }

    fun onPendingTapTimeout(): TapAction {
        if (!hasPendingTap || pointers.isNotEmpty()) {
            return TapAction.NONE
        }
        hasPendingTap = false
        return TapAction.LEFT_CLICK
    }

    fun onAllUp(
        timeMs: Long,
        tapTimeoutMs: Long = 260L,
        allowDeferredTap: Boolean = true
    ): TapAction {
        val elapsed = timeMs - downTimeMs
        val fling = if (maxPointerCount == 2 &&
            didScroll &&
            scrollAxisLock == ScrollAxisLock.VERTICAL &&
            (timeMs - lastScrollSampleTimeMs) in 0L..FLING_LIFTOFF_RECENCY_MS
        ) {
            val speedDpPerMs = abs(scrollVelY) / lastDensity.coerceAtLeast(1f)
            if (speedDpPerMs >= MIN_FLING_SPEED_DP_PER_MS) {
                val maxVy = MAX_FLING_SPEED_DP_PER_MS * lastDensity.coerceAtLeast(1f)
                FlingVelocity(
                    vxPxPerMs = 0f,
                    vyPxPerMs = scrollVelY.coerceIn(-maxVy, maxVy)
                )
            } else {
                null
            }
        } else {
            null
        }

        val coast = if (maxPointerCount == 1 &&
            isDragging &&
            !hadMultiTouch &&
            !isTapDragging &&
            lastPointerInertia > 0f &&
            (timeMs - lastPointerMoveTimeMs) in 0L..45L
        ) {
            val speedPx = hypot(pointerOutVxPxPerMs, pointerOutVyPxPerMs)
            val minSpeed = MIN_COAST_SPEED_DP_PER_MS * lastDensity.coerceAtLeast(1f)
            if (speedPx >= minSpeed) {
                val maxSpeed = MAX_COAST_SPEED_DP_PER_MS * lastDensity.coerceAtLeast(1f)
                val scale = if (speedPx > maxSpeed) maxSpeed / speedPx else 1f
                CoastingVelocity(
                    vxPerMs = pointerOutVxPxPerMs * scale,
                    vyPerMs = pointerOutVyPxPerMs * scale
                )
            } else {
                null
            }
        } else {
            null
        }

        if (isTapDragging) {
            reset()
            return TapAction.TAP_DRAG_END
        }

        if (isTapDragArmed) {
            val wasCleanTap = !hadMultiTouch && !isDragging && elapsed in 0..tapTimeoutMs
            reset()
            return if (wasCleanTap) TapAction.DOUBLE_LEFT_CLICK else TapAction.LEFT_CLICK
        }

        if (maxPointerCount == 1 && !hadMultiTouch && !isDragging && elapsed in 0..tapTimeoutMs) {
            if (allowDeferredTap) {
                pendingTapUpTimeMs = timeMs
                pendingTapX = lastPrimaryX
                pendingTapY = lastPrimaryY
                hasPendingTap = true
                resetGestureOnly()
                return TapAction.PENDING_LEFT_CLICK
            }
            reset()
            return TapAction.LEFT_CLICK
        }

        if (maxPointerCount == 2 && !didScroll && elapsed in 0..TWO_FINGER_TAP_TIMEOUT_MS) {
            reset()
            return TapAction.RIGHT_CLICK
        }

        reset()
        lastFlingVelocity = fling
        lastPointerCoastVelocity = coast
        return TapAction.NONE
    }

    fun consumeFlingVelocity(): FlingVelocity? {
        val v = lastFlingVelocity
        lastFlingVelocity = null
        return v
    }

    fun consumePointerCoastVelocity(): CoastingVelocity? {
        val v = lastPointerCoastVelocity
        lastPointerCoastVelocity = null
        return v
    }

    private fun resetGestureOnly() {
        pointers.clear()
        primaryPointerId = -1
        maxPointerCount = 0
        downTimeMs = 0L
        lastSampleTimeMs = 0L
        totalTravelPx = 0f
        pendingRawX = 0f
        pendingRawY = 0f
        pendingDtMs = 0f
        didScroll = false
        twoFingerTravelPx = 0f
        pendingScrollRawX = 0f
        pendingScrollRawY = 0f
        scrollAxisLock = ScrollAxisLock.UNDECIDED
        scrollVelX = 0f
        scrollVelY = 0f
        lastScrollSampleTimeMs = 0L
        lastFlingVelocity = null
        pointerOutVxPxPerMs = 0f
        pointerOutVyPxPerMs = 0f
        lastPointerMoveTimeMs = 0L
        lastPointerInertia = 0f
        lastPointerCoastVelocity = null
        threeFingerAccumX = 0f
        threeFingerAccumY = 0f
        threeFingerTriggered = false
        isTapDragArmed = false
        isTapDragging = false
        isDragging = false
        hadMultiTouch = false
        accumulator.reset()
    }

    fun reset() {
        hasPendingTap = false
        pendingTapUpTimeMs = 0L
        pendingTapX = 0f
        pendingTapY = 0f
        resetGestureOnly()
    }

    companion object {
        private const val TWO_FINGER_TAP_SLOP_DP = 3.0f
        private const val TWO_FINGER_TAP_TIMEOUT_MS = 300L
        private const val TAP_DRAG_WINDOW_MS = 160L
        private const val TAP_DRAG_MAX_DIST_DP = 32.0f
        private const val THREE_FINGER_SWIPE_THRESHOLD_DP = 24.0f
        private const val AXIS_LOCK_DECIDE_RATIO = 1.5f
        private const val AXIS_LOCK_RATIO = 2.5f
        private const val SCROLL_VEL_EMA_TAU_MS = 25.0f
        private const val FLING_LIFTOFF_RECENCY_MS = 80L
        private const val MIN_FLING_SPEED_DP_PER_MS = 0.25f
        private const val MAX_FLING_SPEED_DP_PER_MS = 8.0f
        private const val MIN_COAST_SPEED_DP_PER_MS = 0.45f
        private const val MAX_COAST_SPEED_DP_PER_MS = 8.0f
        private const val MAX_SCROLL_VEL_PX_PER_MS = 40.0f

        internal fun computeScrollSensitivity(sensitivity: Float): Float {
            return if (!sensitivity.isFinite()) 1.0f else (0.5f + 0.5f * sensitivity).coerceIn(0.6f, 1.5f)
        }
    }
}
