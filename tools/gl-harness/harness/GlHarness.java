import com.universal3d.viewer.io.ImportSource;
import com.universal3d.viewer.io.ProgressSink;
import com.universal3d.viewer.io.FormatRegistry;
import com.universal3d.viewer.io.FormatInfo;
import com.universal3d.viewer.model.*;
import com.universal3d.viewer.render.*;
import org.lwjgl.egl.*;
import org.lwjgl.opengles.*;
import org.lwjgl.system.MemoryStack;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.egl.EGL10.*;
import static org.lwjgl.egl.EGL12.eglBindAPI;
import static org.lwjgl.egl.EGL14.EGL_OPENGL_ES_API;

/**
 * Runs the app's real SceneRenderer (unmodified Kotlin classes) on a headless OpenGL ES 3 context provided by
 * Mesa, through the android.opengl.GLES30 shim. Exercises the same lifecycle as GLSurfaceView
 * (onSurfaceCreated -> onSurfaceChanged -> onDrawFrame), loads real models through the app's importers,
 * fails on any GL error, renderer-reported error, or blank frame, and writes a PNG per scene.
 *
 * Usage: GlHarness <outDir> <model files...>
 */
public class GlHarness {
    static int W, H, fbo;
    static final List<String> failures = new ArrayList<>();
    static final List<String> rendererErrors = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        File out = new File(args[0]); out.mkdirs();
        long dpy = initEgl();
        System.out.println("GL_RENDERER=" + GLES20.glGetString(GLES20.GL_RENDERER) + "  GL_VERSION=" + GLES20.glGetString(GLES20.GL_VERSION));

        RenderSettings settings = new RenderSettings();
        SceneRenderer r = new SceneRenderer(settings, () -> kotlin.Unit.INSTANCE);
        r.setOnRenderError(msg -> { rendererErrors.add(msg); return kotlin.Unit.INSTANCE; });
        r.setDensity(2.0f);

        // ---- portrait phone surface (S24 Ultra is 1440x3120; half resolution keeps the software renderer fast) ----
        surface(720, 1560);
        r.onSurfaceCreated(null, null);
        r.onSurfaceChanged(null, W, H);
        expectNoErrors("onSurfaceCreated/onSurfaceChanged");
        if (r.getRenderError() != null) failures.add("renderer init error: " + r.getRenderError());

        // 1) app start: no model loaded yet (what the user sees behind the empty-state screen)
        frame(r, out, "00_startup_no_model", false);

        List<Model> models = new ArrayList<>();
        for (int i = 1; i < args.length; i++) models.add(load(new File(args[i])));

        int n = 1;
        for (Model m : models) {
            String tag = String.format("%02d_%s", n++, m.getFileName().replaceAll("[^A-Za-z0-9]+", "_"));
            r.setModel(m);
            r.getCamera().reset(m.getBounds(), false);
            settings.setViewMode(ViewMode.SOLID_EDGES);
            frame(r, out, tag + "_a_solid_edges_iso", true);
            settings.setViewMode(ViewMode.SOLID);
            frame(r, out, tag + "_b_solid", true);
            settings.setViewMode(ViewMode.WIREFRAME);
            frame(r, out, tag + "_c_wireframe", true);
            settings.setViewMode(ViewMode.XRAY);
            frame(r, out, tag + "_d_xray", true);
            settings.setViewMode(ViewMode.SOLID_EDGES);
            settings.setProjection(Projection.ORTHOGRAPHIC);
            r.getCamera().standardView(StandardView.FRONT, m.getBounds(), false);
            frame(r, out, tag + "_e_front_ortho", true);
            settings.setProjection(Projection.PERSPECTIVE);
            r.getCamera().standardView(StandardView.ISOMETRIC, m.getBounds(), false);
            settings.setClipAxis(ClipAxis.Z); settings.setClipOffset(0.5f);
            settings.setShowBoundingBox(true);
            frame(r, out, tag + "_f_section_z_bbox", true);
            settings.setClipAxis(ClipAxis.NONE); settings.setShowBoundingBox(false);
            // measurement between two picked points (same code path as a tap)
            float[] ray1 = r.getCamera().pickRay(W * 0.45f, H * 0.5f), ray2 = r.getCamera().pickRay(W * 0.58f, H * 0.47f);
            PickResult p1 = Picker.INSTANCE.pick(m, ray1, null, false), p2 = Picker.INSTANCE.pick(m, ray2, null, false);
            if (p1 != null && p2 != null) {
                r.setMeasurePoints(new float[]{p1.getX(), p1.getY(), p1.getZ(), p2.getX(), p2.getY(), p2.getZ()});
                settings.setSelectedNodeId(p1.getNode().getId());
                System.out.printf("   pick: '%s' at (%.3f, %.3f, %.3f), distance to 2nd point %.3f%n", p1.getNode().getName(), p1.getX(), p1.getY(), p1.getZ(),
                        Math.sqrt(Math.pow(p2.getX() - p1.getX(), 2) + Math.pow(p2.getY() - p1.getY(), 2) + Math.pow(p2.getZ() - p1.getZ(), 2)));
            } else failures.add(tag + ": pick ray through the screen centre missed the model");
            frame(r, out, tag + "_g_measure_select", true);
            r.setMeasurePoints(new float[0]); settings.setSelectedNodeId(-1);
            settings.setBackground(BackgroundPreset.Companion.getALL().get(5));
            frame(r, out, tag + "_h_white_background", true);
            settings.setBackground(BackgroundPreset.Companion.getALL().get(0));
        }

        // ---- rotation to landscape + GL context loss (onSurfaceCreated again with a model loaded) ----
        if (!models.isEmpty()) {
            Model m = models.get(models.size() - 1);
            surface(1560, 720);
            r.onSurfaceCreated(null, null);
            r.onSurfaceChanged(null, W, H);
            r.getCamera().reset(m.getBounds(), false);
            frame(r, out, "90_landscape_after_context_loss", true);
        }

        System.out.println("GL calls: " + android.opengl.GLES30.calls);
        if (!rendererErrors.isEmpty()) failures.add("renderer reported: " + rendererErrors);
        if (failures.isEmpty()) { System.out.println("RESULT OK"); }
        else { System.out.println("RESULT FAIL"); for (String f : failures) System.out.println("  - " + f); System.exit(1); }
    }

    static Model load(File f) throws Exception {
        FormatInfo fi = FormatRegistry.INSTANCE.identify(f, f.getName());
        if (fi == null || fi.getImporter() == null) throw new IllegalStateException("no importer for " + f);
        Model m = fi.getImporter().load(new ImportSource(f, f.getName(), f.length()), ProgressSink.None.INSTANCE);
        // same post-processing the app's ModelLoader does in the background
        for (Node node : m.getMeshNodes()) {
            Mesh mesh = node.getMesh();
            if (mesh.getEdgeIndices() == null && mesh.getEdgePositions() == null) mesh.setEdgeIndices(MeshOps.INSTANCE.featureEdges(mesh.getPositions(), mesh.getIndices(), 32f, 3_000_000));
        }
        System.out.printf("loaded %s [%s]: %d nodes, %d triangles, bbox %.2f x %.2f x %.2f%n", f.getName(), fi.getName(), m.getNodes().size(), m.getTriangleCount(),
                m.getBounds().getSizeX(), m.getBounds().getSizeY(), m.getBounds().getSizeZ());
        return m;
    }

    static void frame(SceneRenderer r, File out, String name, boolean expectModel) throws Exception {
        android.opengl.GLES30.ERRORS.clear();
        r.onDrawFrame(null);
        // drain camera animations the way GLSurfaceView would (continuous redraw while animating)
        for (int i = 0; i < 60 && r.getCamera().isAnimating(); i++) { Thread.sleep(16); r.onDrawFrame(null); }
        GLES20.glFinish();
        expectNoErrors(name);
        ByteBuffer px = ByteBuffer.allocateDirect(W * H * 4);
        GLES20.glReadPixels(0, 0, W, H, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px);
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        int bg = -1, differ = 0, colours = 0; java.util.HashSet<Integer> set = new java.util.HashSet<>();
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int i = ((H - 1 - y) * W + x) * 4;
            int rgb = ((px.get(i) & 0xff) << 16) | ((px.get(i + 1) & 0xff) << 8) | (px.get(i + 2) & 0xff);
            img.setRGB(x, y, rgb);
            if (set.size() < 5000) set.add(rgb);
        }
        // compare against the background gradient (sample the left edge column at each row)
        for (int y = 0; y < H; y += 2) { int ref = img.getRGB(2, y); for (int x = W / 6; x < W * 5 / 6; x += 2) if (Math.abs((img.getRGB(x, y) & 0xff) - (ref & 0xff)) > 12 || Math.abs(((img.getRGB(x, y) >> 8) & 0xff) - ((ref >> 8) & 0xff)) > 12) differ++; }
        double cover = differ * 100.0 / ((H / 2) * (W * 2 / 3 / 2));
        ImageIO.write(img, "png", new File(out, name + ".png"));
        System.out.printf("  %-44s %4dx%-4d  model coverage %5.1f%%  distinct colours %d%n", name, W, H, cover, set.size());
        if (expectModel && cover < 2.0) failures.add(name + ": frame looks empty (" + String.format("%.1f", cover) + "% non-background)");
        if (!expectModel && set.size() < 2) failures.add(name + ": frame is a single flat colour (background gradient missing)");
    }

    static void expectNoErrors(String where) {
        if (!android.opengl.GLES30.ERRORS.isEmpty()) failures.add(where + ": GL errors " + android.opengl.GLES30.ERRORS);
        android.opengl.GLES30.ERRORS.clear();
    }

    static void surface(int w, int h) {
        W = w; H = h;
        if (fbo != 0) GLES20.glDeleteFramebuffers(fbo);
        fbo = GLES20.glGenFramebuffers();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        int color = GLES20.glGenRenderbuffers();
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, color);
        GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES30.GL_RGBA8, w, h);
        GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_RENDERBUFFER, color);
        int depth = GLES20.glGenRenderbuffers();
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, depth);
        GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES30.GL_DEPTH24_STENCIL8, w, h);
        GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES30.GL_DEPTH_STENCIL_ATTACHMENT, GLES20.GL_RENDERBUFFER, depth);
        int st = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (st != GLES20.GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("FBO incomplete 0x" + Integer.toHexString(st));
        while (GLES20.glGetError() != 0) { }
    }

    static long initEgl() {
        try (MemoryStack s = MemoryStack.stackPush()) {
            long dpy = eglGetDisplay(0L /* EGL_DEFAULT_DISPLAY; Mesa picks the surfaceless platform from EGL_PLATFORM */);
            IntBuffer maj = s.mallocInt(1), min = s.mallocInt(1);
            if (!eglInitialize(dpy, maj, min)) throw new IllegalStateException("eglInitialize failed");
            EGL.createDisplayCapabilities(dpy, maj.get(0), min.get(0));
            eglBindAPI(EGL_OPENGL_ES_API);
            long ctx = eglCreateContext(dpy, 0L /* EGL_NO_CONFIG_KHR */, EGL_NO_CONTEXT, s.ints(0x3098 /* EGL_CONTEXT_CLIENT_VERSION */, 3, EGL_NONE));
            if (ctx == EGL_NO_CONTEXT) throw new IllegalStateException("eglCreateContext failed 0x" + Integer.toHexString(eglGetError()));
            if (!eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, ctx)) throw new IllegalStateException("eglMakeCurrent failed");
            GLES.createCapabilities();
            return dpy;
        }
    }
}
