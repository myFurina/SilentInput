package com.silentinput.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.silentinput.app.model.TrackpadVectorMath
import kotlin.math.abs
import kotlin.math.hypot

internal fun DrawScope.drawTrackpadBackdrop(
    minimalStyle: Boolean,
    totalPlaneRotationDeg: Float,
    dotColor: Color,
    tiltIndicatorColor: Color,
    showPalmGuard: Boolean,
    palmGuardMm: Float,
    xdpi: Float,
    ydpi: Float,
    fallbackDensity: Float,
    palmGuardFillColor: Color,
    palmGuardLineColor: Color
) {
    if (minimalStyle && size.width > 0f && size.height > 0f) {
        val step = 16.dp.toPx()
        val radius = 1.dp.toPx()
        val center = Offset(size.width * 0.5f, size.height * 0.5f)
        val visualRot = -totalPlaneRotationDeg
        clipRect {
            rotate(degrees = visualRot, pivot = center) {
                val halfDiag = hypot(size.width, size.height) * 0.5f + step
                val nSteps = (halfDiag / step).toInt()
                for (iy in -nSteps..nSteps) {
                    val y = center.y + iy * step
                    for (ix in -nSteps..nSteps) {
                        val x = center.x + ix * step
                        drawCircle(
                            color = dotColor,
                            radius = radius,
                            center = Offset(x, y)
                        )
                    }
                }
                if (abs(totalPlaneRotationDeg) >= 1f) {
                    val tipY = center.y - minOf(size.width, size.height) * 0.34f
                    val arm = 8.dp.toPx()
                    val strokeW = 2.dp.toPx()
                    drawLine(
                        color = tiltIndicatorColor,
                        start = Offset(center.x - arm, tipY + arm * 0.85f),
                        end = Offset(center.x, tipY),
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = tiltIndicatorColor,
                        start = Offset(center.x, tipY),
                        end = Offset(center.x + arm, tipY + arm * 0.85f),
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round
                    )
                }
            }
        }
    }
    if (showPalmGuard && size.width > 0f && size.height > 0f) {
        val (guardX, guardBottomY) = TrackpadVectorMath.edgePalmGuardPx(
            widthPx = size.width.toInt(),
            heightPx = size.height.toInt(),
            xdpi = xdpi,
            ydpi = ydpi,
            fallbackDensity = fallbackDensity,
            edgePalmMm = palmGuardMm
        )
        if (guardX > 0f) {
            drawRect(
                color = palmGuardFillColor,
                topLeft = Offset.Zero,
                size = Size(guardX, size.height)
            )
            drawLine(
                color = palmGuardLineColor,
                start = Offset(guardX, 0f),
                end = Offset(guardX, size.height - guardBottomY),
                strokeWidth = 1.dp.toPx()
            )
            drawRect(
                color = palmGuardFillColor,
                topLeft = Offset(size.width - guardX, 0f),
                size = Size(guardX, size.height)
            )
            drawLine(
                color = palmGuardLineColor,
                start = Offset(size.width - guardX, 0f),
                end = Offset(size.width - guardX, size.height - guardBottomY),
                strokeWidth = 1.dp.toPx()
            )
        }
        if (guardBottomY > 0f) {
            val innerWidth = (size.width - 2f * guardX).coerceAtLeast(0f)
            if (innerWidth > 0f) {
                drawRect(
                    color = palmGuardFillColor,
                    topLeft = Offset(guardX, size.height - guardBottomY),
                    size = Size(innerWidth, guardBottomY)
                )
                drawLine(
                    color = palmGuardLineColor,
                    start = Offset(guardX, size.height - guardBottomY),
                    end = Offset(size.width - guardX, size.height - guardBottomY),
                    strokeWidth = 1.dp.toPx()
                )
            }
        }
    }
}
