package com.cinematic.camera.ui

import android.content.ClipData
import android.content.Intent
import android.widget.MediaController
import android.os.Build
import android.util.Size
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import android.widget.VideoView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
internal fun MediaPanel(engine: CameraEngine, revision: Int, export: (String, String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var clips by remember { mutableStateOf(emptyList<Clip>()) }
    var search by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Clip?>(null) }
    var delete by remember { mutableStateOf<Clip?>(null) }
    var playing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Int?>(null) }
    var exporting by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(revision) { clips = withContext(Dispatchers.IO) { engine.clips.list() } }
    selected?.let { clip ->
        Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            error?.let { Hint(it, Amber) }
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
        }
    } ?: Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        error?.let { Hint(it, Amber) }
        if (clips.isEmpty()) Hint("Record a take to add it to the library.")
        else {
            OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Search clips") })
            val filtered = clips.filter { it.title.contains(search, ignoreCase = true) }
            Hint("${filtered.size} clips · Newest first")
            LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                items(filtered, key = { it.uri.toString() }) { clip ->
                    ClipRow(clip) { selected = clip }
                    HorizontalDivider(color = Rule)
                }
            }
        }
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

@Composable
private fun ClipRow(clip: Clip, open: () -> Unit) {
    val context = LocalContext.current
    val thumbnail by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, clip.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= 29) context.contentResolver.loadThumbnail(clip.uri, Size(230, 150), null)
                else {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, clip.uri)
                        if (Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 230, 150)
                        else retriever.getFrameAtTime(-1)?.let { android.graphics.Bitmap.createScaledBitmap(it, 230, 150, true) }
                    } finally { retriever.release() }
                }
            }.getOrNull()?.asImageBitmap()
        }
    }
    val capture = clip.details.optJSONObject("capture")
    val look = capture?.optJSONObject("look")?.optString("look")?.takeIf { it.isNotBlank() }
    val seconds = clip.details.optLong("durationUs") / 1_000_000
    Row(Modifier.fillMaxWidth().clip(CardShape).clickable(onClick = open).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(108.dp, 74.dp).clip(InstrumentShape).background(Field)) {
            thumbnail?.let { Image(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
            if (seconds > 0) Hint("%02d:%02d".format(seconds / 60, seconds % 60), Paper,
                Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(InstrumentShape).background(Chrome).padding(4.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(clip.title, color = Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Hint(if (clip.details.optBoolean("verifiedTracks")) clip.summary else "Tracks unavailable")
            if (look != null) Hint(look, Amber, Modifier.clip(InstrumentShape).background(Selected).padding(4.dp))
        }
    }
}
