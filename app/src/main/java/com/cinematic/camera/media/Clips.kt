package com.cinematic.camera.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.content.ContentUris
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
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
    private val journal = File(context.filesDir, "recording-journal").apply { mkdirs() }
    private val gyro = File(context.filesDir, "gyro").apply { mkdirs() }

    fun beginJournal(title: String, capture: JSONObject) {
        require(title.matches(Regex("CAM_[0-9]+_[a-f0-9]{6}")))
        write(AtomicFile(File(journal, "$title.json")), capture.toString().encodeToByteArray())
    }

    fun finishJournal(title: String) {
        require(title.matches(Regex("CAM_[0-9]+_[a-f0-9]{6}")))
        AtomicFile(File(journal, "$title.json")).delete()
    }

    fun pendingCount(): Int = journal.listFiles { f -> f.extension == "json" }?.size ?: 0

    fun gyroText(clip: Clip): String? {
        val filename = clip.details.optJSONObject("capture")?.optJSONObject("gyro")?.optString("file") ?: return null
        if (!filename.matches(Regex("CAM_[0-9]+_[a-f0-9]{6}\\.csv"))) return null
        val file = File(gyro, filename)
        require(file.length() <= 32L * 1024 * 1024) { "Gyro CSV is too large for text export." }
        return file.takeIf { it.isFile }?.readText()
    }

    /** Only examine journaled filenames. Never delete an incomplete or uninspectable MP4. */
    @Suppress("DEPRECATION")
    fun recoverInterrupted(): Int {
        var recovered = 0
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        for (file in journal.listFiles { f -> f.extension == "json" }.orEmpty()) {
            val title = file.nameWithoutExtension
            if (!title.matches(Regex("CAM_[0-9]+_[a-f0-9]{6}"))) continue
            runCatching {
                if (File(directory, "$title.json").isFile) {
                    finishJournal(title)
                    return@runCatching
                }
                val metadata = JSONObject(AtomicFile(file).readFully().decodeToString())
                val modern = Build.VERSION.SDK_INT >= 29
                val columns = if (modern) arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.IS_PENDING)
                    else arrayOf(MediaStore.Video.Media._ID)
                val queryUri = if (modern) MediaStore.setIncludePending(collection) else collection
                val where = if (modern) "${MediaStore.Video.Media.DISPLAY_NAME}=? AND ${MediaStore.Video.Media.OWNER_PACKAGE_NAME}=?"
                    else "${MediaStore.Video.Media.DISPLAY_NAME}=?"
                val args = if (modern) arrayOf("$title.mp4", context.packageName) else arrayOf("$title.mp4")
                val found = resolver.query(queryUri, columns, where, args, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) null else {
                        val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                        val pending = modern && cursor.getInt(1) != 0
                        if (cursor.moveToNext()) null else uri to pending
                    }
                } ?: return@runCatching
                val measured = inspect(found.first)
                metadata.put("recorderError", -1).put("recoveredAfterProcessDeath", true)
                    .put("formatDifferences", org.json.JSONArray(listOf("interrupted recording")))
                val gyroFile = File(gyro, "$title.csv")
                if (gyroFile.isFile) metadata.put("gyro", JSONObject().put("file", gyroFile.name)
                    .put("frameSynchronized", false).put("partial", true))
                if (modern && found.second) {
                    check(resolver.update(found.first, ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }, null, null) == 1)
                }
                save(found.first, title, metadata, measured)
                finishJournal(title)
                recovered++
            }.onFailure { android.util.Log.w("CinematicCamera", "Recovery deferred for $title", it) }
        }
        return recovered
    }

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
                    it.optString("dynamicRangeRequested", "SDR") == "SDR" &&
                    it.optString("videoMimeRequested", "video/avc") == "video/avc" &&
                    it.optInt("recorderError") == 0 && it.optJSONArray("formatDifferences")?.length() == 0
            }

    fun delete(clip: Clip) {
        check(context.contentResolver.delete(clip.uri, null, null) > 0) { "Video could not be deleted." }
        AtomicFile(File(directory, "${clip.title}.json")).delete()
        if (clip.title.matches(Regex("CAM_[0-9]+_[a-f0-9]{6}"))) File(gyro, "${clip.title}.csv").delete()
    }
}
