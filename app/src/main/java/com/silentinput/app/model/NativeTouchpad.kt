package com.silentinput.app.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Initial velocity snapshot for single-finger coasting inertia in Native Touchpad Mode.
 */
data class NativeCoastStart(
    val pointerId: Int,
    val startX: Float,
    val startY: Float,
    val vxUnitsPerMs: Float,
    val vyUnitsPerMs: Float
)

/**
 * Single multi-touch contact coordinate in virtual touchpad device units (40 units/mm).
 */
data class TouchpadContact(
    val pointerId: Int,
    val x: Int,
    val y: Int
)

/**
 * Processed frame ready for transmission over UDP or JSON.
 */
data class TouchpadEmitFrame(
    val contacts: List<TouchpadContact>,
    val isTransition: Boolean
)

/**
 * Maps Android [android.view.MotionEvent] screen coordinates (px) onto the Linux
 * `SilentInput-Virtual-Touchpad` coordinate plane using uniform physical mm scaling and a
 * palm-safe edge margin so libinput edge palm rejection never swallows phone-edge swipes.
 */
object NativeTouchpadMapper {
    const val LEGACY_MAX_COORD = 8000
    const val DEFAULT_MAX_COORD = 16000
    const val MAX_X = LEGACY_MAX_COORD
    const val MAX_Y = LEGACY_MAX_COORD
    const val RES_UNITS_PER_MM = 40f
    const val EDGE_MARGIN = 800
    const val MAX_CONTACTS = 5
    private const val MM_PER_INCH = 25.4f

    fun safeMarginFor(maxCoord: Int = LEGACY_MAX_COORD): Int {
        val safeMax = maxCoord.coerceAtLeast(4000)
        return maxOf(800, (safeMax * 0.10f).roundToInt())
    }

    fun usableSpanMmFor(maxCoord: Int = LEGACY_MAX_COORD): Float {
        val safeMax = maxCoord.coerceAtLeast(4000)
        val margin = safeMarginFor(safeMax)
        return ((safeMax - 2 * margin).coerceAtLeast(1600)) / RES_UNITS_PER_MM
    }

    fun mapContacts(
        pointerIds: IntArray,
        xs: FloatArray,
        ys: FloatArray,
        count: Int,
        surfaceWidthPx: Int,
        surfaceHeightPx: Int,
        xdpi: Float,
        ydpi: Float,
        fallbackDensity: Float,
        sensitivity: Float,
        maxCoord: Int = LEGACY_MAX_COORD
    ): List<TouchpadContact> {
        val desiredScale = 0.7f + sensitivity.coerceIn(0.1f, 2.0f) * 0.8f
        return mapWithScale(
            pointerIds = pointerIds,
            xs = xs,
            ys = ys,
            count = count,
            surfaceWidthPx = surfaceWidthPx,
            surfaceHeightPx = surfaceHeightPx,
            xdpi = xdpi,
            ydpi = ydpi,
            fallbackDensity = fallbackDensity,
            desiredScale = desiredScale,
            maxCoord = maxCoord
        )
    }

    /**
     * 1:1 physical mm mapping anchored at center of [maxCoord] plane for downstream
     * kinematics tuning ([NativeTouchpadKinematics]).
     */
    fun mapRawPhysicalContacts(
        pointerIds: IntArray,
        xs: FloatArray,
        ys: FloatArray,
        count: Int,
        surfaceWidthPx: Int,
        surfaceHeightPx: Int,
        xdpi: Float,
        ydpi: Float,
        fallbackDensity: Float,
        maxCoord: Int = DEFAULT_MAX_COORD,
        rotationDeg: Float = 0f
    ): List<TouchpadContact> {
        return mapWithScale(
            pointerIds = pointerIds,
            xs = xs,
            ys = ys,
            count = count,
            surfaceWidthPx = surfaceWidthPx,
            surfaceHeightPx = surfaceHeightPx,
            xdpi = xdpi,
            ydpi = ydpi,
            fallbackDensity = fallbackDensity,
            desiredScale = 1.0f,
            maxCoord = maxCoord,
            rotationDeg = rotationDeg
        )
    }

    private fun mapWithScale(
        pointerIds: IntArray,
        xs: FloatArray,
        ys: FloatArray,
        count: Int,
        surfaceWidthPx: Int,
        surfaceHeightPx: Int,
        xdpi: Float,
        ydpi: Float,
        fallbackDensity: Float,
        desiredScale: Float,
        maxCoord: Int,
        rotationDeg: Float = 0f
    ): List<TouchpadContact> {
        if (count <= 0 || surfaceWidthPx <= 0 || surfaceHeightPx <= 0) {
            return emptyList()
        }

        val safeMax = maxCoord.coerceAtLeast(4000)
        val margin = safeMarginFor(safeMax)
        val usableSpanMm = usableSpanMmFor(safeMax)
        val centerCoord = safeMax * 0.5f

        val (validXdpi, validYdpi) = TrackpadVectorMath.resolveValidDpi(xdpi, ydpi, fallbackDensity)

        val mmPerPxX = MM_PER_INCH / validXdpi
        val mmPerPxY = MM_PER_INCH / validYdpi

        val widthMm = (surfaceWidthPx * mmPerPxX).coerceAtLeast(1f)
        val heightMm = (surfaceHeightPx * mmPerPxY).coerceAtLeast(1f)

        val angle = TrackpadVectorMath.wrapDegrees180(rotationDeg)
        val applyRot = abs(angle) > 0.05f
        val cosR = if (applyRot) cos(angle * (PI / 180.0)).toFloat() else 1f
        val sinR = if (applyRot) sin(angle * (PI / 180.0)).toFloat() else 0f

        val maxSafeScale = if (applyRot) {
            val diagMm = hypot(widthMm, heightMm).coerceAtLeast(1f)
            minOf(usableSpanMm / diagMm, usableSpanMm / widthMm, usableSpanMm / heightMm)
        } else {
            minOf(usableSpanMm / widthMm, usableSpanMm / heightMm)
        }
        val scale = desiredScale.coerceAtMost(maxSafeScale)

        val halfW = surfaceWidthPx * 0.5f
        val halfH = surfaceHeightPx * 0.5f
        val limitedCount = count.coerceAtMost(MAX_CONTACTS)
            .coerceAtMost(pointerIds.size)
            .coerceAtMost(xs.size)
            .coerceAtMost(ys.size)

        val result = ArrayList<TouchpadContact>(limitedCount)
        for (i in 0 until limitedCount) {
            val px = xs[i]
            val py = ys[i]
            val safeX = if (px.isFinite()) px else halfW
            val safeY = if (py.isFinite()) py else halfH
            val dxMm = (safeX - halfW) * mmPerPxX * scale
            val dyMm = (safeY - halfH) * mmPerPxY * scale
            val rotDxMm = if (applyRot) dxMm * cosR - dyMm * sinR else dxMm
            val rotDyMm = if (applyRot) dxMm * sinR + dyMm * cosR else dyMm
            val tx = (centerCoord + rotDxMm * RES_UNITS_PER_MM)
                .roundToInt()
                .coerceIn(margin, safeMax - margin)
            val ty = (centerCoord + rotDyMm * RES_UNITS_PER_MM)
                .roundToInt()
                .coerceIn(margin, safeMax - margin)
            result.add(TouchpadContact(pointerId = pointerIds[i] and 0xFFFF, x = tx, y = ty))
        }
        return result
    }
}

/**
 * Applies pro-camera precision kinematics parameters (speed, scroll speed, velocity acceleration,
 * adaptive One-Euro smoothing, micro-deadzone) onto 1:1 raw physical contacts without distorting
 * multi-touch pinch geometry.
 */
class NativeTouchpadKinematics {

    private var activeCount = 0
    private var singleId = -1
    private var anchorX = 0f
    private var anchorY = 0f
    private var prevRawX = 0f
    private var prevRawY = 0f
    private var smoothRawX = 0f
    private var smoothRawY = 0f
    private var outX = 0f
    private var outY = 0f
    private var lastTimeMs = 0L
    private var lastSingleMoveTimeMs = 0L
    private var smoothedSpeedMmPerMs = 0f
    private var outVxUnitsPerMs = 0f
    private var outVyUnitsPerMs = 0f
    private var deadzoneBroken = false

    private var twoId0 = -1
    private var twoId1 = -1
    private var smoothCx = 0f
    private var smoothCy = 0f
    private var outCx = 0f
    private var outCy = 0f
    private var prevTwoSpan = 0f
    private var twoLastTimeMs = 0L

    private var multiCount = 0
    private var prevMultiCx = 0f
    private var prevMultiCy = 0f
    private var outMultiCx = 0f
    private var outMultiCy = 0f

    fun reset() {
        activeCount = 0
        singleId = -1
        deadzoneBroken = false
        smoothedSpeedMmPerMs = 0f
        outVxUnitsPerMs = 0f
        outVyUnitsPerMs = 0f
        lastSingleMoveTimeMs = 0L
        twoId0 = -1
        twoId1 = -1
        prevTwoSpan = 0f
        multiCount = 0
    }

    fun consumeCoastStart(upTimeMs: Long, tuning: TrackpadTuningConfig): NativeCoastStart? {
        if (activeCount != 1 || !deadzoneBroken || tuning.pointerInertia <= 0f) {
            reset()
            return null
        }
        val recency = upTimeMs - lastSingleMoveTimeMs
        val speedUnits = hypot(outVxUnitsPerMs, outVyUnitsPerMs)
        if (recency !in 0L..45L || speedUnits < 18f) {
            reset()
            return null
        }
        val maxUnitsPerMs = 160f
        val scale = if (speedUnits > maxUnitsPerMs) maxUnitsPerMs / speedUnits else 1f
        val coast = NativeCoastStart(
            pointerId = singleId,
            startX = outX,
            startY = outY,
            vxUnitsPerMs = outVxUnitsPerMs * scale,
            vyUnitsPerMs = outVyUnitsPerMs * scale
        )
        reset()
        return coast
    }

    fun process(
        rawContacts: List<TouchpadContact>,
        timeMs: Long,
        tuning: TrackpadTuningConfig,
        maxCoord: Int = NativeTouchpadMapper.DEFAULT_MAX_COORD,
        extraRotationDeg: Float = 0f,
        rotationAlreadyApplied: Boolean = false
    ): List<TouchpadContact> {
        if (rawContacts.isEmpty()) {
            reset()
            return emptyList()
        }

        val safeMax = maxCoord.coerceAtLeast(4000)
        val margin = NativeTouchpadMapper.safeMarginFor(safeMax)
        val minAllowed = margin.toFloat()
        val maxAllowed = (safeMax - margin).toFloat()

        return when (rawContacts.size) {
            1 -> processSingle(rawContacts[0], timeMs, tuning, minAllowed, maxAllowed, extraRotationDeg, rotationAlreadyApplied)
            2 -> processTwo(rawContacts[0], rawContacts[1], timeMs, tuning, minAllowed, maxAllowed)
            else -> processMulti(rawContacts, tuning, minAllowed, maxAllowed)
        }
    }

    private fun processSingle(
        c: TouchpadContact,
        timeMs: Long,
        tuning: TrackpadTuningConfig,
        minAllowed: Float,
        maxAllowed: Float,
        extraRotationDeg: Float = 0f,
        rotationAlreadyApplied: Boolean = false
    ): List<TouchpadContact> {
        val rx = c.x.toFloat().coerceIn(minAllowed, maxAllowed)
        val ry = c.y.toFloat().coerceIn(minAllowed, maxAllowed)
        if (activeCount != 1 || singleId != c.pointerId) {
            reset()
            activeCount = 1
            singleId = c.pointerId
            anchorX = rx
            anchorY = ry
            prevRawX = rx
            prevRawY = ry
            smoothRawX = rx
            smoothRawY = ry
            outX = rx
            outY = ry
            lastTimeMs = timeMs
            deadzoneBroken = tuning.deadzoneMm <= 0f
            return listOf(TouchpadContact(c.pointerId, rx.roundToInt(), ry.roundToInt()))
        }

        if (!deadzoneBroken) {
            val dispMm = hypot(rx - anchorX, ry - anchorY) / NativeTouchpadMapper.RES_UNITS_PER_MM
            if (dispMm < tuning.deadzoneMm) {
                lastTimeMs = timeMs
                return listOf(TouchpadContact(c.pointerId, outX.roundToInt(), outY.roundToInt()))
            }
            deadzoneBroken = true
            prevRawX = rx
            prevRawY = ry
            smoothRawX = rx
            smoothRawY = ry
            lastTimeMs = timeMs
            return listOf(TouchpadContact(c.pointerId, outX.roundToInt(), outY.roundToInt()))
        }

        val dtMs = (timeMs - lastTimeMs).coerceIn(2L, 50L).toFloat()
        lastTimeMs = timeMs

        val instSpeedMm = (hypot(rx - prevRawX, ry - prevRawY) / NativeTouchpadMapper.RES_UNITS_PER_MM) / dtMs
        val alphaVel = 1f - exp(-dtMs / 16f)
        smoothedSpeedMmPerMs = if (smoothedSpeedMmPerMs == 0f) {
            instSpeedMm
        } else {
            smoothedSpeedMmPerMs * (1f - alphaVel) + instSpeedMm * alphaVel
        }
        prevRawX = rx
        prevRawY = ry

        val (fx, fy) = if (tuning.smoothing <= 0f) {
            rx to ry
        } else {
            val tauSlowMs = tuning.smoothing * 28f
            val tauMs = tauSlowMs / (1f + smoothedSpeedMmPerMs * 4.5f)
            val alphaPos = if (tauMs <= 0.1f) 1f else (1f - exp(-dtMs / tauMs)).coerceIn(0.12f, 1f)
            (smoothRawX + (rx - smoothRawX) * alphaPos) to (smoothRawY + (ry - smoothRawY) * alphaPos)
        }

        val fdx = fx - smoothRawX
        val fdy = fy - smoothRawY
        smoothRawX = fx
        smoothRawY = fy

        val accelGain = TrackpadVectorMath.computeNativeAccelGain(smoothedSpeedMmPerMs, tuning)
        val gain = tuning.pointerSpeed * accelGain
        val (txDx, txDy) = TrackpadVectorMath.transformMoveDelta(
            dx = fdx * gain,
            dy = fdy * gain,
            tuning = tuning,
            extraRotationDeg = extraRotationDeg,
            applyRotation = !rotationAlreadyApplied
        )
        outX = (outX + txDx).coerceIn(minAllowed, maxAllowed)
        outY = (outY + txDy).coerceIn(minAllowed, maxAllowed)

        val alphaOutVel = 1f - exp(-dtMs / 14f)
        val instOutVx = txDx / dtMs
        val instOutVy = txDy / dtMs
        if (lastSingleMoveTimeMs == 0L) {
            outVxUnitsPerMs = instOutVx
            outVyUnitsPerMs = instOutVy
        } else {
            outVxUnitsPerMs = outVxUnitsPerMs * (1f - alphaOutVel) + instOutVx * alphaOutVel
            outVyUnitsPerMs = outVyUnitsPerMs * (1f - alphaOutVel) + instOutVy * alphaOutVel
        }
        lastSingleMoveTimeMs = timeMs

        val (emitX, emitY) = if (tuning.predictionMs > 0) {
            val (leadX, leadY) = TrackpadVectorMath.clampLeadVector(
                vx = outVxUnitsPerMs,
                vy = outVyUnitsPerMs,
                predictionMs = tuning.predictionMs,
                stepDx = txDx,
                stepDy = txDy,
                maxLeadAbs = 6f * NativeTouchpadMapper.RES_UNITS_PER_MM
            )
            (outX + leadX).coerceIn(minAllowed, maxAllowed) to (outY + leadY).coerceIn(minAllowed, maxAllowed)
        } else {
            outX to outY
        }
        return listOf(TouchpadContact(c.pointerId, emitX.roundToInt(), emitY.roundToInt()))
    }

    private fun processTwo(
        c0: TouchpadContact,
        c1: TouchpadContact,
        timeMs: Long,
        tuning: TrackpadTuningConfig,
        minAllowed: Float,
        maxAllowed: Float
    ): List<TouchpadContact> {
        val rawCx = (c0.x + c1.x) * 0.5f
        val rawCy = (c0.y + c1.y) * 0.5f
        val ox0 = c0.x - rawCx
        val oy0 = c0.y - rawCy
        val ox1 = c1.x - rawCx
        val oy1 = c1.y - rawCy
        val span = hypot((c1.x - c0.x).toFloat(), (c1.y - c0.y).toFloat())

        if (activeCount != 2 || twoId0 != c0.pointerId || twoId1 != c1.pointerId) {
            reset()
            activeCount = 2
            twoId0 = c0.pointerId
            twoId1 = c1.pointerId
            smoothCx = rawCx
            smoothCy = rawCy
            outCx = rawCx
            outCy = rawCy
            prevTwoSpan = span
            twoLastTimeMs = timeMs
            return listOf(c0, c1)
        }

        val dtMs = (timeMs - twoLastTimeMs).coerceIn(2L, 50L).toFloat()
        twoLastTimeMs = timeMs

        val (fcx, fcy) = if (tuning.smoothing <= 0f) {
            rawCx to rawCy
        } else {
            val tau = tuning.smoothing * 18f
            val alpha = if (tau <= 0.1f) 1f else (1f - exp(-dtMs / tau)).coerceIn(0.20f, 1f)
            (smoothCx + (rawCx - smoothCx) * alpha) to (smoothCy + (rawCy - smoothCy) * alpha)
        }

        var dcx = fcx - smoothCx
        var dcy = fcy - smoothCy
        smoothCx = fcx
        smoothCy = fcy

        val spanDelta = abs(span - prevTwoSpan)
        prevTwoSpan = span
        val centroidDelta = hypot(dcx, dcy)
        if (tuning.scrollAxisLock && centroidDelta > 1e-3f && spanDelta < 0.25f * centroidDelta) {
            if (abs(dcx) > 2.2f * abs(dcy)) {
                dcy = 0f
            } else if (abs(dcy) > 2.2f * abs(dcx)) {
                dcx = 0f
            }
        }

        outCx += dcx * tuning.scrollSpeed
        outCy += dcy * tuning.scrollSpeed

        val minOx = minOf(ox0, ox1)
        val maxOx = maxOf(ox0, ox1)
        val minOy = minOf(oy0, oy1)
        val maxOy = maxOf(oy0, oy1)
        val cxLow = (minAllowed - minOx).coerceAtMost(maxAllowed)
        val cxHigh = (maxAllowed - maxOx).coerceAtLeast(cxLow)
        val cyLow = (minAllowed - minOy).coerceAtMost(maxAllowed)
        val cyHigh = (maxAllowed - maxOy).coerceAtLeast(cyLow)
        outCx = outCx.coerceIn(cxLow, cxHigh)
        outCy = outCy.coerceIn(cyLow, cyHigh)

        return listOf(
            TouchpadContact(
                pointerId = c0.pointerId,
                x = (outCx + ox0).roundToInt().coerceIn(minAllowed.toInt(), maxAllowed.toInt()),
                y = (outCy + oy0).roundToInt().coerceIn(minAllowed.toInt(), maxAllowed.toInt())
            ),
            TouchpadContact(
                pointerId = c1.pointerId,
                x = (outCx + ox1).roundToInt().coerceIn(minAllowed.toInt(), maxAllowed.toInt()),
                y = (outCy + oy1).roundToInt().coerceIn(minAllowed.toInt(), maxAllowed.toInt())
            )
        )
    }

    private fun processMulti(
        contacts: List<TouchpadContact>,
        tuning: TrackpadTuningConfig,
        minAllowed: Float,
        maxAllowed: Float
    ): List<TouchpadContact> {
        var sumX = 0f
        var sumY = 0f
        for (c in contacts) {
            sumX += c.x
            sumY += c.y
        }
        val rawCx = sumX / contacts.size
        val rawCy = sumY / contacts.size

        if (activeCount != contacts.size || multiCount != contacts.size) {
            reset()
            activeCount = contacts.size
            multiCount = contacts.size
            prevMultiCx = rawCx
            prevMultiCy = rawCy
            outMultiCx = rawCx
            outMultiCy = rawCy
            return contacts
        }

        val gain = tuning.pointerSpeed.coerceIn(0.85f, 1.60f)
        outMultiCx = (outMultiCx + (rawCx - prevMultiCx) * gain).coerceIn(minAllowed, maxAllowed)
        outMultiCy = (outMultiCy + (rawCy - prevMultiCy) * gain).coerceIn(minAllowed, maxAllowed)
        prevMultiCx = rawCx
        prevMultiCy = rawCy

        return contacts.map { c ->
            val ox = c.x - rawCx
            val oy = c.y - rawCy
            TouchpadContact(
                pointerId = c.pointerId,
                x = (outMultiCx + ox).roundToInt().coerceIn(minAllowed.toInt(), maxAllowed.toInt()),
                y = (outMultiCy + oy).roundToInt().coerceIn(minAllowed.toInt(), maxAllowed.toInt())
            )
        }
    }
}

/**
 * Stabilizes fast multi-finger gestures between phone touchscreens and Linux `libinput`:
 * 1. Configurable entry coalesce window (`0..45ms`, default `22ms`): buffers initial single-finger
 *    contact so staggered 2-finger touchdowns arrive together without moving the cursor first.
 * 2. Optional multi-finger lift lock (`antiLiftWhip`): once a gesture reaches >=2 fingers,
 *    dropping any finger ends the touch (`emptyList()`) so uneven liftoff never whips the cursor.
 */
class NativeTouchpadSessionFilter(
    initialEntryCoalesceMs: Long = DEFAULT_ENTRY_COALESCE_MS,
    initialAntiLiftWhip: Boolean = true
) {
    companion object {
        const val DEFAULT_ENTRY_COALESCE_MS = 22L
    }

    private var entryCoalesceMs: Long = initialEntryCoalesceMs.coerceAtLeast(0L)
    private var antiLiftWhip: Boolean = initialAntiLiftWhip

    private var entryCoalesceStartMs: Long = 0L
    private var bufferedDown: List<TouchpadContact>? = null
    private var bufferedLatest: List<TouchpadContact>? = null
    private var peakPointerCount: Int = 0
    private var liftLocked: Boolean = false
    private var hasSentLiveTouch: Boolean = false

    val isEntryBuffered: Boolean
        @Synchronized get() = bufferedDown != null

    @Synchronized
    fun canCoastSingleFinger(): Boolean =
        !liftLocked && bufferedDown == null && hasSentLiveTouch && peakPointerCount == 1

    @Synchronized
    fun updateConfig(entryCoalesceMs: Long, antiLiftWhip: Boolean) {
        this.entryCoalesceMs = entryCoalesceMs.coerceAtLeast(0L)
        this.antiLiftWhip = antiLiftWhip
    }

    @Synchronized
    fun reset() {
        entryCoalesceStartMs = 0L
        bufferedDown = null
        bufferedLatest = null
        peakPointerCount = 0
        liftLocked = false
        hasSentLiveTouch = false
    }

    @Synchronized
    fun onDown(contacts: List<TouchpadContact>, timeMs: Long): List<TouchpadEmitFrame> {
        reset()
        if (contacts.isEmpty()) return emptyList()
        if (contacts.size == 1 && entryCoalesceMs > 0L) {
            bufferedDown = contacts
            bufferedLatest = contacts
            entryCoalesceStartMs = timeMs
            peakPointerCount = 1
            return emptyList()
        }
        peakPointerCount = contacts.size
        hasSentLiveTouch = true
        return listOf(TouchpadEmitFrame(contacts, isTransition = true))
    }

    @Synchronized
    fun onPointerDown(contacts: List<TouchpadContact>): List<TouchpadEmitFrame> {
        if (liftLocked) return emptyList()
        if (contacts.isEmpty()) return onAllUp()
        if (bufferedDown != null) {
            bufferedDown = null
            bufferedLatest = null
        }
        if (contacts.size > peakPointerCount) {
            peakPointerCount = contacts.size
        }
        hasSentLiveTouch = true
        return listOf(TouchpadEmitFrame(contacts, isTransition = true))
    }

    @Synchronized
    fun onMove(contacts: List<TouchpadContact>, timeMs: Long): List<TouchpadEmitFrame> {
        if (liftLocked) return emptyList()
        if (contacts.isEmpty()) return emptyList()

        val down = bufferedDown
        if (down != null) {
            if (contacts.size >= 2) {
                bufferedDown = null
                bufferedLatest = null
                peakPointerCount = contacts.size
                hasSentLiveTouch = true
                return listOf(TouchpadEmitFrame(contacts, isTransition = true))
            }
            if ((timeMs - entryCoalesceStartMs) < entryCoalesceMs) {
                bufferedLatest = contacts
                return emptyList()
            }
            bufferedDown = null
            bufferedLatest = null
            hasSentLiveTouch = true
            return if (contacts != down) {
                listOf(
                    TouchpadEmitFrame(down, isTransition = true),
                    TouchpadEmitFrame(contacts, isTransition = false)
                )
            } else {
                listOf(TouchpadEmitFrame(down, isTransition = true))
            }
        }

        if (antiLiftWhip && peakPointerCount >= 2 && contacts.size < peakPointerCount) {
            liftLocked = true
            val shouldEmit = hasSentLiveTouch
            hasSentLiveTouch = false
            return if (shouldEmit) {
                listOf(TouchpadEmitFrame(emptyList(), isTransition = true))
            } else {
                emptyList()
            }
        }

        if (contacts.size > peakPointerCount) {
            peakPointerCount = contacts.size
        }
        hasSentLiveTouch = true
        return listOf(TouchpadEmitFrame(contacts, isTransition = false))
    }

    @Synchronized
    fun onEntryTimeout(): List<TouchpadEmitFrame> {
        val down = bufferedDown ?: return emptyList()
        val latest = bufferedLatest
        bufferedDown = null
        bufferedLatest = null
        if (liftLocked) return emptyList()
        hasSentLiveTouch = true
        return if (latest != null && latest != down) {
            listOf(
                TouchpadEmitFrame(down, isTransition = true),
                TouchpadEmitFrame(latest, isTransition = false)
            )
        } else {
            listOf(TouchpadEmitFrame(down, isTransition = true))
        }
    }

    @Synchronized
    fun onPointerUp(remainingContacts: List<TouchpadContact>): List<TouchpadEmitFrame> {
        if (bufferedDown != null) {
            bufferedDown = null
            bufferedLatest = null
        }
        if (liftLocked) return emptyList()
        if (antiLiftWhip && peakPointerCount >= 2 && remainingContacts.size < peakPointerCount) {
            liftLocked = true
            val shouldEmit = hasSentLiveTouch
            hasSentLiveTouch = false
            return if (shouldEmit) {
                listOf(TouchpadEmitFrame(emptyList(), isTransition = true))
            } else {
                emptyList()
            }
        }
        if (remainingContacts.isEmpty()) {
            return onAllUp()
        }
        hasSentLiveTouch = true
        return listOf(TouchpadEmitFrame(remainingContacts, isTransition = true))
    }

    @Synchronized
    fun onAllUp(): List<TouchpadEmitFrame> {
        val down = bufferedDown
        val wasSent = hasSentLiveTouch
        reset()
        return when {
            down != null -> listOf(
                TouchpadEmitFrame(down, isTransition = true),
                TouchpadEmitFrame(emptyList(), isTransition = true)
            )
            wasSent -> listOf(TouchpadEmitFrame(emptyList(), isTransition = true))
            else -> emptyList()
        }
    }

    @Synchronized
    fun onCancel(): List<TouchpadEmitFrame> {
        val wasSent = hasSentLiveTouch
        reset()
        return if (wasSent) {
            listOf(TouchpadEmitFrame(emptyList(), isTransition = true))
        } else {
            emptyList()
        }
    }
}
