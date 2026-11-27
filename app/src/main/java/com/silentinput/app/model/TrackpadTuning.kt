package com.silentinput.app.model

import java.util.Locale
import kotlin.math.roundToInt

enum class TuningCategory {
    BASIC,
    CURVE,
    CORRECTION
}

/**
 * Full-precision trackpad feel tuning configuration modeled after pro-camera manual controls.
 */
data class TrackpadTuningConfig(
    // BASIC
    val pointerSpeed: Float = DEFAULT_POINTER_SPEED,
    val scrollSpeed: Float = DEFAULT_SCROLL_SPEED,
    val pointerAccel: Float = DEFAULT_POINTER_ACCEL,
    val smoothing: Float = DEFAULT_SMOOTHING,
    val deadzoneMm: Float = DEFAULT_DEADZONE_MM,
    val multiTouchCoalesceMs: Int = DEFAULT_MULTI_TOUCH_COALESCE_MS,
    // CURVE
    val yAxisRatio: Float = DEFAULT_Y_AXIS_RATIO,
    val lowSpeedPrecision: Float = DEFAULT_LOW_SPEED_PRECISION,
    val accelThreshold: Float = DEFAULT_ACCEL_THRESHOLD,
    val accelCurve: Float = DEFAULT_ACCEL_CURVE,
    val speedCap: Float = DEFAULT_SPEED_CAP,
    val pointerInertia: Float = DEFAULT_POINTER_INERTIA,
    // CORRECTION
    val rotationDeg: Int = DEFAULT_ROTATION_DEG,
    val angleSnapping: Float = DEFAULT_ANGLE_SNAPPING,
    val edgePalmMm: Float = DEFAULT_EDGE_PALM_MM,
    val predictionMs: Int = DEFAULT_PREDICTION_MS,
    val scrollFlingFriction: Float = DEFAULT_SCROLL_FLING_FRICTION,
    val tapToleranceMm: Float = DEFAULT_TAP_TOLERANCE_MM,
    // TOGGLES
    val antiLiftWhip: Boolean = true,
    val scrollAxisLock: Boolean = true,
    val autoGravityRotation: Boolean = false
) {
    fun clamped(): TrackpadTuningConfig = copy(
        pointerSpeed = snapFloat(pointerSpeed.coerceIn(0.20f, 3.00f), 0.05f),
        scrollSpeed = snapFloat(scrollSpeed.coerceIn(0.20f, 3.00f), 0.05f),
        pointerAccel = snapFloat(pointerAccel.coerceIn(0.00f, 2.00f), 0.05f),
        smoothing = snapFloat(smoothing.coerceIn(0.00f, 1.00f), 0.05f),
        deadzoneMm = snapFloat(deadzoneMm.coerceIn(0.0f, 2.0f), 0.1f),
        multiTouchCoalesceMs = multiTouchCoalesceMs.coerceIn(0, 45),
        yAxisRatio = snapFloat(yAxisRatio.coerceIn(0.40f, 1.60f), 0.05f),
        lowSpeedPrecision = snapFloat(lowSpeedPrecision.coerceIn(0.30f, 1.00f), 0.05f),
        accelThreshold = snapFloat(accelThreshold.coerceIn(0.00f, 0.60f), 0.02f),
        accelCurve = snapFloat(accelCurve.coerceIn(0.50f, 2.50f), 0.10f),
        speedCap = snapFloat(speedCap.coerceIn(1.00f, 5.00f), 0.25f),
        pointerInertia = snapFloat(pointerInertia.coerceIn(0.00f, 1.00f), 0.05f),
        rotationDeg = rotationDeg.coerceIn(-30, 30),
        angleSnapping = snapFloat(angleSnapping.coerceIn(0.00f, 1.00f), 0.05f),
        edgePalmMm = snapFloat(edgePalmMm.coerceIn(0.0f, 12.0f), 0.5f),
        predictionMs = predictionMs.coerceIn(0, 16),
        scrollFlingFriction = snapFloat(scrollFlingFriction.coerceIn(0.20f, 2.00f), 0.10f),
        tapToleranceMm = snapFloat(tapToleranceMm.coerceIn(0.1f, 3.0f), 0.1f)
    )

    fun isDefault(): Boolean = clamped() == TrackpadTuningConfig()

    fun hasModifiedParamIn(category: TuningCategory): Boolean {
        return paramsIn(category).any { param -> getParamStep(param) != param.defaultStep }
    }

    fun getParamStep(param: TuningParam): Int = when (param) {
        TuningParam.POINTER_SPEED -> floatToStep(pointerSpeed, param)
        TuningParam.SCROLL_SPEED -> floatToStep(scrollSpeed, param)
        TuningParam.POINTER_ACCEL -> floatToStep(pointerAccel, param)
        TuningParam.SMOOTHING -> floatToStep(smoothing, param)
        TuningParam.DEADZONE -> floatToStep(deadzoneMm, param)
        TuningParam.MULTI_TOUCH_COALESCE -> (multiTouchCoalesceMs - param.min.toInt()).coerceIn(0, param.maxStep)
        TuningParam.Y_AXIS_RATIO -> floatToStep(yAxisRatio, param)
        TuningParam.LOW_SPEED_PRECISION -> floatToStep(lowSpeedPrecision, param)
        TuningParam.ACCEL_THRESHOLD -> floatToStep(accelThreshold, param)
        TuningParam.ACCEL_CURVE -> floatToStep(accelCurve, param)
        TuningParam.SPEED_CAP -> floatToStep(speedCap, param)
        TuningParam.POINTER_INERTIA -> floatToStep(pointerInertia, param)
        TuningParam.ROTATION_DEG -> (rotationDeg - param.min.toInt()).coerceIn(0, param.maxStep)
        TuningParam.ANGLE_SNAPPING -> floatToStep(angleSnapping, param)
        TuningParam.EDGE_PALM_MM -> floatToStep(edgePalmMm, param)
        TuningParam.PREDICTION_MS -> (predictionMs - param.min.toInt()).coerceIn(0, param.maxStep)
        TuningParam.SCROLL_FLING_FRICTION -> floatToStep(scrollFlingFriction, param)
        TuningParam.TAP_TOLERANCE_MM -> floatToStep(tapToleranceMm, param)
    }

    fun withParamStep(param: TuningParam, rawStep: Int): TrackpadTuningConfig {
        val s = rawStep.coerceIn(0, param.maxStep)
        return when (param) {
            TuningParam.POINTER_SPEED -> copy(pointerSpeed = stepToFloat(param, s))
            TuningParam.SCROLL_SPEED -> copy(scrollSpeed = stepToFloat(param, s))
            TuningParam.POINTER_ACCEL -> copy(pointerAccel = stepToFloat(param, s))
            TuningParam.SMOOTHING -> copy(smoothing = stepToFloat(param, s))
            TuningParam.DEADZONE -> copy(deadzoneMm = stepToFloat(param, s))
            TuningParam.MULTI_TOUCH_COALESCE -> copy(multiTouchCoalesceMs = param.min.toInt() + s)
            TuningParam.Y_AXIS_RATIO -> copy(yAxisRatio = stepToFloat(param, s))
            TuningParam.LOW_SPEED_PRECISION -> copy(lowSpeedPrecision = stepToFloat(param, s))
            TuningParam.ACCEL_THRESHOLD -> copy(accelThreshold = stepToFloat(param, s))
            TuningParam.ACCEL_CURVE -> copy(accelCurve = stepToFloat(param, s))
            TuningParam.SPEED_CAP -> copy(speedCap = stepToFloat(param, s))
            TuningParam.POINTER_INERTIA -> copy(pointerInertia = stepToFloat(param, s))
            TuningParam.ROTATION_DEG -> copy(rotationDeg = param.min.toInt() + s)
            TuningParam.ANGLE_SNAPPING -> copy(angleSnapping = stepToFloat(param, s))
            TuningParam.EDGE_PALM_MM -> copy(edgePalmMm = stepToFloat(param, s))
            TuningParam.PREDICTION_MS -> copy(predictionMs = param.min.toInt() + s)
            TuningParam.SCROLL_FLING_FRICTION -> copy(scrollFlingFriction = stepToFloat(param, s))
            TuningParam.TAP_TOLERANCE_MM -> copy(tapToleranceMm = stepToFloat(param, s))
        }.clamped()
    }

    fun formatValue(param: TuningParam): String = when (param) {
        TuningParam.POINTER_SPEED -> String.format(Locale.US, "%.2fx", pointerSpeed)
        TuningParam.SCROLL_SPEED -> String.format(Locale.US, "%.2fx", scrollSpeed)
        TuningParam.POINTER_ACCEL -> String.format(Locale.US, "%.2f", pointerAccel)
        TuningParam.SMOOTHING -> "${(smoothing * 100f).roundToInt()}%"
        TuningParam.DEADZONE -> String.format(Locale.US, "%.1fmm", deadzoneMm)
        TuningParam.MULTI_TOUCH_COALESCE -> "${multiTouchCoalesceMs}ms"
        TuningParam.Y_AXIS_RATIO -> String.format(Locale.US, "%.2fx", yAxisRatio)
        TuningParam.LOW_SPEED_PRECISION -> String.format(Locale.US, "%.2fx", lowSpeedPrecision)
        TuningParam.ACCEL_THRESHOLD -> String.format(Locale.US, "%.2f", accelThreshold)
        TuningParam.ACCEL_CURVE -> String.format(Locale.US, "%.1f", accelCurve)
        TuningParam.SPEED_CAP -> String.format(Locale.US, "%.2fx", speedCap)
        TuningParam.POINTER_INERTIA -> "${(pointerInertia * 100f).roundToInt()}%"
        TuningParam.ROTATION_DEG -> when {
            rotationDeg > 0 -> "+${rotationDeg}°"
            rotationDeg < 0 -> "${rotationDeg}°"
            else -> "0°"
        }
        TuningParam.ANGLE_SNAPPING -> "${(angleSnapping * 100f).roundToInt()}%"
        TuningParam.EDGE_PALM_MM -> String.format(Locale.US, "%.1fmm", edgePalmMm)
        TuningParam.PREDICTION_MS -> "${predictionMs}ms"
        TuningParam.SCROLL_FLING_FRICTION -> String.format(Locale.US, "%.1fx", scrollFlingFriction)
        TuningParam.TAP_TOLERANCE_MM -> String.format(Locale.US, "%.1fmm", tapToleranceMm)
    }

    fun toLegacySensitivity(): Float = (pointerSpeed * 0.6f).coerceIn(0.1f, 2.0f)

    fun toJson(): String {
        val c = clamped()
        return buildString {
            append("{")
            append("\"pointerSpeed\":${c.pointerSpeed},")
            append("\"scrollSpeed\":${c.scrollSpeed},")
            append("\"pointerAccel\":${c.pointerAccel},")
            append("\"smoothing\":${c.smoothing},")
            append("\"deadzoneMm\":${c.deadzoneMm},")
            append("\"multiTouchCoalesceMs\":${c.multiTouchCoalesceMs},")
            append("\"yAxisRatio\":${c.yAxisRatio},")
            append("\"lowSpeedPrecision\":${c.lowSpeedPrecision},")
            append("\"accelThreshold\":${c.accelThreshold},")
            append("\"accelCurve\":${c.accelCurve},")
            append("\"speedCap\":${c.speedCap},")
            append("\"pointerInertia\":${c.pointerInertia},")
            append("\"rotationDeg\":${c.rotationDeg},")
            append("\"angleSnapping\":${c.angleSnapping},")
            append("\"edgePalmMm\":${c.edgePalmMm},")
            append("\"predictionMs\":${c.predictionMs},")
            append("\"scrollFlingFriction\":${c.scrollFlingFriction},")
            append("\"tapToleranceMm\":${c.tapToleranceMm},")
            append("\"antiLiftWhip\":${c.antiLiftWhip},")
            append("\"scrollAxisLock\":${c.scrollAxisLock},")
            append("\"autoGravityRotation\":${c.autoGravityRotation}")
            append("}")
        }
    }

    companion object {
        const val DEFAULT_POINTER_SPEED = 1.00f
        const val DEFAULT_SCROLL_SPEED = 1.00f
        const val DEFAULT_POINTER_ACCEL = 0.40f
        const val DEFAULT_SMOOTHING = 0.10f
        const val DEFAULT_DEADZONE_MM = 0.2f
        const val DEFAULT_MULTI_TOUCH_COALESCE_MS = 22

        const val DEFAULT_Y_AXIS_RATIO = 1.00f
        const val DEFAULT_LOW_SPEED_PRECISION = 1.00f
        const val DEFAULT_ACCEL_THRESHOLD = 0.12f
        const val DEFAULT_ACCEL_CURVE = 1.00f
        const val DEFAULT_SPEED_CAP = 3.50f
        const val DEFAULT_POINTER_INERTIA = 0.00f

        const val DEFAULT_ROTATION_DEG = 0
        const val DEFAULT_ANGLE_SNAPPING = 0.00f
        const val DEFAULT_EDGE_PALM_MM = 0.0f
        const val DEFAULT_PREDICTION_MS = 0
        const val DEFAULT_SCROLL_FLING_FRICTION = 1.00f
        const val DEFAULT_TAP_TOLERANCE_MM = 0.2f

        fun paramsIn(category: TuningCategory): List<TuningParam> =
            TuningParam.values().filter { it.category == category }

        fun fromLegacySensitivity(legacySensitivity: Float): TrackpadTuningConfig {
            val safe = if (legacySensitivity.isFinite()) legacySensitivity else 0.6f
            val speed = (safe / 0.6f).coerceIn(0.20f, 3.00f)
            return TrackpadTuningConfig(pointerSpeed = speed).clamped()
        }

        fun fromJson(raw: String?, legacySensitivity: Float? = null): TrackpadTuningConfig {
            if (!raw.isNullOrBlank()) {
                val obj = SimpleJson.parseObject(raw)
                if (obj != null) {
                    return TrackpadTuningConfig(
                        pointerSpeed = obj.floatOr("pointerSpeed", DEFAULT_POINTER_SPEED),
                        scrollSpeed = obj.floatOr("scrollSpeed", DEFAULT_SCROLL_SPEED),
                        pointerAccel = obj.floatOr("pointerAccel", DEFAULT_POINTER_ACCEL),
                        smoothing = obj.floatOr("smoothing", DEFAULT_SMOOTHING),
                        deadzoneMm = obj.floatOr("deadzoneMm", DEFAULT_DEADZONE_MM),
                        multiTouchCoalesceMs = obj.intOr("multiTouchCoalesceMs", DEFAULT_MULTI_TOUCH_COALESCE_MS),
                        yAxisRatio = obj.floatOr("yAxisRatio", DEFAULT_Y_AXIS_RATIO),
                        lowSpeedPrecision = obj.floatOr("lowSpeedPrecision", DEFAULT_LOW_SPEED_PRECISION),
                        accelThreshold = obj.floatOr("accelThreshold", DEFAULT_ACCEL_THRESHOLD),
                        accelCurve = obj.floatOr("accelCurve", DEFAULT_ACCEL_CURVE),
                        speedCap = obj.floatOr("speedCap", DEFAULT_SPEED_CAP),
                        pointerInertia = obj.floatOr("pointerInertia", DEFAULT_POINTER_INERTIA),
                        rotationDeg = obj.intOr("rotationDeg", DEFAULT_ROTATION_DEG),
                        angleSnapping = obj.floatOr("angleSnapping", DEFAULT_ANGLE_SNAPPING),
                        edgePalmMm = obj.floatOr("edgePalmMm", DEFAULT_EDGE_PALM_MM),
                        predictionMs = obj.intOr("predictionMs", DEFAULT_PREDICTION_MS),
                        scrollFlingFriction = obj.floatOr("scrollFlingFriction", DEFAULT_SCROLL_FLING_FRICTION),
                        tapToleranceMm = obj.floatOr("tapToleranceMm", DEFAULT_TAP_TOLERANCE_MM),
                        antiLiftWhip = (obj["antiLiftWhip"] as? Boolean) ?: true,
                        scrollAxisLock = (obj["scrollAxisLock"] as? Boolean) ?: true,
                        autoGravityRotation = (obj["autoGravityRotation"] as? Boolean) ?: false
                    ).clamped()
                }
            }
            return if (legacySensitivity != null) {
                fromLegacySensitivity(legacySensitivity)
            } else {
                TrackpadTuningConfig()
            }
        }

        private fun Map<String, Any?>.floatOr(key: String, defaultValue: Float): Float =
            (this[key] as? Number)?.toFloat() ?: defaultValue

        private fun Map<String, Any?>.intOr(key: String, defaultValue: Int): Int =
            (this[key] as? Number)?.toInt() ?: defaultValue

        private fun floatToStep(value: Float, param: TuningParam): Int =
            ((value - param.min) / param.step).roundToInt().coerceIn(0, param.maxStep)

        private fun stepToFloat(param: TuningParam, step: Int): Float =
            snapFloat(param.min + step * param.step, param.step)

        private fun snapFloat(value: Float, step: Float): Float {
            if (step <= 0f || !value.isFinite()) return value
            val steps = (value / step).roundToInt()
            return ((steps * step) * 1000f).roundToInt() / 1000f
        }
    }
}

enum class TrackpadPreset(val config: TrackpadTuningConfig) {
    PRECISE(
        TrackpadTuningConfig(
            pointerSpeed = 0.65f,
            scrollSpeed = 0.85f,
            pointerAccel = 0.15f,
            smoothing = 0.20f,
            deadzoneMm = 0.4f,
            multiTouchCoalesceMs = 24,
            lowSpeedPrecision = 0.65f,
            accelThreshold = 0.20f,
            antiLiftWhip = true,
            scrollAxisLock = true
        )
    ),
    BALANCED(TrackpadTuningConfig()),
    FAST(
        TrackpadTuningConfig(
            pointerSpeed = 1.65f,
            scrollSpeed = 1.50f,
            pointerAccel = 1.10f,
            smoothing = 0.05f,
            deadzoneMm = 0.1f,
            multiTouchCoalesceMs = 18,
            accelThreshold = 0.08f,
            accelCurve = 1.20f,
            speedCap = 4.25f,
            pointerInertia = 0.35f,
            antiLiftWhip = true,
            scrollAxisLock = true
        )
    ),
    RAW(
        TrackpadTuningConfig(
            pointerSpeed = 1.00f,
            scrollSpeed = 1.00f,
            pointerAccel = 0.00f,
            smoothing = 0.00f,
            deadzoneMm = 0.0f,
            multiTouchCoalesceMs = 0,
            antiLiftWhip = true,
            scrollAxisLock = false
        )
    );

    companion object {
        fun match(config: TrackpadTuningConfig): TrackpadPreset? {
            val normalized = config.clamped()
            return values().firstOrNull { it.config == normalized }
        }
    }
}

enum class TuningParam(
    val category: TuningCategory,
    val min: Float,
    val max: Float,
    val step: Float,
    val defaultStep: Int
) {
    // BASIC
    POINTER_SPEED(TuningCategory.BASIC, min = 0.20f, max = 3.00f, step = 0.05f, defaultStep = 16),       // 1.00x (0..56)
    SCROLL_SPEED(TuningCategory.BASIC, min = 0.20f, max = 3.00f, step = 0.05f, defaultStep = 16),        // 1.00x (0..56)
    POINTER_ACCEL(TuningCategory.BASIC, min = 0.00f, max = 2.00f, step = 0.05f, defaultStep = 8),        // 0.40  (0..40)
    SMOOTHING(TuningCategory.BASIC, min = 0.00f, max = 1.00f, step = 0.05f, defaultStep = 2),            // 10%   (0..20)
    DEADZONE(TuningCategory.BASIC, min = 0.0f, max = 2.0f, step = 0.1f, defaultStep = 2),                // 0.2mm (0..20)
    MULTI_TOUCH_COALESCE(TuningCategory.BASIC, min = 0f, max = 45f, step = 1f, defaultStep = 22),        // 22ms  (0..45)

    // CURVE
    Y_AXIS_RATIO(TuningCategory.CURVE, min = 0.40f, max = 1.60f, step = 0.05f, defaultStep = 12),        // 1.00x (0..24)
    LOW_SPEED_PRECISION(TuningCategory.CURVE, min = 0.30f, max = 1.00f, step = 0.05f, defaultStep = 14), // 1.00x (0..14)
    ACCEL_THRESHOLD(TuningCategory.CURVE, min = 0.00f, max = 0.60f, step = 0.02f, defaultStep = 6),      // 0.12  (0..30)
    ACCEL_CURVE(TuningCategory.CURVE, min = 0.50f, max = 2.50f, step = 0.10f, defaultStep = 5),          // 1.0   (0..20)
    SPEED_CAP(TuningCategory.CURVE, min = 1.00f, max = 5.00f, step = 0.25f, defaultStep = 10),           // 3.50x (0..16)
    POINTER_INERTIA(TuningCategory.CURVE, min = 0.00f, max = 1.00f, step = 0.05f, defaultStep = 0),      // 0%    (0..20)

    // CORRECTION
    ROTATION_DEG(TuningCategory.CORRECTION, min = -30f, max = 30f, step = 1f, defaultStep = 30),         // 0°    (0..60)
    ANGLE_SNAPPING(TuningCategory.CORRECTION, min = 0.00f, max = 1.00f, step = 0.05f, defaultStep = 0),  // 0%    (0..20)
    EDGE_PALM_MM(TuningCategory.CORRECTION, min = 0.0f, max = 12.0f, step = 0.5f, defaultStep = 0),      // 0.0mm (0..24)
    PREDICTION_MS(TuningCategory.CORRECTION, min = 0f, max = 16f, step = 1f, defaultStep = 0),           // 0ms   (0..16)
    SCROLL_FLING_FRICTION(TuningCategory.CORRECTION, min = 0.20f, max = 2.00f, step = 0.10f, defaultStep = 8), // 1.0x (0..18)
    TAP_TOLERANCE_MM(TuningCategory.CORRECTION, min = 0.1f, max = 3.0f, step = 0.1f, defaultStep = 1);   // 0.2mm (0..29)

    val maxStep: Int
        get() = ((max - min) / step).roundToInt()
}
