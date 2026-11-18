package com.cinematic.camera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cinematic.camera.capture.Take

internal val Ink = Color(0xFF090A0B)
internal val Panel = Color(0xFF111315)
internal val Raised = Color(0xFF17191B)
internal val Field = Color(0xFF222528)
internal val Rule = Color(0xFF33383B)
internal val Paper = Color(0xFFF4F3F0)
internal val Secondary = Color(0xFFD0D2D0)
internal val Muted = Color(0xFF969B9B)
internal val Amber = Color(0xFFF2B65A)
internal val Selected = Color(0xFF2D2418)
internal val Record = Color(0xFFE94B43)
internal val Native = Color(0xFF77C99A)
internal val Info = Color(0xFF79B8D4)
internal val Unavailable = Color(0xFFA7ADAE)
internal val InstrumentShape = RoundedCornerShape(6.dp)
internal val Numeric = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold,
    fontFeatureSettings = "tnum")
internal val Timecode = Numeric.copy(fontFamily = FontFamily.Monospace)

@Composable
fun InstrumentTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = Amber, onPrimary = Ink, primaryContainer = Selected,
            onPrimaryContainer = Amber, secondary = Info, background = Ink, onBackground = Paper,
            surface = Panel, onSurface = Paper, surfaceVariant = Field, onSurfaceVariant = Secondary,
            outline = Rule, error = Record, surfaceContainer = Raised),
        shapes = Shapes(extraSmall = InstrumentShape, small = InstrumentShape,
            medium = RoundedCornerShape(10.dp), large = RoundedCornerShape(14.dp)),
        typography = Typography(
            titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
            bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
        ),
        content = content,
    )
}

@Composable
internal fun Key(label: String, modifier: Modifier = Modifier, selected: Boolean = false,
                 enabled: Boolean = true, action: () -> Unit) {
    Box(modifier.defaultMinSize(minWidth = 48.dp).heightIn(min = 48.dp).clip(InstrumentShape)
        .background(if (selected) Selected else Panel).border(1.dp, Rule, InstrumentShape)
        .clickable(enabled = enabled, role = Role.Button, onClick = action)
        .padding(horizontal = 10.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (!enabled) Muted else if (selected) Amber else Paper,
            style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun Telemetry(label: String, value: String, enabled: Boolean = true, action: () -> Unit) {
    Column(Modifier.widthIn(min = 72.dp).heightIn(min = 56.dp)
        .clickable(enabled = enabled, role = Role.Button, onClick = action)
        .padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.Center) {
        Text(label, color = Muted, style = MaterialTheme.typography.bodySmall)
        Text(value, style = Numeric, color = Paper)
    }
    VerticalDivider(Modifier.height(32.dp), color = Rule)
}

@Composable
internal fun RecordKey(take: Take, enabled: Boolean, action: () -> Unit) {
    val label = when (take) {
        Take.IDLE -> "Record video"
        Take.STARTING -> "Starting recording"
        Take.RECORDING -> "Recording, stop"
        Take.STOPPING -> "Stopping recording"
        Take.VERIFYING -> "Verifying saved video"
    }
    Box(Modifier.size(72.dp).clip(CircleShape)
        .semantics { contentDescription = label }
        .clickable(enabled = enabled, role = Role.Button, onClick = action),
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(3.dp)) {
            drawCircle(Paper, radius = size.minDimension / 2 - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
            val color = if (!enabled && take == Take.IDLE) Muted else Record
            if (take == Take.RECORDING) {
                val side = size.minDimension * .36f
                drawRoundRect(color, topLeft = Offset(center.x - side / 2, center.y - side / 2),
                    size = Size(side, side), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
            } else if (take == Take.IDLE) drawCircle(color, radius = size.minDimension * .38f)
        }
        if (take in listOf(Take.STARTING, Take.STOPPING, Take.VERIFYING)) {
            CircularProgressIndicator(Modifier.size(28.dp), color = Amber, strokeWidth = 2.dp)
        }
    }
}

internal enum class ToolIcon { SETTINGS, LIBRARY, FLIP, TOOLS, GRID, LOOK, BACK }

@Composable
internal fun IconKey(icon: ToolIcon, label: String, enabled: Boolean = true, selected: Boolean = false,
                     action: () -> Unit) {
    Box(Modifier.size(48.dp).clip(InstrumentShape).background(if (selected) Selected else Panel)
        .border(1.dp, Rule, InstrumentShape).semantics { contentDescription = label }
        .clickable(enabled = enabled, role = Role.Button, onClick = action), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(20.dp)) {
            val color = if (!enabled) Muted else if (selected) Amber else Paper
            val stroke = 1.8.dp.toPx()
            fun point(x: Float, y: Float) = Offset(size.width * x / 20f, size.height * y / 20f)
            fun line(x: Float, y: Float, a: Float, b: Float) =
                drawLine(color, point(x,y), point(a,b), stroke, StrokeCap.Round)
            fun box(x: Float, y: Float, w: Float, h: Float) =
                drawRect(color, point(x,y), Size(size.width*w/20, size.height*h/20), style = Stroke(stroke))
            when (icon) {
                ToolIcon.SETTINGS, ToolIcon.TOOLS -> {
                    line(2f,4f,18f,4f); line(2f,10f,18f,10f); line(2f,16f,18f,16f)
                    line(6f,2f,6f,6f); line(14f,8f,14f,12f); line(8f,14f,8f,18f)
                }
                ToolIcon.LIBRARY -> { box(2f,4f,16f,12f); line(6f,2f,16f,2f); line(7f,9f,10f,12f); line(10f,12f,14f,8f) }
                ToolIcon.FLIP -> {
                    line(3f,6f,17f,6f); line(17f,6f,13f,2f); line(17f,6f,13f,10f)
                    line(17f,14f,3f,14f); line(3f,14f,7f,10f); line(3f,14f,7f,18f)
                }
                ToolIcon.GRID -> { box(2f,2f,16f,16f); line(7f,2f,7f,18f); line(13f,2f,13f,18f); line(2f,7f,18f,7f); line(2f,13f,18f,13f) }
                ToolIcon.LOOK -> {
                    line(10f,2f,18f,10f); line(18f,10f,10f,18f); line(10f,18f,2f,10f); line(2f,10f,10f,2f); line(10f,2f,10f,18f)
                }
                ToolIcon.BACK -> { line(3f,10f,17f,10f); line(3f,10f,9f,4f); line(3f,10f,9f,16f) }
            }
        }
    }
}

@Composable
internal fun Hint(text: String, color: Color = Muted) =
    Text(text, color = color, style = MaterialTheme.typography.bodySmall)

internal fun Modifier.compareAction(label: String, action: () -> Unit): Modifier =
    semantics {
        contentDescription = label
        androidx.compose.ui.semantics.SemanticsProperties.Role.let { this[it] = Role.Button }
        androidx.compose.ui.semantics.SemanticsActions.OnClick.let {
            this[it] = androidx.compose.ui.semantics.AccessibilityAction(label) { action(); true }
        }
    }
