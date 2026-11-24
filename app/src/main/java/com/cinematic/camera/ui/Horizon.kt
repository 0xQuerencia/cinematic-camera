package com.cinematic.camera.ui

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun HorizonOverlay(modifier: Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    var roll by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(context, view) {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            private var x = 0f
            private var y = 0f
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.size < 3) return
                val alpha = if (event.sensor.type == Sensor.TYPE_GRAVITY) 1f else .18f
                x += (event.values[0]-x)*alpha; y += (event.values[1]-y)*alpha
                if (sqrt(x*x+y*y) < 2f) { roll = null; return }
                val rotated = when (view.display?.rotation) {
                    Surface.ROTATION_90 -> y to -x
                    Surface.ROTATION_180 -> -x to -y
                    Surface.ROTATION_270 -> -y to x
                    else -> x to y
                }
                val angle = atan2(rotated.first,rotated.second)*180f/kotlin.math.PI.toFloat()
                roll = ((angle+270f)%180f)-90f
            }
            override fun onAccuracyChanged(sensor: Sensor?,accuracy: Int) = Unit
        }
        if (sensor != null) manager?.registerListener(listener,sensor,SensorManager.SENSOR_DELAY_UI)
        onDispose { manager?.unregisterListener(listener) }
    }
    Canvas(modifier) {
        val value = roll ?: return@Canvas
        val angle = value*kotlin.math.PI.toFloat()/180f
        val half = size.width*.40f
        val delta = Offset(cos(angle)*half,sin(angle)*half)
        val color = if (abs(value)<2f) Native else Paper
        drawLine(color,center-delta,center+delta,2.dp.toPx())
        drawLine(Amber,Offset(center.x-7.dp.toPx(),center.y),Offset(center.x+7.dp.toPx(),center.y),2.dp.toPx())
    }
}
