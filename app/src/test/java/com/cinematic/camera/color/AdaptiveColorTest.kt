package com.cinematic.camera.color

import org.junit.Assert.*
import org.junit.Test

class AdaptiveColorTest {
    private fun ramp(warm: Int = 0): ByteArray = ByteArray(64 * 36 * 4).also { frame ->
        for (i in 0 until 64 * 36) {
            val y = 30 + i % 64 * 3
            frame[i * 4] = (y + warm).coerceIn(0, 255).toByte()
            frame[i * 4 + 1] = y.toByte()
            frame[i * 4 + 2] = (y - warm).coerceIn(0, 255).toByte()
            frame[i * 4 + 3] = 255.toByte()
        }
    }

    @Test fun generatedSceneIsFiniteAndPortable() {
        val frame = ramp(30)
        val choice = requireNotNull(SceneAutoLook.recommend(frame))
        val look = requireNotNull(AdaptiveColor.scene(frame, choice))
        val cube = requireNotNull(look.cube)
        assertEquals(17, cube.size)
        assertTrue(cube.samples.all { it in 0f..1f })
        val roundtrip = Cube.parse(buildString { cube.writeTo(this) }.reader())
        assertArrayEquals(cube.sample(.4f,.5f,.6f), roundtrip.sample(.4f,.5f,.6f), .0001f)
        assertEquals(3, AdaptiveColor.alternatives(choice).size)
        assertTrue(AdaptiveColor.alternatives(choice).all { c -> originalLooks.any { it.name == c.name } })
    }

    @Test fun referenceWarmthTransfersWithoutOutOfRangeValues() {
        val cube = requireNotNull(AdaptiveColor.reference(ramp(), ramp(30))?.cube)
        val middle = cube.sample(.5f,.5f,.5f)
        assertTrue(middle[0] > middle[2])
        assertTrue(cube.samples.all { it in 0f..1f })
    }

    @Test fun blackAndFlatSourcesAreRejected() {
        val black = ByteArray(64 * 36 * 4)
        assertNull(AdaptiveColor.reference(black, ramp()))
        assertNull(AdaptiveColor.scene(black, AutoLookChoice("Film Print", .6f, "Test")))
        assertNull(AdaptiveColor.profile(ByteArray(3)))
    }
}
