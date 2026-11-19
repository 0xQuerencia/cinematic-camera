package com.cinematic.camera.color

import org.junit.Assert.*
import org.junit.Test

class SceneAutoLookTest {
    private fun frame(red: Int, green: Int, blue: Int): ByteArray =
        ByteArray(64 * 36 * 4).also { pixels ->
            for (i in pixels.indices step 4) {
                pixels[i] = red.toByte()
                pixels[i + 1] = green.toByte()
                pixels[i + 2] = blue.toByte()
                pixels[i + 3] = 255.toByte()
            }
        }

    @Test fun sceneRecommendationsMatchVisibleColorAndBrightness() {
        assertEquals("Golden Hour", SceneAutoLook.recommend(frame(190, 110, 60))?.name)
        assertEquals("Forest Film", SceneAutoLook.recommend(frame(55, 145, 60))?.name)
        assertEquals("Rainy City", SceneAutoLook.recommend(frame(50, 110, 180))?.name)
        assertEquals("Moonlit", SceneAutoLook.recommend(frame(30, 34, 45))?.name)
        assertEquals("Neon Nocturne", SceneAutoLook.recommend(frame(60, 10, 120))?.name)
    }

    @Test fun blackOrInvalidSamplesCannotSelectALook() {
        assertNull(SceneAutoLook.recommend(frame(0, 0, 0)))
        assertNull(SceneAutoLook.recommend(ByteArray(8)))
    }

    @Test fun selectionWaitsForAgreementAndDoesNotOscillate() {
        val picker = AutoLookSelector(3, 6000)
        val warm = frame(190, 110, 60)
        val cool = frame(50, 110, 180)
        assertNull(picker.consider(warm, 0))
        assertNull(picker.consider(warm, 1000))
        assertEquals("Golden Hour", picker.consider(warm, 2000)?.name)
        assertNull(picker.consider(cool, 3000))
        assertNull(picker.consider(cool, 4000))
        assertNull(picker.consider(cool, 5000))
        assertEquals("Rainy City", picker.consider(cool, 9000)?.name)
        picker.reset()
        assertNull(picker.consider(warm, 10000))
    }
}
