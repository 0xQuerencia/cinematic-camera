package com.cinematic.camera.capture

import org.junit.Assert.*
import org.junit.Test

class RemoteCommandTest {
    @Test fun wireProtocolIsAllowlisted() {
        assertEquals(RemoteCommand.START, RemoteCommand.fromWire(2))
        assertEquals(RemoteCommand.STOP, RemoteCommand.fromWire(3))
        assertNull(RemoteCommand.fromWire(0))
        assertNull(RemoteCommand.fromWire(255))
        assertEquals(RemoteCommand.entries.size, RemoteCommand.entries.map { it.wire }.distinct().size)
    }
}
