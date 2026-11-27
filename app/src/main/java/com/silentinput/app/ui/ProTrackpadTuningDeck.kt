package com.silentinput.app.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silentinput.app.model.AppStrings
import com.silentinput.app.model.TrackpadPreset
import com.silentinput.app.model.TrackpadTuningConfig
import com.silentinput.app.model.TuningCategory
import com.silentinput.app.model.TuningParam
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun ProTrackpadTuningDeck(
    tuning: TrackpadTuningConfig,
    isHapticEnabled: Boolean,
    onTuningChange: (TrackpadTuningConfig) -> Unit,
    onTuningChangeFinished: () -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    liveAutoRotationDeg: Float = 0f
) {
    val strings = LocalAppStrings.current
    val view = LocalView.current
    var selectedCategory by rememberSaveable { mutableStateOf(TuningCategory.BASIC) }
    var selectedParam by rememberSaveable { mutableStateOf(TuningParam.POINTER_SPEED) }
    val lastParamByCategory = remember {
        mutableStateMapOf(
            TuningCategory.BASIC to TuningParam.POINTER_SPEED,
            TuningCategory.CURVE to TuningParam.Y_AXIS_RATIO,
            TuningCategory.CORRECTION to TuningParam.ROTATION_DEG
        )
    }
    val matchedPreset = remember(tuning) { TrackpadPreset.match(tuning) }
    val categoryParams = remember(selectedCategory) { TrackpadTuningConfig.paramsIn(selectedCategory) }

    fun hapticTick(type: Int = HapticFeedbackConstants.CLOCK_TICK) {
        if (isHapticEnabled) {
            view.performHapticFeedback(type)
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            // Row 1: Presets + Behavior Toggles + Reset & Close
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TrackpadPreset.values().forEach { preset ->
                        val active = matchedPreset == preset
                        val label = when (preset) {
                            TrackpadPreset.PRECISE -> strings.tpTuning.presetPrecise
                            TrackpadPreset.BALANCED -> strings.tpTuning.presetBalanced
                            TrackpadPreset.FAST -> strings.tpTuning.presetFast
                            TrackpadPreset.RAW -> strings.tpTuning.presetRaw
                        }
                        ProMiniChip(
                            label = label,
                            selected = active,
                            onClick = {
                                hapticTick(HapticFeedbackConstants.KEYBOARD_TAP)
                                onTuningChange(preset.config)
                                onTuningChangeFinished()
                            }
                        )
                    }

                    ProMiniChip(
                        label = strings.tpTuning.antiLiftWhip,
                        selected = tuning.antiLiftWhip,
                        showCheck = tuning.antiLiftWhip,
                        onClick = {
                            hapticTick(HapticFeedbackConstants.KEYBOARD_TAP)
                            onTuningChange(tuning.copy(antiLiftWhip = !tuning.antiLiftWhip))
                            onTuningChangeFinished()
                        }
                    )

                    ProMiniChip(
                        label = strings.tpTuning.scrollAxisLock,
                        selected = tuning.scrollAxisLock,
                        showCheck = tuning.scrollAxisLock,
                        onClick = {
                            hapticTick(HapticFeedbackConstants.KEYBOARD_TAP)
                            onTuningChange(tuning.copy(scrollAxisLock = !tuning.scrollAxisLock))
                            onTuningChangeFinished()
                        }
                    )

                    val liveDegInt = liveAutoRotationDeg.roundToInt()
                    val liveDegText = when {
                        liveDegInt > 0 -> "+${liveDegInt}°"
                        liveDegInt < 0 -> "${liveDegInt}°"
                        else -> "0°"
                    }
                    val autoTiltLabel = if (tuning.autoGravityRotation) {
                        "${strings.tpTuning.autoGravityRotation} $liveDegText"
                    } else {
                        strings.tpTuning.autoGravityRotation
                    }
                    ProMiniChip(
                        label = autoTiltLabel,
                        selected = tuning.autoGravityRotation,
                        showCheck = tuning.autoGravityRotation,
                        onClick = {
                            hapticTick(HapticFeedbackConstants.KEYBOARD_TAP)
                            onTuningChange(tuning.copy(autoGravityRotation = !tuning.autoGravityRotation))
                            onTuningChangeFinished()
                        }
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            hapticTick(HapticFeedbackConstants.KEYBOARD_TAP)
                            onReset()
                        },
                        enabled = !tuning.isDefault(),
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = strings.tpTuning.resetDesc,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = strings.tpTuning.closeDesc,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Row 2: 3-Category Pro Tabs (Basic / Curve / Correction)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TuningCategory.values().forEach { category ->
                    val isCatSelected = selectedCategory == category
                    val isCatModified = tuning.hasModifiedParamIn(category)
                    val label = categoryLabel(strings, category)
                    ProCategoryTab(
                        label = label,
                        selected = isCatSelected,
                        isModified = isCatModified,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            hapticTick(HapticFeedbackConstants.CLOCK_TICK)
                            lastParamByCategory[selectedCategory] = selectedParam
                            selectedCategory = category
                            selectedParam = lastParamByCategory[category]
                                ?: TrackpadTuningConfig.paramsIn(category).first()
                        }
                    )
                }
            }

            // Row 3: Active Category Parameter Strip (6 tactile tiles)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                categoryParams.forEach { param ->
                    val isSelected = selectedParam == param
                    val step = tuning.getParamStep(param)
                    val isModified = step != param.defaultStep
                    val copy = strings.tpTuning.paramCopy(param)
                    ProParamTile(
                        shortLabel = copy.short,
                        formattedValue = tuning.formatValue(param),
                        selected = isSelected,
                        isModified = isModified,
                        onClick = {
                            hapticTick(HapticFeedbackConstants.CLOCK_TICK)
                            selectedParam = param
                            lastParamByCategory[selectedCategory] = param
                        }
                    )
                }
            }

            // Row 4: Live Precision Tick-Mark Dial + Fine +/- Step Controls
            val activeCopy = strings.tpTuning.paramCopy(selectedParam)
            ProParameterRuler(
                param = selectedParam,
                tuning = tuning,
                fullLabel = activeCopy.title,
                hint = activeCopy.hint,
                isHapticEnabled = isHapticEnabled,
                onStepSelected = { newStep ->
                    onTuningChange(tuning.withParamStep(selectedParam, newStep))
                },
                onDragFinished = onTuningChangeFinished
            )
        }
    }
}

@Composable
private fun ProCategoryTab(
    label: String,
    selected: Boolean,
    isModified: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bg = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
    }
    val fg = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = LocalIndication.current,
                onClick = onClick
            ),
        shape = RoundedCornerShape(8.dp),
        color = bg,
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = fg,
                maxLines = 1
            )
            if (isModified) {
                Spacer(modifier = Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.5.dp))
                )
            }
        }
    }
}

@Composable
private fun ProMiniChip(
    label: String,
    selected: Boolean,
    showCheck: Boolean = false,
    onClick: () -> Unit
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = LocalIndication.current,
                onClick = onClick
            ),
        shape = RoundedCornerShape(8.dp),
        color = bg,
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            if (showCheck) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(11.dp)
                )
            }
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = fg,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ProParamTile(
    shortLabel: String,
    formattedValue: String,
    selected: Boolean,
    isModified: Boolean,
    onClick: () -> Unit
) {
    val bg = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
    }
    val border = if (selected) {
        BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f))
    }
    Surface(
        modifier = Modifier
            .widthIn(min = 58.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = LocalIndication.current,
                onClick = onClick
            ),
        shape = RoundedCornerShape(10.dp),
        color = bg,
        border = border
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = shortLabel,
                    fontSize = 10.sp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1
                )
                if (isModified) {
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                    )
                }
            }
            Text(
                text = formattedValue,
                style = TextStyle(fontFeatureSettings = "tnum"),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ProParameterRuler(
    param: TuningParam,
    tuning: TrackpadTuningConfig,
    fullLabel: String,
    hint: String,
    isHapticEnabled: Boolean,
    onStepSelected: (Int) -> Unit,
    onDragFinished: () -> Unit
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val stepSpacingPx = remember(density) { with(density) { 12.dp.toPx() } }

    val currentStep = tuning.getParamStep(param)
    val latestStep by rememberUpdatedState(currentStep)
    val latestParam by rememberUpdatedState(param)
    val latestOnStepSelected by rememberUpdatedState(onStepSelected)
    val latestOnDragFinished by rememberUpdatedState(onDragFinished)
    val latestHapticEnabled by rememberUpdatedState(isHapticEnabled)

    var subStepOffsetPx by remember(param) { mutableFloatStateOf(0f) }

    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = fullLabel,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = hint,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = currentStep > 0) {
                            if (isHapticEnabled) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            onStepSelected((currentStep - 1).coerceAtLeast(0))
                            onDragFinished()
                        },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Remove,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = if (currentStep > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    }
                }

                Text(
                    text = tuning.formatValue(param),
                    style = TextStyle(fontFeatureSettings = "tnum"),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.widthIn(min = 52.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Surface(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = currentStep < param.maxStep) {
                            if (isHapticEnabled) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            onStepSelected((currentStep + 1).coerceAtMost(param.maxStep))
                            onDragFinished()
                        },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = if (currentStep < param.maxStep) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    }
                }
            }
        }

        val needleColor = MaterialTheme.colorScheme.primary
        val majorTickColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
        val minorTickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        val defaultDotColor = MaterialTheme.colorScheme.tertiary
        val rulerBg = MaterialTheme.colorScheme.surface

        var dragAnchorStep by remember(param) { mutableIntStateOf(currentStep) }
        var dragTotalPx by remember(param) { mutableFloatStateOf(0f) }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(rulerBg)
                .pointerInput(param) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragAnchorStep = latestStep
                            dragTotalPx = 0f
                            subStepOffsetPx = 0f
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            dragTotalPx += dragAmount
                            // Drag left pulls higher steps under center needle; drag right lowers step
                            val rawFloatStep = dragAnchorStep - (dragTotalPx / stepSpacingPx)
                            val clampedFloat = rawFloatStep.coerceIn(0f, latestParam.maxStep.toFloat())
                            val snapped = clampedFloat.roundToInt().coerceIn(0, latestParam.maxStep)
                            subStepOffsetPx = (snapped - clampedFloat) * stepSpacingPx
                            if (snapped != latestStep) {
                                if (latestHapticEnabled) {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                }
                                latestOnStepSelected(snapped)
                            }
                        },
                        onDragEnd = {
                            subStepOffsetPx = 0f
                            latestOnDragFinished()
                        },
                        onDragCancel = {
                            subStepOffsetPx = 0f
                            latestOnDragFinished()
                        }
                    )
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centerX = size.width * 0.5f
                val bottomY = size.height - 6.dp.toPx()
                val visibleStepsEachSide = ((size.width * 0.5f) / stepSpacingPx).toInt() + 2
                val startStep = (currentStep - visibleStepsEachSide).coerceAtLeast(0)
                val endStep = (currentStep + visibleStepsEachSide).coerceAtMost(param.maxStep)

                for (s in startStep..endStep) {
                    val x = centerX + (s - currentStep) * stepSpacingPx + subStepOffsetPx
                    val distRatio = (abs(x - centerX) / (size.width * 0.5f)).coerceIn(0f, 1f)
                    val fade = 1f - distRatio * 0.72f
                    val isMajor = s % 5 == 0
                    val tickHeight = if (isMajor) 16.dp.toPx() else 9.dp.toPx()
                    val strokeW = if (isMajor) 1.6.dp.toPx() else 1.1.dp.toPx()
                    val baseColor = if (isMajor) majorTickColor else minorTickColor
                    drawLine(
                        color = baseColor.copy(alpha = baseColor.alpha * fade),
                        start = Offset(x, bottomY - tickHeight),
                        end = Offset(x, bottomY),
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round
                    )
                    if (s == param.defaultStep) {
                        drawCircle(
                            color = defaultDotColor.copy(alpha = 0.85f * fade),
                            radius = 2.2.dp.toPx(),
                            center = Offset(x, 6.dp.toPx())
                        )
                    }
                }

                // Center fixed indicator needle
                drawLine(
                    color = needleColor,
                    start = Offset(centerX, 4.dp.toPx()),
                    end = Offset(centerX, size.height - 4.dp.toPx()),
                    strokeWidth = 2.4.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

private fun categoryLabel(strings: AppStrings, category: TuningCategory): String = when (category) {
    TuningCategory.BASIC -> strings.tpTuning.catBasic
    TuningCategory.CURVE -> strings.tpTuning.catCurve
    TuningCategory.CORRECTION -> strings.tpTuning.catCorrection
}
