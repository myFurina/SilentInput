package com.silentinput.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Repeats [onRepeat] while held.
 * - When [cancelOnDrag] is false (e.g., non-scrollable keyboard screen): triggers immediately on
 *   press down, then repeats every [repeatIntervalMs] after [initialDelayMs].
 * - When [cancelOnDrag] is true (e.g., inside a vertically scrollable container): waits to ensure
 *   the gesture is not a scroll drag; triggers once on clean tap release, or starts repeating after
 *   [initialDelayMs] if held stationary.
 */
@Composable
fun Modifier.repeatOnPress(
    interactionSource: MutableInteractionSource,
    initialDelayMs: Long = 350L,
    repeatIntervalMs: Long = 55L,
    cancelOnDrag: Boolean = false,
    onRepeat: () -> Unit
): Modifier {
    val currentOnRepeat by rememberUpdatedState(onRepeat)
    return this.pointerInput(interactionSource, initialDelayMs, repeatIntervalMs, cancelOnDrag) {
        coroutineScope {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val press = PressInteraction.Press(down.position)
                interactionSource.tryEmit(press)

                if (!cancelOnDrag) {
                    currentOnRepeat()
                    val repeatJob = launch {
                        delay(initialDelayMs)
                        while (true) {
                            currentOnRepeat()
                            delay(repeatIntervalMs)
                        }
                    }
                    var released = false
                    try {
                        val up = waitForUpOrCancellation()
                        released = up != null
                    } finally {
                        repeatJob.cancel()
                        interactionSource.tryEmit(
                            if (released) PressInteraction.Release(press)
                            else PressInteraction.Cancel(press)
                        )
                    }
                } else {
                    var emittedRepeat = false
                    var dragged = false
                    var releasedNormally = false
                    val touchSlop = viewConfiguration.touchSlop
                    val repeatJob = launch {
                        delay(initialDelayMs)
                        emittedRepeat = true
                        while (true) {
                            currentOnRepeat()
                            delay(repeatIntervalMs)
                        }
                    }
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed) {
                                dragged = true
                                break
                            }
                            if (!change.pressed) {
                                releasedNormally = true
                                break
                            }
                            val dist = hypot(
                                change.position.x - down.position.x,
                                change.position.y - down.position.y
                            )
                            if (dist > touchSlop) {
                                dragged = true
                                break
                            }
                        }
                        if (releasedNormally && !dragged && !emittedRepeat) {
                            currentOnRepeat()
                        }
                    } finally {
                        repeatJob.cancel()
                        interactionSource.tryEmit(
                            if (releasedNormally && !dragged) PressInteraction.Release(press)
                            else PressInteraction.Cancel(press)
                        )
                    }
                }
            }
        }
    }
}
