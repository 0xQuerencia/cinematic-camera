package com.cinematic.camera.color

import kotlin.math.pow
import kotlin.math.sqrt

/** Small, deterministic scene statistics. Values describe sampled SDR RGB, not semantic objects. */
object AdaptiveColor {
    data class Profile(
        val mean: List<Float>,
        val deviation: List<Float>,
        val dark: Float,
        val bright: Float,
        val warmMidtones: Float,
    )

    fun profile(pixels: ByteArray): Profile? {
        if (pixels.size < 16 || pixels.size % 4 != 0) return null
        val count = pixels.size / 4
        val sum = DoubleArray(3)
        val squares = DoubleArray(3)
        val histogram = IntArray(256)
        var warm = 0
        for (i in pixels.indices step 4) {
            val r = (pixels[i].toInt() and 255) / 255f
            val g = (pixels[i + 1].toInt() and 255) / 255f
            val b = (pixels[i + 2].toInt() and 255) / 255f
            val values = floatArrayOf(r, g, b)
            for (c in 0..2) {
                sum[c] += values[c].toDouble()
                squares[c] += values[c] * values[c].toDouble()
            }
            val y = .2126f * r + .7152f * g + .0722f * b
            histogram[(y * 255).toInt().coerceIn(0, 255)]++
            val chroma = r - b
            if (chroma > .05f && y in .12f.. .88f &&
                (r - g) / chroma in .15f.. .85f) warm++
        }
        fun percentile(p: Float): Float {
            val target = (count * p).toInt().coerceIn(1, count)
            var cumulative = 0
            for (i in histogram.indices) {
                cumulative += histogram[i]
                if (cumulative >= target) return i / 255f
            }
            return 1f
        }
        val means = List(3) { (sum[it] / count).toFloat() }
        return Profile(means, List(3) {
            sqrt((squares[it] / count - means[it].toDouble().pow(2)).coerceAtLeast(0.0)).toFloat()
        }, percentile(.10f), percentile(.90f), warm.toFloat() / count)
    }

    fun alternatives(choice: AutoLookChoice): List<AutoLookChoice> {
        val names = when (choice.name) {
            "Neon Nocturne" -> listOf("Night Amber", "Electric Blue")
            "Moonlit" -> listOf("Night Amber", "Rainy City")
            "Forest Film" -> listOf("Muted Moss", "Vintage Chrome")
            "Golden Hour" -> listOf("Amber Analog", "Warm Print")
            "Rainy City" -> listOf("Steel Blue", "Coastal Air")
            "Soft Highlight", "Soft Portrait" -> listOf("Clean Commercial", "Pastel")
            "Faded Memory" -> listOf("Dusty Rose", "Indie 16")
            else -> listOf("Warm Print", "Documentary")
        }
        return listOf(choice) + names.map { AutoLookChoice(it, (choice.strength * .85f), "Creative alternative") }
    }

    /** Generates a portable SDR cube from a curated look and bounded tonal corrections. */
    fun scene(pixels: ByteArray, choice: AutoLookChoice): Look? {
        val profile = profile(pixels) ?: return null
        if (profile.bright < .035f) return null
        val base = originalLooks.firstOrNull { it.name == choice.name }?.cube ?: return null
        val spread = profile.bright - profile.dark
        val strength = (choice.strength * if (spread > .75f) .80f else 1f).coerceIn(.2f, .85f)
        val lift = ((.48f - profile.mean.average().toFloat()) * .10f).coerceIn(-.045f, .045f)
        val shoulder = if (profile.bright > .88f) .12f else .025f
        val protection = (profile.warmMidtones * .65f).coerceIn(0f, .55f)
        val cube = Cube.create(17) { r, g, b ->
            val baseRgb = base.sample(r, g, b)
            val source = floatArrayOf(r, g, b)
            val y = .2126f * r + .7152f * g + .0722f * b
            val hue = (r - g) / (r - b).coerceAtLeast(.001f)
            val protect = if (r - b > .05f && hue in .15f.. .85f && y in .12f.. .88f) protection else 0f
            FloatArray(3) { c ->
                val v = source[c]
                val result = v + (baseRgb[c] - v) * strength * (1f - protect)
                (result + lift * 4f * v * (1f - v) - shoulder * (result - .75f).coerceAtLeast(0f).pow(2))
                    .coerceIn(0f, 1f)
            }
        }
        return Look("Adaptive ${choice.name}", "Generated", cube)
    }

    /** Approximate global color transfer, not semantic matching or a reconstruction of another LUT. */
    fun reference(source: ByteArray, target: ByteArray): Look? {
        val from = profile(source) ?: return null
        val to = profile(target) ?: return null
        if (from.bright < .035f || to.bright < .035f || from.bright - from.dark < .03f) return null
        val gains = List(3) { (to.deviation[it] / from.deviation[it].coerceAtLeast(.08f)).coerceIn(.75f, 1.25f) }
        val offsets = List(3) { (to.mean[it] - from.mean[it]).coerceIn(-.15f, .15f) }
        return Look("Reference match", "Generated", Cube.create(17) { r, g, b ->
            val sourceRgb = floatArrayOf(r, g, b)
            FloatArray(3) { c ->
                val v = sourceRgb[c]
                val delta = (v - from.mean[c]) * (gains[c] - 1f) + offsets[c]
                (v + delta * (.35f + .65f * 4f * v * (1f - v))).coerceIn(0f, 1f)
            }
        })
    }
}
