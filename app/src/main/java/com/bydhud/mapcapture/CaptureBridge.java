package com.bydhud.mapcapture;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import android.os.SharedMemory;
import android.system.OsConstants;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLSurface;

/** Diagnostic producer only: no file writes, PNG compression, or HUD calls. */
public final class CaptureBridge {
    private static final Uri ENDPOINT = Uri.parse("content://com.bydhud.app.mapframes");
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final Object WAZE_LOCK = new Object();
    private static Bundle wazeRequest;
    private static volatile WeakReference<Object> maps = new WeakReference<>(null);
    private static volatile Context app;
    private static Handler worker;
    private static volatile long draws;
    private static volatile String lastError = "";
    private static String session = "";
    private static long pollIntervalMs = CapturePollPolicy.DEFAULT_MS;
    private static volatile boolean fullSource;
    static boolean fullSource() { return fullSource; }

    public static synchronized void init(Context context) {
        try {
            if (app != null || context == null) return;
            if (context.getPackageName().equals("com.waze") && android.os.Build.VERSION.SDK_INT >= 28
                    && !context.getApplicationInfo().processName.equals(android.app.Application.getProcessName())) return;
            app = context.getApplicationContext();
            if (app == null) { app = null; return; }
            HandlerThread thread = new HandlerThread("MapProbeProducer");
            thread.start();
            worker = new Handler(thread.getLooper());
            worker.post(CaptureBridge::poll);
        } catch (Throwable e) { lastError = error(e); }
    }

    public static void trackMaps(Object controller) {
        try { maps = new WeakReference<>(controller); } catch (Throwable ignored) { }
    }
    static int densityDpi() { return app.getResources().getDisplayMetrics().densityDpi; }

    private static void poll() {
        long delay = CapturePollPolicy.DEFAULT_MS;
        try {
            Bundle hello = new Bundle();
            hello.putString("package", app.getPackageName());
            hello.putString("build", app.getPackageName().equals("com.waze")
                    ? "map-probe-r8-adaptive-poll-hud-consumer-v1" : "map-probe-r11-navigation-state-hud-consumer-v1");
            hello.putBoolean("fullSourceMemory", true);
            hello.putLong("minPollIntervalMs", CapturePollPolicy.MIN_MS);
            hello.putLong("pollIntervalMs", pollIntervalMs);
            hello.putInt("pid", android.os.Process.myPid());
            hello.putBoolean("busy", BUSY.get());
            hello.putLong("draws", draws);
            Object controller = maps.get();
            hello.putString("controller", controller == null ? "none" : identity(controller));
            hello.putString("producerError", lastError);
            hello.putString("captureSession",session);
            hello.putAll(BackgroundCapture.metadata());
            Bundle request = app.getContentResolver().call(ENDPOINT, "poll", null, hello);
            lastError = "";
            boolean active = request != null && request.getBoolean("active");
            delay = CapturePollPolicy.interval(active, request == null
                    ? CapturePollPolicy.DEFAULT_MS
                    : request.getLong("pollIntervalMs", CapturePollPolicy.DEFAULT_MS));
            if (active) session=request.getString("session",session);
            fullSource = active && request.getBoolean("fullSourceMemory", false);
            BackgroundCapture.lease(active);
            if (active || (request!=null && request.getBoolean("journalAccepted"))) BackgroundCapture.acknowledged(hello.getLong("lifecycleSequence"));
            if (request == null || !request.getBoolean("active")) {
                if (takeWaze() != null) BUSY.set(false);
            } else if (request.getLong("id") != 0 && BUSY.compareAndSet(false, true)) {
                request.putLong("producerRequestNs", SystemClock.elapsedRealtimeNanos());
                if (app.getPackageName().equals("com.waze")) {
                    setWaze(request);
                    worker.postDelayed(() -> {
                        if (takeWaze(request)) finish(request, null, "no_gl_frame", "waze", 0, 0);
                    }, 2500);
                } else {
                    new Handler(Looper.getMainLooper()).post(() -> captureMaps(request));
                }
            }
        } catch (Throwable e) { lastError = error(e); BackgroundCapture.lease(false); delay = 5000; }
        finally {
            pollIntervalMs = delay;
            if (worker != null) worker.postDelayed(CaptureBridge::poll, delay);
        }
    }

    private static void captureMaps(Bundle request) {
        AtomicBoolean delivered = new AtomicBoolean();
        try {
            Object controller = maps.get();
            if (controller == null) { worker.post(() -> finish(request, null, "no_map_controller", "maps", 0, 0)); return; }
            String source = identity(controller);
            request.putString("mapsState", BackgroundCapture.mapsInfo(controller));
            Class<?> callback = Class.forName("boon", true, controller.getClass().getClassLoader());
            Object proxy = Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[]{callback}, (p, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    if (method.getName().equals("hashCode")) return System.identityHashCode(p);
                    if (method.getName().equals("equals")) return p == args[0];
                    return "MapProbeCallback";
                }
                if (method.getName().equals("a") && delivered.compareAndSet(false, true)) {
                    try {
                        long callbackNs = SystemClock.elapsedRealtimeNanos();
                        Bitmap bitmap = args != null && args.length == 1 && args[0] instanceof Bitmap ? (Bitmap) args[0] : null;
                        if (bitmap != null) BackgroundCapture.frame(controller);
                        int width = bitmap == null ? 0 : bitmap.getWidth();
                        int height = bitmap == null ? 0 : bitmap.getHeight();
                        // Copy while the callback owns the source; never recycle a navigator-owned Bitmap.
                        Bitmap copy = bitmap == null ? null : smallCopy(bitmap, request.getBoolean("fullSourceMemory"));
                        request.putLong("callbackNs", callbackNs);
                        worker.post(() -> finish(request, copy, copy == null ? "null_snapshot" : "ok", source, width, height));
                    } catch (Throwable e) { worker.post(() -> finish(request, null, error(e), source, 0, 0)); }
                }
                return null;
            });
            request.putLong("captureStartNs", SystemClock.elapsedRealtimeNanos());
            controller.getClass().getMethod("L", callback).invoke(controller, proxy);
            // No second native request until its callback arrives, even after the collector's timeout.
        } catch (Throwable e) {
            if (delivered.compareAndSet(false, true)) worker.post(() -> finish(request, null, error(e), "maps", 0, 0));
        }
    }

    public static void wazeFrame(Object renderer) {
        draws++;
        BackgroundCapture.wazeFrame(renderer);
        Bundle request = takeWaze();
        if (request == null) return;
        try {
            String source = identity(renderer);
            request.putString("frameSourceMode", BackgroundCapture.wazeSource(renderer));
            EGL10 egl = (EGL10) EGLContext.getEGL();
            EGLSurface surface = egl.eglGetCurrentSurface(EGL10.EGL_DRAW);
            int[] w = new int[1], h = new int[1];
            if (!egl.eglQuerySurface(egl.eglGetCurrentDisplay(), surface, EGL10.EGL_WIDTH, w)
                    || !egl.eglQuerySurface(egl.eglGetCurrentDisplay(), surface, EGL10.EGL_HEIGHT, h)
                    || w[0] < 1 || h[0] < 1 || (long) w[0] * h[0] > 12000000) throw new IllegalStateException("invalid_egl_dimensions");
            int width = w[0], height = h[0];
            ByteBuffer bytes = ByteBuffer.allocateDirect(width * height * 4);
            String version = GLES20.glGetString(GLES20.GL_VERSION);
            boolean es3 = version != null && version.contains("OpenGL ES 3");
            int[] old = new int[1];
            int previousError = GLES20.glGetError();
            int target = es3 ? GLES30.GL_READ_FRAMEBUFFER : GLES20.GL_FRAMEBUFFER;
            GLES20.glGetIntegerv(es3 ? GLES30.GL_READ_FRAMEBUFFER_BINDING : GLES20.GL_FRAMEBUFFER_BINDING, old, 0);
            int[] alignment = new int[1]; GLES20.glGetIntegerv(GLES20.GL_PACK_ALIGNMENT, alignment, 0);
            int[] pack = new int[4];
            int[] packKeys = {GLES30.GL_PACK_ROW_LENGTH, GLES30.GL_PACK_SKIP_ROWS, GLES30.GL_PACK_SKIP_PIXELS, GLES30.GL_PIXEL_PACK_BUFFER_BINDING};
            if (es3) for (int i = 0; i < packKeys.length; i++) GLES20.glGetIntegerv(packKeys[i], pack, i);
            request.putLong("captureStartNs", SystemClock.elapsedRealtimeNanos());
            try {
                GLES20.glBindFramebuffer(target, 0);
                GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1);
                if (es3) {
                    for (int i = 0; i < 3; i++) GLES20.glPixelStorei(packKeys[i], 0);
                    GLES20.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
                }
                GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes);
                int status = GLES20.glGetError();
                if (status != GLES20.GL_NO_ERROR) throw new IllegalStateException("glReadPixels_error_" + status);
            } finally {
                GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, alignment[0]);
                if (es3) {
                    for (int i = 0; i < 3; i++) GLES20.glPixelStorei(packKeys[i], pack[i]);
                    GLES20.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pack[3]);
                }
                GLES20.glBindFramebuffer(target, old[0]);
            }
            request.putLong("callbackNs", SystemClock.elapsedRealtimeNanos());
            request.putInt("previousGlError", previousError);
            request.putString("glVersion", version);
            worker.post(() -> {
                try {
                    // Sample the reduced image directly: no full-resolution Java Bitmap allocation.
                    int[] size = PixelMath.size(width, height, request.getBoolean("fullSourceMemory") ? 1920 : 320);
                    int[] pixels = PixelMath.rgba(bytes, width, height, size[0], size[1]);
                    Bitmap bitmap = Bitmap.createBitmap(pixels, size[0], size[1], Bitmap.Config.ARGB_8888);
                    finish(request, bitmap, "ok", source, width, height);
                } catch (Throwable e) { finish(request, null, error(e), source, width, height); }
            });
        } catch (Throwable e) {
            if (worker != null) worker.post(() -> finish(request, null, error(e), "waze", 0, 0));
            else BUSY.set(false);
        }
    }

    private static void setWaze(Bundle request) {
        synchronized (WAZE_LOCK) { wazeRequest = request; }
    }

    private static Bundle takeWaze() {
        synchronized (WAZE_LOCK) {
            Bundle request = wazeRequest;
            wazeRequest = null;
            return request;
        }
    }

    private static boolean takeWaze(Bundle expected) {
        synchronized (WAZE_LOCK) {
            if (wazeRequest != expected) return false;
            wazeRequest = null;
            return true;
        }
    }

    private static Bitmap smallCopy(Bitmap bitmap, boolean fullSource) {
        int[] size = PixelMath.size(bitmap.getWidth(), bitmap.getHeight(), fullSource ? 1920 : 320);
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, size[0], size[1], true);
        if (scaled == bitmap || scaled.getConfig() != Bitmap.Config.ARGB_8888) {
            Bitmap copy = scaled.copy(Bitmap.Config.ARGB_8888, false);
            if (scaled != bitmap) scaled.recycle();
            return copy;
        }
        return scaled;
    }

    private static void finish(Bundle request, Bitmap bitmap, String status, String source, int width, int height) {
        SharedMemory shared = null;
        try {
            request.putString("package", app.getPackageName());
            request.putString("status", status);
            request.putString("source", source);
            request.putAll(BackgroundCapture.metadata());
            request.putInt("sourceWidth", width);
            request.putInt("sourceHeight", height);
            request.putLong("sendNs", SystemClock.elapsedRealtimeNanos());
            if (bitmap != null && request.getBoolean("fullSourceMemory")) {
                shared = SharedMemory.create("bydhud-map-source", bitmap.getByteCount());
                ByteBuffer pixels = shared.mapReadWrite();
                try { bitmap.copyPixelsToBuffer(pixels); }
                finally { SharedMemory.unmap(pixels); }
                if (!shared.setProtect(OsConstants.PROT_READ))
                    throw new IllegalStateException("source_memory_protection");
                request.putInt("bitmapWidth", bitmap.getWidth());
                request.putInt("bitmapHeight", bitmap.getHeight());
                request.putParcelable("sourceMemory", shared);
            } else if (bitmap != null) request.putParcelable("bitmap", bitmap);
            app.getContentResolver().call(ENDPOINT, "result", null, request);
        } catch (Throwable e) { lastError = error(e); }
        finally { if (shared != null) shared.close(); if (bitmap != null) bitmap.recycle(); BUSY.set(false); }
    }

    private static String identity(Object value) { return value.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(value)); }
    private static String error(Throwable e) {
        String trace = android.util.Log.getStackTraceString(e);
        return trace.length() <= 4096 ? trace : trace.substring(0, 4096);
    }
}
