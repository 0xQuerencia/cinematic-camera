package com.cinematic.camera.capture

import org.junit.Assert.*
import org.junit.Test

class BracketTest {
    @Test fun bracketRespectsNativeEvSteps() {
        assertEquals(listOf(-3, 0, 3), Bracket.indices(0, 1f / 3f, -6, 6, 1f))
        assertEquals(listOf(0, 2, 4), Bracket.indices(2, .5f, -4, 4, 1f))
    }
    @Test fun refusesClippedOrInvalidBrackets() {
        assertTrue(Bracket.indices(4, .5f, -4, 4, 1f).isEmpty())
        assertTrue(Bracket.indices(0, 0f, -4, 4, 1f).isEmpty())
        assertTrue(Bracket.indices(0, Float.NaN, -4, 4, 1f).isEmpty())
    }
}
