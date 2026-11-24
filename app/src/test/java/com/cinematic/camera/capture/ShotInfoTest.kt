package com.cinematic.camera.capture

import org.junit.Assert.*
import org.junit.Test

class ShotInfoTest {
    @Test fun boundsProductionFields() {
        val shot = ShotInfo("a".repeat(100), "b".repeat(50), "c".repeat(60), 0, "d".repeat(600)).sanitized()
        assertEquals(80, shot.project.length)
        assertEquals(40, shot.reel.length)
        assertEquals(40, shot.scene.length)
        assertEquals(1, shot.take)
        assertEquals(500, shot.notes.length)
        assertEquals(9999, shot.copy(take = 10000).sanitized().take)
    }
}
