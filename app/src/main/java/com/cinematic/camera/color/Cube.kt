package com.cinematic.camera.color

import java.io.Reader
import kotlin.math.floor
import kotlin.math.pow

class Cube private constructor(
    val size: Int,
    val min: FloatArray,
    val max: FloatArray,
    internal val samples: FloatArray,
) {
    fun sample(r: Float, g: Float, b: Float): FloatArray {
        val p = floatArrayOf(r, g, b).mapIndexed { i, v ->
            ((v - min[i]) / (max[i] - min[i])).coerceIn(0f, 1f) * (size - 1)
        }
        val base = p.map { floor(it).toInt().coerceAtMost(size - 2) }
        val t = p.mapIndexed { i, v -> v - base[i] }
        val result = FloatArray(3)
        for (z in 0..1) for (y in 0..1) for (x in 0..1) {
            val entry = ((base[2] + z) * size * size + (base[1] + y) * size + base[0] + x) * 3
            val weight = (if (x == 1) t[0] else 1 - t[0]) *
                (if (y == 1) t[1] else 1 - t[1]) * (if (z == 1) t[2] else 1 - t[2])
            for (channel in 0..2) result[channel] += samples[entry + channel] * weight
        }
        return result
    }

    fun writeTo(output: Appendable) {
        output.appendLine("LUT_3D_SIZE $size")
        output.appendLine("DOMAIN_MIN ${min.joinToString(" ")}")
        output.appendLine("DOMAIN_MAX ${max.joinToString(" ")}")
        for (i in samples.indices step 3) {
            output.appendLine("${samples[i]} ${samples[i + 1]} ${samples[i + 2]}")
        }
    }

    companion object {
        fun parse(reader: Reader): Cube {
            var min = floatArrayOf(0f, 0f, 0f)
            var max = floatArrayOf(1f, 1f, 1f)
            var table: FloatArray? = null
            var size = 0
            var count = 0
            val headers = mutableSetOf<String>()
            val line = StringBuilder()
            val input = reader.buffered()

            fun consume() {
                val value = line.toString().substringBefore('#').trim()
                line.clear()
                if (value.isEmpty()) return
                val parts = value.split(Regex("\\s+"), limit = 5)
                val key = parts.first()
                if (key in setOf("TITLE", "DOMAIN_MIN", "DOMAIN_MAX", "LUT_3D_SIZE")) {
                    require(count == 0 && headers.add(key)) { "Invalid or duplicate $key header." }
                    when (key) {
                        "TITLE" -> Unit
                        "LUT_3D_SIZE" -> {
                            size = parts.getOrNull(1)?.toIntOrNull() ?: 0
                            require(parts.size == 2 && size in 2..33) { "Supported 3D cube sizes are 2–33." }
                            table = FloatArray(size * size * size * 3)
                        }
                        "DOMAIN_MIN" -> min = triple(parts.drop(1))
                        "DOMAIN_MAX" -> max = triple(parts.drop(1))
                    }
                } else {
                    val entries = requireNotNull(table) { "Missing LUT_3D_SIZE. Only 3D .cube files are supported." }
                    require(count + 3 <= entries.size) { "Too many cube entries." }
                    triple(parts).copyInto(entries, count)
                    count += 3
                }
            }

            var length = 0
            while (true) {
                val character = input.read()
                if (character == -1) break
                require(++length <= 5_000_000) { "Cube file exceeds the import limit." }
                if (character == 10) consume() else {
                    require(line.length < 1024) { "Cube line exceeds the import limit." }
                    if (character != 0xFEFF || length != 1) line.append(character.toChar())
                }
            }
            consume()
            val data = requireNotNull(table) { "Missing LUT_3D_SIZE." }
            require(count == data.size) { "Expected ${data.size / 3} entries, found ${count / 3}." }
            require(min.indices.all { max[it] > min[it] && (max[it] - min[it]).isFinite() }) { "Invalid cube domain." }
            return Cube(size, min, max, data)
        }

        private fun triple(parts: List<String>): FloatArray {
            require(parts.size == 3) { "Expected three finite channel values." }
            return FloatArray(3) { index ->
                requireNotNull(parts[index].toFloatOrNull()?.takeIf { it.isFinite() && it in -16f..16f }) {
                    "Invalid channel value."
                }
            }
        }

        fun create(size: Int = 17, transform: (Float, Float, Float) -> FloatArray): Cube {
            val data = FloatArray(size * size * size * 3)
            val scale = (size - 1).toFloat()
            var index = 0
            for (z in 0 until size) for (y in 0 until size) for (x in 0 until size) {
                transform(x / scale, y / scale, z / scale).copyInto(data, index)
                index += 3
            }
            return Cube(size, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f), data)
        }
    }
}

data class Look(val name: String, val family: String, val cube: Cube?)
val originalLooks by lazy {
    fun look(name: String, family: String, contrast: Float, sat: Float,
             warm: Float, lift: Float, shoulder: Float = 0f, split: Float = 0f): Look =
        Look(name, family, Cube.create { r, g, b ->
            val luma = .2126f * r + .7152f * g + .0722f * b
            val cast = warm + split * (2 * luma - 1)
            floatArrayOf(r, g, b).mapIndexed { index, value ->
                val c = ((luma + (value - luma) * sat - .5f) * contrast + .5f + lift)
                    .coerceIn(0f, 1f)
                (c - shoulder * c.pow(3) + (1 - index) * cast * (1 - c) * (c + .1f))
                    .coerceIn(0f, 1f)
            }.toFloatArray()
        })
    // Original SDR Rec.709 split-tone looks; no external LUT licenses or downloads.
    fun filmLook(name: String, family: String, contrast: Float, saturation: Float,
                 shadows: FloatArray, highlights: FloatArray, fade: Float = 0f,
                 shoulder: Float = .06f): Look =
        Look(name, family, Cube.create(17) { r, g, b ->
            val luma = .2126f * r + .7152f * g + .0722f * b
            val shadowWeight = (1f - luma).pow(1.65f)
            val highlightWeight = luma.pow(1.65f)
            val original = floatArrayOf(r, g, b)
            FloatArray(3) { channel ->
                val separated = luma + (original[channel] - luma) * saturation
                val midtone = (separated - .5f) * contrast + .5f
                val faded = midtone * (1f - fade) + fade * .075f
                val soft = faded - shoulder * ((faded - .55f).coerceAtLeast(0f)).pow(2f)
                (soft + shadows[channel] * shadowWeight + highlights[channel] * highlightWeight)
                    .coerceIn(0f, 1f)
            }
        })
    listOf(
        Look("Neutral", "Natural", null),
        look("Clean Commercial", "Natural", 1.06f, 1.10f, 0f, 0f),
        look("Soft Portrait", "Natural", .94f, .92f, .15f, .015f, .045f),
        look("Documentary", "Natural", 1.08f, .88f, -.03f, 0f),
        look("Indie 16", "Filmic", 1.11f, .95f, .05f, .04f, .07f, .16f),
        look("Film Print", "Filmic", 1.20f, 1.04f, .10f, -.005f, .05f, .18f),
        look("Faded Memory", "Filmic", .86f, .8f, .18f, .04f, .06f),
        look("Golden Hour", "Warm", 1.01f, 1.07f, .30f, .02f, .02f),
        look("Desert", "Warm", 1.09f, .82f, .27f, .01f, .05f),
        look("Steel Blue", "Cool", 1.14f, .82f, -.23f, -.01f),
        look("Moonlit", "Cool", 1.01f, .71f, -.29f, -.04f, .08f),
        look("Bleach Bypass", "Dramatic", 1.30f, .43f, -.03f, -.02f, .05f),
        look("Crushed B&W", "Dramatic", 1.33f, 0f, 0f, -.025f),
        look("Pastel", "Social", .84f, .93f, .07f, .06f, .025f),
        filmLook("Soft Highlight", "Natural", .92f, 1.02f,
            floatArrayOf(.008f, .013f, .020f), floatArrayOf(.010f, .006f, -.005f), shoulder = .14f),
        filmLook("Warm Print", "Filmic", 1.12f, 1.02f,
            floatArrayOf(-.015f, .005f, .016f), floatArrayOf(.037f, .015f, -.012f)),
        filmLook("Dusty Rose", "Filmic", .94f, .84f,
            floatArrayOf(.016f, -.012f, .017f), floatArrayOf(.027f, .006f, .012f), fade = .065f),
        filmLook("Amber Analog", "Filmic", 1.07f, .91f,
            floatArrayOf(-.004f, .012f, -.009f), floatArrayOf(.047f, .017f, -.019f), fade = .027f),
        filmLook("Forest Film", "Landscape", 1.04f, .92f,
            floatArrayOf(-.015f, .018f, .013f), floatArrayOf(.024f, .022f, -.014f), fade = .012f),
        filmLook("Rainy City", "Cool", 1.08f, .79f,
            floatArrayOf(-.022f, .013f, .036f), floatArrayOf(-.015f, .003f, .019f), fade = .015f),
        filmLook("Neon Nocturne", "Night", 1.08f, 1.14f,
            floatArrayOf(-.018f, -.009f, .046f), floatArrayOf(.026f, -.015f, .022f),
            fade = .024f, shoulder = .11f),
        filmLook("Night Amber", "Night", 1.01f, .82f,
            floatArrayOf(.003f, -.018f, .033f), floatArrayOf(.049f, .017f, -.019f),
            fade = .035f, shoulder = .12f),
        filmLook("Coastal Air", "Bright", .96f, .90f,
            floatArrayOf(-.018f, .014f, .027f), floatArrayOf(.012f, .014f, -.010f),
            fade = .022f, shoulder = .13f),
        filmLook("Copper Dusk", "Warm", 1.10f, .89f,
            floatArrayOf(-.010f, .016f, .015f), floatArrayOf(.059f, .020f, -.021f),
            fade = .013f),
        filmLook("Silver Screen", "Dramatic", 1.13f, 0f,
            floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f),
            fade = .011f, shoulder = .12f),
        filmLook("Muted Moss", "Landscape", .98f, .78f,
            floatArrayOf(.000f, .016f, -.010f), floatArrayOf(.020f, .020f, -.008f),
            fade = .042f),
        filmLook("Electric Blue", "Cool", 1.06f, 1.12f,
            floatArrayOf(-.019f, -.002f, .049f), floatArrayOf(-.022f, .010f, .034f),
            shoulder = .10f),
        filmLook("Vintage Chrome", "Filmic", 1.10f, .86f,
            floatArrayOf(-.012f, .012f, .021f), floatArrayOf(.026f, .015f, -.003f),
            fade = .031f),
        filmLook("Saffron", "Warm", 1.07f, 1.05f,
            floatArrayOf(.004f, -.008f, -.017f), floatArrayOf(.049f, .025f, -.024f),
            shoulder = .095f),
    )
}

data class Grade(
    val look: Look = originalLooks.first(),
    val strength: Float = 1f,
    val exposure: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val curve: Float = 1f,
    val highlights: Float = 0f,
    val grain: Float = 0f,
    val vignette: Float = 0f,
    val bloom: Float = 0f,
    val halation: Float = 0f,
)
