package com.cinematic.camera.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

data class Clip(val uri: Uri, val title: String, val details: JSONObject) {
    val manifest: JSONObject get() = JSONObject(details.toString()).apply {
        remove("uri")
        remove("filename")
    }
    val summary: String get() = with(details) {
        val audio = if (optBoolean("audioTrack")) "Audio" else "Silent"
        val fps = optDouble("averageFps").takeIf { it.isFinite() }?.let {
            "${(it * 100).roundToInt() / 100.0} fps"
        } ?: "FPS unavailable"
        "${optInt("width")}×${optInt("height")} · $fps · ${optString("videoMime")} · $audio"
    }
}

class Clips(private val context: Context) {
    private val directory = File(context.filesDir, "clips").apply { mkdirs() }

    fun inspect(uri: Uri): JSONObject {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val tracks = (0 until extractor.trackCount).map { it to extractor.getTrackFormat(it) }
            val (index, video) = tracks.first { (_, track) ->
                track.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            extractor.selectTrack(index)
            var first = Long.MAX_VALUE
            var last = Long.MIN_VALUE
            var count = 0
            while (extractor.sampleTime >= 0) {
                val time = extractor.sampleTime
                first = minOf(first, time)
                last = maxOf(last, time)
                count++
                if (!extractor.advance()) break
            }
            require(count > 0) { "The MP4 contains no video samples." }
            return JSONObject().apply {
                put("videoMime", video.getString(MediaFormat.KEY_MIME))
                put("audioTrack", tracks.any { it.second.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true })
                put("videoSamples", count)
                if (count > 1 && last > first) put("averageFps", (count - 1) * 1_000_000.0 / (last - first))
                listOf(MediaFormat.KEY_WIDTH, MediaFormat.KEY_HEIGHT, MediaFormat.KEY_ROTATION,
                    MediaFormat.KEY_COLOR_STANDARD, MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.KEY_COLOR_RANGE,
                    MediaFormat.KEY_PROFILE, MediaFormat.KEY_FRAME_RATE).forEach { key ->
                    if (video.containsKey(key)) runCatching { put(key, video.getInteger(key)) }
                }
                if (video.containsKey(MediaFormat.KEY_DURATION)) put("durationUs", video.getLong(MediaFormat.KEY_DURATION))
            }
        } finally {
            extractor.release()
        }
    }

    fun save(uri: Uri, title: String, capture: JSONObject, measured: JSONObject?): Clip {
        val file = File(directory, "$title.json")
        val details = JSONObject().apply {
            put("version", 1)
            put("uri", uri.toString())
            put("filename", title)
            put("capture", capture)
            put("verifiedTracks", measured != null)
            measured?.keys()?.forEach { key -> put(key, measured.get(key)) }
        }
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(details.toString(2).toByteArray())
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
        return Clip(uri, title, details)
    }

    fun list(): List<Clip> = directory.listFiles { file -> file.extension == "json" }
        .orEmpty().sortedByDescending { it.name }.mapNotNull { file ->
            runCatching {
                val data = JSONObject(AtomicFile(file).readFully().decodeToString())
                Clip(Uri.parse(data.getString("uri")), data.getString("filename"), data)
            }.getOrNull()
        }

    fun delete(clip: Clip) {
        check(context.contentResolver.delete(clip.uri, null, null) > 0) { "Video could not be deleted." }
        AtomicFile(File(directory, "${clip.title}.json")).delete()
    }
}
