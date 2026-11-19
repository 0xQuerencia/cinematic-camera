package com.cinematic.camera.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import com.cinematic.camera.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
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
    var tab by rememberSaveable { mutableStateOf("Adjust") }
    Column(Modifier.fillMaxSize().background(Panel).verticalScroll(rememberScrollState()).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        KeysRow {
            Key("Split A/B", selected = split, action = onSplit)
            Key("Undo", enabled = canUndo, action = undo)
            Key("Redo", enabled = canRedo, action = redo)
            Key("Reset") { change(Grade(look = grade.look), true) }
            Key("Save look", selected = true, action = save)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clip(CardShape).background(Raised).clickable(onClick = looks).padding(6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LookSwatch(grade.look, Modifier.size(48.dp).clip(InstrumentShape))
                Column {
                    Text(grade.look.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Hint(grade.look.family)
                }
            }
            Column(Modifier.weight(1f).clip(CardShape).background(Raised).padding(horizontal = 8.dp)) {
                if (grade.look.cube != null) {
                    GradeSlider("Intensity", grade, grade.strength, 0f..1f, change) { g, v -> g.copy(strength = v) }
                } else Hint(if (lite) "SDR · Lite preview" else "SDR · Live preview", Info)
            }
        }
        KeysRow {
            listOf("Adjust", "Curves", "FX", "LUT").forEach { tool ->
                Key(tool, selected = tab == tool) { tab = tool }
            }
        }
        when (tab) {
            "Adjust" -> {
                GradeSlider("Digital exposure trim", grade, grade.exposure, -2f..2f, change) { g, v -> g.copy(exposure = v) }
                GradeSlider("Contrast", grade, grade.contrast, .5f..1.5f, change) { g, v -> g.copy(contrast = v) }
                GradeSlider("Highlight softening", grade, grade.highlights, 0f.. .4f, change) { g, v -> g.copy(highlights = v) }
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
            "FX" -> {
                GradeSlider("Grain", grade, grade.grain, 0f..1f, change) { g, v -> g.copy(grain = v) }
                GradeSlider("Vignette", grade, grade.vignette, 0f..1f, change) { g, v -> g.copy(vignette = v) }
                if (lite) Hint("Bloom and halation are skipped in Lite preview.")
                GradeSlider("Bloom", grade, grade.bloom, 0f..1f, change) { g, v -> g.copy(bloom = v) }
                GradeSlider("Halation", grade, grade.halation, 0f..1f, change) { g, v -> g.copy(halation = v) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GradeSlider(label: String, grade: Grade, value: Float, range: ClosedFloatingPointRange<Float>,
                        change: (Grade, Boolean) -> Unit, update: (Grade, Float) -> Grade) {
    var started by remember { mutableStateOf(false) }
    Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Slider(value, {
            change(update(grade, it), !started)
            started = true
        }, Modifier.weight(1.25f), valueRange = range, onValueChangeFinished = { started = false },
            colors = SliderDefaults.colors(activeTrackColor = Amber, inactiveTrackColor = Rule),
            thumb = { Box(Modifier.size(11.dp).clip(CircleShape).background(Paper)) },
            track = { SliderDefaults.Track(it, Modifier.height(3.dp),
                colors = SliderDefaults.colors(activeTrackColor = Amber, inactiveTrackColor = Rule),
                drawStopIndicator = null) })
        Text(if (label == "Intensity") "${(value * 100).roundToInt()}%" else "%.2f".format(value), style = Numeric)
    }
}

private val lookPreviews = LruCache<Look, ImageBitmap>(28)

@Composable
internal fun LookSwatch(look: Look, modifier: Modifier = Modifier) {
    val resources = LocalContext.current.resources
    val loaded by produceState<ImageBitmap?>(lookPreviews.get(look), look, resources) {
        value = lookPreviews.get(look) ?: withContext(Dispatchers.Default) {
            val reference = BitmapFactory.decodeResource(resources, R.drawable.look_reference,
                BitmapFactory.Options().apply { inSampleSize = 4 })
            val cube = look.cube
            val bitmap = if (cube == null) reference else {
                val pixels = IntArray(reference.width * reference.height)
                reference.getPixels(pixels, 0, reference.width, 0, 0, reference.width, reference.height)
                for (i in pixels.indices) {
                    val pixel = pixels[i]
                    val rgb = cube.sample((pixel shr 16 and 255) / 255f, (pixel shr 8 and 255) / 255f, (pixel and 255) / 255f)
                    pixels[i] = android.graphics.Color.rgb(
                        (rgb[0].coerceIn(0f, 1f) * 255).roundToInt(),
                        (rgb[1].coerceIn(0f, 1f) * 255).roundToInt(),
                        (rgb[2].coerceIn(0f, 1f) * 255).roundToInt())
                }
                Bitmap.createBitmap(pixels, reference.width, reference.height, Bitmap.Config.ARGB_8888)
            }
            bitmap.asImageBitmap().also { lookPreviews.put(look, it) }
        }
    }
    val image = loaded
    if (image != null) Image(image, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(Field))
}

@Composable
private fun LookCard(look: Look, favored: Boolean, modifier: Modifier,
                     choose: () -> Unit, favorite: () -> Unit) {
    Column(modifier.clip(CardShape).background(Panel).border(1.dp, Rule, CardShape)
        .clickable(onClick = choose)) {
        LookSwatch(look, Modifier.fillMaxWidth().aspectRatio(1.75f))
        Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(look.name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Paper)
                Hint(look.family)
            }
            IconKey(ToolIcon.STAR, if (favored) "Unfavorite ${look.name}" else "Favorite ${look.name}",
                selected = favored, action = favorite)
        }
    }
}

internal data class StoryPreset(val name: String, val look: String, val fps: Int?, val guide: String)

private val stories = listOf(
    StoryPreset("Everyday", "Neutral", 30, "Off"),
    StoryPreset("Cinematic", "Film Print", 24, "2.39:1"),
    StoryPreset("Night", "Moonlit", 30, "Thirds"),
    StoryPreset("Neon Streets", "Neon Nocturne", 30, "2.39:1"),
    StoryPreset("Rainy Streets", "Rainy City", 30, "2.39:1"),
    StoryPreset("Forest Walk", "Forest Film", 30, "Thirds"),
    StoryPreset("Warm Analog", "Amber Analog", 24, "2.39:1"),
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
    Hint("SDR Rec.709 · .cube or Cinematic Camera JSON")
    if (category == "Looks") KeysRow {
        (listOf("All") + original.map { it.family }.distinct()).forEach {
            Key(it, selected = family == it) { family = it }
        }
    }
    if (category in listOf("Looks", "Favorites")) {
        val filtered = original.filter { it.name.contains(search, true) && (category != "Favorites" || it.name in favorites) &&
            (category != "Looks" || family == "All" || it.family == family) }
        Hint("${filtered.size} looks")
        filtered.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { look ->
                    LookCard(look, look.name in favorites, Modifier.weight(1f), { choose(look) }, { favorite(look.name) })
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
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
