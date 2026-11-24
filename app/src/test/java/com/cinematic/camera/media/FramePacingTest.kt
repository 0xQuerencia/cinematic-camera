package com.cinematic.camera.media

import org.junit.Assert.*
import org.junit.Test

class FramePacingTest {
    @Test fun countsLongIntervalsInPresentationOrder() {
        val result = requireNotNull(FramePacing.analyze(longArrayOf(0,66_000,33_000,99_000,198_000)))
        assertEquals(33_000L,result.median)
        assertEquals(99_000L,result.max)
        assertEquals(1,result.longIntervals)
    }
    @Test fun ignoresDuplicatesAndEmptyTimeline() {
        assertNull(FramePacing.analyze(longArrayOf(0,0)))
        assertNull(FramePacing.analyze(longArrayOf()))
    }
}
