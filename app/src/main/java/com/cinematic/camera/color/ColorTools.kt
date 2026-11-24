package com.cinematic.camera.color

import kotlin.math.sqrt

data class Wheel(val x: Float = 0f, val y: Float = 0f) {
    init { require(x.isFinite() && y.isFinite() && x*x + y*y <= 1.0001f) }
    companion object {
        fun at(x: Float, y: Float): Wheel {
            require(x.isFinite() && y.isFinite())
            val scale = sqrt(x*x + y*y).coerceAtLeast(1f)
            return Wheel(x / scale, y / scale)
        }
    }
}

data class ColorBalance(val shadows: Wheel = Wheel(), val midtones: Wheel = Wheel(), val highlights: Wheel = Wheel())

data class HueBand(val hue: Float = 0f, val saturation: Float = 0f, val lightness: Float = 0f) {
    init { require(hue in -30f..30f && saturation in -.5f.. .5f && lightness in -.15f.. .15f) }
}

data class SelectiveHsl(val bands: List<HueBand> = List(6) { HueBand() }) {
    init { require(bands.size == 6) }
    fun update(index: Int, value: HueBand) = SelectiveHsl(bands.mapIndexed { i, old -> if (i == index) value else old })
    fun uniforms(): FloatArray = bands.flatMap { listOf(it.hue / 360f, it.saturation, it.lightness) }.toFloatArray()
}
