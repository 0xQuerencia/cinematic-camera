package com.cinematic.camera.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.math.roundToInt

data class Clip(val uri: Uri, val title: String, val details: JSONObject) {
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
    private val cubes = File(context.filesDir, "clip-cubes").apply { mkdirs() }

    fun cubeText(clip: Clip): String? {
        val capture = clip.details.optJSONObject("capture") ?: return null
        val ref = capture.optString("cubeRef")
        if (ref.matches(Regex("[0-9a-f]{64}"))) {
            return AtomicFile(File(cubes, "$ref.cube")).readFully().decodeToString()
        }
        return capture.optString("cube").takeIf { it.isNotEmpty() }
    }

    fun manifest(clip: Clip): JSONObject = JSONObject(clip.details.toString()).apply {
        remove("uri")
        remove("filename")
        optJSONObject("capture")?.let { capture ->
            capture.remove("cubeRef")
            cubeText(clip)?.let { capture.put("cube", it) }
        }
    }

    fun inspect(uri: Uri): JSONObject {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val tracks = (0 until extractor.trackCount).map { it to extractor.getTrackFormat(it) }
            val (index, video) = tracks.first { (_, track) ->
                track.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            val videoTime = timeline(extractor, index)
            require(videoTime.count > 0) { "The MP4 contains no video samples." }
            val audioTime = tracks.firstOrNull { (_, track) ->
                track.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }?.let { (audioIndex, _) -> timeline(extractor, audioIndex) }
            return JSONObject().apply {
                put("videoMime", video.getString(MediaFormat.KEY_MIME))
                put("audioTrack", audioTime?.let { it.count > 0 } == true)
                put("audioSamples", audioTime?.count ?: 0)
                put("videoSamples", videoTime.count)
                put("firstVideoPtsUs", videoTime.first)
                put("lastVideoPtsUs", videoTime.last)
                audioTime?.takeIf { it.count > 0 }?.let {
                    put("firstAudioPtsUs", it.first)
                    put("lastAudioPtsUs", it.last)
                }
                if (videoTime.count > 1 && videoTime.last > videoTime.first) {
                    put("averageFps", (videoTime.count - 1) * 1_000_000.0 / (videoTime.last - videoTime.first))
                }
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

    private data class Timeline(val first: Long, val last: Long, val count: Int)

    private fun timeline(extractor: MediaExtractor, index: Int): Timeline {
        extractor.selectTrack(index)
        try {
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
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
            return Timeline(first, last, count)
        } finally {
            extractor.unselectTrack(index)
        }
    }

    fun save(uri: Uri, title: String, capture: JSONObject, measured: JSONObject?): Clip {
        val file = File(directory, "$title.json")
        val details = JSONObject().apply {
            put("version", 1)
            put("uri", uri.toString())
            put("filename", title)
            val stored = JSONObject(capture.toString())
            val cube = stored.remove("cube") as? String
            if (cube != null) {
                val bytes = cube.encodeToByteArray()
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                val indexed = File(cubes, "$hash.cube")
                if (!indexed.isFile) write(AtomicFile(indexed), bytes)
                stored.put("cubeRef", hash)
            }
            put("capture", stored)
            put("verifiedTracks", measured != null)
            measured?.keys()?.forEach { key -> put(key, measured.get(key)) }
        }
        write(AtomicFile(file), details.toString(2).encodeToByteArray())
        return Clip(uri, title, details)
    }

    private fun write(atomic: AtomicFile, bytes: ByteArray) {
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }

    fun list(): List<Clip> = directory.listFiles { file -> file.extension == "json" }
        .orEmpty().sortedByDescending { it.name }.mapNotNull { file ->
            runCatching {
                val data = JSONObject(AtomicFile(file).readFully().decodeToString())
                Clip(Uri.parse(data.getString("uri")), data.getString("filename"), data)
            }.getOrNull()
        }

    fun lastVerifiedFormat(lensId: String): JSONObject? =
        list().asSequence().filter { it.details.optBoolean("verifiedTracks") }
            .mapNotNull { it.details.optJSONObject("capture") }.firstOrNull {
                it.optString("cameraId") == lensId && it.optString("pipeline") != "EXPORT" &&
                    it.optInt("recorderError") == 0 && it.optJSONArray("formatDifferences")?.length() == 0
            }

    fun delete(clip: Clip) {
        check(context.contentResolver.delete(clip.uri, null, null) > 0) { "Video could not be deleted." }
        AtomicFile(File(directory, "${clip.title}.json")).delete()
    }
}
