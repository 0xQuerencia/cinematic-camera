package com.cinematic.camera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cinematic.camera.capture.CameraEngine
import kotlin.math.abs

internal enum class ScopeMode(val label: String) {
    OFF("Off"), HISTOGRAM("Histogram"), WAVEFORM("Waveform"), VECTORSCOPE("Vectorscope"),
    ZEBRA("Luma clipping map"), PEAKING("Edge map"), FALSE_COLOR("False color map"),
}

@Composable
internal fun ScopeOverlay(engine: CameraEngine, mode: ScopeMode, modifier: Modifier) {
    val samples by engine.scopeSamples.collectAsStateWithLifecycle()
    val pixels = samples
    Column(modifier.width(156.dp).background(Ink.copy(alpha = .88f), InstrumentShape).padding(8.dp)) {
        Hint(mode.label, Info)
        if (pixels == null) Hint("Waiting for camera frame")
        else if (pixels.isEmpty()) Hint("GPU readback unavailable")
        else {
            Hint("Source luma · 64×36 samples")
            val luma = remember(pixels) {
                FloatArray(64 * 36) { i ->
                    val index = i * 4
                    ((pixels[index].toInt() and 255) * .2126f + (pixels[index + 1].toInt() and 255) * .7152f +
                        (pixels[index + 2].toInt() and 255) * .0722f) / 255
                }
            }
            val histogram = remember(luma) {
                IntArray(32).also { bins -> luma.forEach { bins[(it * 31).toInt().coerceIn(0, 31)]++ } }
            }
            Canvas(Modifier.fillMaxWidth().height(72.dp)) {
                val w = size.width
                val h = size.height
                when (mode) {
                    ScopeMode.HISTOGRAM -> {
                        val max = histogram.maxOrNull()?.coerceAtLeast(1) ?: 1
                        histogram.forEachIndexed { i, count ->
                            val bar = count.toFloat() / max * h
                            drawRect(Amber, Offset(i * w / 32, h - bar), Size(w / 32 - 1, bar))
                        }
                    }
                    ScopeMode.WAVEFORM -> {
                        for (level in 0..4) drawLine(Rule, Offset(0f, level * h / 4), Offset(w, level * h / 4))
                        luma.forEachIndexed { i, level ->
                            drawCircle(Native.copy(alpha = .46f), 1.15f, Offset(i % 64 * w / 64, (1 - level) * h))
                        }
                    }
                    ScopeMode.VECTORSCOPE -> {
                        drawCircle(Rule, radius = h / 2, style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
                        drawLine(Rule, Offset(center.x - h / 2, center.y), Offset(center.x + h / 2, center.y))
                        drawLine(Rule, Offset(center.x, 0f), Offset(center.x, h))
                        luma.forEachIndexed { i, level ->
                            val r = (pixels[i * 4].toInt() and 255) / 255f
                            val b = (pixels[i * 4 + 2].toInt() and 255) / 255f
                            drawCircle(Native.copy(alpha = .43f), 1.15f,
                                Offset(center.x + (b - level) / 1.8556f * h, center.y - (r - level) / 1.5748f * h))
                        }
                    }
                    ScopeMode.ZEBRA, ScopeMode.PEAKING, ScopeMode.FALSE_COLOR -> luma.forEachIndexed { i, level ->
                        val x = i % 64
                        val y = i / 64
                        val color = when (mode) {
                            ScopeMode.ZEBRA -> if (level >= .94f && (x + y) % 4 < 2) Amber else null
                            ScopeMode.PEAKING -> if (x < 63 && y < 35 &&
                                abs(luma[i + 1] - level) + abs(luma[i + 64] - level) > .22f) Native else null
                            else -> when {
                                level >= .94f -> Record
                                level > .76f -> Amber
                                level in .39f.. .5f -> Native
                                level < .12f -> Info
                                else -> null
                            }
                        } ?: Color(level, level, level)
                        drawRect(color, Offset(x * w / 64, (35 - y) * h / 36), Size(w / 64 + .4f, h / 36 + .4f))
                    }
                    ScopeMode.OFF -> Unit
                }
            }
        }
    }
}
