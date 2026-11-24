package com.cinematic.camera.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cinematic.camera.capture.RemoteCommand
import com.cinematic.camera.capture.RemoteLink
import com.cinematic.camera.capture.RemotePeer

@Composable
internal fun RemotePanel(link: RemoteLink) {
    val state by link.state.collectAsStateWithLifecycle()
    var peers by remember { mutableStateOf(link.peers()) }
    var selected by remember { mutableStateOf<RemotePeer?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        peers = link.peers()
    }
    Hint("Pair two Android devices in system Bluetooth settings. Open Cinematic Camera on both.")
    if (Build.VERSION.SDK_INT >= 31 && !link.permitted()) {
        Key("Allow nearby devices", Modifier.fillMaxWidth()) { permission.launch(Manifest.permission.BLUETOOTH_CONNECT) }
        return
    }
    if (state.role == "Off") {
        Key("Refresh paired devices") { peers = link.peers(); selected = null }
        if (peers.isEmpty()) Hint("No paired Bluetooth devices reported.")
        peers.forEach { peer ->
            Key(peer.name, Modifier.fillMaxWidth(), selected = selected == peer) { selected = peer }
        }
        Section("Role")
        Key("Use this phone as camera", Modifier.fillMaxWidth(), enabled = selected != null) { selected?.let(link::listen) }
        Key("Use this phone as controller", Modifier.fillMaxWidth(), enabled = selected != null) { selected?.let(link::connect) }
        Hint("The camera accepts commands only from the selected bonded peer while foregrounded. No footage is streamed.")
    } else {
        Hint("${state.role} · ${state.peer}", Amber)
        Hint(if (state.connected) "Connected" else "Connecting…", Info)
        if (state.role == "Controller") {
            val enabled = state.connected && !state.busy
            KeysRow {
                Key("Status", enabled = enabled) { link.send(RemoteCommand.STATUS) }
                Key("Start", enabled = enabled) { link.send(RemoteCommand.START) }
                Key("Stop", enabled = enabled) { link.send(RemoteCommand.STOP) }
            }
            KeysRow {
                Key("Zoom −", enabled = enabled) { link.send(RemoteCommand.ZOOM_OUT) }
                Key("Zoom +", enabled = enabled) { link.send(RemoteCommand.ZOOM_IN) }
            }
        }
        Key("Disconnect", Modifier.fillMaxWidth(), action = link::disconnect)
    }
    if (state.message.isNotBlank()) Hint(state.message)
}
