package com.cinematic.camera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cinematic.camera.color.Grade
import com.cinematic.camera.color.Look
import com.cinematic.camera.color.SavedLook

@Composable
internal fun LookLab(grade: Grade, split: Boolean, canUndo: Boolean, canRedo: Boolean, lite: Boolean,
                    change: (Grade, Boolean) -> Unit, onSplit: () -> Unit, undo: () -> Unit, redo: () -> Unit,
                    save: () -> Unit, looks: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf("Light") }
    Column(Modifier.fillMaxSize().background(Panel).verticalScroll(rememberScrollState()).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        KeysRow {
            Key("Split A/B", selected = split, action = onSplit)
            Key("Undo", enabled = canUndo, action = undo)
            Key("Redo", enabled = canRedo, action = redo)
            Key("Reset") { change(Grade(look = grade.look), true) }
            Key("Save", action = save)
        }
        Hint(if (lite) "SDR Rec.709 · Live · Lite" else "SDR Rec.709 · Live · Full", Info)
        KeysRow {
            listOf("Light", "Color", "Curves", "LUT", "Film").forEach { tool ->
                Key(tool, selected = tab == tool) { tab = tool }
            }
        }
        when (tab) {
            "Light" -> {
                GradeSlider("Digital exposure trim", grade, grade.exposure, -2f..2f, change) { g, v -> g.copy(exposure = v) }
                GradeSlider("Contrast", grade, grade.contrast, .5f..1.5f, change) { g, v -> g.copy(contrast = v) }
                GradeSlider("Highlight softening", grade, grade.highlights, 0f.. .4f, change) { g, v -> g.copy(highlights = v) }
            }
            "Color" -> {
                GradeSlider("Saturation", grade, grade.saturation, 0f..2f, change) { g, v -> g.copy(saturation = v) }
                GradeSlider("Digital temperature", grade, grade.temperature, -.3f.. .3f, change) { g, v -> g.copy(temperature = v) }
                GradeSlider("Digital tint", grade, grade.tint, -.3f.. .3f, change) { g, v -> g.copy(tint = v) }
            }
            "Curves" -> {
                GradeSlider("Midtone gamma", grade, grade.curve, .5f..2f, change) { g, v -> g.copy(curve = v) }
                Canvas(Modifier.fillMaxWidth().height(88.dp).background(Ink)) {
                    var previous = Offset(0f, size.height)
                    for (i in 1..64) {
                        val x = i / 64f
                        val y = Math.pow(x.toDouble(), grade.curve.toDouble()).toFloat()
                        val next = Offset(x * size.width, (1 - y) * size.height)
                        drawLine(Amber, previous, next, 1.5.dp.toPx())
                        previous = next
                    }
                }
            }
            "LUT" -> {
                Key(grade.look.name, action = looks)
                if (grade.look.cube != null) {
                    GradeSlider("LUT strength", grade, grade.strength, 0f..1f, change) { g, v -> g.copy(strength = v) }
                    Hint("0 is the untransformed grade. 1 applies the full cube.")
                } else Hint("Choose a look containing a 3D LUT.")
            }
            "Film" -> {
                GradeSlider("Grain", grade, grade.grain, 0f..1f, change) { g, v -> g.copy(grain = v) }
                GradeSlider("Vignette", grade, grade.vignette, 0f..1f, change) { g, v -> g.copy(vignette = v) }
                if (lite) Hint("Bloom and halation are skipped in Lite preview.")
                GradeSlider("Bloom", grade, grade.bloom, 0f..1f, change) { g, v -> g.copy(bloom = v) }
                GradeSlider("Halation", grade, grade.halation, 0f..1f, change) { g, v -> g.copy(halation = v) }
            }
        }
    }
}

@Composable
private fun GradeSlider(label: String, grade: Grade, value: Float, range: ClosedFloatingPointRange<Float>,
                        change: (Grade, Boolean) -> Unit, update: (Grade, Float) -> Grade) {
    var started by remember { mutableStateOf(false) }
    Precision(label, value, range, immediate = {
        change(update(grade, it), !started)
        started = true
    }) { started = false }
}

@Composable
internal fun LookSwatch(look: Look, modifier: Modifier = Modifier) {
    val patches = remember(look) {
        listOf(floatArrayOf(.17f,.22f,.24f), floatArrayOf(.43f,.48f,.42f), floatArrayOf(.70f,.47f,.34f),
            floatArrayOf(.76f,.64f,.49f), floatArrayOf(.41f,.59f,.72f), floatArrayOf(.89f,.89f,.83f)).map {
            val sample = look.cube?.sample(it[0], it[1], it[2]) ?: it
            Color(sample[0].coerceIn(0f, 1f), sample[1].coerceIn(0f, 1f), sample[2].coerceIn(0f, 1f))
        }
    }
    Canvas(modifier) {
        patches.forEachIndexed { i, color ->
            drawRect(color, Offset(size.width * (i % 3) / 3, size.height * (i / 3) / 2),
                Size(size.width / 3 + .5f, size.height / 2 + .5f))
        }
    }
}

internal data class StoryPreset(val name: String, val look: String, val fps: Int?, val guide: String)

private val stories = listOf(
    StoryPreset("Everyday", "Neutral", 30, "Off"),
    StoryPreset("Cinematic", "Film Print", 24, "2.39:1"),
    StoryPreset("Night", "Moonlit", 30, "Thirds"),
    StoryPreset("Portrait", "Soft Portrait", 30, "Thirds"),
    StoryPreset("Documentary", "Documentary", 30, "16:9"),
    StoryPreset("Action", "Clean Commercial", 60, "16:9"),
    StoryPreset("Social Vertical", "Pastel", 30, "9:16"),
)

@Composable
internal fun LooksPanel(original: List<Look>, saved: List<SavedLook>, favorites: Set<String>, importing: Boolean,
                       choose: (Look) -> Unit, apply: (SavedLook) -> Unit, favorite: (String) -> Unit,
                       import: () -> Unit, save: (Boolean) -> Unit, export: (SavedLook) -> Unit,
                       quick: (StoryPreset) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("Looks") }
    var family by rememberSaveable { mutableStateOf("All") }
    OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), label = { Text("Search looks and presets") }, singleLine = true)
    KeysRow {
        listOf("Looks", "Saved", "Capture presets", "Favorites").forEach {
            Key(it, selected = category == it) { category = it }
        }
    }
    KeysRow {
        Key(if (importing) "Importing…" else "Import", enabled = !importing, action = import)
        Key("Save look") { save(false) }
        Key("Save preset") { save(true) }
    }
    Hint("Import a 3D .cube authored for SDR Rec.709 or a version 1 Cinematic Camera JSON file. Check the source profile in the LUT documentation.")
    if (category == "Looks") KeysRow {
        (listOf("All") + original.map { it.family }.distinct()).forEach {
            Key(it, selected = family == it) { family = it }
        }
    }
    if (category in listOf("Looks", "Favorites")) {
        original.filter { it.name.contains(search, true) && (category != "Favorites" || it.name in favorites) &&
            (category != "Looks" || family == "All" || it.family == family) }.forEach { look ->
            HorizontalDivider(color = Rule)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LookSwatch(look, Modifier.size(72.dp, 48.dp))
                Key(look.name, Modifier.weight(1f)) { choose(look) }
                Key(if (look.name in favorites) "Unfavorite" else "Favorite") { favorite(look.name) }
            }
            Hint(look.family)
        }
    }
    if (category == "Capture presets") {
        Section("Quick presets")
        stories.filter { it.name.contains(search, true) }.forEach { story ->
            Key(story.name, Modifier.fillMaxWidth()) { quick(story) }
            Hint("${story.look} · ${story.fps} fps target · ${story.guide} guide")
        }
        Section("Saved presets")
    }
    if (category != "Looks") {
        saved.filter { it.name.contains(search, true) && when (category) {
            "Capture presets" -> it.capture != null
            "Saved" -> it.capture == null
            else -> it.id in favorites
        } }.forEach { item ->
            HorizontalDivider(color = Rule)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LookSwatch(item.grade.look, Modifier.size(72.dp, 48.dp))
                Key(item.name, Modifier.weight(1f)) { apply(item) }
                Key("Export") { export(item) }
            }
            Key(if (item.id in favorites) "Unfavorite ${item.name}" else "Favorite ${item.name}") { favorite(item.id) }
        }
    }
}
