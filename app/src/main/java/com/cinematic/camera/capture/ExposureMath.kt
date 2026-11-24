package com.cinematic.camera.capture

import kotlin.math.roundToLong

object ExposureMath {
    fun angleToNanoseconds(angle: Double, fps: Int): Long {
        require(angle.isFinite() && angle > 0.0 && angle <= 360.0 && fps > 0)
        return (1_000_000_000.0 * angle / (360.0 * fps)).roundToLong()
    }

    fun nanosecondsToAngle(ns: Long, fps: Int): Double? {
        if (ns <= 0L || fps <= 0) return null
        return ns * fps / 1_000_000_000.0 * 360.0
    }

    fun mainsCandidates(fps: Int, hz: Int, min: Long, max: Long): List<Long> {
        require(fps > 0 && hz in listOf(50, 60) && min > 0 && max >= min)
        val ceiling = minOf(max, 1_000_000_000L / fps)
        val interval = 1_000_000_000.0 / (hz * 2)
        val count = (ceiling / interval).toInt().coerceIn(0, 120)
        return (1..count).map { (it * interval).roundToLong() }
            .filter { it in min..ceiling }
    }
}
