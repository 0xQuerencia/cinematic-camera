package com.cinematic.camera.color

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class SavedLook(val id: String, val name: String, val grade: Grade, val capture: JSONObject?)

fun Grade.toJson(embedCube: Boolean = false) = JSONObject().apply {
    put("version", 1)
    put("input", "SDR Rec.709")
    put("output", "SDR Rec.709")
    put("look", look.name)
    put("strength", strength.toDouble())
    put("exposure", exposure.toDouble())
    put("contrast", contrast.toDouble())
    put("saturation", saturation.toDouble())
    put("temperature", temperature.toDouble())
    put("tint", tint.toDouble())
    put("curve", curve.toDouble())
    put("highlights", highlights.toDouble())
    put("grain", grain.toDouble())
    put("vignette", vignette.toDouble())
    put("bloom", bloom.toDouble())
    put("halation", halation.toDouble())
    if (embedCube) look.cube?.let { put("cube", buildString { it.writeTo(this) }) }
}

class LookStorage(context: Context) {
    private val directory = File(context.filesDir, "looks").apply { mkdirs() }

    fun all(): List<SavedLook> = directory.listFiles { file -> file.extension == "json" }.orEmpty()
        .sortedByDescending { it.lastModified() }.mapNotNull { file ->
            runCatching { decode(file.nameWithoutExtension, AtomicFile(file).readFully().decodeToString()) }.getOrNull()
        }

    fun save(name: String, grade: Grade, capture: JSONObject?, id: String = UUID.randomUUID().toString()): SavedLook {
        val look = SavedLook(id, name.trim(), grade, capture)
        val raw = encode(look)
        decode(id, raw)
        val target = AtomicFile(File(directory, "$id.json"))
        val output = target.startWrite()
        try {
            output.write(raw.encodeToByteArray())
            target.finishWrite(output)
        } catch (error: Exception) {
            target.failWrite(output)
            throw error
        }
        return look
    }

    fun encode(look: SavedLook): String = JSONObject().apply {
        put("version", 1)
        put("name", look.name)
        put("grade", look.grade.toJson(embedCube = true))
        look.capture?.let { put("capture", it) }
    }.toString(2)

    fun decode(id: String, raw: String): SavedLook {
        require(raw.length <= 5_500_000) { "Look file exceeds the import limit." }
        val document = JSONObject(raw)
        require(document.getInt("version") == 1) { "Unsupported look file version." }
        val name = document.getString("name").trim()
        require(name.isNotEmpty() && name.length <= 64) { "Enter a name of 1–64 characters." }
        val grade = document.getJSONObject("grade")
        require(grade.getInt("version") == 1 &&
            grade.getString("input") == "SDR Rec.709" && grade.getString("output") == "SDR Rec.709") {
            "Only SDR Rec.709 looks are supported."
        }
        val cube = grade.optString("cube").takeIf { it.isNotEmpty() }?.let { Cube.parse(it.reader()) }
        val look = Look(grade.optString("look", name).take(64), "Saved", cube)
        fun read(key: String, range: ClosedFloatingPointRange<Float>) =
            grade.getDouble(key).toFloat().also { require(it in range) { "Invalid $key." } }
        val result = Grade(look, read("strength", 0f..1f), read("exposure", -2f..2f),
            read("contrast", .5f..1.5f), read("saturation", 0f..2f), read("temperature", -.3f.. .3f),
            read("tint", -.3f.. .3f), read("curve", .5f..2f), read("highlights", 0f.. .4f),
            read("grain", 0f..1f), read("vignette", 0f..1f), read("bloom", 0f..1f), read("halation", 0f..1f))
        return SavedLook(id, name, result, document.optJSONObject("capture"))
    }
}
