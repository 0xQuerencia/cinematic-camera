package com.cinematic.camera.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cinematic.camera.capture.CameraEngine
import com.cinematic.camera.capture.ExposureMath
import com.cinematic.camera.capture.CaptureState
import com.cinematic.camera.capture.Pipeline
import com.cinematic.camera.capture.VideoProfile
import com.cinematic.camera.capture.Bracket
import com.cinematic.camera.color.Grade
import com.cinematic.camera.color.originalLooks
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
internal fun ToggleRow(label: String, value: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = change, enabled = enabled)
    }
}

@Composable
internal fun Mark(status: String, reason: String) {
    val color = when (status) { "Native" -> Native; "Assisted" -> Amber; "Unavailable" -> Unavailable; else -> Info }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Hint(if (status == "Unavailable") "╱ $status" else "● $status", color)
        Hint(reason)
    }
}

@Composable
internal fun KeysRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
internal fun Section(title: String) {
    HorizontalDivider(color = Rule)
    Text(title, style = MaterialTheme.typography.titleMedium)
}

@Composable
internal fun Precision(label: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean = true,
                       unit: String = "", immediate: ((Float) -> Unit)? = null, commit: (Float) -> Unit) {
    if (range.start >= range.endInclusive) return
    var scrubbing by remember { mutableStateOf(false) }
    var pending by remember { mutableFloatStateOf(value) }
    var numeric by remember { mutableStateOf(false) }
    var entry by remember { mutableStateOf("") }
    val shown = (if (scrubbing) pending else value).coerceIn(range)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Key("%.2f%s".format(shown, unit), enabled = enabled) {
            entry = "%.3f".format(java.util.Locale.ROOT, shown)
            numeric = true
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val step = (range.endInclusive - range.start) / 100
        Key("−", enabled = enabled) {
            val changed = (shown - step).coerceIn(range)
            immediate?.invoke(changed)
            commit(changed)
        }
        Slider(shown, {
            scrubbing = true
            pending = it
            immediate?.invoke(it)
        }, Modifier.weight(1f).heightIn(min = 48.dp), enabled = enabled, valueRange = range,
            onValueChangeFinished = { scrubbing = false; commit(pending) })
        Key("+", enabled = enabled) {
            val changed = (shown + step).coerceIn(range)
            immediate?.invoke(changed)
            commit(changed)
        }
    }
    if (numeric) AlertDialog(onDismissRequest = { numeric = false }, title = { Text(label) }, text = {
        OutlinedTextField(entry, { entry = it.take(24) }, label = { Text("Value $unit") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
    }, confirmButton = {
        val parsed = entry.replace(',', '.').toFloatOrNull()?.takeIf { it in range }
        TextButton(enabled = parsed != null, onClick = {
            parsed?.let { immediate?.invoke(it); commit(it) }
            numeric = false
        }) { Text("Apply") }
    }, dismissButton = { TextButton(onClick = { numeric = false }) { Text("Cancel") } })
}

@Composable
internal fun OutputPanel(state: CaptureState, engine: CameraEngine, photoGrade: Grade = Grade(), changePhotoGrade: (Grade) -> Unit = {}) {
    val enabled = !state.active
    if (state.photoMode) {
        Section("Still image format")
        KeysRow {
            Key("JPEG", selected = !state.rawPhoto, enabled = enabled) { engine.rawPhoto(false) }
            Key("RAW / DNG", selected = state.rawPhoto, enabled = enabled && state.controls.rawPhoto) { engine.rawPhoto(true) }
        }
        Hint(if (state.controls.rawPhoto) "RAW support is advertised by this camera; saved DNG must be tested." else "This camera does not advertise RAW still capture.")
        Section("Exposure bracketing")
        val range = state.controls.ev
        val bracketAvailable = range != null && state.manual.iso == null && !state.manual.aeLock
        KeysRow {
            listOf(0f, .7f, 1f, 2f).forEach { spacing ->
                val valid = spacing == 0f || (bracketAvailable && range != null &&
                    Bracket.indices(state.ev, state.controls.evStep, range.lower, range.upper, spacing).isNotEmpty())
                Key(if (spacing == 0f) "Single" else "3 × ±$spacing EV",
                    selected = state.bracketSpacing == spacing, enabled = enabled && valid) { engine.photoBracket(spacing) }
            }
        }
        Hint("Three separate exposures; no automatic HDR merge. Requires auto exposure and sufficient EV range.")
        Section("JPEG film look")
        if (state.rawPhoto) Hint("RAW is ungraded. Choose JPEG for film-look copies.")
        else {
            KeysRow {
                originalLooks.forEach { look ->
                    Column(Modifier.width(100.dp)) {
                        LookSwatch(look, Modifier.fillMaxWidth().height(48.dp))
                        Key(look.name, Modifier.fillMaxWidth(), selected = photoGrade.look.name == look.name, enabled = enabled) {
                            changePhotoGrade(Grade(look = look, strength = photoGrade.strength))
                        }
                    }
                }
            }
            if (photoGrade.look.cube != null) Precision("Look strength", photoGrade.strength * 100f, 0f..100f,
                enabled = enabled, unit = "%") { changePhotoGrade(photoGrade.copy(strength = it / 100f)) }
            Hint("Saves the original JPEG and a separate LUT-only sRGB copy (up to 8 MP for bounded memory). Live preview remains ungraded.")
        }
        Hint("Photos are saved to Pictures/Cinematic Camera.")
        return
    }
    Section("Codec & dynamic range")
    VideoProfile.entries.forEach { profile ->
        Key(profile.label, Modifier.fillMaxWidth(), selected = state.profile == profile,
            enabled = enabled && state.profileFormats[profile].orEmpty().isNotEmpty()) { engine.selectVideoProfile(profile) }
        if (state.profileFormats[profile].orEmpty().isEmpty()) Hint("Not advertised for this lens and encoder.")
    }
    Hint("These are capture requests. Clip details report the actual codec and color metadata.")
    if (state.profile.hdr) Hint("HLG is direct capture only. SDR LUTs are not applied to HDR footage.", Amber)
    Section("Resolution")
    KeysRow {
        state.formats.forEach { format ->
            Key(format.label, selected = format == state.format, enabled = enabled) {
                val fps = state.fps?.takeIf { it in format.rates }
                engine.configure(format = format, fps = fps)
                if (fps != state.fps) engine.tell("Target frame rate changed to Auto for the selected resolution.")
            }
        }
    }
    if (state.formats.isEmpty()) Hint("No SDR video sizes reported for this camera.")
    Section("Frame rate")
    KeysRow {
        Key("Auto", selected = state.fps == null, enabled = enabled) { engine.configure(fps = null) }
        state.format?.rates.orEmpty().forEach { fps ->
            Key("$fps fps", selected = state.fps == fps, enabled = enabled) { engine.configure(fps = fps) }
        }
    }
    Hint("The camera advertises these fixed rates for the selected size. The library measures average FPS from saved timestamps.")
    Section("Codec & bitrate")
    Hint("${state.profile.label} · MP4 target")
    Mark("Unavailable", "Genuine sensor LOG and RAW video are not available through this pipeline.")
    KeysRow {
        for (rate in listOf(4, 8, 12, 16, 24, 32)) {
            Key("$rate Mb/s", selected = state.bitrate == rate, enabled = enabled) { engine.configure(bitrate = rate) }
        }
    }
    Hint("Bitrate is a target. The encoder can clamp it to its supported range.")
    Section("Look output")
    Pipeline.entries.forEach { pipeline ->
        Key(pipeline.label, Modifier.fillMaxWidth(), selected = state.pipeline == pipeline,
            enabled = enabled && (!state.profile.hdr || pipeline == Pipeline.DIRECT)) { engine.configure(pipeline = pipeline) }
    }
    Hint("Clean saves the ungraded video with a look manifest. Baked sends the selected grade to the encoder. Direct bypasses GPU grading.")
    if (state.gpuFailed) Key("Retry color session", enabled = enabled) { engine.configure() }
    ToggleRow("Electronic video stabilization", state.stabilization, enabled && state.controls.eis) {
        engine.configure(stabilization = it)
    }
    Mark(when { !state.controls.eis -> "Unavailable"; state.sensor.eis == true -> "Native"; else -> "Checking" },
        if (state.controls.eis) "Camera-reported EIS. Enabling stabilization can crop the image."
        else "CameraX does not report video stabilization for this lens.")
}

@Composable
internal fun ExposurePanel(state: CaptureState, engine: CameraEngine, lab: () -> Unit) {
    val controls = state.controls
    val manual = state.manual
    val iso = controls.iso
    val shutter = controls.shutter
    Section("Motion angle")
    val motionAvailable = !state.photoMode && !state.active && state.fps != null &&
        iso != null && shutter != null && state.sensor.iso != null && state.sensor.shutter != null
    KeysRow {
        Key("Off", selected = state.motionAngle == null, enabled = !state.active && state.motionAngle != null) { engine.motion(null) }
        listOf(90.0, 172.8, 180.0, 270.0).forEach { angle ->
            Key("${angle.toString().removeSuffix(".0")}°", selected = state.motionAngle == angle,
                enabled = motionAvailable) { engine.motion(angle) }
        }
    }
    Hint("Locks the chosen shutter angle across frame-rate changes, matching ISO to the measured exposure when applied. Not continuous auto ISO.")
    Section("ISO & shutter")
    Text("ISO ${state.sensor.iso ?: "—"}  ·  ${state.sensor.shutter.shutterLabel()}", style = Numeric)
    if (iso != null && shutter != null) {
        val matches = manual.iso != null && state.sensor.iso == manual.iso &&
            state.sensor.shutter?.let { read -> manual.shutter?.let { kotlin.math.abs(read - it) <= maxOf(100_000, it / 20) } } == true
        Mark(if (matches) "Native" else "Checking", if (matches) "Capture results confirm the manual values."
            else "The camera advertises manual sensor control. Readouts come from capture results.")
        KeysRow {
            Key("Auto", selected = manual.iso == null) { engine.manual(manual.copy(iso = null, shutter = null)) }
            Key("Manual", selected = manual.iso != null, enabled = (state.photoMode || state.fps != null) && state.sensor.iso != null && state.sensor.shutter != null) {
                val frame = if (state.photoMode) shutter.upper else 1_000_000_000L / checkNotNull(state.fps)
                engine.manual(manual.copy(iso = iso.clamp(checkNotNull(state.sensor.iso)),
                    shutter = shutter.clamp(minOf(checkNotNull(state.sensor.shutter), frame)), aeLock = false))
            }
        }
        if (!state.photoMode && state.fps == null) Hint("Select a fixed frame rate before setting manual exposure.")
        if (manual.iso != null && manual.shutter != null) {
            Precision("Sensor ISO", manual.iso.toFloat(), iso.lower.toFloat()..iso.upper.toFloat()) {
                engine.manual(manual.copy(iso = it.roundToInt()))
            }
            val max = minOf(shutter.upper, state.fps?.takeUnless { state.photoMode }?.let { 1_000_000_000L / it } ?: shutter.upper)
            Precision("Exposure time", manual.shutter / 1_000_000f, shutter.lower / 1_000_000f..max / 1_000_000f,
                unit = " ms") { engine.manual(manual.copy(shutter = (it * 1_000_000).roundToLong())) }
            val fps = state.fps.takeUnless { state.photoMode }
            if (fps != null && max >= shutter.lower) {
                Section("Shutter angle")
                val actual = state.sensor.shutter?.let { ExposureMath.nanosecondsToAngle(it, fps) }
                Hint(actual?.let { "Reported exposure at $fps fps target: %.1f°".format(it) }
                    ?: "Waiting for the camera exposure readout.")
                KeysRow {
                    listOf(45.0, 90.0, 144.0, 172.8, 180.0, 216.0, 270.0, 360.0).forEach { angle ->
                        val ns = ExposureMath.angleToNanoseconds(angle, fps)
                        Key("${angle.toString().removeSuffix(".0")}°",
                            selected = kotlin.math.abs(manual.shutter - ns) < 100_000,
                            enabled = ns in shutter.lower..max) { engine.manual(manual.copy(shutter = ns)) }
                    }
                }
                var mains by rememberSaveable { mutableStateOf(50) }
                Section("Flicker guidance")
                KeysRow {
                    Key("50 Hz", selected = mains == 50) { mains = 50 }
                    Key("60 Hz", selected = mains == 60) { mains = 60 }
                }
                val candidates = ExposureMath.mainsCandidates(fps, mains, shutter.lower, shutter.upper)
                if (candidates.isEmpty()) Hint("No half-cycle exposure fits this frame rate and sensor range.")
                else KeysRow {
                    candidates.take(6).forEach { ns ->
                        Key(ns.shutterLabel(), selected = kotlin.math.abs(manual.shutter - ns) < 100_000) {
                            engine.manual(manual.copy(shutter = ns))
                        }
                    }
                }
                Hint("Mains-based suggestions can reduce flicker. LEDs with independent PWM may require different settings.")
            }
        }
    } else Mark("Unavailable", "This camera does not expose manual ISO and exposure time. Use Auto exposure or EV compensation.")
    Section("Auto exposure")
    controls.ev?.let { ev ->
        Mark(if (state.sensor.ev != null && state.sensor.ev == state.ev) "Native" else "Checking",
            "EV compensation biases the camera's auto exposure.")
        Precision("Exposure bias", state.ev * controls.evStep, ev.lower * controls.evStep..ev.upper * controls.evStep,
            enabled = manual.iso == null && !manual.aeLock, unit = " EV") { engine.ev((it / controls.evStep).roundToInt()) }
    } ?: Mark("Unavailable", "CameraX reports no EV compensation range.")
    ToggleRow("AE lock", manual.aeLock, controls.aeLock && manual.iso == null) {
        engine.manual(manual.copy(aeLock = it))
    }
    Section("Auto-exposure anti-banding")
    if (controls.antibandingModes.isEmpty()) {
        Mark("Unavailable", "This camera does not advertise anti-banding modes.")
    } else {
        KeysRow {
            Key("Default", selected = manual.antibanding == null, enabled = manual.iso == null && !manual.aeLock) {
                engine.manual(manual.copy(antibanding = null))
            }
            controls.antibandingModes.forEach { mode ->
                Key(antibandingLabel(mode), selected = manual.antibanding == mode,
                    enabled = manual.iso == null && !manual.aeLock) { engine.manual(manual.copy(antibanding = mode)) }
            }
        }
        val confirmed = manual.antibanding != null && manual.antibanding == state.sensor.antibanding
        Mark(if (confirmed && manual.iso == null) "Native" else "Checking",
            "Reported: ${antibandingLabel(state.sensor.antibanding)}. Anti-banding is an auto-exposure request, not a guarantee.")
    }
    if (!controls.aeLock) Hint("This lens does not advertise AE lock.")
    else Mark(if (state.sensor.aeLocked) "Native" else "Checking", if (state.sensor.aeLocked) "Capture results report AE locked." else "Available in auto exposure.")
    Mark("Assisted", "Digital exposure trim in Look Lab changes rendered brightness.")
    Key("Open digital exposure", enabled = !state.active && !state.photoMode, action = lab)
}

@Composable
internal fun FocusPanel(state: CaptureState, engine: CameraEngine) {
    Section("Optical focus")
    val focus = state.manual.focus
    val supported = state.controls.focus > 0f
    Mark(when {
        !supported -> "Unavailable"
        focus != null && state.sensor.focus?.let { kotlin.math.abs(it - focus) < .10f } == true -> "Native"
        else -> "Checking"
    }, if (supported) "Camera focus distance in diopters. Zero is infinity."
    else "This camera does not expose an adjustable optical focus distance.")
    if (supported) {
        KeysRow {
            Key("Auto", selected = focus == null) { engine.manual(state.manual.copy(focus = null)) }
            Key("Manual", selected = focus != null) { engine.manual(state.manual.copy(focus = state.sensor.focus ?: 0f)) }
        }
        if (focus != null) {
            Precision("Focus distance", focus, 0f..state.controls.focus, unit = " D") {
                engine.manual(state.manual.copy(focus = it))
            }
            var a by remember(state.lens?.id) { mutableStateOf<Float?>(null) }
            var b by remember(state.lens?.id) { mutableStateOf<Float?>(null) }
            var pulling by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            KeysRow {
                Key("Mark A", enabled = !pulling && state.sensor.focus != null) { a = state.sensor.focus }
                Key("Mark B", enabled = !pulling && state.sensor.focus != null) { b = state.sensor.focus }
                Key("Go to A", enabled = !pulling && a != null) { a?.let { engine.manual(state.manual.copy(focus = it)) } }
                Key("Pull to B", enabled = !pulling && b != null && state.sensor.focus != null) {
                    val target = b ?: return@Key
                    val start = state.sensor.focus ?: return@Key
                    scope.launch {
                        pulling = true
                        try {
                            for (step in 1..12) {
                                engine.manual(state.manual.copy(focus = start + (target - start) * step / 12))
                                delay(125)
                            }
                        } finally {
                            pulling = false
                        }
                    }
                }
            }
            Hint("A ${a?.let { "%.2f D".format(it) } ?: "—"} · B ${b?.let { "%.2f D".format(it) } ?: "—"}")
        }
    }
    Hint("Tap the viewfinder for metering. Touch and hold to hold autofocus where supported.")
    Key("Release AF") { engine.releaseFocus() }
}

@Composable
internal fun WhiteBalancePanel(state: CaptureState, engine: CameraEngine, lab: () -> Unit) {
    Section("Camera white balance")
    val modes = state.controls.whiteBalance
    if (modes.isNotEmpty()) {
        Mark(if (state.manual.wb != null && state.sensor.awb == state.manual.wb) "Native" else "Checking",
            "Camera2 white balance presets.")
        KeysRow {
            Key("Auto", selected = state.manual.wb == null) { engine.manual(state.manual.copy(wb = null)) }
            modes.forEach { mode ->
                Key(wbLabel(mode), selected = mode == state.manual.wb) { engine.manual(state.manual.copy(wb = mode, wbLock = false)) }
            }
        }
    } else Mark("Unavailable", "This camera exposes no selectable Camera2 white balance presets.")
    ToggleRow("AWB lock", state.manual.wbLock, state.controls.wbLock && state.manual.wb == null) {
        engine.manual(state.manual.copy(wbLock = it))
    }
    if (!state.controls.wbLock) Hint("This camera does not report AWB lock support.")
    Mark("Assisted", "Temperature and tint in Look Lab adjust rendered color.")
    Key("Open color tools", enabled = !state.active && !state.photoMode, action = lab)
}

@Composable
internal fun LensPanel(state: CaptureState, engine: CameraEngine) {
    Section("Camera")
    KeysRow {
        state.lenses.forEach { lens ->
            Key(lens.label, selected = state.lens == lens, enabled = !state.active) { engine.selectLens(lens) }
        }
    }
    if (state.controls.physicalIds.isNotEmpty()) {
        Hint("Physical camera IDs in the logical camera: ${state.controls.physicalIds.sorted().joinToString()}")
    }
    val zoom = state.controls.zoom
    if (zoom.start < zoom.endInclusive) {
        Mark("Assisted", "Camera zoom may crop or switch lenses according to the device.")
        Precision("Zoom ratio", state.zoom, zoom, unit = "×", commit = engine::zoom)
    } else Mark("Unavailable", "No zoom range is reported for this camera.")
    Section("Continuous light")
    ToggleRow("Torch", state.torch, state.controls.flash && state.ready && !state.active &&
        (state.thermal < 3 || state.torch), engine::setTorch)
    Mark(if (!state.controls.flash) "Unavailable" else if (state.ready) "Native" else "Checking",
        if (state.controls.flash) "Actual torch state reported by CameraX. Set before recording."
        else "This lens does not advertise a flash unit.")
}

@Composable
internal fun AudioPanel(state: CaptureState, engine: CameraEngine, change: (Boolean) -> Unit) {
    ToggleRow("Record microphone", state.audio, !state.active, change)
    Section("Connected input devices")
    if (state.audioInputs.isEmpty()) Hint("No audio input devices reported.")
    else state.audioInputs.forEach { Hint(it, Paper) }
    Hint("Android selects the actual microphone route. A connected input is not proof that it is being recorded.")
    if (state.audioInputChanged && state.active) Hint("Audio devices changed; review this take.", Amber)
    Section("Audio format")
    KeysRow {
        listOf(0,1,2).forEach { channels ->
            Key(when(channels) { 1 -> "Mono"; 2 -> "Stereo"; else -> "Auto" },
                selected = state.audioChannels == channels, enabled = !state.active) { engine.audioFormat(channels = channels) }
        }
    }
    KeysRow {
        listOf(0,96,128,192,256).forEach { kbps ->
            Key(if (kbps == 0) "Auto" else "$kbps kb/s",selected = state.audioKbps == kbps,
                enabled = !state.active) { engine.audioFormat(kbps = kbps) }
        }
    }
    Hint("Channel count and bitrate are encoder targets, not guaranteed settings.")
    ToggleRow("Mute this take",state.audioMuted,state.audio && state.busy == com.cinematic.camera.capture.Take.RECORDING,engine::muteAudio)
    CaptureStatus(state,null)
    Mark("Unavailable", "CameraX does not expose independent hardware gain or explicit input selection.")
}

@Composable
internal fun MonitorPanel(guide: String, change: (String) -> Unit, state: CaptureState,
                          mode: ScopeMode, scope: (ScopeMode) -> Unit) {
    Section("Frame guides")
    KeysRow { guides.forEach { frame -> Key(frame, selected = frame == guide) { change(frame) } } }
    Hint("Guides sit over the live viewfinder.")
    Section("Audio")
    CaptureStatus(state, null)
    Section("Scopes")
    if (state.pipeline == Pipeline.DIRECT) {
        Mark("Unavailable", "Switch to Clean or Baked to enable GPU sampling.")
    } else {
        KeysRow { ScopeMode.entries.forEach { scopeMode ->
            Key(scopeMode.label, selected = mode == scopeMode) { scope(scopeMode) }
        } }
        Hint("Scopes sample ungraded camera RGB. The clipping and edge maps are low-resolution aids and use sensor orientation.")
    }
}

internal fun Long?.shutterLabel(): String = when {
    this == null || this <= 0 -> "—"
    this < 1_000_000_000L -> "1/${(1_000_000_000.0 / this).roundToInt()} s"
    else -> "%.2f s".format(this / 1_000_000_000.0)
}

internal fun antibandingLabel(value: Int?): String = when (value) {
    0 -> "Off"; 1 -> "50 Hz"; 2 -> "60 Hz"; 3 -> "Auto"; else -> "—"
}

internal fun wbLabel(value: Int?): String = when (value) {
    0 -> "Off"; 1 -> "Auto"; 2 -> "Incandescent"; 3 -> "Fluorescent"; 4 -> "Warm fluorescent"
    5 -> "Daylight"; 6 -> "Cloudy"; 7 -> "Twilight"; 8 -> "Shade"; else -> "—"
}

internal fun diagnostics(state: CaptureState): JSONObject = JSONObject().apply {
    put("version", 1)
    put("androidApi", Build.VERSION.SDK_INT)
    put("manufacturer", Build.MANUFACTURER)
    put("model", Build.MODEL)
    put("motionAngle", state.motionAngle ?: JSONObject.NULL)
    put("lens", state.lens?.label ?: JSONObject.NULL)
    put("cameraHardwareLevel", state.controls.level ?: JSONObject.NULL)
    put("physicalCameraIds", JSONArray(state.controls.physicalIds.toList()))
    put("availableLenses", JSONArray(state.lenses.map { it.label }))
    put("advertisedFormats", JSONArray(state.formats.map {
        JSONObject().put("width", it.width).put("height", it.height).put("fixedFpsCandidates", JSONArray(it.rates))
    }))
    put("pipeline", state.pipeline.name)
    put("recordingProfile", state.profile.name)
    put("advertisedRecordingProfiles", JSONArray(state.profileFormats.keys.map { it.name }))
    put("sessionOpen", state.ready)
    put("colorPipelineFailed", state.gpuFailed)
    put("thermalStatus", if (Build.VERSION.SDK_INT >= 29) state.thermal else JSONObject.NULL)
    put("freeBytes", state.bytes)
    put("advertised", JSONObject()
        .put("sensorIso", state.controls.iso?.toString() ?: JSONObject.NULL)
        .put("exposureNanoseconds", state.controls.shutter?.toString() ?: JSONObject.NULL)
        .put("minimumFocusDistanceDiopters", state.controls.focus.toDouble())
        .put("cameraAwbModes", JSONArray(state.controls.whiteBalance))
        .put("aeLock", state.controls.aeLock).put("awbLock", state.controls.wbLock)
        .put("electronicStabilization", state.controls.eis)
        .put("flashUnit", state.controls.flash)
        .put("antiBandingModes", JSONArray(state.controls.antibandingModes)))
    put("lastSensorResult", JSONObject().put("iso", state.sensor.iso ?: JSONObject.NULL)
        .put("exposureNs", state.sensor.shutter ?: JSONObject.NULL)
        .put("awb", state.sensor.awb ?: JSONObject.NULL)
        .put("focusDiopters", state.sensor.focus?.toDouble() ?: JSONObject.NULL)
        .put("antiBandingMode", state.sensor.antibanding ?: JSONObject.NULL)
        .put("torch", state.torch))
}
