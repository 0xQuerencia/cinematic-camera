package com.cinematic.camera.color

import android.annotation.SuppressLint
import android.opengl.GLES30 as GL
import android.util.Half
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class GradeShader(private val inputTarget: Int) : AutoCloseable {
    private val program: Int
    private val vao: Int
    private val lut: Int
    private var uploaded: Cube? = null
    private val uniforms = mutableMapOf<String, Int>()

    init {
        val vertex = compile(GL.GL_VERTEX_SHADER, vertexShader)
        val source = if (inputTarget == GL.GL_TEXTURE_2D) fragmentShader
            .replace("#extension GL_OES_EGL_image_external_essl3 : require", "")
            .replace("samplerExternalOES", "sampler2D") else fragmentShader
        val fragment = compile(GL.GL_FRAGMENT_SHADER, source)
        program = GL.glCreateProgram()
        GL.glAttachShader(program, vertex)
        GL.glAttachShader(program, fragment)
        GL.glLinkProgram(program)
        GL.glDeleteShader(vertex)
        GL.glDeleteShader(fragment)
        val linked = IntArray(1)
        GL.glGetProgramiv(program, GL.GL_LINK_STATUS, linked, 0)
        check(linked[0] != 0) { GL.glGetProgramInfoLog(program) }
        val id = IntArray(1)
        GL.glGenVertexArrays(1, id, 0)
        vao = id[0]
        GL.glGenTextures(1, id, 0)
        lut = id[0]
        GL.glActiveTexture(GL.GL_TEXTURE1)
        GL.glBindTexture(GL.GL_TEXTURE_3D, lut)
        parameters(GL.GL_TEXTURE_3D)
        GL.glTexParameteri(GL.GL_TEXTURE_3D, GL.GL_TEXTURE_WRAP_R, GL.GL_CLAMP_TO_EDGE)
        GL.glTexImage3D(GL.GL_TEXTURE_3D, 0, GL.GL_RGBA16F, 1, 1, 1, 0, GL.GL_RGBA, GL.GL_HALF_FLOAT, null)
        GL.glActiveTexture(GL.GL_TEXTURE0)
    }

    @SuppressLint("HalfFloat")
    fun prepare(value: Grade, seed: Float, lite: Boolean, inputName: Int? = null) {
        val cube = value.look.cube
        GL.glActiveTexture(GL.GL_TEXTURE1)
        GL.glBindTexture(GL.GL_TEXTURE_3D, lut)
        if (uploaded !== cube) {
            if (cube != null) {
                val buffer = ByteBuffer.allocateDirect(cube.samples.size / 3 * 4 * Short.SIZE_BYTES)
                    .order(ByteOrder.nativeOrder()).asShortBuffer()
                for (offset in cube.samples.indices step 3) {
                    buffer.put(Half.toHalf(cube.samples[offset]))
                    buffer.put(Half.toHalf(cube.samples[offset + 1]))
                    buffer.put(Half.toHalf(cube.samples[offset + 2]))
                    buffer.put(Half.toHalf(1f))
                }
                buffer.flip()
                GL.glTexImage3D(GL.GL_TEXTURE_3D, 0, GL.GL_RGBA16F, cube.size, cube.size, cube.size,
                    0, GL.GL_RGBA, GL.GL_HALF_FLOAT, buffer)
                check(GL.glGetError() == GL.GL_NO_ERROR) { "GPU LUT upload failed." }
            }
            uploaded = cube
        }
        GL.glActiveTexture(GL.GL_TEXTURE0)
        inputName?.let { GL.glBindTexture(inputTarget, it) }
        GL.glUseProgram(program)
        GL.glBindVertexArray(vao)
        GL.glUniform1i(location("camera"), 0)
        GL.glUniform1i(location("cube"), 1)
        GL.glUniform1f(location("edge"), cube?.size?.toFloat() ?: 1f)
        if (cube != null) {
            GL.glUniform3fv(location("domainMin"), 1, cube.min, 0)
            GL.glUniform3fv(location("domainMax"), 1, cube.max, 0)
        }
        GL.glUniform4f(location("light"), value.exposure, value.contrast, value.curve, value.highlights)
        GL.glUniform4f(location("color"), value.saturation, value.temperature, value.tint,
            if (cube != null) value.strength else 0f)
        GL.glUniform4f(location("film"), value.grain, value.vignette, value.bloom, value.halation)
        GL.glUniform3fv(location("masterCurve"), 1, value.curves.master.values(), 0)
        GL.glUniform3fv(location("redCurve"), 1, value.curves.red.values(), 0)
        GL.glUniform3fv(location("greenCurve"), 1, value.curves.green.values(), 0)
        GL.glUniform3fv(location("blueCurve"), 1, value.curves.blue.values(), 0)
        GL.glUniform1f(location("skinProtection"), value.skinProtection)
        GL.glUniform1f(location("seed"), seed)
        GL.glUniform1i(location("lite"), if (lite) 1 else 0)
    }

    fun draw(transform: FloatArray, enabled: Boolean, split: Boolean) {
        GL.glUniformMatrix4fv(location("transform"), 1, false, transform, 0)
        GL.glUniform1i(location("gradeEnabled"), if (enabled) 1 else 0)
        GL.glUniform1i(location("split"), if (split) 1 else 0)
        GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun close() {
        GL.glDeleteTextures(1, intArrayOf(lut), 0)
        GL.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        GL.glDeleteProgram(program)
    }

    private fun location(key: String) = uniforms.getOrPut(key) { GL.glGetUniformLocation(program, key) }

    private fun compile(type: Int, source: String): Int {
        val shader = GL.glCreateShader(type)
        GL.glShaderSource(shader, source)
        GL.glCompileShader(shader)
        val status = IntArray(1)
        GL.glGetShaderiv(shader, GL.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val message = GL.glGetShaderInfoLog(shader)
            GL.glDeleteShader(shader)
            error(message)
        }
        return shader
    }
}

internal fun parameters(target: Int) {
    GL.glTexParameteri(target, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
    GL.glTexParameteri(target, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
    GL.glTexParameteri(target, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
    GL.glTexParameteri(target, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
}

private val vertexShader = """
    #version 300 es
    uniform mat4 transform;
    out vec2 uv;
    out vec2 frame;
    void main() {
        frame = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
        gl_Position = vec4(frame * 2.0 - 1.0, 0.0, 1.0);
        uv = (transform * vec4(frame, 0.0, 1.0)).xy;
    }
""".trimIndent()

private val fragmentShader = """
    #version 300 es
    #extension GL_OES_EGL_image_external_essl3 : require
    precision highp float;
    uniform samplerExternalOES camera;
    uniform highp sampler3D cube;
    uniform vec3 domainMin;
    uniform vec3 domainMax;
    uniform vec4 light;
    uniform vec4 color;
    uniform vec4 film;
    uniform vec3 masterCurve;
    uniform vec3 redCurve;
    uniform vec3 greenCurve;
    uniform vec3 blueCurve;
    uniform float skinProtection;
    uniform float edge;
    uniform float seed;
    uniform int gradeEnabled;
    uniform int split;
    uniform int lite;
    in vec2 uv;
    in vec2 frame;
    out vec4 outputColor;
    const vec3 luma = vec3(.2126, .7152, .0722);
    float curveAt(float inputValue, vec3 knots) {
        float x = clamp(inputValue, 0.0, 1.0) * 4.0;
        if (x < 1.0) return x * knots.x;
        if (x < 2.0) return mix(knots.x, knots.y, x - 1.0);
        if (x < 3.0) return mix(knots.y, knots.z, x - 2.0);
        return mix(knots.z, 1.0, x - 3.0);
    }
    float warmMask(vec3 src) {
        float chroma = src.r - src.b;
        float hue = (src.r - src.g) / max(chroma, .001);
        float y = dot(src, luma);
        return smoothstep(.03, .12, chroma)
            * smoothstep(.10, .30, hue) * (1.0 - smoothstep(.70, .90, hue))
            * smoothstep(.05, .20, y) * (1.0 - smoothstep(.82, .98, y));
    }
    void main() {
        vec3 source = texture(camera, uv).rgb;
        if (gradeEnabled == 0 || (split == 1 && frame.x < .5)) {
            outputColor = vec4(source, 1.0);
            return;
        }
        vec3 rgb = source * exp2(light.x);
        rgb = (rgb - .5) * light.y + .5;
        rgb *= vec3(1.0 + color.y + color.z*.4, 1.0 - color.z*.5, 1.0 - color.y + color.z*.4);
        rgb = mix(vec3(dot(rgb, luma)), rgb, color.x);
        rgb = pow(clamp(rgb, 0.0, 1.0), vec3(light.z));
        rgb -= light.w * pow(rgb, vec3(3.0));
        vec3 preLook = rgb;
        if (color.w > 0.0) {
            vec3 coord = clamp((rgb - domainMin) / (domainMax - domainMin), 0.0, 1.0);
            rgb = mix(rgb, texture(cube, (coord * (edge - 1.0) + .5) / edge).rgb, color.w);
        }
        // Identity curves leave even out-of-range imported LUT values unchanged.
        if (any(notEqual(masterCurve, vec3(.25, .5, .75))) ||
            any(notEqual(redCurve, vec3(.25, .5, .75))) ||
            any(notEqual(greenCurve, vec3(.25, .5, .75))) ||
            any(notEqual(blueCurve, vec3(.25, .5, .75)))) {
            rgb = vec3(curveAt(curveAt(rgb.r, masterCurve), redCurve),
                       curveAt(curveAt(rgb.g, masterCurve), greenCurve),
                       curveAt(curveAt(rgb.b, masterCurve), blueCurve));
        }
        if (skinProtection > 0.0) {
            vec3 restored = vec3(dot(rgb, luma)) + preLook - vec3(dot(preLook, luma));
            rgb = mix(rgb, restored, skinProtection * warmMask(source));
        }
        if (lite == 0 && film.z + film.w > 0.0) {
            float glow = 0.0;
            for (int i = 0; i < 4; ++i) {
                vec2 dir = vec2(float(i & 1) * 2.0 - 1.0, float(i >> 1) * 2.0 - 1.0);
                glow += max(dot(texture(camera, uv + dir * .004).rgb, luma) - .68, 0.0);
            }
            rgb += glow * (.35 * film.z + vec3(1.0, .24, .08) * .45 * film.w);
        }
        if (film.x > 0.0) {
            float noise = fract(sin(dot(floor(gl_FragCoord.xy) + seed, vec2(12.9898, 78.233))) * 43758.5453) - .5;
            rgb += noise * film.x * .12 * (1.0 - .65 * dot(source, luma));
        }
        rgb *= 1.0 - film.y * smoothstep(.22, .72, length(frame - .5));
        outputColor = vec4(clamp(rgb, 0.0, 1.0), 1.0);
    }
""".trimIndent()
