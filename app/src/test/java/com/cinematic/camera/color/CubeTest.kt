package com.cinematic.camera.color

import org.junit.Assert.*
import org.junit.Test

class CubeTest {
    @Test fun identityInterpolatesAndClamps() {
        val cube = Cube.create(2) { r, g, b -> floatArrayOf(r, g, b) }
        assertArrayEquals(floatArrayOf(.13f, .37f, .91f), cube.sample(.13f, .37f, .91f), .00001f)
        assertArrayEquals(floatArrayOf(0f, 1f, .25f), cube.sample(-.1f, 2f, .25f), .00001f)
    }

    @Test fun redMovesFastestInCubeTable() {
        val cube = Cube.parse("""
            TITLE "Reverse channels"
            LUT_3D_SIZE 2
            0 0 0
            0 0 1
            0 1 0
            0 1 1
            1 0 0
            1 0 1
            1 1 0
            1 1 1
        """.trimIndent().reader())
        assertArrayEquals(floatArrayOf(.8f, .5f, .25f), cube.sample(.25f, .5f, .8f), .00001f)
    }

    @Test fun mapsInputDomainAndRoundTripsOutOfRangeValues() {
        val input = """
            LUT_3D_SIZE 2
            DOMAIN_MIN -1 0 0
            DOMAIN_MAX 1 2 4
            -0.1 0 0
            1.1 0 0
            -0.1 1 0
            1.1 1 0
            -0.1 0 1
            1.1 0 1
            -0.1 1 1
            1.1 1 1
        """.trimIndent()
        val start = Cube.parse(input.reader())
        val decoded = Cube.parse(buildString { start.writeTo(this) }.reader())
        assertArrayEquals(floatArrayOf(.5f, .5f, .5f), decoded.sample(0f, 1f, 2f), .00001f)
        assertEquals(1.1f, decoded.sample(1f, 0f, 0f)[0], .00001f)
    }

    @Test fun rejectsInvalidTables() {
        listOf(
            "LUT_1D_SIZE 2\n0 0 0",
            "LUT_3D_SIZE 2\n0 0 0",
            "LUT_3D_SIZE 256",
            "LUT_3D_SIZE 2\nLUT_3D_SIZE 2",
            "LUT_3D_SIZE 2\nNaN 0 0",
            "LUT_3D_SIZE 2\n0 0 0 1",
            "LUT_3D_SIZE 2\n${"0 0 0\n".repeat(9)}",
            "LUT_3D_SIZE 2\nDOMAIN_MIN 2 0 0\n${"0 0 0\n".repeat(8)}",
            "#${"x".repeat(1024)}",
        ).forEach { bad -> assertThrows(IllegalArgumentException::class.java) { Cube.parse(bad.reader()) } }
    }

    @Test fun originalCatalogHasUniqueNamesAndExportableCubes() {
        assertEquals(29, originalLooks.size)
        assertEquals(originalLooks.size, originalLooks.map { it.name }.toSet().size)
        listOf("Golden Hour", "Moonlit", "Film Print", "Neon Nocturne",
            "Forest Film", "Rainy City", "Soft Highlight").forEach { name ->
            assertTrue(originalLooks.any { it.name == name && it.cube != null })
        }
        originalLooks.drop(1).forEach { look ->
            val cube = requireNotNull(look.cube)
            val decoded = Cube.parse(buildString { cube.writeTo(this) }.reader())
            assertArrayEquals(cube.sample(.2f, .4f, .6f), decoded.sample(.2f, .4f, .6f), .00001f)
        }
    }
}
