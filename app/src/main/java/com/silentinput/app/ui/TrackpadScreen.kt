package com.silentinput.app.ui

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silentinput.app.model.NativeTouchpadKinematics
import com.silentinput.app.model.NativeTouchpadMapper
import com.silentinput.app.model.NativeTouchpadSessionFilter
import com.silentinput.app.model.PointerCoastingEngine
import com.silentinput.app.model.ScrollFlingEngine
import com.silentinput.app.model.TouchpadContact
import com.silentinput.app.model.TouchpadEmitFrame
import com.silentinput.app.model.TrackpadAccumulator
import com.silentinput.app.model.TrackpadGestureProcessor
import com.silentinput.app.model.TrackpadTuningConfig
import com.silentinput.app.model.TrackpadVectorMath
import com.silentinput.app.model.resolvePhysicalDensityFactor
import com.silentinput.app.ui.theme.SilentMotion
import com.silentinput.app.ui.theme.pressScale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun TrackpadScreen(
    tuning: TrackpadTuningConfig,
    onTuningChange: (TrackpadTuningConfig) -> Unit,
    onTuningChangeFinished: () -> Unit = {},
    onResetTuning: () -> Unit = {},
    naturalScrolling: Boolean = true,
    hiResScroll: Boolean = false,
    isHapticEnabled: Boolean = false,
    isConnected: Boolean = true,
    isLeftHeld: Boolean = false,
    nativeTouchpadActive: Boolean = false,
    touchpadMaxCoord: Int = NativeTouchpadMapper.LEGACY_MAX_COORD,
    onMouseMove: (dx: Float, dy: Float) -> Unit,
    onMouseClick: (button: String) -> Unit,
    onMouseScroll: (wheel: Float, hwheel: Float) -> Unit,
    onTouchpadFrame: (List<TouchpadContact>, Boolean) -> Unit = { _, _ -> },
    onLeftMouseHoldChange: (Boolean) -> Unit = {},
    onSendKey: (String) -> Unit = {}
) {
    val view = LocalView.current
    val strings = LocalAppStrings.current
    val currentIsLeftHeld by rememberUpdatedState(isLeftHeld)
    val currentOnLeftHoldChange by rememberUpdatedState(onLeftMouseHoldChange)
    var showProTuningDeck by rememberSaveable { mutableStateOf(false) }
    val isSurfaceTouchingRef = remember { booleanArrayOf(false) }
    val autoGravityDeg by rememberAutoGravityRotationDeg(
        enabled = tuning.autoGravityRotation,
        isTouchingProvider = { isSurfaceTouchingRef[0] }
    )

    DisposableEffect(view) {
        view.isSoundEffectsEnabled = false
        onDispose {
            if (currentIsLeftHeld) {
                currentOnLeftHoldChange(false)
            }
        }
    }

    fun setLeftHold(held: Boolean) {
        if (held && !isConnected) return
        if (isLeftHeld == held) return
        if (isHapticEnabled) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        onLeftMouseHoldChange(held)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        // Quick selection, screenshot, and pro tuning toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val dragSelectInteraction = remember { MutableInteractionSource() }
            val dragSelectEnabled = isConnected || isLeftHeld
            val dragSelectContainer by animateColorAsState(
                targetValue = if (isLeftHeld) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                animationSpec = tween(SilentMotion.DURATION_STATE_MS),
                label = "dragSelectBg"
            )
            val dragSelectContent by animateColorAsState(
                targetValue = if (isLeftHeld) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                animationSpec = tween(SilentMotion.DURATION_STATE_MS),
                label = "dragSelectFg"
            )
            Button(
                onClick = { setLeftHold(!isLeftHeld) },
                enabled = dragSelectEnabled,
                interactionSource = dragSelectInteraction,
                modifier = Modifier
                    .weight(1.25f)
                    .height(42.dp)
                    .pressScale(dragSelectInteraction, pressedScale = 0.96f, enabled = dragSelectEnabled),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = dragSelectContainer,
                    contentColor = dragSelectContent
                ),
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Text(
                    text = if (isLeftHeld) strings.tpHoldingLeftBtn else strings.tpDragSelectBtn,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }

            val screenshotInteraction = remember { MutableInteractionSource() }
            OutlinedButton(
                onClick = {
                    if (isHapticEnabled) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onSendKey("prtsc")
                },
                interactionSource = screenshotInteraction,
                modifier = Modifier
                    .weight(0.85f)
                    .height(42.dp)
                    .pressScale(screenshotInteraction, pressedScale = 0.96f),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Text(strings.tpScreenshotBtn, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }

            val tuneInteraction = remember { MutableInteractionSource() }
            val tuneBg by animateColorAsState(
                targetValue = if (showProTuningDeck) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
                animationSpec = tween(SilentMotion.DURATION_STATE_MS),
                label = "tuneBtnBg"
            )
            OutlinedButton(
                onClick = {
                    if (isHapticEnabled) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    showProTuningDeck = !showProTuningDeck
                },
                interactionSource = tuneInteraction,
                modifier = Modifier
                    .weight(1.05f)
                    .height(42.dp)
                    .pressScale(tuneInteraction, pressedScale = 0.96f),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = tuneBg,
                    contentColor = if (showProTuningDeck) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                ),
                border = BorderStroke(
                    width = if (showProTuningDeck) 1.5.dp else 1.dp,
                    color = if (showProTuningDeck) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                    }
                ),
                contentPadding = PaddingValues(horizontal = 6.dp)
            ) {
                Text(
                    text = "${strings.tpTuning.proTuneBtn} ${String.format(Locale.US, "%.2fx", tuning.pointerSpeed)}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }

        TrackpadSurface(
            sensitivity = tuning.toLegacySensitivity(),
            tuning = tuning,
            naturalScrolling = naturalScrolling,
            hiResScroll = hiResScroll,
            isHapticEnabled = isHapticEnabled,
            nativeTouchpadActive = nativeTouchpadActive,
            touchpadMaxCoord = touchpadMaxCoord,
            onMouseMove = onMouseMove,
            onMouseClick = { btn ->
                if (isLeftHeld && btn == "left") {
                    setLeftHold(false)
                } else {
                    onMouseClick(btn)
                }
            },
            onMouseScroll = onMouseScroll,
            onTouchpadFrame = onTouchpadFrame,
            onLeftMouseHoldChange = onLeftMouseHoldChange,
            onSendKey = onSendKey,
            isLeftHeld = isLeftHeld,
            minimalStyle = true,
            showEdgeGuardOverlay = showProTuningDeck,
            externalAutoRotationDeg = autoGravityDeg,
            onTouchingChanged = { isSurfaceTouchingRef[0] = it },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            hintText = when {
                isLeftHeld -> strings.tpHoldingHint
                nativeTouchpadActive -> strings.tpNativeHint
                else -> strings.tpDefaultHint
            }
        )

        AnimatedVisibility(
            visible = showProTuningDeck,
            enter = SilentMotion.expandSectionTransition(),
            exit = SilentMotion.collapseSectionTransition()
        ) {
            ProTrackpadTuningDeck(
                tuning = tuning,
                isHapticEnabled = isHapticEnabled,
                onTuningChange = onTuningChange,
                onTuningChangeFinished = onTuningChangeFinished,
                onReset = onResetTuning,
                onClose = { showProTuningDeck = false },
                modifier = Modifier.padding(top = 8.dp),
                liveAutoRotationDeg = autoGravityDeg
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Mouse Buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val leftBtnInteraction = remember { MutableInteractionSource() }
            val leftBtnContainer by animateColorAsState(
                targetValue = if (isLeftHeld) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                animationSpec = tween(SilentMotion.DURATION_STATE_MS),
                label = "leftBtnBg"
            )
            val leftBtnContent by animateColorAsState(
                targetValue = if (isLeftHeld) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
                animationSpec = tween(SilentMotion.DURATION_STATE_MS),
                label = "leftBtnFg"
            )
            Button(
                onClick = {
                    if (isLeftHeld) {
                        setLeftHold(false)
                    } else {
                        if (isHapticEnabled) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onMouseClick("left")
                    }
                },
                interactionSource = leftBtnInteraction,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .pressScale(leftBtnInteraction, pressedScale = 0.96f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = leftBtnContainer)
            ) {
                Text(
                    text = if (isLeftHeld) strings.tpReleaseLeftBtn else strings.tpLeftBtn,
                    fontWeight = FontWeight.Bold,
                    color = leftBtnContent
                )
            }

            val rightBtnInteraction = remember { MutableInteractionSource() }
            Button(
                onClick = {
                    if (isHapticEnabled) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onMouseClick("right")
                },
                interactionSource = rightBtnInteraction,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .pressScale(rightBtnInteraction, pressedScale = 0.96f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Text(strings.tpRightBtn, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TrackpadSurface(
    sensitivity: Float,
    tuning: TrackpadTuningConfig = TrackpadTuningConfig.fromLegacySensitivity(sensitivity),
    naturalScrolling: Boolean = true,
    hiResScroll: Boolean = false,
    isHapticEnabled: Boolean = false,
    nativeTouchpadActive: Boolean = false,
    touchpadMaxCoord: Int = NativeTouchpadMapper.LEGACY_MAX_COORD,
    onMouseMove: (dx: Float, dy: Float) -> Unit,
    onMouseClick: (button: String) -> Unit,
    onMouseScroll: (wheel: Float, hwheel: Float) -> Unit,
    onTouchpadFrame: (List<TouchpadContact>, Boolean) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    isLeftHeld: Boolean = false,
    minimalStyle: Boolean = false,
    showEdgeGuardOverlay: Boolean = false,
    hintText: String? = null,
    onLeftMouseHoldChange: (Boolean) -> Unit = {},
    onSendKey: (String) -> Unit = {},
    externalAutoRotationDeg: Float? = null,
    onTouchingChanged: (Boolean) -> Unit = {}
) {
    val view = LocalView.current
    val context = LocalContext.current
    val fallbackDensity = LocalDensity.current.density
    val displayMetrics = remember(context) { context.resources.displayMetrics }
    val density = remember(displayMetrics, fallbackDensity) {
        resolvePhysicalDensityFactor(displayMetrics.xdpi, displayMetrics.ydpi, fallbackDensity)
    }
    val xdpi = displayMetrics.xdpi
    val ydpi = displayMetrics.ydpi
    val coroutineScope = rememberCoroutineScope()
    val resolvedHintText = hintText ?: LocalAppStrings.current.tpSurfaceStandaloneDefaultHint

    val localTouchingRef = remember { booleanArrayOf(false) }
    val currentOnTouchingChanged by rememberUpdatedState(onTouchingChanged)
    val internalAutoRotDeg by rememberAutoGravityRotationDeg(
        enabled = externalAutoRotationDeg == null && tuning.autoGravityRotation,
        isTouchingProvider = { localTouchingRef[0] }
    )
    val effectiveAutoRotDeg = if (tuning.autoGravityRotation) {
        externalAutoRotationDeg ?: internalAutoRotDeg
    } else {
        0f
    }
    val totalPlaneRotationDeg = TrackpadVectorMath.wrapDegrees180(tuning.rotationDeg + effectiveAutoRotDeg)

    val currentSensitivity by rememberUpdatedState(tuning.toLegacySensitivity())
    val currentTuning by rememberUpdatedState(tuning)
    val currentTotalPlaneRotationDeg by rememberUpdatedState(totalPlaneRotationDeg)
    val currentTouchpadMaxCoord by rememberUpdatedState(touchpadMaxCoord)
    val currentNaturalScrolling by rememberUpdatedState(naturalScrolling)
    val currentHiResScroll by rememberUpdatedState(hiResScroll)
    val currentHapticEnabled by rememberUpdatedState(isHapticEnabled)
    val currentNativeTouchpadActive by rememberUpdatedState(nativeTouchpadActive)
    val currentIsLeftHeld by rememberUpdatedState(isLeftHeld)
    val currentOnMouseMove by rememberUpdatedState(onMouseMove)
    val currentOnMouseClick by rememberUpdatedState(onMouseClick)
    val currentOnMouseScroll by rememberUpdatedState(onMouseScroll)
    val currentOnTouchpadFrame by rememberUpdatedState(onTouchpadFrame)
    val currentOnLeftHoldChange by rememberUpdatedState(onLeftMouseHoldChange)
    val currentOnSendKey by rememberUpdatedState(onSendKey)

    fun triggerHaptic(type: Int = HapticFeedbackConstants.KEYBOARD_TAP) {
        if (currentHapticEnabled) {
            view.performHapticFeedback(type)
        }
    }

    fun dispatchClick(btnStr: String) {
        triggerHaptic()
        currentOnMouseClick(btnStr)
    }

    val gestureProcessor = remember { TrackpadGestureProcessor(TrackpadAccumulator()) }
    val flingEngine = remember { ScrollFlingEngine() }
    val pendingTapJobState = remember { mutableStateOf<Job?>(null) }
    val flingJobState = remember { mutableStateOf<Job?>(null) }
    val mouseCoastJobState = remember { mutableStateOf<Job?>(null) }
    val tapDragActiveState = remember { mutableStateOf(false) }
    val nativeFilter = remember { NativeTouchpadSessionFilter() }
    val nativeKinematics = remember { NativeTouchpadKinematics() }
    val nativeEntryJobState = remember { mutableStateOf<Job?>(null) }
    val nativeCoastJobState = remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(tuning.multiTouchCoalesceMs, tuning.antiLiftWhip) {
        nativeFilter.updateConfig(
            entryCoalesceMs = tuning.multiTouchCoalesceMs.toLong(),
            antiLiftWhip = tuning.antiLiftWhip
        )
    }
    var surfaceSizePx by remember { mutableStateOf(IntSize.Zero) }
    val pointerIdsBuf = remember { IntArray(10) }
    val pointerXsBuf = remember { FloatArray(10) }
    val pointerYsBuf = remember { FloatArray(10) }
    val pointerOrigIdxBuf = remember { IntArray(10) }
    val ignoredPalmPointerIds = remember { mutableSetOf<Int>() }
    val validActivePointerIds = remember { mutableSetOf<Int>() }
    val strokePlaneRotationRef = remember { floatArrayOf(0f) }

    fun syncTouchingState() {
        val active = validActivePointerIds.isNotEmpty()
        if (localTouchingRef[0] != active) {
            localTouchingRef[0] = active
            currentOnTouchingChanged(active)
        }
    }

    fun cancelFling() {
        flingJobState.value?.cancel()
        flingJobState.value = null
        flingEngine.stop()
    }

    fun cancelMouseCoast() {
        mouseCoastJobState.value?.cancel()
        mouseCoastJobState.value = null
    }

    fun releaseTapDragIfActive() {
        if (tapDragActiveState.value) {
            tapDragActiveState.value = false
            currentOnLeftHoldChange(false)
        }
    }

    fun cancelNativeEntryJob() {
        nativeEntryJobState.value?.cancel()
        nativeEntryJobState.value = null
    }

    fun dispatchNativeFrames(frames: List<TouchpadEmitFrame>) {
        for (frame in frames) {
            currentOnTouchpadFrame(frame.contacts, frame.isTransition)
        }
    }

    fun cancelNativeCoast(flushLift: Boolean = true) {
        if (nativeCoastJobState.value != null) {
            nativeCoastJobState.value?.cancel()
            nativeCoastJobState.value = null
            if (flushLift) {
                dispatchNativeFrames(nativeFilter.onAllUp())
            }
        }
    }

    fun isInPalmGuard(xPx: Float, yPx: Float): Boolean {
        return TrackpadVectorMath.isInEdgePalmGuard(
            xPx = xPx,
            yPx = yPx,
            widthPx = surfaceSizePx.width,
            heightPx = surfaceSizePx.height,
            xdpi = xdpi,
            ydpi = ydpi,
            fallbackDensity = fallbackDensity,
            edgePalmMm = currentTuning.edgePalmMm
        )
    }

    fun rotateFallbackPoint(rawX: Float, rawY: Float): Pair<Float, Float> {
        return TrackpadVectorMath.rotatePointAroundCenter(
            x = rawX,
            y = rawY,
            centerX = surfaceSizePx.width * 0.5f,
            centerY = surfaceSizePx.height * 0.5f,
            rotationDeg = strokePlaneRotationRef[0]
        )
    }

    fun mapNativeContacts(event: MotionEvent, excludeActionIndex: Int = -1): List<TouchpadContact> {
        var outCount = 0
        val pCount = event.pointerCount
        for (p in 0 until pCount) {
            if (p == excludeActionIndex) continue
            val pid = event.getPointerId(p)
            if (pid in ignoredPalmPointerIds) continue
            if (outCount >= NativeTouchpadMapper.MAX_CONTACTS) break
            pointerIdsBuf[outCount] = pid
            pointerXsBuf[outCount] = event.getX(p)
            pointerYsBuf[outCount] = event.getY(p)
            outCount++
        }
        val rawMapped = NativeTouchpadMapper.mapRawPhysicalContacts(
            pointerIds = pointerIdsBuf,
            xs = pointerXsBuf,
            ys = pointerYsBuf,
            count = outCount,
            surfaceWidthPx = surfaceSizePx.width,
            surfaceHeightPx = surfaceSizePx.height,
            xdpi = xdpi,
            ydpi = ydpi,
            fallbackDensity = fallbackDensity,
            maxCoord = currentTouchpadMaxCoord,
            rotationDeg = strokePlaneRotationRef[0]
        )
        return nativeKinematics.process(
            rawContacts = rawMapped,
            timeMs = event.eventTime,
            tuning = currentTuning,
            maxCoord = currentTouchpadMaxCoord,
            rotationAlreadyApplied = true
        )
    }

    LaunchedEffect(nativeTouchpadActive) {
        if (nativeTouchpadActive) {
            pendingTapJobState.value?.cancel()
            pendingTapJobState.value = null
            cancelFling()
            cancelMouseCoast()
            releaseTapDragIfActive()
            ignoredPalmPointerIds.clear()
            validActivePointerIds.clear()
            syncTouchingState()
            gestureProcessor.reset()
        } else {
            cancelNativeEntryJob()
            cancelNativeCoast(flushLift = false)
            nativeKinematics.reset()
            ignoredPalmPointerIds.clear()
            validActivePointerIds.clear()
            syncTouchingState()
            dispatchNativeFrames(nativeFilter.onCancel())
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            pendingTapJobState.value?.cancel()
            pendingTapJobState.value = null
            cancelFling()
            cancelMouseCoast()
            releaseTapDragIfActive()
            cancelNativeEntryJob()
            cancelNativeCoast(flushLift = false)
            nativeKinematics.reset()
            ignoredPalmPointerIds.clear()
            validActivePointerIds.clear()
            syncTouchingState()
            dispatchNativeFrames(nativeFilter.onCancel())
            gestureProcessor.reset()
        }
    }

    val borderWidth by animateDpAsState(
        targetValue = if (isLeftHeld) 2.dp else 1.dp,
        animationSpec = tween(200),
        label = "trackpadBorderWidth"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isLeftHeld) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)
        },
        animationSpec = tween(200),
        label = "trackpadBorderColor"
    )

    val dotColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f)
    val tiltIndicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.34f)
    val palmGuardFillColor = MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
    val palmGuardLineColor = MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
    val showPalmGuard = showEdgeGuardOverlay && currentTuning.edgePalmMm > 0f
    val palmGuardMm = currentTuning.edgePalmMm
    Card(
        modifier = modifier
            .onSizeChanged { surfaceSizePx = it }
            .pointerInteropFilter { event ->
                fun handleNativeDown(pid: Int) {
                    view.requestUnbufferedDispatch(event)
                    cancelNativeEntryJob()
                    cancelNativeCoast(flushLift = true)
                    nativeKinematics.reset()
                    strokePlaneRotationRef[0] = currentTotalPlaneRotationDeg
                    validActivePointerIds.add(pid)
                    syncTouchingState()
                    val mapped = mapNativeContacts(event)
                    dispatchNativeFrames(nativeFilter.onDown(mapped, event.eventTime))
                    val coalesceMs = currentTuning.multiTouchCoalesceMs.toLong()
                    if (nativeFilter.isEntryBuffered && coalesceMs > 0L) {
                        nativeEntryJobState.value = coroutineScope.launch {
                            delay(coalesceMs)
                            dispatchNativeFrames(nativeFilter.onEntryTimeout())
                        }
                    }
                }

                fun handleNativeAllUp() {
                    cancelNativeEntryJob()
                    val tuningSnap = currentTuning
                    val maxCoord = currentTouchpadMaxCoord
                    val coastStart = if (nativeFilter.canCoastSingleFinger()) {
                        nativeKinematics.consumeCoastStart(event.eventTime, tuningSnap)
                    } else {
                        nativeKinematics.reset()
                        null
                    }
                    if (coastStart != null) {
                        cancelNativeCoast(flushLift = false)
                        nativeCoastJobState.value = coroutineScope.launch {
                            val engine = PointerCoastingEngine(stopSpeedPerMs = 1.2f)
                            engine.start(coastStart.vxUnitsPerMs, coastStart.vyUnitsPerMs, tuningSnap.pointerInertia)
                            var cx = coastStart.startX
                            var cy = coastStart.startY
                            val safeMax = maxCoord.coerceAtLeast(4000)
                            val margin = NativeTouchpadMapper.safeMarginFor(safeMax).toFloat()
                            val maxAllowed = safeMax - margin
                            var lastTickNs = System.nanoTime()
                            while (engine.isActive) {
                                delay(8L)
                                val nowNs = System.nanoTime()
                                val dtMs = ((nowNs - lastTickNs) / 1_000_000f).coerceIn(1f, 50f)
                                lastTickNs = nowNs
                                val (dx, dy) = engine.step(dtMs)
                                val nextX = (cx + dx).coerceIn(margin, maxAllowed)
                                val nextY = (cy + dy).coerceIn(margin, maxAllowed)
                                if (nextX == cx && nextY == cy && (dx != 0f || dy != 0f)) {
                                    engine.stop()
                                    break
                                }
                                cx = nextX
                                cy = nextY
                                currentOnTouchpadFrame(
                                    listOf(TouchpadContact(coastStart.pointerId, cx.roundToInt(), cy.roundToInt())),
                                    false
                                )
                            }
                            nativeCoastJobState.value = null
                            dispatchNativeFrames(nativeFilter.onAllUp())
                        }
                    } else {
                        dispatchNativeFrames(nativeFilter.onAllUp())
                    }
                }

                fun handleFallbackDown(pointerIndex: Int, pid: Int) {
                    view.requestUnbufferedDispatch(event)
                    cancelFling()
                    cancelMouseCoast()
                    pendingTapJobState.value?.cancel()
                    pendingTapJobState.value = null
                    strokePlaneRotationRef[0] = currentTotalPlaneRotationDeg
                    validActivePointerIds.add(pid)
                    syncTouchingState()
                    val (rx, ry) = rotateFallbackPoint(event.getX(pointerIndex), event.getY(pointerIndex))
                    if (gestureProcessor.onPrimaryDown(
                            pointerId = pid,
                            x = rx,
                            y = ry,
                            timeMs = event.eventTime,
                            density = density
                        )
                    ) {
                        dispatchClick("left")
                    }
                }

                fun handleFallbackAllUp() {
                    pendingTapJobState.value?.cancel()
                    pendingTapJobState.value = null
                    when (gestureProcessor.onAllUp(event.eventTime, allowDeferredTap = !currentIsLeftHeld)) {
                        TrackpadGestureProcessor.TapAction.LEFT_CLICK -> dispatchClick("left")
                        TrackpadGestureProcessor.TapAction.PENDING_LEFT_CLICK -> {
                            pendingTapJobState.value = coroutineScope.launch {
                                delay(160L)
                                if (gestureProcessor.onPendingTapTimeout() == TrackpadGestureProcessor.TapAction.LEFT_CLICK) {
                                    dispatchClick("left")
                                }
                            }
                        }
                        TrackpadGestureProcessor.TapAction.DOUBLE_LEFT_CLICK -> {
                            coroutineScope.launch {
                                dispatchClick("left")
                                delay(35L)
                                dispatchClick("left")
                            }
                        }
                        TrackpadGestureProcessor.TapAction.RIGHT_CLICK -> dispatchClick("right")
                        TrackpadGestureProcessor.TapAction.TAP_DRAG_END -> releaseTapDragIfActive()
                        TrackpadGestureProcessor.TapAction.NONE -> {}
                    }
                    gestureProcessor.consumeFlingVelocity()?.let { vel ->
                        cancelFling()
                        flingEngine.start(vel)
                        flingJobState.value = coroutineScope.launch {
                            var lastTickNs = System.nanoTime()
                            val tickMs = if (currentHiResScroll) 8L else 16L
                            val flingAcc = TrackpadAccumulator()
                            while (flingEngine.isActive) {
                                delay(tickMs)
                                val nowNs = System.nanoTime()
                                val dtMs = ((nowNs - lastTickNs) / 1_000_000f).coerceIn(1f, 50f)
                                lastTickNs = nowNs
                                val tuningSnap = currentTuning
                                val (wv, wh) = flingEngine.step(
                                    dtMs = dtMs,
                                    density = density,
                                    sensitivity = tuningSnap.scrollSpeed,
                                    naturalScrolling = currentNaturalScrolling,
                                    hiResScroll = currentHiResScroll,
                                    accumulator = flingAcc,
                                    friction = tuningSnap.scrollFlingFriction
                                )
                                if (wv != 0 || wh != 0) {
                                    currentOnMouseScroll(wv.toFloat(), wh.toFloat())
                                }
                            }
                        }
                    }
                    gestureProcessor.consumePointerCoastVelocity()?.let { vel ->
                        cancelMouseCoast()
                        val coastAcc = TrackpadAccumulator()
                        val inertiaSnap = currentTuning.pointerInertia
                        mouseCoastJobState.value = coroutineScope.launch {
                            val engine = PointerCoastingEngine(stopSpeedPerMs = 0.04f)
                            engine.start(vel.vxPerMs, vel.vyPerMs, inertiaSnap)
                            var lastTickNs = System.nanoTime()
                            while (engine.isActive) {
                                delay(8L)
                                val nowNs = System.nanoTime()
                                val dtMs = ((nowNs - lastTickNs) / 1_000_000f).coerceIn(1f, 50f)
                                lastTickNs = nowNs
                                val (fdx, fdy) = engine.step(dtMs)
                                val (mx, my) = coastAcc.addMoveDelta(fdx, fdy, sensitivity = 1.0f)
                                if (mx != 0 || my != 0) {
                                    currentOnMouseMove(mx.toFloat(), my.toFloat())
                                }
                            }
                            mouseCoastJobState.value = null
                        }
                    }
                }

                if (currentNativeTouchpadActive) {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            ignoredPalmPointerIds.clear()
                            validActivePointerIds.clear()
                            syncTouchingState()
                            val pid = event.getPointerId(0)
                            if (isInPalmGuard(event.getX(0), event.getY(0))) {
                                ignoredPalmPointerIds.add(pid)
                            } else {
                                handleNativeDown(pid)
                            }
                        }
                        MotionEvent.ACTION_POINTER_DOWN -> {
                            val idx = event.actionIndex
                            val pid = event.getPointerId(idx)
                            if (isInPalmGuard(event.getX(idx), event.getY(idx))) {
                                ignoredPalmPointerIds.add(pid)
                            } else if (validActivePointerIds.isEmpty()) {
                                handleNativeDown(pid)
                            } else {
                                cancelNativeEntryJob()
                                cancelNativeCoast(flushLift = true)
                                validActivePointerIds.add(pid)
                                val mapped = mapNativeContacts(event)
                                dispatchNativeFrames(nativeFilter.onPointerDown(mapped))
                            }
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (validActivePointerIds.isNotEmpty()) {
                                val mapped = mapNativeContacts(event)
                                if (mapped.isNotEmpty()) {
                                    val frames = nativeFilter.onMove(mapped, event.eventTime)
                                    if (!nativeFilter.isEntryBuffered) {
                                        cancelNativeEntryJob()
                                    }
                                    dispatchNativeFrames(frames)
                                }
                            }
                        }
                        MotionEvent.ACTION_POINTER_UP -> {
                            val idx = event.actionIndex
                            val pid = event.getPointerId(idx)
                            if (!ignoredPalmPointerIds.remove(pid) && validActivePointerIds.remove(pid)) {
                                syncTouchingState()
                                if (validActivePointerIds.isEmpty()) {
                                    handleNativeAllUp()
                                } else {
                                    cancelNativeEntryJob()
                                    val mapped = mapNativeContacts(event, excludeActionIndex = idx)
                                    dispatchNativeFrames(nativeFilter.onPointerUp(mapped))
                                }
                            }
                        }
                        MotionEvent.ACTION_UP -> {
                            val pid = event.getPointerId(event.actionIndex)
                            ignoredPalmPointerIds.clear()
                            if (validActivePointerIds.remove(pid) && validActivePointerIds.isEmpty()) {
                                syncTouchingState()
                                handleNativeAllUp()
                            } else {
                                validActivePointerIds.clear()
                                syncTouchingState()
                            }
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            ignoredPalmPointerIds.clear()
                            validActivePointerIds.clear()
                            syncTouchingState()
                            cancelNativeEntryJob()
                            cancelNativeCoast(flushLift = false)
                            nativeKinematics.reset()
                            dispatchNativeFrames(nativeFilter.onCancel())
                        }
                    }
                    return@pointerInteropFilter true
                }

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        ignoredPalmPointerIds.clear()
                        validActivePointerIds.clear()
                        syncTouchingState()
                        val pid = event.getPointerId(0)
                        if (isInPalmGuard(event.getX(0), event.getY(0))) {
                            ignoredPalmPointerIds.add(pid)
                        } else {
                            handleFallbackDown(0, pid)
                        }
                        true
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        val idx = event.actionIndex
                        val pid = event.getPointerId(idx)
                        if (isInPalmGuard(event.getX(idx), event.getY(idx))) {
                            ignoredPalmPointerIds.add(pid)
                        } else if (validActivePointerIds.isEmpty()) {
                            handleFallbackDown(idx, pid)
                        } else {
                            cancelFling()
                            cancelMouseCoast()
                            pendingTapJobState.value?.cancel()
                            pendingTapJobState.value = null
                            releaseTapDragIfActive()
                            validActivePointerIds.add(pid)
                            val (rx, ry) = rotateFallbackPoint(event.getX(idx), event.getY(idx))
                            gestureProcessor.onPointerDown(
                                pointerId = pid,
                                x = rx,
                                y = ry
                            )
                        }
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        var activeCount = 0
                        val rawCount = event.pointerCount
                        for (p in 0 until rawCount) {
                            val pid = event.getPointerId(p)
                            if (pid in ignoredPalmPointerIds) continue
                            if (activeCount >= pointerIdsBuf.size) break
                            pointerIdsBuf[activeCount] = pid
                            pointerOrigIdxBuf[activeCount] = p
                            activeCount++
                        }
                        if (activeCount == 0) {
                            return@pointerInteropFilter true
                        }
                        var sumDx = 0
                        var sumDy = 0
                        var sumWheel = 0
                        var sumHWheel = 0
                        val sens = currentSensitivity
                        val tuningSnap = currentTuning
                        val natural = currentNaturalScrolling
                        val hiRes = currentHiResScroll

                        fun handleSampleOut(out: TrackpadGestureProcessor.FrameOutput) {
                            if (out.tapDragStarted && !tapDragActiveState.value) {
                                triggerHaptic()
                                tapDragActiveState.value = true
                                currentOnLeftHoldChange(true)
                            }
                            out.threeFingerGesture?.let { gesture ->
                                triggerHaptic(HapticFeedbackConstants.LONG_PRESS)
                                val combo = when (gesture) {
                                    TrackpadGestureProcessor.ThreeFingerGesture.SWIPE_LEFT ->
                                        if (natural) "ctrl+alt+right" else "ctrl+alt+left"
                                    TrackpadGestureProcessor.ThreeFingerGesture.SWIPE_RIGHT ->
                                        if (natural) "ctrl+alt+left" else "ctrl+alt+right"
                                    TrackpadGestureProcessor.ThreeFingerGesture.SWIPE_UP,
                                    TrackpadGestureProcessor.ThreeFingerGesture.SWIPE_DOWN -> "super"
                                }
                                currentOnSendKey(combo)
                            }
                            sumDx += out.moveDx
                            sumDy += out.moveDy
                            sumWheel += out.wheelDelta
                            sumHWheel += out.hwheelDelta
                        }

                        fun dispatchFallbackMoveSample(sampleTimeMs: Long) {
                            val out = gestureProcessor.onMoveSample(
                                pointerIds = pointerIdsBuf,
                                xs = pointerXsBuf,
                                ys = pointerYsBuf,
                                count = activeCount,
                                sampleTimeMs = sampleTimeMs,
                                density = density,
                                sensitivity = sens,
                                naturalScrolling = natural,
                                hiResScroll = hiRes,
                                scrollSensitivityOverride = tuningSnap.scrollSpeed,
                                pointerAccel = tuningSnap.pointerAccel,
                                smoothing = tuningSnap.smoothing,
                                deadzoneMm = tuningSnap.deadzoneMm,
                                tuning = tuningSnap,
                                rotationAlreadyApplied = true
                            )
                            handleSampleOut(out)
                        }

                        val histSize = event.historySize
                        for (h in 0 until histSize) {
                            for (p in 0 until activeCount) {
                                val origIdx = pointerOrigIdxBuf[p]
                                val (rx, ry) = rotateFallbackPoint(
                                    event.getHistoricalX(origIdx, h),
                                    event.getHistoricalY(origIdx, h)
                                )
                                pointerXsBuf[p] = rx
                                pointerYsBuf[p] = ry
                            }
                            dispatchFallbackMoveSample(event.getHistoricalEventTime(h))
                        }

                        for (p in 0 until activeCount) {
                            val origIdx = pointerOrigIdxBuf[p]
                            val (rx, ry) = rotateFallbackPoint(event.getX(origIdx), event.getY(origIdx))
                            pointerXsBuf[p] = rx
                            pointerYsBuf[p] = ry
                        }
                        dispatchFallbackMoveSample(event.eventTime)

                        if (sumWheel != 0 || sumHWheel != 0) {
                            currentOnMouseScroll(sumWheel.toFloat(), sumHWheel.toFloat())
                        }
                        if (sumDx != 0 || sumDy != 0) {
                            currentOnMouseMove(sumDx.toFloat(), sumDy.toFloat())
                        }
                        true
                    }
                    MotionEvent.ACTION_POINTER_UP -> {
                        val idx = event.actionIndex
                        val pid = event.getPointerId(idx)
                        if (!ignoredPalmPointerIds.remove(pid) && validActivePointerIds.remove(pid)) {
                            syncTouchingState()
                            val (rx, ry) = rotateFallbackPoint(event.getX(idx), event.getY(idx))
                            gestureProcessor.onPointerUp(
                                pointerId = pid,
                                x = rx,
                                y = ry
                            )
                            if (validActivePointerIds.isEmpty()) {
                                handleFallbackAllUp()
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val pid = event.getPointerId(event.actionIndex)
                        ignoredPalmPointerIds.clear()
                        if (validActivePointerIds.remove(pid) && validActivePointerIds.isEmpty()) {
                            syncTouchingState()
                            handleFallbackAllUp()
                        } else {
                            validActivePointerIds.clear()
                            syncTouchingState()
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        ignoredPalmPointerIds.clear()
                        validActivePointerIds.clear()
                        syncTouchingState()
                        pendingTapJobState.value?.cancel()
                        pendingTapJobState.value = null
                        cancelFling()
                        cancelMouseCoast()
                        releaseTapDragIfActive()
                        gestureProcessor.reset()
                        true
                    }
                    else -> true
                }
            },
        shape = RoundedCornerShape(if (minimalStyle) 18.dp else 16.dp),
        border = if (!minimalStyle || isLeftHeld) BorderStroke(borderWidth, borderColor) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (minimalStyle) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawTrackpadBackdrop(
                        minimalStyle = minimalStyle,
                        totalPlaneRotationDeg = totalPlaneRotationDeg,
                        dotColor = dotColor,
                        tiltIndicatorColor = tiltIndicatorColor,
                        showPalmGuard = showPalmGuard,
                        palmGuardMm = palmGuardMm,
                        xdpi = xdpi,
                        ydpi = ydpi,
                        fallbackDensity = fallbackDensity,
                        palmGuardFillColor = palmGuardFillColor,
                        palmGuardLineColor = palmGuardLineColor
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = resolvedHintText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (minimalStyle) 0.55f else 0.6f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 22.sp
            )
        }
    }
}
