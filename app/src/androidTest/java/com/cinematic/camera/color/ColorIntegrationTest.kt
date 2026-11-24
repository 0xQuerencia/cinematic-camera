package com.cinematic.camera.color

import android.opengl.EGL14 as EGL
import android.opengl.EGLConfig
import android.opengl.GLES30 as GL
import android.opengl.Matrix
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ColorIntegrationTest {
    @Test fun gradeAndPresetRoundTrip() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = LookStorage(context)
        val grade = Grade(look = originalLooks[6], strength = .37f, exposure = .25f, grain = .1f,
            curves = RgbCurves(red = ToneCurve(.15f, .4f, .85f)), skinProtection = .65f,
            balance = ColorBalance(shadows = Wheel(.2f, -.1f)),
            hsl = SelectiveHsl(List(6) { if (it == 2) HueBand(.1f, -.2f, .15f) else HueBand() }), diffusion = .3f)
        val initial = SavedLook("round-trip", "Test grade", grade, JSONObject().put("fps", 30))
        val decoded = storage.decode(initial.id, storage.encode(initial))
        assertEquals(initial.name, decoded.name)
        assertEquals(30, decoded.capture?.getInt("fps"))
        assertEquals(.37f, decoded.grade.strength, .00001f)
        assertEquals(.25f, decoded.grade.exposure, .00001f)
        assertEquals(grade.curves, decoded.grade.curves)
        assertEquals(.65f, decoded.grade.skinProtection, .00001f)
        assertEquals(grade.balance, decoded.grade.balance)
        assertEquals(grade.hsl, decoded.grade.hsl)
        assertEquals(.3f, decoded.grade.diffusion, .00001f)
        val legacy = JSONObject(storage.encode(initial))
        legacy.getJSONObject("grade").apply { remove("curves"); remove("skinProtection"); remove("balance"); remove("hsl"); remove("diffusion") }
        val legacyGrade = storage.decode(initial.id, legacy.toString()).grade
        assertEquals(RgbCurves(), legacyGrade.curves)
        assertEquals(0f, legacyGrade.skinProtection, .00001f)
        assertEquals(ColorBalance(), legacyGrade.balance)
        assertEquals(SelectiveHsl(), legacyGrade.hsl)
        assertEquals(0f, legacyGrade.diffusion, .00001f)
        val expected = requireNotNull(grade.look.cube).sample(.14f, .43f, .89f)
        assertArrayEquals(expected, requireNotNull(decoded.grade.look.cube).sample(.14f, .43f, .89f), .00001f)
        assertThrows(IllegalArgumentException::class.java) {
            storage.decode(initial.id, storage.encode(initial).replaceFirst("\"version\": 1", "\"version\": 999"))
        }
    }

    @Test fun gpuLutMatchesCpuReferenceAtThreeStrengths() {
        val display = EGL.eglGetDisplay(EGL.EGL_DEFAULT_DISPLAY)
        assertTrue(EGL.eglInitialize(display, null, 0, null, 0))
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        assertTrue(EGL.eglChooseConfig(display, intArrayOf(EGL.EGL_RENDERABLE_TYPE, 0x40,
            EGL.EGL_SURFACE_TYPE, EGL.EGL_PBUFFER_BIT, EGL.EGL_RED_SIZE, 8, EGL.EGL_GREEN_SIZE, 8,
            EGL.EGL_BLUE_SIZE, 8, EGL.EGL_NONE), 0, configs, 0, 1, count, 0))
        val config = requireNotNull(configs[0])
        val context = EGL.eglCreateContext(display, config, EGL.EGL_NO_CONTEXT,
            intArrayOf(EGL.EGL_CONTEXT_CLIENT_VERSION, 3, EGL.EGL_NONE), 0)
        val surface = EGL.eglCreatePbufferSurface(display, config,
            intArrayOf(EGL.EGL_WIDTH, 3, EGL.EGL_HEIGHT, 1, EGL.EGL_NONE), 0)
        try {
            assertTrue(EGL.eglMakeCurrent(display, surface, surface, context))
            val colors = intArrayOf(25, 105, 190, 80, 216, 11, 238, 76, 122)
            val buffer = ByteBuffer.allocateDirect(12)
            colors.asList().chunked(3).forEach { pixel ->
                pixel.forEach { buffer.put(it.toByte()) }
                buffer.put(255.toByte())
            }
            buffer.flip()
            val id = IntArray(1)
            GL.glGenTextures(1, id, 0)
            GL.glBindTexture(GL.GL_TEXTURE_2D, id[0])
            parameters(GL.GL_TEXTURE_2D)
            GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA8, 3, 1, 0, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, buffer)
            GL.glViewport(0, 0, 3, 1)
            val cube = Cube.create(3) { r, g, b -> floatArrayOf(b, r * .7f + g * .3f, 1 - r) }
            val matrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
            GradeShader(GL.GL_TEXTURE_2D).use { shader ->
                for (strength in listOf(0f, .4f, 1f)) {
                    shader.prepare(Grade(Look("Test", "Test", cube), strength), 0f, false, id[0])
                    shader.draw(matrix, enabled = true, split = false)
                    buffer.clear()
                    GL.glReadPixels(0, 0, 3, 1, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, buffer)
                    assertEquals(GL.GL_NO_ERROR, GL.glGetError())
                    for (i in 0..2) {
                        val input = FloatArray(3) { colors[i * 3 + it] / 255f }
                        val graded = cube.sample(input[0], input[1], input[2])
                        for (channel in 0..2) {
                            val expected = input[channel] * (1 - strength) + graded[channel] * strength
                            val actual = (buffer[i * 4 + channel].toInt() and 255) / 255f
                            assertEquals("Pixel $i, channel $channel, strength $strength", expected, actual, .013f)
                        }
                    }
                }
                val curves = RgbCurves(master = ToneCurve(.1f, .45f, .9f), red = ToneCurve(.3f, .6f, .8f))
                shader.prepare(Grade(curves = curves), 0f, false, id[0])
                shader.draw(matrix, enabled = true, split = false)
                buffer.clear()
                GL.glReadPixels(0, 0, 3, 1, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, buffer)
                assertEquals(GL.GL_NO_ERROR, GL.glGetError())
                for (i in 0..2) {
                    val expected = curves.map(colors[i * 3] / 255f, colors[i * 3 + 1] / 255f, colors[i * 3 + 2] / 255f)
                    for (channel in 0..2) {
                        val actual = (buffer[i * 4 + channel].toInt() and 255) / 255f
                        assertEquals("Curve pixel $i channel $channel", expected[channel], actual, .013f)
                    }
                }
            }
        } finally {
            EGL.eglMakeCurrent(display, EGL.EGL_NO_SURFACE, EGL.EGL_NO_SURFACE, EGL.EGL_NO_CONTEXT)
            EGL.eglDestroySurface(display, surface)
            EGL.eglDestroyContext(display, context)
            EGL.eglTerminate(display)
            EGL.eglReleaseThread()
        }
    }
}
