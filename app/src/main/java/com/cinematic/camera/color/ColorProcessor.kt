package com.cinematic.camera.color

import android.graphics.SurfaceTexture
import android.opengl.EGL14 as EGL
import android.opengl.EGLConfig
import android.opengl.EGLSurface
import android.opengl.EGLExt
import android.opengl.GLES30 as GL
import android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.ProcessingException
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor

class ColorProcessor(
    val bake: Boolean,
    private val samples: (ByteArray) -> Unit,
    private val failure: (Throwable) -> Unit,
) : SurfaceProcessor, AutoCloseable {
    private val thread = HandlerThread("Cinematic GPU").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    val executor = Executor { handler.post(it) }
    val effect = object : CameraEffect(if (bake) PREVIEW or VIDEO_CAPTURE else PREVIEW, executor, this, { report(it) }) {}
    @Volatile var grade = Grade()
    @Volatile var compare = false
    @Volatile var split = false
    @Volatile var lite = false
    @Volatile var scopeEnabled = false
    @Volatile var autoSampleEnabled = false

    private var display = EGL.EGL_NO_DISPLAY
    private var context = EGL.EGL_NO_CONTEXT
    private var pbuffer = EGL.EGL_NO_SURFACE
    private lateinit var config: EGLConfig
    private lateinit var shader: GradeShader
    private var scopeFramebuffer = 0
    private var nextScopeNs = 0L
    private val scopeBuffer by lazy { ByteBuffer.allocateDirect(64 * 36 * 4) }
    private var ready = false
    private var retired = false
    private var failed = false
    private var current: SurfaceTexture? = null
    private var inputs = 0
    private val outputs = linkedMapOf<SurfaceOutput, EGLSurface>()
    private val transform = FloatArray(16)
    private val cameraTransform = FloatArray(16)

    private fun init() {
        if (ready) return
        display = EGL.eglGetDisplay(EGL.EGL_DEFAULT_DISPLAY)
        check(EGL.eglInitialize(display, null, 0, null, 0)) { "Could not initialize EGL." }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(
            EGL.EGL_RENDERABLE_TYPE, 0x40,
            EGL.EGL_SURFACE_TYPE, EGL.EGL_WINDOW_BIT or EGL.EGL_PBUFFER_BIT,
            EGL.EGL_RED_SIZE, 8, EGL.EGL_GREEN_SIZE, 8, EGL.EGL_BLUE_SIZE, 8,
            0x3142, 1, EGL.EGL_NONE
        )
        check(EGL.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "No recordable OpenGL ES 3 config."
        }
        config = checkNotNull(configs[0])
        context = EGL.eglCreateContext(display, config, EGL.EGL_NO_CONTEXT,
            intArrayOf(EGL.EGL_CONTEXT_CLIENT_VERSION, 3, EGL.EGL_NONE), 0)
        check(context != EGL.EGL_NO_CONTEXT) { "Could not create an ES 3 context." }
        pbuffer = EGL.eglCreatePbufferSurface(display, config,
            intArrayOf(EGL.EGL_WIDTH, 1, EGL.EGL_HEIGHT, 1, EGL.EGL_NONE), 0)
        makeCurrent(pbuffer)
        shader = GradeShader(GL_TEXTURE_EXTERNAL_OES)
        ready = true
    }

    private fun makeCurrent(surface: EGLSurface) {
        check(surface != EGL.EGL_NO_SURFACE && EGL.eglMakeCurrent(display, surface, surface, context)) {
            "Could not activate GPU surface (0x${EGL.eglGetError().toString(16)})."
        }
    }

    override fun onInputSurface(request: SurfaceRequest) {
        if (retired || failed) {
            request.willNotProvideSurface()
            return
        }
        try {
            init()
            makeCurrent(pbuffer)
            val name = IntArray(1)
            GL.glGenTextures(1, name, 0)
            GL.glBindTexture(GL_TEXTURE_EXTERNAL_OES, name[0])
            parameters(GL_TEXTURE_EXTERNAL_OES)
            val texture = SurfaceTexture(name[0]).apply {
                setDefaultBufferSize(request.resolution.width, request.resolution.height)
            }
            val surface = Surface(texture)
            current = texture
            inputs++
            request.provideSurface(surface, executor) {
                if (current === texture) current = null
                texture.setOnFrameAvailableListener(null)
                surface.release()
                texture.release()
                if (ready) {
                    makeCurrent(pbuffer)
                    GL.glDeleteTextures(1, name, 0)
                }
                inputs--
                releaseIfIdle()
            }
            texture.setOnFrameAvailableListener({ input ->
                if (input === current && !retired && !failed) {
                    runCatching { draw(input) }.onFailure { report(it) }
                }
            }, handler)
        } catch (error: Exception) {
            request.willNotProvideSurface()
            report(error)
            throw ProcessingException()
        }
    }

    override fun onOutputSurface(output: SurfaceOutput) {
        if (retired || failed) {
            output.close()
            return
        }
        try {
            init()
            val surface = output.getSurface(executor) { event -> remove(event.surfaceOutput) }
            val window = EGL.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL.EGL_NONE), 0)
            check(window != EGL.EGL_NO_SURFACE) { "Could not create a GPU output surface." }
            makeCurrent(window)
            EGL.eglSwapInterval(display, 0)
            outputs[output] = window
        } catch (error: Exception) {
            output.close()
            report(error)
            throw ProcessingException()
        }
    }

    private fun draw(input: SurfaceTexture) {
        makeCurrent(pbuffer)
        input.updateTexImage()
        if (outputs.isEmpty()) return
        input.getTransformMatrix(cameraTransform)
        val value = grade
        shader.prepare(value, (input.timestamp / 1_000_000L % 1024).toFloat(), lite && !bake)
        for ((output, window) in outputs) {
            makeCurrent(window)
            output.updateTransformMatrix(transform, cameraTransform)
            GL.glViewport(0, 0, output.size.width, output.size.height)
            shader.draw(transform, !compare, split)
            EGLExt.eglPresentationTimeANDROID(display, window, input.timestamp)
            check(EGL.eglSwapBuffers(display, window)) { "GPU output swap failed." }
        }
        if ((scopeEnabled || autoSampleEnabled) && input.timestamp >= nextScopeNs) {
            nextScopeNs = input.timestamp + when {
                scopeEnabled && lite -> 650_000_000L
                scopeEnabled -> 350_000_000L
                else -> 1_200_000_000L
            }
            runCatching { readScope() }.onFailure {
                scopeEnabled = false
                autoSampleEnabled = false
                android.util.Log.w("CinematicCamera", "GPU sample readback disabled", it)
                samples(ByteArray(0))
            }
        }
    }

    private fun readScope() {
        makeCurrent(pbuffer)
        try {
            if (scopeFramebuffer == 0) {
                val id = IntArray(1)
                GL.glGenTextures(1, id, 0)
                GL.glBindTexture(GL.GL_TEXTURE_2D, id[0])
                parameters(GL.GL_TEXTURE_2D)
                GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA8, 64, 36, 0,
                    GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, null)
                val texture = id[0]
                GL.glGenFramebuffers(1, id, 0)
                scopeFramebuffer = id[0]
                GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, scopeFramebuffer)
                GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, texture, 0)
                check(GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER) == GL.GL_FRAMEBUFFER_COMPLETE)
            }
            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, scopeFramebuffer)
            GL.glViewport(0, 0, 64, 36)
            shader.draw(cameraTransform, false, false)
            scopeBuffer.clear()
            GL.glReadPixels(0, 0, 64, 36, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, scopeBuffer)
            check(GL.glGetError() == GL.GL_NO_ERROR)
            samples(ByteArray(scopeBuffer.capacity()).also { scopeBuffer.get(it) })
        } finally {
            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
        }
    }

    private fun remove(output: SurfaceOutput) {
        outputs.remove(output)?.let { window ->
            if (ready) makeCurrent(pbuffer)
            EGL.eglDestroySurface(display, window)
        }
        output.close()
    }

    private fun report(error: Throwable) {
        if (failed || retired) return
        failed = true
        main.post { failure(error) }
    }

    override fun close() {
        handler.post {
            retired = true
            outputs.keys.toList().forEach { remove(it) }
            releaseIfIdle()
        }
    }

    private fun releaseIfIdle() {
        if (!retired || inputs != 0) return
        if (display != EGL.EGL_NO_DISPLAY) {
            EGL.eglMakeCurrent(display, EGL.EGL_NO_SURFACE, EGL.EGL_NO_SURFACE, EGL.EGL_NO_CONTEXT)
            if (pbuffer != EGL.EGL_NO_SURFACE) EGL.eglDestroySurface(display, pbuffer)
            if (context != EGL.EGL_NO_CONTEXT) EGL.eglDestroyContext(display, context)
            EGL.eglTerminate(display)
            EGL.eglReleaseThread()
        }
        thread.quitSafely()
    }

}
