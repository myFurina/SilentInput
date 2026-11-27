package com.silentinput.app.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * Pure vector math, DPI resolution, velocity lead clamping, and physical palm-guard helpers shared
 * between Native Touchpad Kinematics and Legacy/Mouse Fallback Accumulator.
 */
object TrackpadVectorMath {
    private const val MM_PER_INCH = 25.4f

    fun resolveValidDpi(
        xdpi: Float,
        ydpi: Float,
        fallbackDensity: Float
    ): Pair<Float, Float> {
        val safeDensityDpi = fallbackDensity.takeIf { it.isFinite() }?.coerceIn(0.75f, 6f)?.times(160f) ?: 160f
        val validXdpi = if (xdpi.isFinite() && xdpi in 72f..1000f) xdpi else safeDensityDpi
        val validYdpi = if (ydpi.isFinite() && ydpi in 72f..1000f) ydpi else safeDensityDpi
        return validXdpi to validYdpi
    }

    fun clampLeadVector(
        vx: Float,
        vy: Float,
        predictionMs: Int,
        stepDx: Float,
        stepDy: Float,
        maxLeadAbs: Float = Float.POSITIVE_INFINITY
    ): Pair<Float, Float> {
        if (predictionMs <= 0) return 0f to 0f
        var leadX = vx * predictionMs
        var leadY = vy * predictionMs
        val leadMag = hypot(leadX, leadY)
        val maxLead = minOf(hypot(stepDx, stepDy) * 1.5f, maxLeadAbs)
        if (leadMag > maxLead && leadMag > 1e-5f) {
            val capScale = maxLead / leadMag
            leadX *= capScale
            leadY *= capScale
        }
        return leadX to leadY
    }

    fun wrapDegrees180(deg: Float): Float {
        if (!deg.isFinite()) return 0f
        var d = deg % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    fun rotatePointAroundCenter(
        x: Float,
        y: Float,
        centerX: Float,
        centerY: Float,
        rotationDeg: Float
    ): Pair<Float, Float> {
        if (!x.isFinite() || !y.isFinite()) return centerX to centerY
        val angle = wrapDegrees180(rotationDeg)
        if (abs(angle) <= 0.05f) return x to y
        val rad = angle * (PI / 180.0)
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val dx = x - centerX
        val dy = y - centerY
        return (centerX + dx * c - dy * s) to (centerY + dx * s + dy * c)
    }

    fun transformMoveDelta(
        dx: Float,
        dy: Float,
        tuning: TrackpadTuningConfig,
        extraRotationDeg: Float = 0f,
        applyRotation: Boolean = true
    ): Pair<Float, Float> {
        if (!dx.isFinite() || !dy.isFinite()) return 0f to 0f
        if (dx == 0f && dy == 0f) return 0f to 0f

        var rx = dx
        var ry = dy

        if (applyRotation) {
            val safeExtra = if (extraRotationDeg.isFinite()) extraRotationDeg else 0f
            val totalRotDeg = wrapDegrees180(tuning.rotationDeg + safeExtra)
            if (abs(totalRotDeg) > 0.05f) {
                val rad = totalRotDeg * (PI / 180.0)
                val c = cos(rad).toFloat()
                val s = sin(rad).toFloat()
                val rotX = rx * c - ry * s
                val rotY = rx * s + ry * c
                rx = rotX
                ry = rotY
            }
        }

        if (tuning.angleSnapping > 0f) {
            val mag = hypot(rx, ry)
            if (mag > 1e-5f) {
                val ax = abs(rx)
                val ay = abs(ry)
                val coneRad = tuning.angleSnapping.coerceIn(0f, 1f) * 18f * (PI.toFloat() / 180f)
                val err = atan2(minOf(ax, ay), maxOf(ax, ay))
                if (err < coneRad && coneRad > 1e-5f) {
                    val ratio = (err / coneRad).coerceIn(0f, 1f)
                    val damp = ratio * ratio
                    val sx = if (ax >= ay) rx else rx * damp
                    val sy = if (ay > ax) ry else ry * damp
                    val sMag = hypot(sx, sy)
                    if (sMag > 1e-5f) {
                        val scale = mag / sMag
                        rx = sx * scale
                        ry = sy * scale
                    }
                }
            }
        }

        val yRatio = tuning.yAxisRatio.coerceIn(0.40f, 1.60f)
        return rx to (ry * yRatio)
    }

    fun computeLowSpeedGain(
        speed: Float,
        tuning: TrackpadTuningConfig
    ): Float {
        if (tuning.lowSpeedPrecision >= 0.999f) return 1f
        val safeLow = tuning.lowSpeedPrecision.coerceIn(0.30f, 1.00f)
        val cutoff = maxOf(0.14f, tuning.accelThreshold)
        val t = (speed.coerceAtLeast(0f) / cutoff).coerceIn(0f, 1f)
        val s = t * t * (3f - 2f * t)
        return safeLow + (1f - safeLow) * s
    }

    fun computeNativeAccelGain(
        speedMmPerMs: Float,
        tuning: TrackpadTuningConfig
    ): Float {
        val lowGain = computeLowSpeedGain(speedMmPerMs, tuning)
        val cap = tuning.speedCap.coerceIn(1.00f, 5.00f)
        if (tuning.pointerAccel <= 0f) {
            return lowGain.coerceAtMost(cap)
        }
        val excess = ((speedMmPerMs - tuning.accelThreshold) / 0.75f).coerceIn(0f, 2.0f)
        val curved = if (excess <= 0f) 0f else excess.toDouble().pow(tuning.accelCurve.toDouble()).toFloat()
        val accelMult = (1f + tuning.pointerAccel * curved).coerceAtMost(cap)
        return (lowGain * accelMult).coerceAtMost(cap)
    }

    fun edgePalmGuardPx(
        widthPx: Int,
        heightPx: Int,
        xdpi: Float,
        ydpi: Float,
        fallbackDensity: Float,
        edgePalmMm: Float
    ): Pair<Float, Float> {
        if (edgePalmMm <= 0f || widthPx <= 0 || heightPx <= 0) return 0f to 0f
        val (validXdpi, validYdpi) = resolveValidDpi(xdpi, ydpi, fallbackDensity)
        val pxPerMmX = validXdpi / MM_PER_INCH
        val pxPerMmY = validYdpi / MM_PER_INCH
        val guardX = (edgePalmMm * pxPerMmX).coerceAtMost(widthPx * 0.25f)
        val guardBottomY = (edgePalmMm * pxPerMmY).coerceAtMost(heightPx * 0.20f)
        return guardX to guardBottomY
    }

    fun isInEdgePalmGuard(
        xPx: Float,
        yPx: Float,
        widthPx: Int,
        heightPx: Int,
        xdpi: Float,
        ydpi: Float,
        fallbackDensity: Float,
        edgePalmMm: Float
    ): Boolean {
        val (guardX, guardBottomY) = edgePalmGuardPx(
            widthPx = widthPx,
            heightPx = heightPx,
            xdpi = xdpi,
            ydpi = ydpi,
            fallbackDensity = fallbackDensity,
            edgePalmMm = edgePalmMm
        )
        if (guardX <= 0f && guardBottomY <= 0f) return false
        return xPx < guardX || xPx > (widthPx - guardX) || yPx > (heightPx - guardBottomY)
    }
}

/**
 * Estimates continuous 360-degree screen-plane tilt rotation compensation from Android's
 * gravity/accelerometer sensor, preserving orientation when resting flat and freezing on touch.
 */
class GravityTiltEstimator {
    var currentDeg: Float = 0f
        private set

    private var filtGx: Float = 0f
    private var filtGy: Float = 0f
    private var filtGz: Float = 0f
    private var hasAccelSeed: Boolean = false
    private var hasAngleSeed: Boolean = false

    fun reset() {
        currentDeg = 0f
        filtGx = 0f
        filtGy = 0f
        filtGz = 0f
        hasAccelSeed = false
        hasAngleSeed = false
    }

    fun update(
        rawGx: Float,
        rawGy: Float,
        rawGz: Float,
        displayRotation: Int = 0,
        isAccelerometerFallback: Boolean = false,
        isTouching: Boolean = false
    ): Float {
        if (!rawGx.isFinite() || !rawGy.isFinite() || !rawGz.isFinite()) {
            return currentDeg
        }

        val gx: Float
        val gy: Float
        val gz: Float
        if (isAccelerometerFallback) {
            if (!hasAccelSeed) {
                filtGx = rawGx
                filtGy = rawGy
                filtGz = rawGz
                hasAccelSeed = true
            } else {
                val alpha = 0.20f
                filtGx += (rawGx - filtGx) * alpha
                filtGy += (rawGy - filtGy) * alpha
                filtGz += (rawGz - filtGz) * alpha
            }
            gx = filtGx
            gy = filtGy
            gz = filtGz
        } else {
            gx = rawGx
            gy = rawGy
            gz = rawGz
        }

        if (isTouching) {
            return currentDeg
        }

        val (sx, sy) = when (displayRotation) {
            1 -> -gy to gx       // Surface.ROTATION_90
            2 -> -gx to -gy      // Surface.ROTATION_180
            3 -> gy to -gx       // Surface.ROTATION_270
            else -> gx to gy     // Surface.ROTATION_0
        }

        val gPlane = hypot(sx, sy)
        val gTotal = hypot(gPlane, gz).coerceAtLeast(1f)
        val planeRatio = gPlane / gTotal
        // Hold current orientation cleanly when resting flat on a table (< ~17.5 deg lift)
        if (planeRatio < 0.30f) {
            return currentDeg
        }

        val rawDeg = TrackpadVectorMath.wrapDegrees180(
            Math.toDegrees(atan2((-sx).toDouble(), sy.toDouble())).toFloat()
        )
        if (!hasAngleSeed) {
            currentDeg = rawDeg
            hasAngleSeed = true
            return currentDeg
        }

        val diff = TrackpadVectorMath.wrapDegrees180(rawDeg - currentDeg)
        val confidence = ((planeRatio - 0.30f) / 0.25f).coerceIn(0.20f, 1f)
        if (abs(diff) >= 0.35f) {
            currentDeg = TrackpadVectorMath.wrapDegrees180(currentDeg + diff * 0.22f * confidence)
        }
        return currentDeg
    }
}
