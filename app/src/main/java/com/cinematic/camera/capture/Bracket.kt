package com.cinematic.camera.capture

import kotlin.math.roundToInt

object Bracket {
    fun indices(center: Int, stepEv: Float, min: Int, max: Int, spacing: Float): List<Int> {
        if (!stepEv.isFinite() || stepEv <= 0f || !spacing.isFinite() || spacing <= 0f || center !in min..max) return emptyList()
        val offset = (spacing / stepEv).roundToInt().coerceAtLeast(1)
        val values = listOf(center.toLong() - offset, center.toLong(), center.toLong() + offset)
        return if (values.all { it in min.toLong()..max.toLong() }) values.map { it.toInt() } else emptyList()
    }
}
