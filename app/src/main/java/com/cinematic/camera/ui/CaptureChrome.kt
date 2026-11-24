package com.cinematic.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cinematic.camera.capture.CaptureState
import com.cinematic.camera.capture.Take
import com.cinematic.camera.capture.Pipeline
import com.cinematic.camera.color.Look

internal val Chrome = Ink.copy(alpha = .86f)
internal val CardShape = RoundedCornerShape(12.dp)

@Composable
internal fun ModeSwitch(pro: Boolean, enabled: Boolean, select: (Boolean) -> Unit) {
    Row(Modifier.clip(CircleShape).background(Chrome).border(1.dp, Rule, CircleShape)
        .selectableGroup().padding(3.dp)) {
        listOf(false to "Simple", true to "Pro").forEach { (mode, label) ->
            val selected = pro == mode
            Box(Modifier.defaultMinSize(minWidth = 60.dp, minHeight = 48.dp)
                .clip(CircleShape).background(if (selected) Amber else Chrome)
                .selectable(selected, enabled, role = Role.Tab) { select(mode) }
                .padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                Text(label, color = if (selected) Ink else Paper, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
internal fun LookStrip(looks: List<Look>, active: Look, enabled: Boolean, open: () -> Unit, select: (Look) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(CardShape).background(Chrome).border(1.dp, Rule, CardShape)
        .padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconKey(ToolIcon.LOOK, "Look library", enabled = enabled, action = open)
            Hint("Look", Amber)
        }
        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(looks, key = { it.name }) { look ->
                val selected = look.name == active.name
                Column(Modifier.width(72.dp).clip(InstrumentShape)
                    .border(1.dp, if (selected) Amber else Rule, InstrumentShape)
                    .selectable(selected, enabled, role = Role.Button) { select(look) },
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    LookSwatch(look, Modifier.fillMaxWidth().height(40.dp))
                    Text(look.name, Modifier.padding(horizontal = 3.dp, vertical = 4.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (selected) Amber else Paper,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
internal fun SimpleCapture(
    state: CaptureState, look: Look, looks: List<Look>, notice: String?, guide: Boolean,
    modifier: Modifier = Modifier,
    selectMode: (Boolean) -> Unit, onSettings: () -> Unit, onGuide: () -> Unit,
    onExposure: () -> Unit, onLooks: () -> Unit, selectLook: (Look) -> Unit,
    onLibrary: () -> Unit, onRecord: () -> Unit, onFlip: () -> Unit, onCaptureMode: (Boolean) -> Unit,
    autoLook: Boolean, autoStatus: String?, onAutoLook: () -> Unit,
    compareControl: @Composable () -> Unit,
    preview: @Composable (Modifier) -> Unit,
) {
    Box(modifier) {
        preview(Modifier.matchParentSize())
        Column(Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Chrome, androidx.compose.ui.graphics.Color.Transparent,
                androidx.compose.ui.graphics.Color.Transparent, Ink)))
            .padding(horizontal = 12.dp, vertical = 6.dp)) {
            Box(Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Box(Modifier.align(Alignment.CenterStart)) {
                    IconKey(ToolIcon.SETTINGS, "Settings", enabled = !state.active, action = onSettings)
                }
                Box(Modifier.align(Alignment.Center)) { ModeSwitch(false, !state.active, selectMode) }
                Box(Modifier.align(Alignment.CenterEnd)) {
                    IconKey(ToolIcon.GRID, "Frame guides and scopes", selected = guide, action = onGuide)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.align(Alignment.CenterStart)) { compareControl() }
                Column(Modifier.align(Alignment.CenterEnd)
                    .clip(InstrumentShape).background(Chrome).border(1.dp, Rule, InstrumentShape)
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .clickable(role = Role.Button, onClick = onExposure).padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("EV", color = Amber, style = MaterialTheme.typography.bodySmall)
                    Text(if (state.controls.ev == null) "—" else "%+.1f".format(state.ev * state.controls.evStep),
                        color = Paper, style = Numeric)
                }
            }
            if (notice != null) Text(notice, Modifier.clip(InstrumentShape).background(Chrome).padding(7.dp),
                color = Amber, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (state.pipeline != Pipeline.DIRECT) {
                Hint(if (state.pipeline == Pipeline.BAKED) "Baked look" else "Look preview · Clean file",
                    if (state.pipeline == Pipeline.BAKED) Amber else Paper,
                    Modifier.align(Alignment.CenterHorizontally).clip(InstrumentShape)
                        .background(Chrome).padding(horizontal = 8.dp, vertical = 4.dp))
                Spacer(Modifier.height(4.dp))
            }
            if (!state.photoMode && !state.profile.hdr) Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Key("Auto Look", selected = autoLook, enabled = !state.active, action = onAutoLook)
                if (autoStatus != null) Hint(autoStatus, Paper,
                    Modifier.weight(1f).clip(InstrumentShape).background(Chrome).padding(6.dp))
            }
            if (!state.photoMode && !state.profile.hdr) LookStrip(looks, look, !state.active, onLooks, selectLook)
            CaptureModeSwitch(state, onCaptureMode)
            Box(Modifier.fillMaxWidth().height(84.dp)) {
                Box(Modifier.align(Alignment.CenterStart).padding(start = 20.dp)) {
                    IconKey(ToolIcon.LIBRARY, "Media library", enabled = !state.active, action = onLibrary)
                }
                Box(Modifier.align(Alignment.Center)) {
                    RecordKey(state.busy, state.busy == Take.RECORDING || (state.busy == Take.IDLE && state.ready), onRecord, state.photoMode)
                }
                Box(Modifier.align(Alignment.CenterEnd).padding(end = 20.dp)) {
                    IconKey(ToolIcon.FLIP, "Switch camera", enabled = !state.active && state.lenses.size > 1, action = onFlip)
                }
            }
        }
    }
}

@Composable
internal fun ExposureStrip(state: CaptureState, hidden: Set<String> = emptySet(), open: (Drawer) -> Unit) {
    if (listOf("ISO","SHUTTER","WB","FOCUS","EV").all { it in hidden }) return
    Row(Modifier.fillMaxWidth().clip(InstrumentShape).background(Panel)
        .horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        if ("ISO" !in hidden) Telemetry("ISO", state.sensor.iso?.toString() ?: "—", emphasized = true) { open(Drawer.EXPOSURE) }
        if ("SHUTTER" !in hidden) Telemetry("SHUTTER", state.sensor.shutter.shutterLabel(), emphasized = true) { open(Drawer.EXPOSURE) }
        if ("WB" !in hidden) Telemetry("WB", wbLabel(state.sensor.awb), emphasized = true) { open(Drawer.WB) }
        if ("FOCUS" !in hidden) Telemetry("FOCUS", state.sensor.focus?.let { "%.2f D".format(it) } ?: "—", emphasized = true) { open(Drawer.FOCUS) }
        if ("EV" !in hidden) Telemetry("EV", state.controls.ev?.let { "%+.1f".format(state.ev * state.controls.evStep) } ?: "—",
            emphasized = true) { open(Drawer.EXPOSURE) }
    }
}

@Composable
internal fun QuickControls(open: (Drawer) -> Unit) {
    val actions = listOf(
        Drawer.EXPOSURE to ToolIcon.TOOLS, Drawer.FOCUS to ToolIcon.GRID,
        Drawer.WB to ToolIcon.LOOK, Drawer.LENS to ToolIcon.FLIP,
        Drawer.AUDIO to ToolIcon.SCOPE, Drawer.OUTPUT to ToolIcon.SETTINGS,
        Drawer.MONITOR to ToolIcon.SCOPE,
    )
    actions.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { (target, icon) ->
                Column(Modifier.weight(1f).clip(CardShape).background(Panel)
                    .border(1.dp, Rule, CardShape).clickable(role = Role.Button) { open(target) }
                    .heightIn(min = 78.dp).padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
                    ToolGlyph(icon, Amber)
                    Text(target.label, style = MaterialTheme.typography.bodySmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
internal fun LibraryNav(panel: Drawer, onCamera: () -> Unit, onLab: () -> Unit, onLooks: () -> Unit, onMedia: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(CardShape)
        .background(Panel).border(1.dp, Rule, CardShape).selectableGroup()) {
        LibraryTab("Camera", ToolIcon.CAMERA, false, Modifier.weight(1f), onCamera)
        LibraryTab("Look Lab", ToolIcon.TOOLS, false, Modifier.weight(1f), onLab)
        LibraryTab("Looks", ToolIcon.LOOK, panel == Drawer.LOOKS, Modifier.weight(1f), onLooks)
        LibraryTab("Media", ToolIcon.LIBRARY, panel == Drawer.MEDIA, Modifier.weight(1f), onMedia)
    }
}

@Composable
private fun LibraryTab(label: String, icon: ToolIcon, active: Boolean, modifier: Modifier, action: () -> Unit) {
    val tone = if (active) Amber else Secondary
    Column(modifier.heightIn(min = 56.dp).background(if (active) Selected else Panel)
        .selectable(active, role = Role.Tab, onClick = action).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally) {
        ToolGlyph(icon, tone)
        Hint(label, tone)
    }
}

@Composable
internal fun CaptureModeSwitch(state: CaptureState, select: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically) {
        Key("Photo", selected = state.photoMode, enabled = !state.active) { select(true) }
        Spacer(Modifier.width(6.dp))
        Key("Video", selected = !state.photoMode, enabled = !state.active) { select(false) }
    }
}
