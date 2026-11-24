package com.cinematic.camera.capture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File

/** Raw Android gyroscope samples on the monotonic boot clock. No claim of frame synchronization. */
internal class GyroRecorder(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val directory = File(context.filesDir, "gyro").apply { mkdirs() }
    val available get() = sensor != null
    private var thread: HandlerThread? = null
    private var writer: BufferedWriter? = null
    private var filename: String? = null
    private var count = 0
    private var anchor = 0L
    private var first = 0L
    private var last = 0L
    private var failure = false
    private var truncated = false

    @Synchronized fun start(title: String): Boolean {
        val gyro = sensor ?: return false
        if (writer != null) return false
        require(title.matches(Regex("CAM_[0-9]+_[a-f0-9]{6}")))
        val file = File(directory, "$title.csv")
        return try {
            writer = file.bufferedWriter().apply {
                write("timestamp_ns,x_rad_s,y_rad_s,z_rad_s\n")
            }
            filename = file.name
            count = 0; first = 0; last = 0; failure = false; truncated = false
            anchor = SystemClock.elapsedRealtimeNanos()
            val worker = HandlerThread("CinematicGyro").apply { start() }
            thread = worker
            check(manager.registerListener(this, gyro, 20_000, Handler(worker.looper)))
            true
        } catch (error: Exception) {
            stop()
            file.delete()
            false
        }
    }

    @Synchronized override fun onSensorChanged(event: SensorEvent) {
        val output = writer ?: return
        if (failure || event.values.size < 3) return
        if (count >= 360_000) { truncated = true; return }
        if (event.timestamp <= last || event.values.take(3).any { !it.isFinite() }) return
        try {
            output.append(event.timestamp.toString())
            for (i in 0..2) output.append(',').append(event.values[i].toString())
            output.append('\n')
            if (count == 0) first = event.timestamp
            last = event.timestamp
            count++
            if (count % 100 == 0) output.flush()
        } catch (error: Exception) { failure = true }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    @Synchronized fun stop(): JSONObject? {
        val name = filename
        manager?.unregisterListener(this)
        runCatching { writer?.close() }.onFailure { failure = true }
        writer = null
        thread?.quitSafely()
        thread = null
        filename = null
        if (name == null) return null
        return JSONObject().put("file", name).put("units", "rad/s")
            .put("clock", "elapsedRealtimeNanos / sensor boot clock")
            .put("recordingStartCallbackNs", anchor).put("firstSampleNs", first)
            .put("lastSampleNs", last).put("samples", count)
            .put("truncated", truncated).put("writeError", failure)
            .put("frameSynchronized", false)
    }
}
