package com.cinematic.camera.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.cinematic.camera.capture.CameraEngine
import com.cinematic.camera.capture.CaptureState

@Composable
internal fun ProductionPanel(state: CaptureState, engine: CameraEngine) {
    val shot = state.shot
    val enabled = !state.active
    Section("Next take")
    OutlinedTextField(shot.project, { engine.updateShot(shot.copy(project = it)) },
        Modifier.fillMaxWidth(), enabled = enabled, label = { Text("Project") }, singleLine = true)
    OutlinedTextField(shot.reel, { engine.updateShot(shot.copy(reel = it)) },
        Modifier.fillMaxWidth(), enabled = enabled, label = { Text("Reel") }, singleLine = true)
    OutlinedTextField(shot.scene, { engine.updateShot(shot.copy(scene = it)) },
        Modifier.fillMaxWidth(), enabled = enabled, label = { Text("Scene / shot") }, singleLine = true)
    Row {
        Key("−", enabled = enabled && shot.take > 1) { engine.updateShot(shot.copy(take = shot.take - 1)) }
        Text("Take ${shot.take}", Modifier.weight(1f), style = Numeric)
        Key("+", enabled = enabled && shot.take < 9999) { engine.updateShot(shot.copy(take = shot.take + 1)) }
    }
    OutlinedTextField(shot.notes, { engine.updateShot(shot.copy(notes = it)) },
        Modifier.fillMaxWidth(), enabled = enabled, label = { Text("Shot notes") }, maxLines = 4)
    Hint("Metadata is frozen into each video manifest. The take counter advances when recording starts.")
    Section("Motion data")
    ToggleRow("Record raw gyroscope CSV", state.gyroEnabled, enabled && state.gyroAvailable, engine::enableGyro)
    Mark(if (state.gyroAvailable) "Native" else "Unavailable",
        if (state.gyroAvailable) "Timestamped angular velocity in rad/s, nominal 50 Hz. Not frame-synchronized stabilization metadata."
        else "Android reports no gyroscope.")
    Section("Interrupted recordings")
    Hint(if (state.recovering) "Checking journals…" else "${state.pendingRecovery} unresolved recording journal(s).")
    Key("Retry recovery", Modifier.fillMaxWidth(), enabled = enabled && !state.recovering, action = engine::recoverRecordings)
    Hint("Recover playable MP4s and their manifests after process death. Incomplete files are retained, not automatically deleted or repaired.")
}
