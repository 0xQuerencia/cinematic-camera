package com.cinematic.camera.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cinematic.camera.color.AdaptiveColor
import com.cinematic.camera.color.AutoLookChoice
import com.cinematic.camera.color.Grade
import com.cinematic.camera.color.SceneAutoLook
import com.cinematic.camera.color.originalLooks
import kotlin.math.roundToInt

internal fun referencePixels(context: Context, uri: Uri): ByteArray {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Could not read image." }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
    val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    } ?: error("Could not decode image.")
    val scaled = Bitmap.createScaledBitmap(bitmap, 64, 36, true)
    try {
        val argb = IntArray(64 * 36)
        scaled.getPixels(argb, 0, 64, 0, 0, 64, 36)
        return ByteArray(64 * 36 * 4).also { out ->
            argb.forEachIndexed { i, color ->
                out[i * 4] = (color shr 16).toByte()
                out[i * 4 + 1] = (color shr 8).toByte()
                out[i * 4 + 2] = color.toByte()
                out[i * 4 + 3] = 255.toByte()
            }
        }
    } finally {
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
    }
}

@Composable
internal fun AdaptiveLookPanel(
    pixels: ByteArray?, grade: Grade, automatic: Boolean, adaptive: Boolean,
    enabled: Boolean, busy: Boolean,
    toggleAuto: () -> Unit, changeAdaptive: (Boolean) -> Unit,
    choose: (AutoLookChoice) -> Unit, generate: () -> Unit,
    match: () -> Unit, save: () -> Unit, export: () -> Unit,
) {
    val recommendation = remember(pixels) { pixels?.let(SceneAutoLook::recommend) }
    val profile = remember(pixels) { pixels?.let(AdaptiveColor::profile) }
    ToggleRow("Auto select a look", automatic, enabled && !busy) { toggleAuto() }
    ToggleRow("Generate scene-specific LUT", adaptive, enabled && !busy, changeAdaptive)
    Hint("Auto selection runs on-device. It changes only between takes and is fixed during a recording.")
    Section("Live scene")
    if (profile == null || recommendation == null) Hint("Waiting for a usable camera sample. Open the lens and point at a lit scene.")
    else {
        Text(recommendation.reason, style = MaterialTheme.typography.titleMedium)
        Hint("Sampled luma: ${(profile.dark * 100).roundToInt()}–${(profile.bright * 100).roundToInt()}% · 10th–90th percentile")
        Hint("Warm-color regions: ${(profile.warmMidtones * 100).roundToInt()}% (not face detection)")
        Section("Recommended looks")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AdaptiveColor.alternatives(recommendation).forEach { choice ->
                val look = originalLooks.firstOrNull { it.name == choice.name }
                Column(Modifier.weight(1f)) {
                    if (look != null) LookSwatch(look, Modifier.fillMaxWidth().height(52.dp))
                    Key(choice.name, Modifier.fillMaxWidth(), enabled = enabled && !busy,
                        selected = grade.look.name == choice.name) { choose(choice) }
                }
            }
        }
        Hint("Thumbnails use a fixed reference image, not the current scene.")
    }
    Section("Build a look")
    Key(if (busy) "Processing…" else "Generate from scene", Modifier.fillMaxWidth(), enabled = enabled && !busy && recommendation != null, action = generate)
    Key("Match a reference photo", Modifier.fillMaxWidth(), enabled = enabled && !busy && recommendation != null, action = match)
    Hint("Reference matching estimates global color and contrast. It is not object-aware or an exact reproduction.")
    Section("Current look")
    Text(grade.look.name)
    KeysRow {
        Key("Save look", enabled = enabled && !busy && grade.look.cube != null, action = save)
        Key("Export .cube", enabled = enabled && !busy && grade.look.cube != null, action = export)
    }
    Hint("Cube export includes the generated color transform only; separate Look Lab effects are stored in the full preset.")
}
