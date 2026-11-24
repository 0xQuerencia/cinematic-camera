package com.cinematic.camera.color

import org.junit.Assert.*
import org.junit.Test

class ColorToolsTest {
    @Test fun wheelClampsToUnitDisk() {
        val value = Wheel.at(1f,1f)
        assertEquals(1f,value.x*value.x+value.y*value.y,.0001f)
        assertEquals(Wheel(),Wheel.at(0f,0f))
    }
    @Test fun selectiveBandUpdatesOnlyOneColor() {
        val hsl = SelectiveHsl().update(2,HueBand(15f,.2f,.05f))
        assertEquals(HueBand(),hsl.bands[1])
        assertEquals(HueBand(15f,.2f,.05f),hsl.bands[2])
        assertEquals(18,hsl.uniforms().size)
        assertThrows(IllegalArgumentException::class.java) { HueBand(40f) }
    }
}
