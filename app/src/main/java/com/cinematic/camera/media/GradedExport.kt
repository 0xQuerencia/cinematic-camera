@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.cinematic.camera.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.opengl.GLES30 as GL
import android.opengl.Matrix
import android.os.Build
import android.provider.MediaStore
import androidx.media3.common.util.Size
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.cinematic.camera.color.Grade
import com.cinematic.camera.color.GradeShader
import com.cinematic.camera.color.LookStorage
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal class GradedExport(private val context: Context, private val clips: Clips) {
    suspend fun render(source: Clip, progress: (Int?) -> Unit): Clip = coroutineScope {
        val metadata = source.details.getJSONObject("capture")
        require(metadata.optString("pipeline") == "CLEAN") { "Choose a clean recording with a look manifest." }
        require(source.details.optBoolean("verifiedTracks")) { "The source MP4 could not be inspected." }
        val grade = withContext(Dispatchers.IO) {
            val look = JSONObject(metadata.getJSONObject("look").toString())
            clips.cubeText(source)?.let { look.put("cube", it) }
            LookStorage(context).decode("", JSONObject().put("version", 1).put("name", "Clip look")
                .put("grade", look).toString()).grade
        }
        val temporary = withContext(Dispatchers.IO) { File.createTempFile("grade-", ".mp4", context.cacheDir) }
        try {
            val transformer = Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264).build()
            val progressJob = launch {
                val holder = ProgressHolder()
                while (true) {
                    progress(if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else null)
                    delay(250)
                }
            }
            try {
                suspendCancellableCoroutine { result ->
                    transformer.addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (result.isActive) result.resume(Unit)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult,
                                             exportException: ExportException) {
                            if (result.isActive) result.resumeWithException(exportException)
                        }
                    })
                    result.invokeOnCancellation { transformer.cancel() }
                    val effect = GlEffect { _, useHdr ->
                        if (useHdr) throw VideoFrameProcessingException("Graded export requires SDR footage.")
                        ExportShader(grade)
                    }
                    val edited = EditedMediaItem.Builder(MediaItem.fromUri(source.uri))
                        .setEffects(Effects(emptyList(), listOf(effect))).build()
                    transformer.start(edited, temporary.absolutePath)
                }
            } finally {
                progressJob.cancel()
            }
            progress(100)
            withContext(Dispatchers.IO + NonCancellable) { publish(source, metadata, temporary) }
        } finally {
            withContext(Dispatchers.IO + NonCancellable) { temporary.delete() }
        }
    }

    private fun publish(source: Clip, capture: JSONObject, file: File): Clip {
        val name = "${source.title}_Grade_${System.currentTimeMillis()}"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$name.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Cinematic Camera")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)) {
            "Could not create the destination MP4."
        }
        try {
            checkNotNull(resolver.openOutputStream(uri)).use { output -> file.inputStream().use { it.copyTo(output) } }
            val measured = clips.inspect(uri)
            if (source.details.optBoolean("audioTrack")) {
                check(measured.optBoolean("audioTrack")) { "Export did not retain the source audio track." }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                val ready = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                check(resolver.update(uri, ready, null, null) == 1) { "Could not publish the exported MP4." }
            }
            val export = JSONObject(capture.toString())
                .put("pipeline", "EXPORT").put("sourceClip", source.title)
                .put("sourceRecorderError", capture.optInt("recorderError")).put("recorderError", 0)
                .put("recordedBytes", file.length())
            return clips.save(uri, name, export, measured)
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }
}

private class ExportShader(private val grade: Grade) : BaseGlShaderProgram(false, 1) {
    private val transform = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val shader = try {
        GradeShader(GL.GL_TEXTURE_2D)
    } catch (error: Exception) {
        throw VideoFrameProcessingException(error)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            shader.prepare(grade, (presentationTimeUs / 1000L % 1024).toFloat(), false, inputTexId)
            shader.draw(transform, enabled = true, split = false)
            check(GL.glGetError() == GL.GL_NO_ERROR) { "OpenGL could not render the export frame." }
        } catch (error: Exception) {
            throw VideoFrameProcessingException(error, presentationTimeUs)
        }
    }

    override fun release() {
        shader.close()
        super.release()
    }
}
