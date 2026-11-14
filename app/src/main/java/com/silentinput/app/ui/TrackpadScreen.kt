package com.silentinput.app.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silentinput.app.model.TrackpadAccumulator
import com.silentinput.app.ui.theme.SilentMotion
import com.silentinput.app.ui.theme.pressScale
import kotlin.math.hypot

@Composable
fun TrackpadScreen(
    sensitivity: Float,
    onSensitivityChange: (Float) -> Unit,
    naturalScrolling: Boolean = true,
    isHapticEnabled: Boolean = false,
    isConnected: Boolean = true,
    isLeftHeld: Boolean = false,
    onMouseMove: (dx: Float, dy: Float) -> Unit,
    onMouseClick: (button: String) -> Unit,
    onMouseScroll: (wheel: Float) -> Unit,
    onLeftMouseHoldChange: (Boolean) -> Unit = {},
    onSendKey: (String) -> Unit = {}
) {
    val view = LocalView.current
    val strings = LocalAppStrings.current
    val currentIsLeftHeld by rememberUpdatedState(isLeftHeld)
    val currentOnLeftHoldChange by rememberUpdatedState(onLeftMouseHoldChange)

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
        // Quick selection & screenshot toolbar
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
                    .weight(1.4f)
                    .height(42.dp)
                    .pressScale(dragSelectInteraction, pressedScale = 0.96f, enabled = dragSelectEnabled),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = dragSelectContainer,
                    contentColor = dragSelectContent
                )
            ) {
                AnimatedContent(
                    targetState = isLeftHeld,
                    transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(110)) },
                    label = "dragSelectText"
                ) { held ->
                    Text(
                        text = if (held) strings.tpHoldingLeftBtn else strings.tpDragSelectBtn,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            val screenshotInteraction = remember { MutableInteractionSource() }
            OutlinedButton(
                onClick = {
                    if (isHapticEnabled) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onSendKey("prtsc")
                },
                interactionSource = screenshotInteraction,
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .pressScale(screenshotInteraction, pressedScale = 0.96f),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Text(strings.tpScreenshotBtn, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }

        TrackpadSurface(
            sensitivity = sensitivity,
            naturalScrolling = naturalScrolling,
            isHapticEnabled = isHapticEnabled,
            onMouseMove = onMouseMove,
            onMouseClick = { btn ->
                if (isLeftHeld && btn == "left") {
                    setLeftHold(false)
                } else {
                    onMouseClick(btn)
                }
            },
            onMouseScroll = onMouseScroll,
            isLeftHeld = isLeftHeld,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            hintText = if (isLeftHeld) strings.tpHoldingHint else strings.tpDefaultHint
        )

        Spacer(modifier = Modifier.height(12.dp))

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
                AnimatedContent(
                    targetState = isLeftHeld,
                    transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(110)) },
                    label = "leftBtnText"
                ) { held ->
                    Text(
                        text = if (held) strings.tpReleaseLeftBtn else strings.tpLeftBtn,
                        fontWeight = FontWeight.Bold,
                        color = leftBtnContent
                    )
                }
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

        Spacer(modifier = Modifier.height(8.dp))

        // Sensitivity slider
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(strings.tpSensitivityLabel(String.format("%.1fx", sensitivity)), style = MaterialTheme.typography.labelSmall)
            Spacer(modifier = Modifier.width(8.dp))
            Slider(
                value = sensitivity,
                onValueChange = onSensitivityChange,
                valueRange = 0.1f..2.0f,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun TrackpadSurface(
    sensitivity: Float,
    naturalScrolling: Boolean = true,
    isHapticEnabled: Boolean = false,
    onMouseMove: (dx: Float, dy: Float) -> Unit,
    onMouseClick: (button: String) -> Unit,
    onMouseScroll: (wheel: Float) -> Unit,
    modifier: Modifier = Modifier,
    isLeftHeld: Boolean = false,
    hintText: String? = null
) {
    val view = LocalView.current
    val resolvedHintText = hintText ?: LocalAppStrings.current.tpSurfaceStandaloneDefaultHint

    val currentSensitivity by rememberUpdatedState(sensitivity)
    val currentNaturalScrolling by rememberUpdatedState(naturalScrolling)
    val currentHapticEnabled by rememberUpdatedState(isHapticEnabled)
    val currentIsLeftHeld by rememberUpdatedState(isLeftHeld)
    val currentOnMouseMove by rememberUpdatedState(onMouseMove)
    val currentOnMouseClick by rememberUpdatedState(onMouseClick)
    val currentOnMouseScroll by rememberUpdatedState(onMouseScroll)

    fun triggerHaptic(type: Int = HapticFeedbackConstants.KEYBOARD_TAP) {
        if (currentHapticEnabled) {
            view.performHapticFeedback(type)
        }
    }

    fun dispatchClick(btnStr: String) {
        triggerHaptic()
        currentOnMouseClick(btnStr)
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

    Card(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { dispatchClick("left") },
                    onDoubleTap = {
                        if (currentIsLeftHeld) {
                            dispatchClick("left")
                        } else {
                            dispatchClick("left")
                            dispatchClick("left")
                        }
                    },
                    onLongPress = { dispatchClick("right") }
                )
            }
            .pointerInput(Unit) {
                val accumulator = TrackpadAccumulator()
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    accumulator.reset()
                    var totalDragDist = 0f
                    var gestureStarted = false
                    val touchSlop = viewConfiguration.touchSlop

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressedChanges = event.changes.filter { it.pressed }
                        if (pressedChanges.isEmpty()) break

                        val sens = currentSensitivity
                        if (pressedChanges.size >= 2) {
                            gestureStarted = true
                            val avgDy = pressedChanges.map { it.positionChange().y }.average().toFloat()
                            pressedChanges.forEach { it.consume() }
                            val intWheel = accumulator.addScrollDelta(
                                avgDy = avgDy,
                                sensitivity = sens,
                                naturalScrolling = currentNaturalScrolling
                            )
                            if (intWheel != 0) {
                                currentOnMouseScroll(intWheel.toFloat())
                            }
                        } else {
                            val change = pressedChanges[0]
                            val delta = change.positionChange()
                            if (!gestureStarted) {
                                totalDragDist += hypot(delta.x, delta.y)
                                if (totalDragDist > touchSlop) {
                                    gestureStarted = true
                                }
                            }
                            if (gestureStarted) {
                                change.consume()
                                val (intX, intY) = accumulator.addMoveDelta(delta.x, delta.y, sens)
                                if (intX != 0 || intY != 0) {
                                    currentOnMouseMove(intX.toFloat(), intY.toFloat())
                                }
                            }
                        }
                    }
                }
            },
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(borderWidth, borderColor),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = resolvedHintText,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "trackpadHintText"
            ) { textToShow ->
                Text(
                    text = textToShow,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    lineHeight = 22.sp
                )
            }
        }
    }
}
