package com.cinematic.camera.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cinematic.camera.capture.CameraEngine
import com.cinematic.camera.capture.CaptureState
import com.cinematic.camera.capture.Pipeline
import com.cinematic.camera.capture.Take
import com.cinematic.camera.color.Cube
import com.cinematic.camera.color.Grade
import com.cinematic.camera.color.Look
import com.cinematic.camera.color.LookStorage
import com.cinematic.camera.color.SavedLook
import com.cinematic.camera.color.originalLooks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.log10

private val Context.preferences by preferencesDataStore("instrument")
private val proKey = booleanPreferencesKey("pro")
private val favoriteKey = stringSetPreferencesKey("favorites")
private val leftKey = booleanPreferencesKey("left_handed")
internal enum class Drawer(val label: String) {
    OUTPUT("Output"), EXPOSURE("Exposure"), FOCUS("Focus"), WB("White balance"),
    LENS("Lens"), AUDIO("Audio"), TOOLS("Manual controls"), LOOKS("Looks & presets"),
    MEDIA("Media library"), SETTINGS("Settings & diagnostics"), MONITOR("Monitoring")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraApp(engine: CameraEngine) {
    val context = LocalContext.current
    val state by engine.state.collectAsStateWithLifecycle()
    val prefs by context.preferences.data.collectAsStateWithLifecycle(initialValue = null)
    val pro = prefs?.get(proKey) ?: true
    val left = prefs?.get(leftKey) ?: false
    val scope = rememberCoroutineScope()
    val storage = remember { LookStorage(context) }
    var hasCamera by remember { mutableStateOf(context.granted(Manifest.permission.CAMERA)) }
    var drawer by rememberSaveable { mutableStateOf<Drawer?>(null) }
    var guide by rememberSaveable { mutableStateOf("Off") }
    var instrument by rememberSaveable { mutableStateOf(ScopeMode.OFF) }
    var lab by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(instrument, lab) { engine.scopes(instrument != ScopeMode.OFF && !lab) }
    var compare by remember { mutableStateOf(false) }
    var split by rememberSaveable { mutableStateOf(true) }
    var grade by remember { mutableStateOf(Grade()) }
    var saved by remember { mutableStateOf<List<SavedLook>>(emptyList()) }
    val favoriteNames = prefs?.get(favoriteKey).orEmpty()
    var history by remember { mutableStateOf(listOf<Grade>()) }
    var undone by remember { mutableStateOf(listOf<Grade>()) }
    var saveAs by remember { mutableStateOf<Boolean?>(null) }
    var saveName by remember { mutableStateOf("") }
    var currentSaved by remember { mutableStateOf<SavedLook?>(null) }
    var exportText by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCamera = it
    }
    val recordPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) engine.start(grade)
        else engine.tell("Permission denied. Microphone audio can be turned off in Settings.")
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            val content = exportText
            scope.launch {
                runCatching { withContext(Dispatchers.IO) {
                    checkNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(content) }
                } }.onSuccess { engine.tell("File exported.") }
                    .onFailure { engine.tell("Export failed. ${it.localizedMessage.orEmpty()}") }
            }
        }
    }
    fun exportFile(name: String, content: String) {
        exportText = content
        export.launch(name)
    }
    fun edit(value: Grade, checkpoint: Boolean = true) {
        if (state.active) return
        if (checkpoint && grade != value) {
            history = (history.takeLast(39) + grade)
            undone = emptyList()
        }
        grade = value
        if (state.pipeline == Pipeline.DIRECT && value != Grade()) engine.configure(pipeline = Pipeline.CLEAN)
    }
    fun choose(value: Look) {
        currentSaved = null
        if (value in originalLooks) edit(Grade(look = value))
        else edit(grade.copy(look = value))
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true
            scope.launch {
                runCatching { withContext(Dispatchers.IO) {
                    val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                        null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Imported"
                    val reader = checkNotNull(context.contentResolver.openInputStream(uri)).bufferedReader()
                    reader.use {
                        if (name.endsWith(".json", true)) {
                            val buffer = CharArray(8192)
                            val raw = StringBuilder()
                            while (true) {
                                val count = it.read(buffer)
                                if (count < 0) break
                                require(raw.length + count <= 5_500_000) { "Preset exceeds the import limit." }
                                raw.append(buffer, 0, count)
                            }
                            val decoded = storage.decode("", raw.toString())
                            storage.save(decoded.name, decoded.grade, decoded.capture)
                        } else {
                            val title = name.substringBeforeLast('.').trim().take(64).ifEmpty { "Imported cube" }
                            storage.save(title, Grade(look = Look(title, "Imported", Cube.parse(it))), null)
                        }
                    }
                } }.onSuccess {
                    saved = listOf(it) + saved
                    currentSaved = it
                    edit(it.grade)
                    engine.tell("Imported ${it.name}.")
                }.onFailure { engine.tell("Import failed. ${it.localizedMessage.orEmpty()}") }
                importing = false
            }
        }
    }
    LaunchedEffect(Unit) { saved = withContext(Dispatchers.IO) { storage.all() } }
    LaunchedEffect(grade, compare, split, lab, state.busy) { engine.render(grade, compare, split && lab) }
    val orientation = LocalConfiguration.current.orientation
    val view = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    LaunchedEffect(orientation, state.busy) {
        view.post { view.display?.rotation?.let(engine::orientation) }
    }
    fun record() {
        if (state.busy == Take.RECORDING) {
            engine.stop()
            return
        }
        val permissions = buildList {
            if (state.audio && !context.granted(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT < 29 && !context.granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (permissions.isEmpty()) engine.start(grade)
        else recordPermissions.launch(permissions.toTypedArray())
    }
    fun applySaved(item: SavedLook) {
        val configuration = item.capture
        val skipped = mutableListOf<String>()
        if (configuration != null) {
            val format = state.formats.firstOrNull { it.label == configuration.optString("resolution") }
            val fps = configuration.optInt("fps").takeIf { it > 0 }
            val bitrate = configuration.optInt("bitrate").takeIf { it in 4..32 }
            val pipeline = Pipeline.entries.firstOrNull { it.name == configuration.optString("pipeline") }
            val wantedStabilization = configuration.optBoolean("stabilization")
            val supportedFps = fps?.takeIf { it in (format ?: state.format)?.rates.orEmpty() }
            if (format == null) skipped.add("resolution")
            if (fps != null && supportedFps == null) skipped.add("frame rate")
            if (bitrate == null) skipped.add("bitrate")
            if (pipeline == null) skipped.add("output mode")
            if (wantedStabilization && !state.controls.eis) skipped.add("stabilization")
            engine.configure(format ?: state.format, supportedFps, bitrate ?: state.bitrate,
                pipeline ?: state.pipeline, wantedStabilization && state.controls.eis)
            engine.setAudio(configuration.optBoolean("audio", true))
            guide = configuration.optString("guide", guide).takeIf { it in guides } ?: guide
            if (grade != item.grade) {
                history = history.takeLast(39) + grade
                undone = emptyList()
                grade = item.grade
            }
        } else edit(item.grade)
        currentSaved = item
        drawer = null
        if (skipped.isNotEmpty()) engine.tell("Preset adapted. Skipped ${skipped.joinToString()}.")
    }

    val notice = state.warning ?: if (state.audioIssue && state.active) "Microphone reported an error." else state.message
    val live = remember {
        movableContentOf<Modifier, String, Boolean, () -> Unit> { modifier, frame, available, permission ->
            Viewfinder(engine, view, modifier, frame, available, permission)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Ink).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val landscape = maxWidth > maxHeight && maxWidth >= 600.dp
        val margin = if (landscape) 12.dp else 16.dp
        Column(Modifier.fillMaxSize().padding(horizontal = margin), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconKey(if (lab) ToolIcon.BACK else ToolIcon.SETTINGS, if (lab) "Back to camera" else "Settings",
                    enabled = !state.active) { if (lab) lab = false else drawer = Drawer.SETTINGS }
                if (lab) Text("Look Lab", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                else {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Key("Simple", selected = !pro) { scope.launch { context.preferences.edit { it[proKey] = false } } }
                        Key("Pro", selected = pro) { scope.launch { context.preferences.edit { it[proKey] = true } } }
                    }
                    Key("${state.format?.label ?: "—"} · ${state.fps ?: "Auto"}", enabled = !state.active) {
                        drawer = Drawer.OUTPUT
                    }
                }
            }
            if (pro && !lab) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(Panel),
                    verticalAlignment = Alignment.CenterVertically) {
                    Telemetry("RES", state.format?.label ?: "—", !state.active) { drawer = Drawer.OUTPUT }
                    Telemetry("FPS", state.fps?.let { "$it target" } ?: "Auto", !state.active) { drawer = Drawer.OUTPUT }
                    Telemetry("SHUTTER", state.sensor.shutter.shutterLabel()) { drawer = Drawer.EXPOSURE }
                    Telemetry("ISO", state.sensor.iso?.toString() ?: "—") { drawer = Drawer.EXPOSURE }
                    Telemetry("FOCUS", state.sensor.focus?.let { "%.2f D".format(it) } ?: "—") { drawer = Drawer.FOCUS }
                    Telemetry("WB", wbLabel(state.sensor.awb)) { drawer = Drawer.WB }
                    Telemetry("LENS", state.lens?.label ?: "—", !state.active) { drawer = Drawer.LENS }
                    Telemetry("CODEC", "AVC target", !state.active) { drawer = Drawer.OUTPUT }
                }
            }
            val dock: @Composable (Boolean) -> Unit = { vertical ->
                CaptureDock(state, vertical, onRecord = ::record, onLibrary = { drawer = Drawer.MEDIA },
                    onFlip = engine::flip, onTools = { drawer = Drawer.TOOLS })
            }
            val liveContent: @Composable (Modifier) -> Unit = { modifier ->
                Box(modifier.background(Color.Black)) {
                    live(Modifier.fillMaxSize(), guide, hasCamera) { cameraPermission.launch(Manifest.permission.CAMERA) }
                    if (state.busy == Take.RECORDING) {
                        Text("● REC ${state.timecode}", Modifier.align(Alignment.TopStart).padding(8.dp)
                            .background(Ink.copy(alpha = .84f), InstrumentShape).padding(8.dp), color = Record, style = Timecode)
                    }
                    if (!lab && instrument != ScopeMode.OFF && state.pipeline != Pipeline.DIRECT) {
                        ScopeOverlay(engine, instrument, Modifier.align(Alignment.TopEnd).padding(8.dp))
                    }
                    if (!lab && state.pipeline != Pipeline.DIRECT && hasCamera && !state.active) {
                        CompareKey(compare, Modifier.align(Alignment.BottomEnd).padding(8.dp)) { compare = it }
                    }
                }
            }
            if (landscape) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (left && !lab) dock(true)
                    if (!lab) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconKey(ToolIcon.GRID, "Frame guides and scopes", selected = guide != "Off") { drawer = Drawer.MONITOR }
                        IconKey(ToolIcon.LOOK, "Look Lab", enabled = !state.active) {
                            if (state.pipeline == Pipeline.DIRECT) engine.configure(pipeline = Pipeline.CLEAN)
                            lab = true
                        }
                    }
                    liveContent(Modifier.weight(1f).fillMaxHeight())
                    if (!left && !lab) dock(true)
                    if (lab) Box(Modifier.widthIn(max = 340.dp).fillMaxHeight()) {
                        LookLab(grade, split, history.isNotEmpty(), undone.isNotEmpty(), state.lite,
                            change = { value, checkpoint -> edit(value, checkpoint) }, onSplit = { split = !split },
                            undo = { undone = undone + grade; grade = history.last(); history = history.dropLast(1) },
                            redo = { history = history + grade; grade = undone.last(); undone = undone.dropLast(1) },
                            save = { saveName = currentSaved?.name.orEmpty(); saveAs = false }, looks = { drawer = Drawer.LOOKS })
                    }
                }
            } else {
                liveContent(Modifier.weight(1f).fillMaxWidth())
                if (lab) Box(Modifier.weight(.9f).fillMaxWidth()) {
                    LookLab(grade, split, history.isNotEmpty(), undone.isNotEmpty(), state.lite,
                        change = { value, checkpoint -> edit(value, checkpoint) }, onSplit = { split = !split },
                        undo = { undone = undone + grade; grade = history.last(); history = history.dropLast(1) },
                        redo = { history = history + grade; grade = undone.last(); undone = undone.dropLast(1) },
                        save = { saveName = currentSaved?.name.orEmpty(); saveAs = false }, looks = { drawer = Drawer.LOOKS })
                }
            }
            if (!lab) {
                CaptureStatus(state, notice)
                if (!pro) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Key("Looks", enabled = !state.active) { drawer = Drawer.LOOKS }
                    }
                    items(originalLooks, key = { it.name }) { look ->
                        Column(Modifier.width(88.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            LookSwatch(look, Modifier.fillMaxWidth().height(28.dp))
                            Key(look.name, Modifier.fillMaxWidth(), selected = grade.look === look,
                                enabled = !state.active) { choose(look) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Key(grade.look.name, enabled = !state.active) { drawer = Drawer.LOOKS }
                    Key("Look Lab", enabled = !state.active) {
                        if (state.pipeline == Pipeline.DIRECT) engine.configure(pipeline = Pipeline.CLEAN)
                        lab = true
                    }
                    Key("Guides", selected = guide != "Off") { drawer = Drawer.MONITOR }
                    if (!pro) Key("Exposure") { drawer = Drawer.EXPOSURE }
                }
                if (!landscape) dock(false)
            } else if (notice != null) Hint(notice, Amber)
        }
    }

    val deviceReport = { diagnostics(state) }
    drawer?.let { panel ->
        ModalBottomSheet(onDismissRequest = { drawer = null }, containerColor = Raised,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .72f).dp)
                .verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(panel.label, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    Key("Close") { drawer = null }
                }
                state.message?.let { Hint(it, Amber) }
                when (panel) {
                    Drawer.OUTPUT -> OutputPanel(state, engine)
                    Drawer.EXPOSURE -> ExposurePanel(state, engine) { drawer = null; lab = true }
                    Drawer.FOCUS -> FocusPanel(state, engine)
                    Drawer.WB -> WhiteBalancePanel(state, engine) { drawer = null; lab = true }
                    Drawer.LENS -> LensPanel(state, engine)
                    Drawer.AUDIO -> AudioPanel(state, engine)
                    Drawer.TOOLS -> {
                        listOf(Drawer.EXPOSURE, Drawer.FOCUS, Drawer.WB, Drawer.LENS, Drawer.AUDIO, Drawer.OUTPUT, Drawer.MONITOR)
                            .forEach { target -> Key(target.label, Modifier.fillMaxWidth()) { drawer = target } }
                    }
                    Drawer.MONITOR -> MonitorPanel(guide, { guide = it }, state, instrument, { instrument = it })
                    Drawer.SETTINGS -> {
                        Key("Recording format", Modifier.fillMaxWidth()) { drawer = Drawer.OUTPUT }
                        Key("Microphone", Modifier.fillMaxWidth()) { drawer = Drawer.AUDIO }
                        ToggleRow("Left-handed landscape", left, !state.active) {
                            scope.launch { context.preferences.edit { prefs -> prefs[leftKey] = it } }
                        }
                        ToggleRow("Lite preview", state.lite, !state.active, engine::setLite)
                        Hint("Lite skips preview bloom and halation. Baked capture uses the full selected grade.")
                        Key("Export device report") { exportFile("cinematic-device.json", deviceReport().toString(2)) }
                        Text(deviceReport().toString(2), style = Timecode, color = Secondary)
                    }
                    Drawer.LOOKS -> LooksPanel(originalLooks, saved, favoriteNames, importing,
                        choose = { choose(it); drawer = null }, apply = ::applySaved,
                        favorite = { name -> scope.launch { context.preferences.edit { settings ->
                            val existing = settings[favoriteKey].orEmpty()
                            settings[favoriteKey] = if (name in existing) existing - name else existing + name
                        } } },
                        import = { import.launch(arrayOf("*/*")) },
                        save = { preset -> saveName = if (preset) "" else currentSaved?.name.orEmpty(); saveAs = preset },
                        export = { item -> exportFile("${item.name}.json", storage.encode(item)) })
                    Drawer.MEDIA -> MediaPanel(engine, state.clipRevision, ::exportFile)
                }
            }
        }
    }
    saveAs?.let { preset ->
        AlertDialog(onDismissRequest = { saveAs = null },
            title = { Text(if (preset) "Save capture preset" else "Save look") },
            text = {
                OutlinedTextField(saveName, { saveName = it.take(64) }, label = { Text("Name") }, singleLine = true)
            },
            confirmButton = {
                TextButton(enabled = saveName.isNotBlank(), onClick = {
                    val name = saveName.trim()
                    val value = grade
                    val config = if (preset) JSONObject().apply {
                        put("resolution", state.format?.label)
                        put("fps", state.fps ?: 0)
                        put("bitrate", state.bitrate)
                        put("pipeline", state.pipeline.name)
                        put("audio", state.audio)
                        put("guide", guide)
                        put("stabilization", state.stabilization)
                    } else null
                    val replace = currentSaved?.takeIf { !preset && it.capture == null && it.name == name }
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) {
                            if (replace != null) storage.save(name, value, config, replace.id)
                            else storage.save(name, value, config)
                        } }.onSuccess {
                            saved = listOf(it) + saved.filterNot { old -> old.id == it.id }
                            if (!preset) currentSaved = it
                            engine.tell("Saved ${it.name}.")
                        }.onFailure { engine.tell("Save failed. ${it.localizedMessage.orEmpty()}") }
                    }
                    saveAs = null
                }) { Text("Save") }
            }, dismissButton = { TextButton(onClick = { saveAs = null }) { Text("Cancel") } })
    }
}

private fun Context.granted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

internal val guides = listOf("Off", "Thirds", "16:9", "9:16", "1:1", "2.39:1")

@Composable
private fun Viewfinder(engine: CameraEngine, target: PreviewView, modifier: Modifier, guide: String,
                       available: Boolean, request: () -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (available) {
            AndroidView(factory = {
                val detector = android.view.GestureDetector(it, object : android.view.GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent) = true
                    override fun onSingleTapUp(e: MotionEvent): Boolean {
                        target.performClick()
                        engine.focus(e.x, e.y, false)
                        return true
                    }
                    override fun onLongPress(e: MotionEvent) { engine.focus(e.x, e.y, true) }
                })
                target.contentDescription = "Live camera viewfinder"
                target.setOnTouchListener { _, event -> detector.onTouchEvent(event) }
                engine.attach(target)
                target
            }, modifier = Modifier.fillMaxSize())
            if (guide != "Off") Canvas(Modifier.fillMaxSize()) {
                val color = Color.White.copy(alpha = .65f)
                val stroke = 1.dp.toPx()
                if (guide == "Thirds") {
                    for (n in 1..2) {
                        val x = size.width * n / 3
                        val y = size.height * n / 3
                        drawLine(color, Offset(x, 0f), Offset(x, size.height), stroke)
                        drawLine(color, Offset(0f, y), Offset(size.width, y), stroke)
                    }
                } else {
                    val ratio = when (guide) { "16:9" -> 16f / 9; "9:16" -> 9f / 16; "2.39:1" -> 2.39f; else -> 1f }
                    val width = minOf(size.width, size.height * ratio) * .94f
                    val height = width / ratio
                    drawRect(color, Offset((size.width - width) / 2, (size.height - height) / 2),
                        androidx.compose.ui.geometry.Size(width, height), style = Stroke(stroke))
                }
            }
        } else Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Camera access", style = MaterialTheme.typography.titleMedium)
            Hint("Allow camera access to open the viewfinder. Microphone access is requested when you record.")
            Key("Allow camera", action = request)
        }
    }
}

@Composable
private fun CaptureDock(state: CaptureState, vertical: Boolean, onRecord: () -> Unit, onLibrary: () -> Unit,
                        onFlip: () -> Unit, onTools: () -> Unit) {
    val keys: @Composable () -> Unit = {
        IconKey(ToolIcon.LIBRARY, "Media library", enabled = !state.active, action = onLibrary)
        RecordKey(state.busy, state.busy == Take.RECORDING || (state.busy == Take.IDLE && state.ready), onRecord)
        IconKey(ToolIcon.FLIP, "Switch camera", enabled = !state.active && state.lenses.size > 1, action = onFlip)
        IconKey(ToolIcon.TOOLS, "Manual controls", action = onTools)
    }
    if (vertical) Column(Modifier.width(76.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally) { keys() }
    else Row(Modifier.fillMaxWidth().heightIn(min = 84.dp), horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically) { keys() }
}

@Composable
internal fun CaptureStatus(state: CaptureState, notice: String?) {
    Column(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Hint(state.pipeline.label, if (state.pipeline == Pipeline.BAKED) Amber else Info)
            Hint("~${state.remainingMinutes} min free")
        }
        val level = state.amplitude
        if (level != null) {
            val db = (20 * log10(level.coerceIn(.001, 1.0))).toInt()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint("MIC $db dBFS")
                LinearProgressIndicator(progress = { ((db + 60) / 60f).coerceIn(0f, 1f) },
                    modifier = Modifier.weight(1f), color = if (db >= -1) Record else Native, trackColor = Field)
            }
        }
        if (notice != null) Hint(notice, Amber)
    }
}

@Composable
private fun CompareKey(compare: Boolean, modifier: Modifier, toggle: (Boolean) -> Unit) {
    val action by rememberUpdatedState(toggle)
    Box(modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
        .background(Ink.copy(alpha = .84f), InstrumentShape)
        .pointerInput(Unit) {
            detectTapGestures(onPress = {
                action(true)
                try { tryAwaitRelease() } finally { action(false) }
            })
        }.then(compareSemantics(compare) { action(!compare) }), contentAlignment = Alignment.Center) {
        Hint(if (compare) "Before" else "A/B", if (compare) Amber else Paper)
    }
}

private fun compareSemantics(compare: Boolean, toggle: () -> Unit): Modifier =
    Modifier.compareAction(if (compare) "Restore look" else "Compare with original", toggle)
