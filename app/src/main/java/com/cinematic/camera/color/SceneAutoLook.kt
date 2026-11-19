package com.cinematic.camera.color

import kotlin.math.max
import kotlin.math.min

data class AutoLookChoice(val name: String, val strength: Float, val reason: String)

/**
 * Lightweight SDR scene analysis of the 64x36 RGBA scope buffer. No camera frames leave the phone.
 * This recommends a creative look, not a technically calibrated scene classification.
 */
object SceneAutoLook {
    fun recommend(pixels: ByteArray): AutoLookChoice? {
        if (pixels.size != 64 * 36 * 4) return null
        var brightness = 0f
        var chroma = 0f
        var warmth = 0f
        var green = 0f
        var highlights = 0
        var shadows = 0
        val count = pixels.size / 4
        for (i in pixels.indices step 4) {
            val r = (pixels[i].toInt() and 255) / 255f
            val g = (pixels[i + 1].toInt() and 255) / 255f
            val b = (pixels[i + 2].toInt() and 255) / 255f
            val y = .2126f * r + .7152f * g + .0722f * b
            brightness += y
            chroma += max(r, max(g, b)) - min(r, min(g, b))
            warmth += r - b
            green += (g - (r + b) * .5f).coerceAtLeast(0f)
            if (y > .87f) highlights++
            if (y < .16f) shadows++
        }
        brightness /= count
        chroma /= count
        warmth /= count
        green /= count
        if (brightness < .035f) return null // covered lens or near-black frame
        val brightShare = highlights.toFloat() / count
        val darkShare = shadows.toFloat() / count
        return when {
            brightness < .26f && chroma > .23f ->
                AutoLookChoice("Neon Nocturne", .54f, "Colorful low-light scene")
            brightness < .28f ->
                AutoLookChoice("Moonlit", .46f, "Low light: protect shadow detail")
            green > .055f && brightness > .30f ->
                AutoLookChoice("Forest Film", .72f, "Green-dominant daylight")
            warmth > .085f && brightness > .24f ->
                AutoLookChoice("Golden Hour", .62f, "Warm scene illumination")
            warmth < -.07f && brightness > .26f ->
                AutoLookChoice("Rainy City", .65f, "Cool scene illumination")
            brightShare > .12f && darkShare > .07f ->
                AutoLookChoice("Soft Highlight", .72f, "Bright highlights and deep shadows")
            brightness > .72f ->
                AutoLookChoice("Soft Portrait", .58f, "Bright scene: gentle contrast")
            chroma < .095f ->
                AutoLookChoice("Faded Memory", .66f, "Muted, low-saturation colors")
            else ->
                AutoLookChoice("Film Print", .64f, "Balanced cinematic contrast")
        }
    }
}

/** Require several agreeing samples and a hold period so the viewfinder does not flicker. */
class AutoLookSelector(
    private val consecutiveSamples: Int = 3,
    private val holdMillis: Long = 6_000L,
) {
    private var candidate: String? = null
    private var seen = 0
    private var applied: String? = null
    private var appliedAt: Long? = null

    fun reset() {
        candidate = null
        seen = 0
        applied = null
        appliedAt = null
    }

    fun consider(frame: ByteArray, nowMillis: Long): AutoLookChoice? {
        val suggestion = SceneAutoLook.recommend(frame) ?: run {
            candidate = null
            seen = 0
            return null
        }
        if (suggestion.name == candidate) seen++ else {
            candidate = suggestion.name
            seen = 1
        }
        if (seen < consecutiveSamples || suggestion.name == applied) return null
        if (appliedAt?.let { nowMillis - it < holdMillis } == true) return null
        applied = suggestion.name
        appliedAt = nowMillis
        return suggestion
    }
}
