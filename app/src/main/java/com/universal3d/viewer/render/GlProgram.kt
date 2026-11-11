package com.universal3d.viewer.render

import android.opengl.GLES30
import android.util.Log

/** Thin wrapper around a compiled/linked GLSL program with cached uniform locations. */
class GlProgram(vertexSrc: String, fragmentSrc: String) {
    val id: Int
    private val uniforms = HashMap<String, Int>()

    init {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertexSrc)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSrc)
        id = GLES30.glCreateProgram()
        GLES30.glAttachShader(id, vs); GLES30.glAttachShader(id, fs)
        GLES30.glLinkProgram(id)
        val ok = IntArray(1); GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) { val log = GLES30.glGetProgramInfoLog(id); Log.e("GlProgram", "link failed: $log"); throw RuntimeException("Shader link failed: $log") }
        GLES30.glDeleteShader(vs); GLES30.glDeleteShader(fs)
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
        val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) { val log = GLES30.glGetShaderInfoLog(s); Log.e("GlProgram", "compile failed: $log\n$src"); throw RuntimeException("Shader compile failed: $log") }
        return s
    }

    fun use() = GLES30.glUseProgram(id)
    fun u(name: String): Int = uniforms.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }
    fun set(name: String, v: Float) = GLES30.glUniform1f(u(name), v)
    fun set(name: String, v: Int) = GLES30.glUniform1i(u(name), v)
    fun set3(name: String, x: Float, y: Float, z: Float) = GLES30.glUniform3f(u(name), x, y, z)
    fun set4(name: String, x: Float, y: Float, z: Float, w: Float) = GLES30.glUniform4f(u(name), x, y, z, w)
    fun setMat4(name: String, m: FloatArray) = GLES30.glUniformMatrix4fv(u(name), 1, false, m, 0)
    fun setColor(name: String, argb: Int, alpha: Float = ((argb ushr 24) and 0xff) / 255f) =
        GLES30.glUniform4f(u(name), ((argb shr 16) and 0xff) / 255f, ((argb shr 8) and 0xff) / 255f, (argb and 0xff) / 255f, alpha)
    fun delete() = GLES30.glDeleteProgram(id)
}

object Shaders {
    // Every shader declares BOTH float and int precision explicitly. GLSL ES 3.00 requires a uniform used in
    // both stages to have identical precision, and the default int precision differs between the vertex
    // (highp) and fragment (mediump) stages; a mismatch is a link error that would stop rendering entirely.
    const val MESH_VS = """#version 300 es
precision highp float;
precision highp int;
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
uniform mat4 uViewProj;
uniform mat4 uView;
out vec3 vWorldPos;
out vec3 vViewPos;
out vec3 vNormal;
void main() {
    vWorldPos = aPos;
    vViewPos = (uView * vec4(aPos, 1.0)).xyz;
    vNormal = aNormal; // only used by the fragment shader when uHasNormals == 1
    gl_Position = uViewProj * vec4(aPos, 1.0);
}
"""
    const val MESH_FS = """#version 300 es
precision highp float;
precision highp int;
in vec3 vWorldPos;
in vec3 vViewPos;
in vec3 vNormal;
uniform mat4 uView;
uniform vec4 uColor;
uniform int uHasNormals;
uniform vec4 uClip;       // plane in world space: dot(xyz, p) + w >= 0 is kept
uniform int uClipEnabled;
uniform vec3 uLightDirView; // normalized, pointing towards the light (view space)
uniform float uAmbient;
uniform float uIntensity;
uniform float uSpecular;
uniform int uFlat;
out vec4 fragColor;
void main() {
    if (uClipEnabled == 1 && dot(vWorldPos, uClip.xyz) + uClip.w < 0.0) discard;
    vec3 n;
    if (uHasNormals == 1 && uFlat == 0) {
        n = normalize(mat3(uView) * vNormal);
    } else {
        n = normalize(cross(dFdx(vViewPos), dFdy(vViewPos)));
    }
    if (!gl_FrontFacing) n = -n;
    // make sure the normal faces the viewer for two-sided CAD shells
    vec3 v = normalize(-vViewPos);
    if (dot(n, v) < 0.0) n = -n;
    vec3 l = normalize(uLightDirView);
    float diff = max(dot(n, l), 0.0);
    // soft fill light from the opposite upper side and a faint rim
    vec3 l2 = normalize(vec3(-l.x, abs(l.y) + 0.3, l.z * 0.5 + 0.5));
    float fill = max(dot(n, l2), 0.0) * 0.35;
    vec3 h = normalize(l + v);
    float spec = pow(max(dot(n, h), 0.0), 48.0) * uSpecular;
    float rim = pow(1.0 - max(dot(n, v), 0.0), 3.0) * 0.12;
    vec3 base = uColor.rgb;
    vec3 c = base * (uAmbient + (diff + fill) * uIntensity * 0.85) + vec3(spec * uIntensity) + base * rim;
    fragColor = vec4(c, uColor.a);
}
"""
    const val LINE_VS = """#version 300 es
precision highp float;
precision highp int;
layout(location = 0) in vec3 aPos;
uniform mat4 uViewProj;
uniform float uPointSize;
out vec3 vWorldPos;
void main() {
    vWorldPos = aPos;
    gl_Position = uViewProj * vec4(aPos, 1.0);
    gl_PointSize = uPointSize;
}
"""
    const val LINE_FS = """#version 300 es
precision highp float;
precision highp int;
in vec3 vWorldPos;
uniform vec4 uColor;
uniform vec4 uClip;
uniform int uClipEnabled;
uniform int uPoint;
out vec4 fragColor;
void main() {
    if (uClipEnabled == 1 && dot(vWorldPos, uClip.xyz) + uClip.w < 0.0) discard;
    if (uPoint == 1) {
        vec2 d = gl_PointCoord - vec2(0.5);
        float r = length(d);
        if (r > 0.5) discard;
        float ring = smoothstep(0.32, 0.38, r);
        fragColor = vec4(mix(uColor.rgb, vec3(1.0), ring * 0.9), uColor.a);
        return;
    }
    fragColor = uColor;
}
"""
    const val BG_VS = """#version 300 es
precision highp float;
precision highp int;
layout(location = 0) in vec2 aPos;
out float vT;
void main() { vT = aPos.y * 0.5 + 0.5; gl_Position = vec4(aPos, 0.999, 1.0); }
"""
    const val BG_FS = """#version 300 es
precision highp float;
precision highp int;
in float vT;
uniform vec4 uTop;
uniform vec4 uBottom;
out vec4 fragColor;
void main() { fragColor = mix(uBottom, uTop, vT); }
"""
}
