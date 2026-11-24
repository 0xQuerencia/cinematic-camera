package com.cinematic.camera.capture

import org.junit.Assert.*
import org.junit.Test

class ExposureMathTest {
    @org.junit.Test fun matchedIsoPreservesExposureWithinSensorLimits() {
        org.junit.Assert.assertEquals(200, ExposureMath.matchedIso(100, 20_000_000, 10_000_000, 50, 1600))
        org.junit.Assert.assertEquals(1600, ExposureMath.matchedIso(800, 30_000_000, 10_000_000, 50, 1600))
        org.junit.Assert.assertEquals(50, ExposureMath.matchedIso(100, 1_000_000, 20_000_000, 50, 1600))
    }

    @Test fun standardShutterAngles() {
        assertEquals(20_833_333L, ExposureMath.angleToNanoseconds(180.0, 24))
        assertEquals(16_666_667L, ExposureMath.angleToNanoseconds(180.0, 30))
        assertEquals(180.0, ExposureMath.nanosecondsToAngle(16_666_667L, 30)!!, .001)
    }

    @Test fun mainsCandidatesRespectFrameAndSensorLimits() {
        assertEquals(listOf(10_000_000L, 20_000_000L, 30_000_000L),
            ExposureMath.mainsCandidates(30, 50, 1_000_000L, 40_000_000L))
        assertEquals(listOf(8_333_333L),
            ExposureMath.mainsCandidates(60, 60, 1_000_000L, 20_000_000L))
        assertTrue(ExposureMath.mainsCandidates(120, 50, 1_000_000L, 40_000_000L).isEmpty())
    }

    @Test fun invalidValuesDoNotProduceExposureRequests() {
        assertNull(ExposureMath.nanosecondsToAngle(0, 30))
        assertThrows(IllegalArgumentException::class.java) { ExposureMath.angleToNanoseconds(361.0, 30) }
    }
}
