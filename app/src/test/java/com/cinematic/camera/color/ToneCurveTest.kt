package com.cinematic.camera.color

import org.junit.Assert.*
import org.junit.Test

class ToneCurveTest {
    @Test fun identityMatchesInput() {
        for (i in 0..1000) {
            val x = i / 1000f
            assertEquals(x, ToneCurve().map(x), .00001f)
        }
    }

    @Test fun curvesInterpolateAllKnotsAndPreserveEndpoints() {
        val c = ToneCurve(.12f, .52f, .84f)
        assertEquals(0f, c.map(-1f), .00001f)
        assertEquals(.12f, c.map(.25f), .00001f)
        assertEquals(.32f, c.map(.375f), .00001f)
        assertEquals(.52f, c.map(.5f), .00001f)
        assertEquals(.84f, c.map(.75f), .00001f)
        assertEquals(1f, c.map(2f), .00001f)
    }

    @Test fun masterThenChannelMappingIsIndependent() {
        val curves = RgbCurves(master = ToneCurve(.2f, .4f, .7f), red = ToneCurve(.3f, .6f, .8f))
        val mapped = curves.map(.5f, .5f, .5f)
        assertArrayEquals(floatArrayOf(.48f, .4f, .4f), mapped, .00001f)
    }

    @Test fun rejectsInvalidKnots() {
        assertThrows(IllegalArgumentException::class.java) { ToneCurve(.8f, .5f, .9f) }
        assertThrows(IllegalArgumentException::class.java) { ToneCurve(Float.NaN, .5f, .75f) }
        assertThrows(IllegalArgumentException::class.java) { ToneCurve(.2f, .5f, 1.1f) }
    }
}
