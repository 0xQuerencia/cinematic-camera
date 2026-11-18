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
    LaunchedEffect(revision) { clips = withContext(Dispatchers.IO) { engine.clips.list() } }
    error?.let { Hint(it, Amber) }
    if (clips.isEmpty()) Hint("Record a take to add it to the library.")
    selected?.let { clip ->
        Key("All clips") { selected = null; playing = false }
        Text(clip.title, style = MaterialTheme.typography.titleMedium)
        val capture = clip.details.optJSONObject("capture")
        Hint(when (capture?.optString("pipeline")) {
            "BAKED" -> "Baked pipeline"
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
            Key("Look manifest") { export("${clip.title}.json", clip.manifest.toString(2)) }
            capture?.optString("cube")?.takeIf { it.isNotEmpty() }?.let { cube ->
                Key("Export cube") { export("${clip.title}.cube", cube) }
            }
            Key("Delete") { delete = clip }
        }
        capture?.let { data ->
            Hint("Target ${data.optString("requestedResolution")} · ${data.optString("requestedFps")} · ${data.optInt("targetBitrateMbps")} Mb/s")
            Hint("Video samples ${clip.details.optInt("videoSamples")} · ${data.optLong("recordedBytes") / 1024} KiB")
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
