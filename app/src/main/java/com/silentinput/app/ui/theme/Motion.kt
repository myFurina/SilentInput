package com.silentinput.app.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 极简黑白灰动效语言规范：
 * 展开顺滑减速、收起干脆利落、微交互完全在 Draw 阶段完成以保证零输入延迟。
 */
object SilentMotion {
    const val DURATION_EXPAND_MS = 240
    const val DURATION_COLLAPSE_MS = 160
    const val DURATION_STATE_MS = 180

    val EmphasizedDecelerateEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    val EmphasizedAccelerateEasing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

    fun expandSectionTransition(): EnterTransition {
        return expandVertically(
            expandFrom = Alignment.Top,
            animationSpec = tween(DURATION_EXPAND_MS, easing = EmphasizedDecelerateEasing)
        ) + fadeIn(
            animationSpec = tween(180, delayMillis = 25, easing = LinearOutSlowInEasing)
        )
    }

    fun collapseSectionTransition(): ExitTransition {
        return shrinkVertically(
            shrinkTowards = Alignment.Top,
            animationSpec = tween(DURATION_COLLAPSE_MS, easing = EmphasizedAccelerateEasing)
        ) + fadeOut(
            animationSpec = tween(110, easing = FastOutLinearInEasing)
        )
    }
}

/**
 * RenderNode Draw 阶段的零延迟按压微缩放反馈，不触发重组与测量布局。
 */
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.96f,
    enabled: Boolean = true
): Modifier = composed {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scaleState = animateFloatAsState(
        targetValue = if (enabled && isPressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "pressScale"
    )
    this.graphicsLayer {
        val s = scaleState.value
        scaleX = s
        scaleY = s
    }
}
