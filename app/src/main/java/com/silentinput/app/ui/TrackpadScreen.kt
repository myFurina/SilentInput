package com.silentinput.app.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
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
import kotlin.math.hypot

@Composable
fun TrackpadScreen(
    sensitivity: Float,
    onSensitivityChange: (Float) -> Unit,
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
            Button(
                onClick = { setLeftHold(!isLeftHeld) },
                enabled = isConnected || isLeftHeld,
                modifier = Modifier.weight(1.4f).height(42.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isLeftHeld) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (isLeftHeld) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                )
            ) {
                Text(
                    text = if (isLeftHeld) "正在框选 · 点此松开" else "框选（按住左键）",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            OutlinedButton(
                onClick = {
                    if (isHapticEnabled) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onSendKey("prtsc")
                },
                modifier = Modifier.weight(1f).height(42.dp),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Text("截屏 PrtSc", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }

        TrackpadSurface(
            sensitivity = sensitivity,
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
            hintText = if (isLeftHeld) {
                "正在按住左键框选/拖拽\n单指滑动选中范围 · 轻点或按按钮松开"
            } else {
                "触控区域\n单指移动 · 双指滚动 · 轻点左键\n长按右键 · 点上方「框选」拖拽选字/截图"
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Mouse Buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {
                    if (isLeftHeld) {
                        setLeftHold(false)
                    } else {
                        if (isHapticEnabled) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onMouseClick("left")
                    }
                },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Text(
                    text = if (isLeftHeld) "松开左键" else "左键",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Button(
                onClick = {
                    if (isHapticEnabled) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onMouseClick("right")
                },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Text("右键", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Sensitivity slider
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("灵敏度: ${String.format("%.1fx", sensitivity)}", style = MaterialTheme.typography.labelSmall)
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
    isHapticEnabled: Boolean = false,
    onMouseMove: (dx: Float, dy: Float) -> Unit,
    onMouseClick: (button: String) -> Unit,
    onMouseScroll: (wheel: Float) -> Unit,
    modifier: Modifier = Modifier,
    isLeftHeld: Boolean = false,
    hintText: String = "触控区域\n单指移动 · 双指滚动 · 轻点左键"
) {
    val view = LocalView.current

    val currentSensitivity by rememberUpdatedState(sensitivity)
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
                            val intWheel = accumulator.addScrollDelta(avgDy, sens)
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
        border = if (isLeftHeld) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = hintText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 22.sp
            )
        }
    }
}
