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
        GL.glUniform2f(location("shadowWheel"), value.balance.shadows.x, value.balance.shadows.y)
        GL.glUniform2f(location("midWheel"), value.balance.midtones.x, value.balance.midtones.y)
        GL.glUniform2f(location("highlightWheel"), value.balance.highlights.x, value.balance.highlights.y)
        GL.glUniform1i(location("hslEnabled"), if (value.hsl == SelectiveHsl()) 0 else 1)
        GL.glUniform3fv(location("hslBands[0]"), 6, value.hsl.uniforms(), 0)
        GL.glUniform1f(location("diffusion"), value.diffusion)
        GL.glUniform1f(location("seed"), seed)
        GL.glUniform1i(location("lite"), if (lite) 1 else 0)
    }

    fun draw(transform: FloatArray, enabled: Boolean, split: Boolean, monitor: Int = 0, zebra: Float = .95f, edgeSensitivity: Float = .5f) {
        GL.glUniformMatrix4fv(location("transform"), 1, false, transform, 0)
        GL.glUniform1i(location("gradeEnabled"), if (enabled) 1 else 0)
        GL.glUniform1i(location("split"), if (split) 1 else 0)
        GL.glUniform1i(location("monitor"), monitor)
        GL.glUniform2f(location("monitorSettings"), zebra, edgeSensitivity)
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
    uniform vec2 shadowWheel;
    uniform vec2 midWheel;
    uniform vec2 highlightWheel;
    uniform vec3 hslBands[6];
    uniform int hslEnabled;
    uniform float diffusion;
    uniform float edge;
    uniform float seed;
    uniform int gradeEnabled;
    uniform int split;
    uniform int lite;
    uniform int monitor;
    uniform vec2 monitorSettings;
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
    vec3 colorOffset(vec2 wheel) {
        vec3 v = vec3(wheel.x, -.5*wheel.x + .8660254*wheel.y, -.5*wheel.x - .8660254*wheel.y);
        return v - vec3(dot(v, luma));
    }
    vec3 toHsl(vec3 c) {
        float hi = max(c.r, max(c.g, c.b));
        float lo = min(c.r, min(c.g, c.b));
        float d = hi-lo, l = (hi+lo)*.5;
        if (d < .0001) return vec3(0.0,0.0,l);
        float h = hi == c.r ? (c.g-c.b)/d : hi == c.g ? (c.b-c.r)/d+2.0 : (c.r-c.g)/d+4.0;
        return vec3(fract(h/6.0), d/max(1.0-abs(2.0*l-1.0),.0001),l);
    }
    vec3 fromHsl(vec3 hsl) {
        vec3 shape = clamp(abs(fract(hsl.x + vec3(0.0,2.0/3.0,1.0/3.0))*6.0-3.0)-1.0,0.0,1.0);
        float chroma = (1.0-abs(2.0*hsl.z-1.0))*hsl.y;
        return (shape-.5)*chroma + hsl.z;
    }
    vec3 selective(vec3 rgb) {
        vec3 hsl = toHsl(clamp(rgb,0.0,1.0));
        if (hsl.y < .001) return rgb;
        vec3 change = vec3(0.0);
        for (int i=0; i<6; ++i) {
            float distance = abs(hsl.x-float(i)/6.0);
            distance = min(distance,1.0-distance);
            float weight = max(0.0,1.0-distance*6.0);
            change += hslBands[i]*weight;
        }
        hsl.x = fract(hsl.x+change.x);
        hsl.y = clamp(hsl.y*(1.0+change.y),0.0,1.0);
        hsl.z = clamp(hsl.z+change.z*smoothstep(0.0,.15,hsl.y),0.0,1.0);
        return fromHsl(hsl);
    }
    vec3 monitorColor(vec3 source, vec3 rgb) {
        if (monitor == 0) return rgb;
        float y = dot(source,luma);
        if (monitor == 1) {
            if (y >= monitorSettings.x && mod(floor((gl_FragCoord.x+gl_FragCoord.y)/5.0),2.0)<1.0)
                return mix(rgb,vec3(1.0,.73,.18),.88);
        } else if (monitor == 2) {
            vec2 dx = dFdx(uv), dy = dFdy(uv);
            float edgeAmount = abs(dot(texture(camera,uv+dx*2.0).rgb,luma)-y)
                + abs(dot(texture(camera,uv+dy*2.0).rgb,luma)-y);
            if (edgeAmount > mix(.28,.025,monitorSettings.y)) return mix(rgb,vec3(.15,1.0,.42),.85);
        } else if (monitor == 3) {
            if (y >= .94) return vec3(.98,.15,.1);
            if (y >= .76) return vec3(1.0,.76,.2);
            if (y >= .39 && y <= .5) return vec3(.2,.8,.42);
            if (y < .12) return vec3(.19,.38,.9);
            return vec3(y);
        }
        return rgb;
    }
    void main() {
        vec3 source = texture(camera, uv).rgb;
        if (gradeEnabled == 0 || (split == 1 && frame.x < .5)) {
            outputColor = vec4(monitorColor(source,source), 1.0);
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
        if (dot(abs(shadowWheel)+abs(midWheel)+abs(highlightWheel),vec2(1.0)) > 0.0) {
            float y = clamp(dot(rgb,luma),0.0,1.0);
            rgb += .12 * (colorOffset(shadowWheel)*(1.0-smoothstep(.12,.58,y)) +
                colorOffset(midWheel)*(1.0-abs(2.0*y-1.0)) +
                colorOffset(highlightWheel)*smoothstep(.42,.9,y));
        }
        if (hslEnabled == 1) rgb = selective(rgb);
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
        if (lite == 0 && diffusion > 0.0) {
            vec3 diffuse = vec3(0.0);
            for (int i=0; i<4; ++i) {
                vec2 dir = vec2(float(i & 1)*2.0-1.0,float(i >> 1)*2.0-1.0);
                diffuse += texture(camera,uv+dir*.0018).rgb;
            }
            rgb += (diffuse*.25-source)*diffusion*.35;
        }
        if (film.x > 0.0) {
            float noise = fract(sin(dot(floor(gl_FragCoord.xy) + seed, vec2(12.9898, 78.233))) * 43758.5453) - .5;
            rgb += noise * film.x * .12 * (1.0 - .65 * dot(source, luma));
        }
        rgb *= 1.0 - film.y * smoothstep(.22, .72, length(frame - .5));
        outputColor = vec4(clamp(monitorColor(source,rgb), 0.0, 1.0), 1.0);
    }
""".trimIndent()
