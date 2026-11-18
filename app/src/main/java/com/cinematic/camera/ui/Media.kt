package com.cinematic.camera.ui

import android.content.ClipData
import android.content.Intent
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.cinematic.camera.capture.CameraEngine
import com.cinematic.camera.media.Clip
import com.cinematic.camera.media.GradedExport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun MediaPanel(engine: CameraEngine, revision: Int, export: (String, String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var clips by remember { mutableStateOf(emptyList<Clip>()) }
    var selected by remember { mutableStateOf<Clip?>(null) }
    var delete by remember { mutableStateOf<Clip?>(null) }
    var playing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Int?>(null) }
    var exporting by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(revision) { clips = withContext(Dispatchers.IO) { engine.clips.list() } }
    error?.let { Hint(it, Amber) }
    if (clips.isEmpty()) Hint("Record a take to add it to the library.")
    selected?.let { clip ->
        Key("All clips", enabled = exporting == null) { selected = null; playing = false }
        Text(clip.title, style = MaterialTheme.typography.titleMedium)
        val capture = clip.details.optJSONObject("capture")
        Hint(when (capture?.optString("pipeline")) {
            "BAKED" -> "Baked pipeline"
            "EXPORT" -> "Baked look · offline export"
            "CLEAN" -> "Clean file · look manifest"
            else -> "Direct capture · clean file"
        }, Info)
        Hint(if (clip.details.optBoolean("verifiedTracks")) clip.summary else "Tracks could not be verified.")
        if (playing) {
            AndroidView(factory = {
                VideoView(it).apply {
                    setMediaController(MediaController(it).also { controller -> controller.setAnchorView(this) })
                    setOnErrorListener { _, _, _ -> error = "Android could not open this video."; true }
                    setVideoURI(clip.uri)
                    setOnPreparedListener { start() }
                }
            }, modifier = Modifier.fillMaxWidth().height(200.dp), onReset = null, onRelease = { it.stopPlayback() })
        }
        KeysRow {
            Key(if (playing) "Close player" else "Play") { playing = !playing }
            Key("Share video") {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "video/mp4"
                    putExtra(Intent.EXTRA_STREAM, clip.uri)
                    clipData = ClipData.newUri(context.contentResolver, clip.title, clip.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Share video"))
            }
            Key("Look manifest") {
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { engine.clips.manifest(clip).toString(2) } }
                        .onSuccess { export("${clip.title}.json", it) }
                        .onFailure { error = "Could not read the look manifest." }
                }
            }
            if (capture?.let { it.has("cubeRef") || it.has("cube") } == true) {
                Key("Export cube") {
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { checkNotNull(engine.clips.cubeText(clip)) } }
                            .onSuccess { export("${clip.title}.cube", it) }
                            .onFailure { error = "Could not read the saved cube." }
                    }
                }
            }
            if (capture?.optString("pipeline") == "CLEAN" && clip.details.optBoolean("verifiedTracks")) {
                Key("Render graded copy", enabled = exporting == null) {
                    playing = false
                    error = null
                    progress = null
                    exporting = scope.launch {
                        try {
                            val rendered = GradedExport(context, engine.clips).render(clip) { progress = it }
                            clips = listOf(rendered) + clips
                            selected = rendered
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (failure: Exception) {
                            android.util.Log.e("CinematicCamera", "Graded export failed", failure)
                            error = "Graded export failed. ${failure.localizedMessage.orEmpty()}"
                        } finally {
                            exporting = null
                        }
                    }
                }
            }
            Key("Delete", enabled = exporting == null) { delete = clip }
        }
        if (exporting != null) {
            val amount = progress
            Hint(if (amount == 100) "Publishing graded MP4…" else "Rendering graded MP4${amount?.let { " · $it%" }.orEmpty()}")
            if (amount == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { amount / 100f }, modifier = Modifier.fillMaxWidth())
            Key("Cancel export", enabled = amount != 100) { exporting?.cancel() }
        }
        capture?.let { data ->
            Hint("Target ${data.optString("requestedResolution")} · ${data.optString("requestedFps")} · ${data.optInt("targetBitrateMbps")} Mb/s")
            Hint("Video samples ${clip.details.optInt("videoSamples")} · ${data.optLong("recordedBytes") / 1024} KiB")
            data.optJSONArray("formatDifferences")?.takeIf { it.length() > 0 }?.let { differences ->
                Hint("Measured output differs in ${(0 until differences.length()).joinToString { differences.getString(it) }}.", Amber)
            }
            if (data.optInt("recorderError") != 0) Hint("Recovered recording · status ${data.optInt("recorderError")}", Amber)
        }
    } ?: clips.forEach { clip ->
        HorizontalDivider(color = Rule)
        Key(clip.title, Modifier.fillMaxWidth()) { selected = clip }
        Hint(if (clip.details.optBoolean("verifiedTracks")) clip.summary else "Tracks could not be verified.")
    }
    delete?.let { clip ->
        AlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete this clip?") },
            text = { Text("The MP4 and its look manifest will be removed from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    delete = null
                    playing = false
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { engine.clips.delete(clip) } }
                            .onSuccess { clips = clips.filterNot { it.uri == clip.uri }; selected = null }
                            .onFailure { error = it.localizedMessage ?: "Delete failed." }
                    }
                }) { Text("Delete", color = Record) }
            },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel") } })
    }
}
