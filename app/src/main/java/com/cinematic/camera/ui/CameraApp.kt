package com.cinematic.camera.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.draw.clip
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
import com.cinematic.camera.color.AutoLookSelector
import com.cinematic.camera.color.Grade
import com.cinematic.camera.color.Look
import com.cinematic.camera.color.LookStorage
import com.cinematic.camera.color.SavedLook
import com.cinematic.camera.color.originalLooks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.log10

private val Context.preferences by preferencesDataStore("instrument")
private val proKey = booleanPreferencesKey("pro")
private val audioKey = booleanPreferencesKey("audio")
private val liteKey = booleanPreferencesKey("lite_preview")
private val favoriteKey = stringSetPreferencesKey("favorites")
private val leftKey = booleanPreferencesKey("left_handed")
private val quickLooks by lazy {
    val featured = listOf("Neutral", "Golden Hour", "Film Print", "Steel Blue")
    originalLooks.sortedBy { featured.indexOf(it.name).takeIf { index -> index >= 0 } ?: featured.size }
}
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
    val pro = prefs?.get(proKey) ?: false
    val left = prefs?.get(leftKey) ?: false
    val scope = rememberCoroutineScope()
    val audioEnabled = prefs?.get(audioKey) ?: true
    val liteEnabled = prefs?.get(liteKey) ?: false
    LaunchedEffect(audioEnabled) { engine.setAudio(audioEnabled) }
    LaunchedEffect(liteEnabled) { engine.setLite(liteEnabled) }
    fun setAudio(enabled: Boolean) {
        engine.setAudio(enabled)
        scope.launch { context.preferences.edit { it[audioKey] = enabled } }
    }
    val storage = remember { LookStorage(context) }
    var hasCamera by remember { mutableStateOf(context.granted(Manifest.permission.CAMERA)) }
    var drawer by rememberSaveable { mutableStateOf<Drawer?>(null) }

    var guide by rememberSaveable { mutableStateOf("Off") }
    var instrument by rememberSaveable { mutableStateOf(ScopeMode.OFF) }
    var zebraLevel by rememberSaveable { mutableIntStateOf(95) }
    var edgeSensitivity by rememberSaveable { mutableIntStateOf(50) }
    var largeScope by rememberSaveable { mutableStateOf(false) }
    var clearPreview by rememberSaveable { mutableStateOf(false) }
    var lab by rememberSaveable { mutableStateOf(false) }
    val library = drawer == Drawer.LOOKS || drawer == Drawer.MEDIA
    BackHandler(library || lab) { if (library) drawer = null else lab = false }
    LaunchedEffect(library) { engine.pausePreview(library) }
    fun selectMode(value: Boolean) { scope.launch { context.preferences.edit { it[proKey] = value } } }
    LaunchedEffect(instrument, lab, clearPreview, library) { engine.scopes(instrument != ScopeMode.OFF && !lab && !clearPreview && !library) }
    var compare by remember { mutableStateOf(false) }
    var split by rememberSaveable { mutableStateOf(true) }
    var grade by remember { mutableStateOf(Grade()) }
    var autoLook by rememberSaveable { mutableStateOf(false) }
    var autoReason by remember { mutableStateOf<String?>(null) }
    val autoPicker = remember { AutoLookSelector() }
    val autoPixels by engine.scopeSamples.collectAsStateWithLifecycle()
    LaunchedEffect(autoLook, lab, library, state.active) {
        engine.autoLookSampling(autoLook && !lab && !library && !state.active)
    }
    LaunchedEffect(lab) {
        if (lab && autoLook) {
            autoLook = false
            autoReason = null
            autoPicker.reset()
        }
    }
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
    fun toggleAutoLook() {
        if (state.active || state.photoMode) return
        autoLook = !autoLook
        autoReason = if (autoLook) "Analyzing scene color and light…" else null
        autoPicker.reset()
        if (autoLook && state.pipeline == Pipeline.DIRECT) engine.configure(pipeline = Pipeline.CLEAN)
    }
    fun choose(value: Look) {
        if (state.photoMode) engine.photoMode(false)
        autoLook = false
        autoReason = null
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
                    autoLook = false
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
    LaunchedEffect(autoPixels, autoLook, state.active, state.pipeline, lab, library) {
        if (!autoLook || state.active || lab || library || state.pipeline == Pipeline.DIRECT) return@LaunchedEffect
        val pixels = autoPixels ?: return@LaunchedEffect
        if (pixels.isEmpty()) {
            autoReason = "Scene analysis unavailable on this camera"
            return@LaunchedEffect
        }
        val recommendation = autoPicker.consider(pixels, SystemClock.elapsedRealtime()) ?: return@LaunchedEffect
        val selected = originalLooks.firstOrNull { it.name == recommendation.name } ?: return@LaunchedEffect
        if (grade.look.name != selected.name || grade.strength != recommendation.strength) {
            grade = Grade(look = selected, strength = recommendation.strength)
            currentSaved = null
        }
        autoReason = recommendation.reason
    }
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
    fun selectCaptureMode(photo: Boolean) {
        if (state.active || state.photoMode == photo) return
        autoLook = false
        autoReason = null
        autoPicker.reset()
        compare = false
        lab = false
        engine.photoMode(photo)
    }
    fun record() {
        if (state.busy == Take.RECORDING) {
            engine.stop()
            return
        }
        val permissions = buildList {
            if (!state.photoMode && state.audio && !context.granted(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT < 29 && !context.granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (permissions.isEmpty()) engine.start(grade)
        else recordPermissions.launch(permissions.toTypedArray())
    }
    fun applySaved(item: SavedLook) {
        if (state.photoMode) engine.photoMode(false)
        autoLook = false
        autoReason = null
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
            setAudio(configuration.optBoolean("audio", true))
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

    fun quickPreset(choice: StoryPreset) {
        if (state.photoMode) engine.photoMode(false)
        autoLook = false
        autoReason = null
        val format = state.formats.firstOrNull { it.label == "1080p" }
            ?: state.formats.firstOrNull { it.label == "720p" } ?: state.format
        val rate = choice.fps?.takeIf { it in format?.rates.orEmpty() }
        val look = originalLooks.first { it.name == choice.look }
        engine.configure(format = format, fps = rate, pipeline = if (look.cube == null) Pipeline.DIRECT else Pipeline.CLEAN)
        grade = Grade(look = look)
        currentSaved = null
        guide = choice.guide
        drawer = null
        if (choice.fps != null && rate == null) engine.tell("${choice.name} requested ${choice.fps} fps. This lens and resolution use Auto.")
    }

    val autoStatus = if (autoLook) when {
        state.pipeline == Pipeline.DIRECT -> "Auto Look paused · Direct capture"
        state.active -> "Auto Look locked · ${grade.look.name}"
        else -> "Auto Look · ${grade.look.name} · ${autoReason ?: "Analyzing scene…"}"
    } else null
    val notice = state.warning ?: if (state.audioIssue && state.active) "Microphone reported an error." else state.message
    val live = remember {
        movableContentOf<Modifier, String, Boolean, () -> Unit> { modifier, frame, available, permission ->
            Viewfinder(engine, view, modifier, frame, available, permission)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Ink).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val landscape = maxWidth > maxHeight && maxWidth >= 600.dp
        SideEffect {
            view.scaleType = if (!landscape && (!pro || lab)) PreviewView.ScaleType.FILL_CENTER else PreviewView.ScaleType.FIT_CENTER
        }
        val liveContent: @Composable (Modifier) -> Unit = { modifier ->
                Box(modifier.background(Color.Black)) {
                    live(Modifier.fillMaxSize(), if (clearPreview && !lab) "Off" else guide, hasCamera) { cameraPermission.launch(Manifest.permission.CAMERA) }
                    if (lab && split && hasCamera && !state.gpuFailed) {
                        Box(Modifier.align(Alignment.Center).fillMaxHeight().width(1.dp).background(Paper.copy(alpha = .68f)))
                        Hint("Before", Paper, Modifier.align(Alignment.BottomStart).padding(10.dp).background(Chrome).padding(5.dp))
                        Hint("After", Paper, Modifier.align(Alignment.BottomEnd).padding(10.dp).background(Chrome).padding(5.dp))
                    }
                    if (state.busy == Take.RECORDING) {
                        Text("● REC ${state.timecode}", Modifier.align(Alignment.TopCenter).padding(top = if (!pro && !landscape && !lab) 64.dp else 8.dp)
                            .background(Ink.copy(alpha = .84f), InstrumentShape).padding(8.dp), color = Record, style = Timecode)
                    }
                    if (!lab && !clearPreview && instrument != ScopeMode.OFF && state.pipeline != Pipeline.DIRECT) {
                        ScopeOverlay(engine, instrument, Modifier.align(Alignment.TopEnd).padding(8.dp), zebraLevel, edgeSensitivity, largeScope)
                    }
                    if (!lab && (pro || landscape) && state.pipeline != Pipeline.DIRECT && hasCamera && !state.active) {
                        CompareKey(compare, Modifier.align(Alignment.BottomEnd).padding(8.dp)) { compare = it }
                    }
                }
            }
        if (library) {
            val panel = checkNotNull(drawer)
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 58.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconKey(ToolIcon.BACK, "Back to camera") { drawer = null }
                    Text(panel.label, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                }
                LibraryBody(panel, Modifier.weight(1f), engine, state.clipRevision, ::exportFile) {
                    LooksPanel(originalLooks, saved, favoriteNames, importing,
                        choose = { choose(it); drawer = null }, apply = ::applySaved,
                        favorite = { name -> scope.launch { context.preferences.edit { settings ->
                            val existing = settings[favoriteKey].orEmpty()
                            settings[favoriteKey] = if (name in existing) existing - name else existing + name
                        } } },
                        import = { import.launch(arrayOf("*/*")) }, quick = ::quickPreset,
                        save = { preset -> saveName = if (preset) "" else currentSaved?.name.orEmpty(); saveAs = preset },
                        export = { item -> exportFile("${item.name}.json", storage.encode(item)) })
                }
                LibraryNav(panel, onCamera = { drawer = null; lab = false },
                    onLab = {
                        if (state.photoMode) engine.photoMode(false)
                        drawer = null
                        if (state.pipeline == Pipeline.DIRECT) engine.configure(pipeline = Pipeline.CLEAN)
                        lab = true
                    },
                    onLooks = { drawer = Drawer.LOOKS }, onMedia = { drawer = Drawer.MEDIA })
            }
        } else if (!pro && !lab && !landscape) {
            SimpleCapture(state, grade.look, quickLooks, notice, guide != "Off", Modifier.fillMaxSize(),
                ::selectMode, { drawer = Drawer.SETTINGS }, { drawer = Drawer.MONITOR }, { drawer = Drawer.EXPOSURE },
                { drawer = Drawer.LOOKS }, ::choose, { drawer = Drawer.MEDIA }, ::record, engine::flip, ::selectCaptureMode,
                autoLook, autoStatus, ::toggleAutoLook, compareControl = {
                    if (state.pipeline != Pipeline.DIRECT && hasCamera && !state.active) {
                        CompareKey(compare, Modifier) { compare = it }
                    }
                }, preview = liveContent)
        } else {
            val margin = if (landscape) 12.dp else 16.dp
            Column(Modifier.fillMaxSize().padding(horizontal = margin), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!landscape || lab || !pro) Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconKey(if (lab) ToolIcon.BACK else ToolIcon.SETTINGS, if (lab) "Back to camera" else "Settings",
                        enabled = !state.active) { if (lab) lab = false else drawer = Drawer.SETTINGS }
                    if (lab) Text("Look Lab", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    else {
                        Box(Modifier.weight(1f)) { ModeSwitch(pro, !state.active, ::selectMode) }
                        Key(if (state.photoMode) (if (state.rawPhoto) "DNG" else "JPEG") else "${state.format?.label ?: "—"} · ${state.fps ?: "Auto"}", enabled = !state.active) {
                            drawer = Drawer.OUTPUT
                        }
                    }
                }
                if (!lab) CaptureModeSwitch(state, ::selectCaptureMode)
                if (pro && !lab) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .clip(InstrumentShape).background(Panel),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (landscape) {
                            IconKey(ToolIcon.SETTINGS, "Settings", enabled = !state.active) { drawer = Drawer.SETTINGS }
                            Spacer(Modifier.width(4.dp))
                        }
                        Telemetry("RES", if (state.photoMode) (if (state.rawPhoto) "DNG" else "JPEG") else state.format?.label ?: "—", !state.active) { drawer = Drawer.OUTPUT }
                        Telemetry("FPS", if (state.photoMode) "Still" else state.fps?.let { "$it target" } ?: "Auto", !state.active && !state.photoMode) { drawer = Drawer.OUTPUT }
                        Telemetry("CODEC", if (state.photoMode) (if (state.rawPhoto) "RAW" else "JPEG") else "AVC target", !state.active) { drawer = Drawer.OUTPUT }
                        Telemetry("LUT", if (state.photoMode) "Off" else grade.look.name, !state.active && !state.photoMode) { drawer = Drawer.LOOKS }
                        Telemetry("LENS", state.lens?.label ?: "—", !state.active) { drawer = Drawer.LENS }
                        Telemetry("STAB", if (state.stabilization && !state.photoMode) "On" else "Off", !state.active && !state.photoMode) { drawer = Drawer.OUTPUT }
                        if (landscape) {
                            ModeSwitch(pro, !state.active, ::selectMode)
                            Key("Auto Look", selected = autoLook, enabled = !state.active && !state.photoMode, action = ::toggleAutoLook)
                        }
                    }
                }
                val dock: @Composable (Boolean) -> Unit = { vertical ->
                    CaptureDock(state, vertical, onRecord = ::record, onLibrary = { drawer = Drawer.MEDIA },
                        onFlip = engine::flip, onTools = { drawer = Drawer.TOOLS })
                }
                if (landscape) {
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (left && !lab) dock(true)
                        if (!lab) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconKey(ToolIcon.SCOPE, "Monitoring", selected = instrument != ScopeMode.OFF) { drawer = Drawer.MONITOR }
                            IconKey(ToolIcon.GRID, "Frame guides", selected = guide != "Off") { drawer = Drawer.MONITOR }
                            IconKey(ToolIcon.LOOK, "Look Lab", enabled = !state.active && !state.photoMode) {
                                if (state.pipeline == Pipeline.DIRECT) engine.configure(pipeline = Pipeline.CLEAN)
                                lab = true
                            }
                            IconKey(ToolIcon.FLIP, "Switch camera", enabled = !state.active && state.lenses.size > 1,
                                action = engine::flip)
                        }
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            liveContent(Modifier.weight(1f).fillMaxWidth().clip(InstrumentShape))
                            if (pro && !lab) ExposureStrip(state) { drawer = it }
                        }
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
                    liveContent(Modifier.weight(if (lab) 1f else 1.7f).fillMaxWidth().clip(CardShape))
                    if (pro && !lab) ExposureStrip(state) { drawer = it }
                    if (lab) Box(Modifier.weight(.9f).fillMaxWidth()) {
                        LookLab(grade, split, history.isNotEmpty(), undone.isNotEmpty(), state.lite,
                            change = { value, checkpoint -> edit(value, checkpoint) }, onSplit = { split = !split },
                            undo = { undone = undone + grade; grade = history.last(); history = history.dropLast(1) },
                            redo = { history = history + grade; grade = undone.last(); undone = undone.dropLast(1) },
                            save = { saveName = currentSaved?.name.orEmpty(); saveAs = false }, looks = { drawer = Drawer.LOOKS })
                    }
                }
                if (!lab) {
                    CaptureStatus(state, notice ?: autoStatus)
                    if (!pro && !state.photoMode) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            Key("Auto Look", selected = autoLook, enabled = !state.active && !state.photoMode, action = ::toggleAutoLook)
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
                    if (!landscape && !state.photoMode) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Key("Auto Look", selected = autoLook, enabled = !state.active && !state.photoMode, action = ::toggleAutoLook)
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
    }

    val deviceReport = { diagnostics(state) }
    drawer?.takeUnless { library }?.let { panel ->
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
                    Drawer.AUDIO -> AudioPanel(state, ::setAudio)
                    Drawer.TOOLS -> QuickControls { drawer = it }
                    Drawer.MONITOR -> {
                        MonitorPanel(guide, { guide = it }, state, instrument, { instrument = it })
                        Section("Viewfinder layout")
                        ToggleRow("Clear viewfinder", clearPreview) { clearPreview = it }
                        Hint("Hides guides and sampled scopes, not the recording indicator or camera controls.")
                        ToggleRow("Large scope", largeScope) { largeScope = it }
                        if (instrument == ScopeMode.ZEBRA) {
                            Precision("Clipping threshold", zebraLevel.toFloat(), 70f..100f, unit = "%") { zebraLevel = it.toInt() }
                        }
                        if (instrument == ScopeMode.PEAKING) {
                            Precision("Edge sensitivity", edgeSensitivity.toFloat(), 0f..100f, unit = "%") { edgeSensitivity = it.toInt() }
                        }
                    }
                    Drawer.SETTINGS -> {
                        Key(if (state.photoMode) "Photo format" else "Recording format", Modifier.fillMaxWidth()) { drawer = Drawer.OUTPUT }
                        Key("Microphone", Modifier.fillMaxWidth()) { drawer = Drawer.AUDIO }
                        ToggleRow("Left-handed landscape", left, !state.active) {
                            scope.launch { context.preferences.edit { prefs -> prefs[leftKey] = it } }
                        }
                        ToggleRow("Lite preview", state.lite, !state.active) { enabled ->
                            engine.setLite(enabled)
                            scope.launch { context.preferences.edit { it[liteKey] = enabled } }
                        }
                        Hint("Lite skips preview bloom and halation. Baked capture uses the full selected grade.")
                        Hint("Auto Look analyzes local color and light to pick a look before recording. A look stays fixed during each take.")
                        Key("Export device report") { exportFile("cinematic-device.json", deviceReport().toString(2)) }
                        Text(deviceReport().toString(2), style = Timecode, color = Secondary)
                    }
                    Drawer.LOOKS, Drawer.MEDIA -> Unit
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
    var focusTarget by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(focusTarget) {
        if (focusTarget != null) {
            delay(950)
            focusTarget = null
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (available) {
            AndroidView(factory = {
                var tapped: Offset? = null
                target.setOnClickListener {
                    val point = tapped ?: Offset(target.width / 2f, target.height / 2f)
                    focusTarget = point
                    engine.focus(point.x, point.y, false)
                }
                val detector = android.view.GestureDetector(it, object : android.view.GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent) = true
                    override fun onSingleTapUp(e: MotionEvent): Boolean {
                        tapped = Offset(e.x, e.y)
                        target.performClick()
                        tapped = null
                        return true
                    }
                    override fun onLongPress(e: MotionEvent) {
                        focusTarget = Offset(e.x, e.y)
                        engine.focus(e.x, e.y, true)
                    }
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
            val point = focusTarget
            if (point != null) Canvas(Modifier.fillMaxSize()) {
                val radius = 21.dp.toPx()
                val corner = 8.dp.toPx()
                for (vertical in listOf(-1, 1)) for (horizontal in listOf(-1, 1)) {
                    val start = Offset(point.x + horizontal * radius, point.y + vertical * radius)
                    drawLine(Amber, start, start - Offset(horizontal * corner, 0f), 1.5.dp.toPx())
                    drawLine(Amber, start, start - Offset(0f, vertical * corner), 1.5.dp.toPx())
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
        RecordKey(state.busy, state.busy == Take.RECORDING || (state.busy == Take.IDLE && state.ready), onRecord, state.photoMode)
        if (!vertical) IconKey(ToolIcon.FLIP, "Switch camera", enabled = !state.active && state.lenses.size > 1, action = onFlip)
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
            Hint(if (state.photoMode) "PHOTO · ${if (state.rawPhoto) "DNG" else "JPEG"}" else state.pipeline.label, if (state.pipeline == Pipeline.BAKED) Amber else Info)
            Hint(if (state.photoMode) "${state.bytes / (1024 * 1024)} MB free" else "~${state.remainingMinutes} min free")
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
        }.compareAction(if (compare) "Restore look" else "Compare with original") { action(!compare) }, contentAlignment = Alignment.Center) {
        Hint(if (compare) "Before" else "A/B", if (compare) Amber else Paper)
    }
}

@Composable
private fun LibraryBody(panel: Drawer, modifier: Modifier, engine: CameraEngine, revision: Int,
                        export: (String, String) -> Unit, looks: @Composable () -> Unit) {
    if (panel == Drawer.MEDIA) MediaPanel(engine, revision, export, modifier)
    else Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) { looks() }
}
