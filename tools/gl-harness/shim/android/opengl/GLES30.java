package android.opengl;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.opengles.GLES20;
import org.lwjgl.system.MemoryUtil;

/**
 * Test shim: replaces android.opengl.GLES30 on the host JVM and forwards every call the app makes to a real
 * OpenGL ES 3 driver (Mesa llvmpipe via LWJGL). After each call glGetError() is checked and any error is
 * recorded together with the call name, so the harness can fail on GL misuse that a phone would silently ignore.
 * Only the entry points used by the app exist; a missing one fails at link time of the harness, not silently.
 */
public final class GLES30 {
    private GLES30() {}
    public static final List<String> ERRORS = new ArrayList<>();
    public static int calls = 0;

    private static void chk(String name) {
        calls++;
        int e;
        while ((e = GLES20.glGetError()) != 0) ERRORS.add(name + " -> 0x" + Integer.toHexString(e));
    }
    private static long addr(Buffer b) {
        if (b == null) return 0L;
        if (!(b instanceof ByteBuffer) || !b.isDirect()) throw new IllegalArgumentException("shim expects direct ByteBuffer, got " + b.getClass());
        return MemoryUtil.memAddress((ByteBuffer) b);
    }

    // ---- buffers ----
    public static void glGenBuffers(int n, int[] buffers, int offset) { for (int i = 0; i < n; i++) buffers[offset + i] = GLES20.glGenBuffers(); chk("glGenBuffers"); }
    public static void glDeleteBuffers(int n, int[] buffers, int offset) { for (int i = 0; i < n; i++) GLES20.glDeleteBuffers(buffers[offset + i]); chk("glDeleteBuffers"); }
    public static void glBindBuffer(int target, int buffer) { GLES20.glBindBuffer(target, buffer); chk("glBindBuffer"); }
    public static void glBufferData(int target, int size, Buffer data, int usage) { GLES20.nglBufferData(target, size, addr(data), usage); chk("glBufferData"); }
    public static void glBufferSubData(int target, int offset, int size, Buffer data) { GLES20.nglBufferSubData(target, offset, size, addr(data)); chk("glBufferSubData"); }

    // ---- vertex state & drawing ----
    public static void glVertexAttribPointer(int indx, int size, int type, boolean normalized, int stride, int offset) { GLES20.glVertexAttribPointer(indx, size, type, normalized, stride, (long) offset); chk("glVertexAttribPointer"); }
    public static void glEnableVertexAttribArray(int i) { GLES20.glEnableVertexAttribArray(i); chk("glEnableVertexAttribArray"); }
    public static void glDisableVertexAttribArray(int i) { GLES20.glDisableVertexAttribArray(i); chk("glDisableVertexAttribArray"); }
    public static void glDrawElements(int mode, int count, int type, int offset) { GLES20.glDrawElements(mode, count, type, (long) offset); chk("glDrawElements"); }
    public static void glDrawArrays(int mode, int first, int count) { GLES20.glDrawArrays(mode, first, count); chk("glDrawArrays"); }

    // ---- state ----
    public static void glEnable(int cap) { GLES20.glEnable(cap); chk("glEnable"); }
    public static void glDisable(int cap) { GLES20.glDisable(cap); chk("glDisable"); }
    public static void glLineWidth(float w) { GLES20.glLineWidth(w); chk("glLineWidth"); }
    public static void glViewport(int x, int y, int w, int h) { GLES20.glViewport(x, y, w, h); chk("glViewport"); }
    public static void glDepthMask(boolean f) { GLES20.glDepthMask(f); chk("glDepthMask"); }
    public static void glDepthFunc(int f) { GLES20.glDepthFunc(f); chk("glDepthFunc"); }
    public static void glBlendFunc(int s, int d) { GLES20.glBlendFunc(s, d); chk("glBlendFunc"); }
    public static void glPolygonOffset(float f, float u) { GLES20.glPolygonOffset(f, u); chk("glPolygonOffset"); }
    public static void glClearColor(float r, float g, float b, float a) { GLES20.glClearColor(r, g, b, a); chk("glClearColor"); }
    public static void glClear(int mask) { GLES20.glClear(mask); chk("glClear"); }
    public static String glGetString(int name) { String s = GLES20.glGetString(name); chk("glGetString"); return s; }

    // ---- shaders & programs ----
    public static int glCreateShader(int type) { int r = GLES20.glCreateShader(type); chk("glCreateShader"); return r; }
    public static void glShaderSource(int shader, String src) { GLES20.glShaderSource(shader, src); chk("glShaderSource"); }
    public static void glCompileShader(int shader) { GLES20.glCompileShader(shader); chk("glCompileShader"); }
    public static void glGetShaderiv(int shader, int pname, int[] params, int offset) { params[offset] = GLES20.glGetShaderi(shader, pname); chk("glGetShaderiv"); }
    public static String glGetShaderInfoLog(int shader) { String s = GLES20.glGetShaderInfoLog(shader); chk("glGetShaderInfoLog"); return s; }
    public static void glDeleteShader(int shader) { GLES20.glDeleteShader(shader); chk("glDeleteShader"); }
    public static int glCreateProgram() { int r = GLES20.glCreateProgram(); chk("glCreateProgram"); return r; }
    public static void glAttachShader(int p, int s) { GLES20.glAttachShader(p, s); chk("glAttachShader"); }
    public static void glLinkProgram(int p) { GLES20.glLinkProgram(p); chk("glLinkProgram"); }
    public static void glGetProgramiv(int p, int pname, int[] params, int offset) { params[offset] = GLES20.glGetProgrami(p, pname); chk("glGetProgramiv"); }
    public static String glGetProgramInfoLog(int p) { String s = GLES20.glGetProgramInfoLog(p); chk("glGetProgramInfoLog"); return s; }
    public static void glDeleteProgram(int p) { GLES20.glDeleteProgram(p); chk("glDeleteProgram"); }
    public static void glUseProgram(int p) { GLES20.glUseProgram(p); chk("glUseProgram"); }
    public static int glGetUniformLocation(int p, String name) { int r = GLES20.glGetUniformLocation(p, name); chk("glGetUniformLocation"); return r; }
    public static void glUniform1f(int l, float x) { GLES20.glUniform1f(l, x); chk("glUniform1f"); }
    public static void glUniform1i(int l, int x) { GLES20.glUniform1i(l, x); chk("glUniform1i"); }
    public static void glUniform3f(int l, float x, float y, float z) { GLES20.glUniform3f(l, x, y, z); chk("glUniform3f"); }
    public static void glUniform4f(int l, float x, float y, float z, float w) { GLES20.glUniform4f(l, x, y, z, w); chk("glUniform4f"); }
    public static void glUniformMatrix4fv(int l, int count, boolean transpose, float[] v, int offset) {
        float[] m = new float[16 * count]; System.arraycopy(v, offset, m, 0, m.length);
        GLES20.glUniformMatrix4fv(l, transpose, m); chk("glUniformMatrix4fv");
    }

    // ---- constants used by the app (values from the GLES 2.0/3.0 headers) ----
    public static final int GL_TRIANGLES = 0x0004, GL_LINES = 0x0001, GL_POINTS = 0x0000, GL_TRIANGLE_STRIP = 0x0005;
    public static final int GL_ARRAY_BUFFER = 0x8892, GL_ELEMENT_ARRAY_BUFFER = 0x8893, GL_STATIC_DRAW = 0x88E4, GL_DYNAMIC_DRAW = 0x88E8;
    public static final int GL_FLOAT = 0x1406, GL_UNSIGNED_INT = 0x1405;
    public static final int GL_DEPTH_TEST = 0x0B71, GL_BLEND = 0x0BE2, GL_CULL_FACE = 0x0B44, GL_POLYGON_OFFSET_FILL = 0x8037, GL_LEQUAL = 0x0203;
    public static final int GL_SRC_ALPHA = 0x0302, GL_ONE_MINUS_SRC_ALPHA = 0x0303;
    public static final int GL_COLOR_BUFFER_BIT = 0x4000, GL_DEPTH_BUFFER_BIT = 0x0100;
    public static final int GL_VERTEX_SHADER = 0x8B31, GL_FRAGMENT_SHADER = 0x8B30, GL_COMPILE_STATUS = 0x8B81, GL_LINK_STATUS = 0x8B82;
    public static final int GL_RENDERER = 0x1F01, GL_VERSION = 0x1F02;
}
