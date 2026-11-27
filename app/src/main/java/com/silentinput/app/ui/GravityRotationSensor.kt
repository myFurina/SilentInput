package com.silentinput.app.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.silentinput.app.model.GravityTiltEstimator
import com.silentinput.app.model.TrackpadVectorMath
import kotlin.math.abs

@Composable
internal fun rememberAutoGravityRotationDeg(
    enabled: Boolean,
    isTouchingProvider: () -> Boolean
): State<Float> {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestIsTouchingProvider by rememberUpdatedState(isTouchingProvider)
    val degState = remember { mutableFloatStateOf(0f) }
    val estimator = remember { GravityTiltEstimator() }
    var isResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> isResumed = true
                Lifecycle.Event.ON_PAUSE -> isResumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(context, view, enabled, isResumed) {
        if (!enabled || !isResumed) {
            if (!enabled) {
                estimator.reset()
                degState.floatValue = 0f
            }
            return@DisposableEffect onDispose {}
        }

        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val gravitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val accelSensor = if (gravitySensor == null) {
            sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        } else {
            null
        }
        val activeSensor = gravitySensor ?: accelSensor
        if (sensorManager == null || activeSensor == null) {
            estimator.reset()
            degState.floatValue = 0f
            return@DisposableEffect onDispose {}
        }

        val isFallbackAccel = gravitySensor == null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val values = event.values
                if (values.size < 3) return
                val rot = view.display?.rotation ?: Surface.ROTATION_0
                val updated = estimator.update(
                    rawGx = values[0],
                    rawGy = values[1],
                    rawGz = values[2],
                    displayRotation = rot,
                    isAccelerometerFallback = isFallbackAccel,
                    isTouching = latestIsTouchingProvider()
                )
                if (abs(TrackpadVectorMath.wrapDegrees180(updated - degState.floatValue)) >= 0.4f || (updated == 0f && degState.floatValue != 0f)) {
                    degState.floatValue = updated
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sensorManager.registerListener(listener, activeSensor, SensorManager.SENSOR_DELAY_UI)
        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }

    return degState
}
