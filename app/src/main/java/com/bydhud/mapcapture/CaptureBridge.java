package com.bydhud.mapcapture;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.ColorMatrixColorFilter;
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
    private static volatile int outputEdge = 320;
    private static Boolean swapRedBlue;
    private static volatile long leaseMs = 6000;
    private static final java.util.WeakHashMap<Object, AtomicBoolean> MAPS_PENDING = new java.util.WeakHashMap<>();
    static int outputEdge() { return outputEdge; }
    static long leaseMs() { return leaseMs; }
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
                    ? "map-probe-r9-policy-bulk-hud-consumer-v1" : "map-probe-r12-policy-watchdog-hud-consumer-v1");
            hello.putBoolean("fullSourceMemory", true);
            hello.putLong("captureProtocol", 2L);
            hello.putString("captureCapabilities", CapturePollPolicy.CAPABILITIES);
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
            if (request == null) throw new IllegalStateException("empty_collector_reply");
            lastError = "";
            boolean active = request != null && request.getBoolean("active");
            delay = CapturePollPolicy.interval(active, request == null
                    ? CapturePollPolicy.DEFAULT_MS
                    : request.getLong("pollIntervalMs", CapturePollPolicy.DEFAULT_MS));
            if (active) session=request.getString("session",session);
            fullSource = active && request.getBoolean("fullSourceMemory", false);
            outputEdge = CapturePollPolicy.outputEdge(request == null ? 0 : request.getInt("outputMaxEdge"), fullSource);
            leaseMs = CapturePollPolicy.bounded(request == null ? 0 : request.getLong("leaseMs"), 6000, 1000, 30000);
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
                    }, CapturePollPolicy.bounded(request.getLong("frameTimeoutMs"), 2500, 100, 30000));
                } else {
                    new Handler(Looper.getMainLooper()).post(() -> captureMaps(request));
                }
            }
        } catch (Throwable e) { lastError = error(e); delay = CapturePollPolicy.DEFAULT_MS; } // Existing lease expires if IPC stays unavailable.
        finally {
            pollIntervalMs = delay;
            if (worker != null) worker.postDelayed(CaptureBridge::poll, delay);
        }
    }

    private static void captureMaps(Bundle request) {
        AtomicBoolean delivered = new AtomicBoolean();
        Object controller = maps.get();
        try {
            if (controller == null) { worker.post(() -> finish(request, null, "no_map_controller", "maps", 0, 0)); return; }
            if (MAPS_PENDING.containsKey(controller)) {
                worker.post(() -> finish(request, null, "snapshot_waiting_for_previous_callback", identity(controller), 0, 0));
                return;
            }
            MAPS_PENDING.put(controller, delivered);
            String source = identity(controller);
            request.putString("mapsState", BackgroundCapture.mapsInfo(controller));
            Class<?> callback = Class.forName("boon", true, controller.getClass().getClassLoader());
            Object proxy = Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[]{callback}, (p, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    if (method.getName().equals("hashCode")) return System.identityHashCode(p);
                    if (method.getName().equals("equals")) return p == args[0];
                    return "MapProbeCallback";
                }
                if (!method.getName().equals("a")) return null;
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (MAPS_PENDING.get(controller) == delivered) MAPS_PENDING.remove(controller);
                });
                if (delivered.compareAndSet(false, true)) {
                    try {
                        long callbackNs = SystemClock.elapsedRealtimeNanos();
                        Bitmap bitmap = args != null && args.length == 1 && args[0] instanceof Bitmap ? (Bitmap) args[0] : null;
                        if (bitmap != null) BackgroundCapture.frame(controller);
                        int width = bitmap == null ? 0 : bitmap.getWidth();
                        int height = bitmap == null ? 0 : bitmap.getHeight();
                        // Copy while the callback owns the source; never recycle a navigator-owned Bitmap.
                        Bitmap copy = bitmap == null ? null : smallCopy(bitmap, CapturePollPolicy.outputEdge(request.getInt("outputMaxEdge"), request.getBoolean("fullSourceMemory")));
                        request.putLong("callbackNs", callbackNs);
                        worker.post(() -> finish(request, copy, copy == null ? "null_snapshot" : "ok", source, width, height));
                    } catch (Throwable e) { worker.post(() -> finish(request, null, error(e), source, 0, 0)); }
                }
                return null;
            });
            request.putLong("captureStartNs", SystemClock.elapsedRealtimeNanos());
            controller.getClass().getMethod("L", callback).invoke(controller, proxy);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (delivered.compareAndSet(false, true)) {
                    // Do not overlap native snapshots. A late callback or a new controller allows recovery.
                    worker.post(() -> finish(request, null, "snapshot_callback_timeout", source, 0, 0));
                }
            }, CapturePollPolicy.bounded(request.getLong("frameTimeoutMs"), 2500, 100, 30000));
        } catch (Throwable e) {
            if (MAPS_PENDING.get(controller) == delivered) MAPS_PENDING.remove(controller);
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
                    long conversionStart = SystemClock.elapsedRealtimeNanos();
                    int edge = CapturePollPolicy.outputEdge(request.getInt("outputMaxEdge"), request.getBoolean("fullSourceMemory"));
                    Bitmap bitmap = rgbaBitmap(bytes, width, height, edge);
                    request.putLong("conversionNs", SystemClock.elapsedRealtimeNanos() - conversionStart);
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

    static Bitmap rgbaBitmap(ByteBuffer bytes, int width, int height, int edge) {
        int[] size = PixelMath.size(width, height, edge);
        // Raw Android RGBA storage is premultiplied. Opaque GL frames need no conversion.
        // Keep legacy low-resolution requests and oversized surfaces on the reduced-memory path.
        boolean bulk = edge > 320 && width <= 1920 && height <= 1920;
        for (int i = 3; bulk && i < width * height * 4; i += 4) bulk = bytes.get(i) == (byte) 255;
        if (!bulk) return Bitmap.createBitmap(PixelMath.rgba(bytes, width, height, size[0], size[1]),
                size[0], size[1], Bitmap.Config.ARGB_8888);
        Bitmap raw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Bitmap upright = null;
        try {
            bytes.rewind();
            raw.copyPixelsFromBuffer(bytes);
            upright = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
            Matrix matrix = new Matrix();
            matrix.setScale(size[0] / (float) width, -size[1] / (float) height);
            matrix.postTranslate(0, size[1]);
            Paint paint = null;
            if (needsRedBlueSwap()) {
                paint = new Paint();
                paint.setColorFilter(new ColorMatrixColorFilter(new float[]{
                        0,0,1,0,0, 0,1,0,0,0, 1,0,0,0,0, 0,0,0,1,0}));
            }
            new Canvas(upright).drawBitmap(raw, matrix, paint);
            return upright;
        } catch (RuntimeException | OutOfMemoryError e) {
            if (upright != null) upright.recycle();
            throw e;
        } finally { raw.recycle(); }
    }

    private static synchronized boolean needsRedBlueSwap() {
        if (swapRedBlue == null) {
            Bitmap pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            try {
                pixel.eraseColor(0xffff0000);
                ByteBuffer memory = ByteBuffer.allocate(4);
                pixel.copyPixelsToBuffer(memory);
                swapRedBlue = memory.get(0) != (byte)255;
            } finally { pixel.recycle(); }
        }
        return swapRedBlue;
    }

    private static Bitmap smallCopy(Bitmap bitmap, int edge) {
        int[] size = PixelMath.size(bitmap.getWidth(), bitmap.getHeight(), edge);
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
            request.putLong("captureProtocol", 2L);
            request.putString("package", app.getPackageName());
            request.putString("status", status);
            request.putString("source", source);
            request.putAll(BackgroundCapture.metadata());
            request.putInt("sourceWidth", width);
            request.putInt("sourceHeight", height);
            request.putLong("sendNs", SystemClock.elapsedRealtimeNanos());
            if (bitmap != null && request.getBoolean("fullSourceMemory")) {
                long copyStart = SystemClock.elapsedRealtimeNanos();
                shared = SharedMemory.create("bydhud-map-source", bitmap.getByteCount());
                ByteBuffer pixels = shared.mapReadWrite();
                try { bitmap.copyPixelsToBuffer(pixels); }
                finally { SharedMemory.unmap(pixels); }
                if (!shared.setProtect(OsConstants.PROT_READ))
                    throw new IllegalStateException("source_memory_protection");
                request.putInt("bitmapWidth", bitmap.getWidth());
                request.putInt("bitmapHeight", bitmap.getHeight());
                request.putParcelable("sourceMemory", shared);
                request.putLong("sharedCopyNs", SystemClock.elapsedRealtimeNanos() - copyStart);
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
