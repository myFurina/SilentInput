package com.silentinput.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class TrackpadTuningTest {

    @Test
    fun `tuning config json roundtrips all 18 parameters and handles legacy formats`() {
        val custom = TrackpadTuningConfig(
            pointerSpeed = 1.85f,
            scrollSpeed = 0.65f,
            pointerAccel = 1.25f,
            smoothing = 0.35f,
            deadzoneMm = 0.7f,
            multiTouchCoalesceMs = 30,
            yAxisRatio = 0.75f,
            lowSpeedPrecision = 0.55f,
            accelThreshold = 0.24f,
            accelCurve = 1.6f,
            speedCap = 4.25f,
            pointerInertia = 0.45f,
            rotationDeg = -14,
            angleSnapping = 0.50f,
            edgePalmMm = 4.5f,
            predictionMs = 7,
            scrollFlingFriction = 1.4f,
            tapToleranceMm = 0.8f,
            antiLiftWhip = false,
            scrollAxisLock = false,
            autoGravityRotation = true
        )
        val parsed = TrackpadTuningConfig.fromJson(custom.toJson())
        assertEquals(custom, parsed)

        // Legacy 6-field JSON falls back to defaults for new parameters
        val legacy6Json = """{"pointerSpeed":1.4,"scrollSpeed":1.2,"pointerAccel":0.5,"smoothing":0.2,"deadzoneMm":0.3,"multiTouchCoalesceMs":18,"antiLiftWhip":false}"""
        val fromLegacyJson = TrackpadTuningConfig.fromJson(legacy6Json)
        assertEquals(1.4f, fromLegacyJson.pointerSpeed, 0.001f)
        assertEquals(1.0f, fromLegacyJson.yAxisRatio, 0.001f)
        assertEquals(0, fromLegacyJson.rotationDeg)
        assertFalse(fromLegacyJson.antiLiftWhip)
        assertTrue(fromLegacyJson.scrollAxisLock)
        assertFalse(fromLegacyJson.autoGravityRotation)

        val migrated = TrackpadTuningConfig.fromJson(null, legacySensitivity = 1.2f)
        assertEquals(2.00f, migrated.pointerSpeed, 0.01f)
        assertTrue(TrackpadTuningConfig().isDefault())
        assertFalse(custom.isDefault())
    }

    @Test
    fun `presets categories steps and localization copy remain complete`() {
        for (preset in TrackpadPreset.values()) {
            assertEquals(preset, TrackpadPreset.match(preset.config))
        }
        assertEquals(3, TuningCategory.values().size)
        for (category in TuningCategory.values()) {
            assertEquals(6, TrackpadTuningConfig.paramsIn(category).size)
        }
        val base = TrackpadTuningConfig()
        for (param in TuningParam.values()) {
            val step = base.getParamStep(param)
            assertEquals(param.defaultStep, step)
            assertEquals(base, base.withParamStep(param, step))

            val zhCopy = TrackpadTuningStrings.ZH.paramCopy(param)
            val enCopy = TrackpadTuningStrings.EN.paramCopy(param)
            assertTrue(zhCopy.short.isNotBlank() && zhCopy.title.isNotBlank() && zhCopy.hint.isNotBlank())
            assertTrue(enCopy.short.isNotBlank() && enCopy.title.isNotBlank() && enCopy.hint.isNotBlank())
            assertTrue("EN hint too long (${enCopy.hint.length}): ${enCopy.hint}", enCopy.hint.length <= 45)
        }
        assertTrue(TrackpadTuningStrings.ZH.autoGravityRotation.isNotBlank())
        assertTrue(TrackpadTuningStrings.EN.autoGravityRotation.isNotBlank())
    }

    @Test
    fun `gravity tilt estimator supports full 360 wrapping flat-table hold and touch freeze`() {
        val estimator = GravityTiltEstimator()
        val g = 9.81f

        // 1. Upright portrait -> 0 deg
        repeat(20) {
            estimator.update(rawGx = 0f, rawGy = g, rawGz = 0f, displayRotation = 0)
        }
        assertEquals(0f, estimator.currentDeg, 0.1f)

        // 2. Clockwise 30 deg tilt (-g*sin(30), g*cos(30), 0) -> converges to +30 deg
        val cwGx = -g * kotlin.math.sin(Math.toRadians(30.0)).toFloat()
        val cwGy = g * kotlin.math.cos(Math.toRadians(30.0)).toFloat()
        repeat(45) {
            estimator.update(rawGx = cwGx, rawGy = cwGy, rawGz = 0f, displayRotation = 0)
        }
        assertEquals(30f, estimator.currentDeg, 1.0f)

        // Passing estimated angle into transformMoveDelta restores world-horizontal right swipe (+cos30, -sin30) to (+1, 0)
        val (restoredX, restoredY) = TrackpadVectorMath.transformMoveDelta(
            dx = kotlin.math.cos(Math.toRadians(30.0)).toFloat(),
            dy = -kotlin.math.sin(Math.toRadians(30.0)).toFloat(),
            tuning = TrackpadTuningConfig(),
            extraRotationDeg = estimator.currentDeg
        )
        assertEquals(1.0f, restoredX, 0.03f)
        assertEquals(0.0f, restoredY, 0.03f)

        // 3. While touching the screen, tilt updates must freeze completely
        val frozenAt = estimator.currentDeg
        repeat(15) {
            estimator.update(rawGx = -cwGx, rawGy = cwGy, rawGz = 0f, displayRotation = 0, isTouching = true)
        }
        assertEquals(frozenAt, estimator.currentDeg, 0.001f)

        // 4. Sideways +90 deg (-g, 0, 0), -90 deg (+g, 0, 0), and upside-down 180 deg (0, -g, 0)
        estimator.reset()
        assertEquals(90f, estimator.update(rawGx = -g, rawGy = 0f, rawGz = 0f, displayRotation = 0), 0.1f)
        estimator.reset()
        assertEquals(-90f, estimator.update(rawGx = g, rawGy = 0f, rawGz = 0f, displayRotation = 0), 0.1f)
        estimator.reset()
        assertEquals(180f, estimator.update(rawGx = 0f, rawGy = -g, rawGz = 0f, displayRotation = 0), 0.1f)

        // 5. Shortest-arc wrap crossing +175 -> -175 never swings through 0 deg
        estimator.reset()
        val p175x = -g * kotlin.math.sin(Math.toRadians(175.0)).toFloat()
        val p175y = g * kotlin.math.cos(Math.toRadians(175.0)).toFloat()
        assertEquals(175f, estimator.update(rawGx = p175x, rawGy = p175y, rawGz = 0f, displayRotation = 0), 0.2f)
        val n175x = -g * kotlin.math.sin(Math.toRadians(-175.0)).toFloat()
        val n175y = g * kotlin.math.cos(Math.toRadians(-175.0)).toFloat()
        repeat(10) {
            val step = estimator.update(rawGx = n175x, rawGy = n175y, rawGz = 0f, displayRotation = 0)
            assertTrue("Expected wrap near +/-180, got $step", abs(step) >= 174f)
        }

        // 6. Flat on table preserves existing orientation instead of snapping to 0 deg
        estimator.reset()
        assertEquals(180f, estimator.update(rawGx = 0f, rawGy = -g, rawGz = 0f, displayRotation = 0), 0.1f)
        repeat(20) {
            estimator.update(rawGx = 0.4f, rawGy = 0.4f, rawGz = g, displayRotation = 0)
        }
        assertEquals(180f, estimator.currentDeg, 0.1f)

        // 7. Landscape ROTATION_90 upright (9.81, 0, 0) -> 0 deg
        estimator.reset()
        repeat(20) {
            estimator.update(rawGx = g, rawGy = 0f, rawGz = 0f, displayRotation = 1)
        }
        assertEquals(0f, estimator.currentDeg, 0.1f)
    }

    @Test
    fun `whole trackpad plane rotation covers native multi-touch and fallback center rotation`() {
        val (rx90, ry90) = TrackpadVectorMath.rotatePointAroundCenter(
            x = 600f,
            y = 500f,
            centerX = 500f,
            centerY = 500f,
            rotationDeg = 90f
        )
        assertEquals(500f, rx90, 0.05f)
        assertEquals(600f, ry90, 0.05f)

        val (rx180, ry180) = TrackpadVectorMath.rotatePointAroundCenter(
            x = 600f,
            y = 400f,
            centerX = 500f,
            centerY = 500f,
            rotationDeg = 180f
        )
        assertEquals(400f, rx180, 0.05f)
        assertEquals(600f, ry180, 0.05f)

        // Native 180-deg rotated whole plane maps two fingers above/right of center to below/left while keeping span exact
        val unrotated = NativeTouchpadMapper.mapRawPhysicalContacts(
            pointerIds = intArrayOf(0, 1),
            xs = floatArrayOf(400f, 600f),
            ys = floatArrayOf(500f, 500f),
            count = 2,
            surfaceWidthPx = 1000,
            surfaceHeightPx = 1000,
            xdpi = 400f,
            ydpi = 400f,
            fallbackDensity = 2.5f,
            maxCoord = NativeTouchpadMapper.DEFAULT_MAX_COORD,
            rotationDeg = 0f
        )
        val rotated180 = NativeTouchpadMapper.mapRawPhysicalContacts(
            pointerIds = intArrayOf(0, 1),
            xs = floatArrayOf(400f, 600f),
            ys = floatArrayOf(500f, 500f),
            count = 2,
            surfaceWidthPx = 1000,
            surfaceHeightPx = 1000,
            xdpi = 400f,
            ydpi = 400f,
            fallbackDensity = 2.5f,
            maxCoord = NativeTouchpadMapper.DEFAULT_MAX_COORD,
            rotationDeg = 180f
        )
        assertTrue(rotated180[0].x > 8000 && rotated180[1].x < 8000)
        val spanUnrot = hypot((unrotated[1].x - unrotated[0].x).toFloat(), (unrotated[1].y - unrotated[0].y).toFloat())
        val spanRot = hypot((rotated180[1].x - rotated180[0].x).toFloat(), (rotated180[1].y - rotated180[0].y).toFloat())
        // 180-deg rotation keeps scale exact or safely clamped within 320mm usable plane (zero pinch distortion)
        assertTrue(abs(rotated180[1].x - 8000) == abs(rotated180[0].x - 8000))
        assertTrue(spanRot > 0f && spanUnrot > 0f && abs(spanRot - spanUnrot) <= spanUnrot * 0.35f)
    }

    @Test
    fun `kinematics deadzone holds anchor and transitions cleanly without jump`() {
        val kinematics = NativeTouchpadKinematics()
        val cfg = TrackpadTuningConfig(
            pointerSpeed = 1.50f,
            pointerAccel = 0f,
            smoothing = 0f,
            deadzoneMm = 1.0f // 40 units @ 40 units/mm
        )
        val maxCoord = NativeTouchpadMapper.DEFAULT_MAX_COORD

        val d0 = kinematics.process(listOf(TouchpadContact(0, 8000, 8000)), 100L, cfg, maxCoord)
        assertEquals(listOf(TouchpadContact(0, 8000, 8000)), d0)

        // Move 20 units (0.5mm < 1.0mm) -> still locked at anchor
        val d1 = kinematics.process(listOf(TouchpadContact(0, 8020, 8000)), 108L, cfg, maxCoord)
        assertEquals(listOf(TouchpadContact(0, 8000, 8000)), d1)

        // Break deadzone at 45 units (1.125mm >= 1.0mm) -> baseline anchored smoothly without sudden leap
        val d2 = kinematics.process(listOf(TouchpadContact(0, 8045, 8000)), 116L, cfg, maxCoord)
        assertEquals(listOf(TouchpadContact(0, 8000, 8000)), d2)

        // Subsequent +40 units scales cleanly by 1.5x -> +60
        val d3 = kinematics.process(listOf(TouchpadContact(0, 8085, 8000)), 124L, cfg, maxCoord)
        assertEquals(listOf(TouchpadContact(0, 8060, 8000)), d3)
    }

    @Test
    fun `yAxisRatio scales vertical movement independently across native and mouse modes`() {
        val kinematics = NativeTouchpadKinematics()
        val cfg = TrackpadTuningConfig(
            pointerSpeed = 1.00f,
            pointerAccel = 0f,
            smoothing = 0f,
            deadzoneMm = 0f,
            yAxisRatio = 0.50f
        )
        val maxCoord = NativeTouchpadMapper.DEFAULT_MAX_COORD
        kinematics.process(listOf(TouchpadContact(0, 8000, 8000)), 0L, cfg, maxCoord)
        val out = kinematics.process(listOf(TouchpadContact(0, 8100, 8100)), 16L, cfg, maxCoord)[0]
        assertEquals(8100, out.x)
        assertEquals(8050, out.y)

        val acc = TrackpadAccumulator()
        val (mx, my) = acc.addAcceleratedMoveDelta(
            dx = 60f,
            dy = 60f,
            dtMs = 16f,
            density = 3.0f,
            sensitivity = 1.0f,
            tuning = cfg
        )
        assertEquals(60, mx)
        assertEquals(30, my)
    }

    @Test
    fun `rotationDeg rotates movement vectors cleanly`() {
        val cfg30 = TrackpadTuningConfig(rotationDeg = 30)
        val (rx30, ry30) = TrackpadVectorMath.transformMoveDelta(100f, 0f, cfg30)
        assertEquals(86.6025f, rx30, 0.05f)
        assertEquals(50.0f, ry30, 0.05f)

        val cfgNeg = TrackpadTuningConfig(rotationDeg = -30)
        val (rxNeg, ryNeg) = TrackpadVectorMath.transformMoveDelta(0f, 100f, cfgNeg)
        assertEquals(50.0f, rxNeg, 0.05f)
        assertEquals(86.6025f, ryNeg, 0.05f)
    }

    @Test
    fun `angleSnapping straightens near cardinal motion while preserving magnitude and diagonals`() {
        val snapCfg = TrackpadTuningConfig(angleSnapping = 1.0f) // max ~18 deg cone
        // ~5 deg off horizontal -> strong pull onto X axis, preserving magnitude ~100
        val (sx, sy) = TrackpadVectorMath.transformMoveDelta(99.6f, 8.7f, snapCfg)
        val magBefore = hypot(99.6f, 8.7f)
        val magAfter = hypot(sx, sy)
        assertEquals(magBefore, magAfter, 0.05f)
        assertTrue("Expected snapped sy ($sy) much smaller than raw 8.7f", abs(sy) < 1.5f)

        // 45 deg diagonal -> completely untouched
        val (dx45, dy45) = TrackpadVectorMath.transformMoveDelta(70f, 70f, snapCfg)
        assertEquals(70f, dx45, 0.01f)
        assertEquals(70f, dy45, 0.01f)
    }

    @Test
    fun `lowSpeedPrecision attenuates slow micro moves while accelThreshold curve and speedCap shape fast flicks`() {
        val cfg = TrackpadTuningConfig(
            pointerAccel = 1.50f,
            lowSpeedPrecision = 0.50f,
            accelThreshold = 0.30f,
            accelCurve = 1.40f,
            speedCap = 2.00f
        )
        val slowGain = TrackpadVectorMath.computeNativeAccelGain(0.02f, cfg)
        val mediumLinearGain = TrackpadVectorMath.computeNativeAccelGain(0.30f, cfg)
        val fastGain = TrackpadVectorMath.computeNativeAccelGain(3.00f, cfg)

        assertTrue("Expected slow gain ~0.5x, got $slowGain", slowGain in 0.49f..0.55f)
        assertEquals(1.00f, mediumLinearGain, 0.02f)
        assertEquals(2.00f, fastGain, 0.001f)

        val mouseSlow = TrackpadAccumulator.computePointerGain(
            velocityDpPerMs = 0.02f,
            accelStrength = cfg.pointerAccel,
            accelThreshold = cfg.accelThreshold,
            accelCurve = cfg.accelCurve,
            speedCap = cfg.speedCap,
            lowSpeedPrecision = cfg.lowSpeedPrecision
        )
        val mouseFast = TrackpadAccumulator.computePointerGain(
            velocityDpPerMs = 3.00f,
            accelStrength = cfg.pointerAccel,
            accelThreshold = cfg.accelThreshold,
            accelCurve = cfg.accelCurve,
            speedCap = cfg.speedCap,
            lowSpeedPrecision = cfg.lowSpeedPrecision
        )
        assertTrue(mouseSlow < 0.6f)
        assertEquals(2.00f, mouseFast, 0.001f)
    }

    @Test
    fun `predictionMs adds forward lead while moving and cancels cleanly on stop without drift`() {
        val rawK = NativeTouchpadKinematics()
        val predK = NativeTouchpadKinematics()
        val rawCfg = TrackpadTuningConfig(pointerAccel = 0f, smoothing = 0f, deadzoneMm = 0f, predictionMs = 0)
        val predCfg = TrackpadTuningConfig(pointerAccel = 0f, smoothing = 0f, deadzoneMm = 0f, predictionMs = 10)
        val maxCoord = NativeTouchpadMapper.DEFAULT_MAX_COORD

        rawK.process(listOf(TouchpadContact(0, 8000, 8000)), 0L, rawCfg, maxCoord)
        predK.process(listOf(TouchpadContact(0, 8000, 8000)), 0L, predCfg, maxCoord)

        val rawMove = rawK.process(listOf(TouchpadContact(0, 8080, 8000)), 10L, rawCfg, maxCoord)[0]
        val predMove = predK.process(listOf(TouchpadContact(0, 8080, 8000)), 10L, predCfg, maxCoord)[0]
        assertTrue("Expected predicted x (${predMove.x}) ahead of raw (${rawMove.x})", predMove.x > rawMove.x)

        // When finger comes to rest, prediction lead drops to 0 (no net drift)
        val predStop = predK.process(listOf(TouchpadContact(0, 8080, 8000)), 20L, predCfg, maxCoord)[0]
        assertEquals(8080, predStop.x)

        // Accumulator mode prediction also maintains zero cumulative drift on stop
        val accNoPred = TrackpadAccumulator()
        val accPred = TrackpadAccumulator()
        var sumNoPred = 0
        var sumPred = 0
        repeat(3) {
            sumNoPred += accNoPred.addAcceleratedMoveDelta(12f, 0f, 8f, 3.0f, 1.0f, tuning = rawCfg).first
            sumPred += accPred.addAcceleratedMoveDelta(12f, 0f, 8f, 3.0f, 1.0f, tuning = predCfg).first
        }
        assertTrue("Expected predicted total ($sumPred) >= non-predicted ($sumNoPred) during motion", sumPred >= sumNoPred)
        sumNoPred += accNoPred.addAcceleratedMoveDelta(0f, 0f, 8f, 3.0f, 1.0f, tuning = rawCfg).first
        sumPred += accPred.addAcceleratedMoveDelta(0f, 0f, 8f, 3.0f, 1.0f, tuning = predCfg).first
        assertEquals(sumNoPred, sumPred)
    }

    @Test
    fun `isInEdgePalmGuard protects left right and bottom margins safely`() {
        val w = 1000
        val h = 1000
        // ~254 dpi => 10 px/mm, 5mm guard => 50px left/right/bottom
        assertTrue(TrackpadVectorMath.isInEdgePalmGuard(20f, 500f, w, h, 254f, 254f, 2f, 5.0f))
        assertTrue(TrackpadVectorMath.isInEdgePalmGuard(980f, 500f, w, h, 254f, 254f, 2f, 5.0f))
        assertTrue(TrackpadVectorMath.isInEdgePalmGuard(500f, 970f, w, h, 254f, 254f, 2f, 5.0f))
        // Center and top edge stay active
        assertFalse(TrackpadVectorMath.isInEdgePalmGuard(500f, 500f, w, h, 254f, 254f, 2f, 5.0f))
        assertFalse(TrackpadVectorMath.isInEdgePalmGuard(500f, 10f, w, h, 254f, 254f, 2f, 5.0f))
        // Guard <= 0mm disables protection completely
        assertFalse(TrackpadVectorMath.isInEdgePalmGuard(1f, 999f, w, h, 254f, 254f, 2f, 0.0f))
    }

    @Test
    fun `pointer coasting engine and native coast start handle fast flicks and staleness cleanly`() {
        val engine = PointerCoastingEngine(stopSpeedPerMs = 0.04f)
        engine.start(vxPerMs = 2.0f, vyPerMs = 0f, inertia = 0.6f)
        assertTrue(engine.isActive)
        var total = 0f
        var steps = 0
        while (engine.isActive && steps < 300) {
            total += engine.step(8f).first
            steps++
        }
        assertFalse(engine.isActive)
        assertTrue("Expected positive coast travel, got $total", total > 20f)

        val kinematics = NativeTouchpadKinematics()
        val cfg = TrackpadTuningConfig(pointerAccel = 0f, smoothing = 0f, deadzoneMm = 0f, pointerInertia = 0.5f)
        val maxCoord = NativeTouchpadMapper.DEFAULT_MAX_COORD
        kinematics.process(listOf(TouchpadContact(0, 8000, 8000)), 100L, cfg, maxCoord)
        kinematics.process(listOf(TouchpadContact(0, 8400, 8000)), 108L, cfg, maxCoord) // 50 units/ms
        // Stale liftoff (> 45ms pause before lift) cancels coasting
        assertNull(kinematics.consumeCoastStart(200L, cfg))

        kinematics.process(listOf(TouchpadContact(0, 8000, 8000)), 300L, cfg, maxCoord)
        kinematics.process(listOf(TouchpadContact(0, 8400, 8000)), 308L, cfg, maxCoord)
        val coast = kinematics.consumeCoastStart(312L, cfg)
        assertNotNull(coast)
        assertTrue(coast!!.vxUnitsPerMs >= 18f)
    }

    @Test
    fun `scroll fling friction scales decay duration`() {
        val highFriction = ScrollFlingEngine().apply { start(FlingVelocity(0f, -4f)) }
        val lowFriction = ScrollFlingEngine().apply { start(FlingVelocity(0f, -4f)) }
        val accHigh = TrackpadAccumulator()
        val accLow = TrackpadAccumulator()

        var highSteps = 0
        while (highFriction.isActive && highSteps < 300) {
            highFriction.step(16f, 3f, 1f, true, true, accHigh, friction = 2.0f)
            highSteps++
        }
        var lowSteps = 0
        while (lowFriction.isActive && lowSteps < 600) {
            lowFriction.step(16f, 3f, 1f, true, true, accLow, friction = 0.3f)
            lowSteps++
        }
        assertTrue("Expected high friction ($highSteps steps) to stop much earlier than low friction ($lowSteps steps)", highSteps * 3 < lowSteps)
    }

    @Test
    fun `scrollAxisLock disabled allows diagonal two finger scroll and tapToleranceMm holds small jitter`() {
        val processor = TrackpadGestureProcessor(TrackpadAccumulator())
        val unlockedCfg = TrackpadTuningConfig(scrollAxisLock = false, tapToleranceMm = 2.0f)
        val ids = intArrayOf(0, 1)
        val xs = floatArrayOf(100f, 160f)
        val ys = floatArrayOf(200f, 200f)

        processor.onPrimaryDown(pointerId = 0, x = xs[0], y = ys[0], timeMs = 1000L, density = 3.0f)
        processor.onPointerDown(pointerId = 1, x = xs[1], y = ys[1])

        // Dominant vertical swipe (dy=+30) with slight horizontal motion (dx=+10): unlocked emits both
        xs[0] = 110f; xs[1] = 170f
        ys[0] = 230f; ys[1] = 230f
        val out = processor.onMoveSample(
            pointerIds = ids,
            xs = xs,
            ys = ys,
            count = 2,
            sampleTimeMs = 1016L,
            density = 3.0f,
            sensitivity = 1.0f,
            naturalScrolling = true,
            hiResScroll = true,
            tuning = unlockedCfg
        )
        assertTrue(out.wheelDelta > 0)
        assertTrue(out.hwheelDelta < 0)

        // Single-finger tap with 15px (~5dp) wobble stays as tap when tapToleranceMm = 2.0f (~37.8px threshold)
        val tapProc = TrackpadGestureProcessor(TrackpadAccumulator())
        tapProc.onPrimaryDown(pointerId = 0, x = 200f, y = 200f, timeMs = 2000L, density = 3.0f)
        tapProc.onMoveSample(
            pointerIds = intArrayOf(0),
            xs = floatArrayOf(215f),
            ys = floatArrayOf(200f),
            count = 1,
            sampleTimeMs = 2020L,
            density = 3.0f,
            sensitivity = 1.0f,
            naturalScrolling = true,
            tuning = unlockedCfg
        )
        assertFalse(tapProc.isDragging)
        assertEquals(TrackpadGestureProcessor.TapAction.PENDING_LEFT_CLICK, tapProc.onAllUp(2060L))
    }

    @Test
    fun `kinematics velocity acceleration amplifies fast flicks more than slow drags`() {
        val slowK = NativeTouchpadKinematics()
        val fastK = NativeTouchpadKinematics()
        val cfg = TrackpadTuningConfig(
            pointerSpeed = 1.00f,
            pointerAccel = 1.50f,
            smoothing = 0f,
            deadzoneMm = 0f
        )
        val maxCoord = NativeTouchpadMapper.DEFAULT_MAX_COORD

        slowK.process(listOf(TouchpadContact(0, 8000, 8000)), 0L, cfg, maxCoord)
        val slowOut = slowK.process(listOf(TouchpadContact(0, 8040, 8000)), 40L, cfg, maxCoord) // 1mm in 40ms

        fastK.process(listOf(TouchpadContact(0, 8000, 8000)), 0L, cfg, maxCoord)
        val fastOut = fastK.process(listOf(TouchpadContact(0, 8040, 8000)), 2L, cfg, maxCoord) // 1mm in 2ms

        assertTrue(fastOut[0].x > slowOut[0].x)
    }

    @Test
    fun `kinematics two-finger scroll speed scales translation while preserving exact pinch distance`() {
        val kinematics = NativeTouchpadKinematics()
        val cfg = TrackpadTuningConfig(
            pointerSpeed = 1.00f,
            scrollSpeed = 2.00f,
            smoothing = 0f
        )
        val maxCoord = NativeTouchpadMapper.DEFAULT_MAX_COORD

        val p0 = listOf(TouchpadContact(0, 7600, 8000), TouchpadContact(1, 8400, 8000))
        val out0 = kinematics.process(p0, 100L, cfg, maxCoord)
        assertEquals(p0, out0)

        // Translate both fingers +100 vertically (8000 -> 8100), keeping X span = 800
        val p1 = listOf(TouchpadContact(0, 7600, 8100), TouchpadContact(1, 8400, 8100))
        val out1 = kinematics.process(p1, 110L, cfg, maxCoord)
        assertEquals(2, out1.size)
        // Centroid moves +200 because scrollSpeed = 2.0x -> y = 8200
        assertEquals(8200, out1[0].y)
        assertEquals(8200, out1[1].y)
        // Pinch distance preserved 1:1 (8400 - 7600 = 800)
        assertEquals(800, abs(out1[1].x - out1[0].x))
    }

    @Test
    fun `kinematics adaptive smoothing damps sudden micro jitter`() {
        val rawK = NativeTouchpadKinematics()
        val smoothK = NativeTouchpadKinematics()
        val rawCfg = TrackpadTuningConfig(pointerAccel = 0f, smoothing = 0f, deadzoneMm = 0f)
        val smoothCfg = TrackpadTuningConfig(pointerAccel = 0f, smoothing = 1.0f, deadzoneMm = 0f)
        val maxCoord = NativeTouchpadMapper.DEFAULT_MAX_COORD

        rawK.process(listOf(TouchpadContact(0, 8000, 8000)), 0L, rawCfg, maxCoord)
        smoothK.process(listOf(TouchpadContact(0, 8000, 8000)), 0L, smoothCfg, maxCoord)

        val rOut = rawK.process(listOf(TouchpadContact(0, 8020, 8000)), 8L, rawCfg, maxCoord)[0]
        val sOut = smoothK.process(listOf(TouchpadContact(0, 8020, 8000)), 8L, smoothCfg, maxCoord)[0]
        assertEquals(8020, rOut.x)
        assertTrue(sOut.x in 8001..8019)
    }
}
