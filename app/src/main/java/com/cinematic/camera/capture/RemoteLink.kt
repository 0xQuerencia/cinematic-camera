package com.cinematic.camera.capture

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

internal enum class RemoteCommand(val wire: Int) {
    STATUS(1), START(2), STOP(3), ZOOM_IN(4), ZOOM_OUT(5);
    companion object { fun fromWire(wire: Int) = entries.firstOrNull { it.wire == wire } }
}

internal data class RemotePeer(val name: String, val address: String)
internal data class RemoteStatus(val role: String = "Off", val peer: String = "",
                                 val connected: Boolean = false, val busy: Boolean = false,
                                 val message: String = "")

/** User-selected, bonded Bluetooth peer only; secure RFCOMM, no discovery or internet. */
@SuppressLint("MissingPermission")
internal class RemoteLink(private val context: Context, private val command: (RemoteCommand) -> String) {
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val flow = MutableStateFlow(RemoteStatus())
    val state = flow.asStateFlow()
    private val lock = Any()
    private var generation = 0
    private var socket: BluetoothSocket? = null
    private var server: BluetoothServerSocket? = null

    fun permitted() = Build.VERSION.SDK_INT < 31 ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun peers(): List<RemotePeer> {
        if (!permitted()) return emptyList()
        return runCatching { adapter?.bondedDevices.orEmpty().map { RemotePeer(it.name ?: "Paired device", it.address) }
            .sortedBy { it.name } }.getOrDefault(emptyList())
    }

    fun listen(peer: RemotePeer) = begin(peer, host = true)
    fun connect(peer: RemotePeer) = begin(peer, host = false)

    private fun begin(peer: RemotePeer, host: Boolean) {
        if (!permitted() || peers().none { it.address == peer.address }) return
        disconnect()
        val id = synchronized(lock) {
            val next = ++generation
            flow.value = RemoteStatus(if (host) "Camera" else "Controller", peer.name, message = "Opening secure link…")
            next
        }
        executor.execute {
            try {
                val bluetooth = checkNotNull(adapter)
                check(bluetooth.isEnabled) { "Turn on Bluetooth." }
                if (host) {
                    val listener = bluetooth.listenUsingRfcommWithServiceRecord("Cinematic Camera", service)
                    synchronized(lock) {
                        if (generation != id) { listener.close(); return@execute }
                        server = listener
                        flow.value = flow.value.copy(message = "Waiting for selected paired device.")
                    }
                    while (true) {
                        val accepted = listener.accept()
                        if (accepted.remoteDevice.address != peer.address) { accepted.close(); continue }
                        synchronized(lock) {
                            if (generation != id) { accepted.close(); return@execute }
                            socket = accepted
                            server = null
                            flow.value = flow.value.copy(connected = true, message = "Paired controller connected.")
                        }
                        listener.close()
                        val input = DataInputStream(accepted.inputStream)
                        val output = DataOutputStream(accepted.outputStream)
                        while (synchronized(lock) { generation == id }) {
                            val request = RemoteCommand.fromWire(input.readUnsignedByte())
                            val reply = if (request == null) "Unknown command." else {
                                val task = FutureTask {
                                    if (synchronized(lock) { generation == id }) command(request) else "Link closed."
                                }
                                main.post(task)
                                try { task.get(4, TimeUnit.SECONDS) }
                                catch (error: Exception) { task.cancel(false); "Camera did not respond." }
                            }
                            output.writeUTF(reply.take(200)); output.flush()
                            Thread.sleep(100)
                        }
                        break
                    }
                } else {
                    val device = bluetooth.getRemoteDevice(peer.address)
                    val link = device.createRfcommSocketToServiceRecord(service)
                    synchronized(lock) {
                        if (generation != id) { link.close(); return@execute }
                        socket = link
                    }
                    link.connect()
                    synchronized(lock) {
                        if (generation != id) { link.close(); return@execute }
                        flow.value = flow.value.copy(connected = true, message = "Connected to camera.")
                    }
                }
            } catch (error: Exception) {
                finish(id, error.localizedMessage?.take(120) ?: "Bluetooth link closed.")
            }
        }
    }

    fun send(request: RemoteCommand) {
        val session = synchronized(lock) {
            if (flow.value.role != "Controller" || !flow.value.connected || flow.value.busy) return
            val link = socket ?: return
            flow.value = flow.value.copy(busy = true)
            generation to link
        }
        executor.execute {
            try {
                DataOutputStream(session.second.outputStream).apply { writeByte(request.wire); flush() }
                val reply = DataInputStream(session.second.inputStream).readUTF().take(200)
                synchronized(lock) {
                    if (generation == session.first) flow.value = flow.value.copy(busy = false, message = reply)
                }
            } catch (error: Exception) { finish(session.first, "Controller connection lost.") }
        }
    }

    private fun finish(id: Int, message: String) {
        synchronized(lock) {
            if (generation != id) return
            closeSockets()
            flow.value = RemoteStatus(message = message)
        }
    }

    fun disconnect() {
        synchronized(lock) {
            generation++
            closeSockets()
            flow.value = RemoteStatus()
        }
    }

    private fun closeSockets() {
        runCatching { server?.close() }; server = null
        runCatching { socket?.close() }; socket = null
    }

    fun close() { disconnect(); executor.shutdownNow() }

    companion object { private val service = UUID.fromString("2e8d4a7d-0f41-4844-9643-75cd12cb8370") }
}
