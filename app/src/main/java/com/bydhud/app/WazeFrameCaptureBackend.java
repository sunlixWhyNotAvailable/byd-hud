package com.bydhud.app;

//selects the Waze frame source while preserving screencap as beta fallback evidence.

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

//keeps frame acquisition separate from parser decisions so old Waze merge behavior stays intact.
final class WazeFrameCaptureBackend {
    static final String BACKEND_MEDIAPROJECTION = "mediaprojection";
    static final String BACKEND_PIXELCOPY = "pixelcopy";
    static final String BACKEND_SCREENCAP_FALLBACK = "screencap_fallback";
    static final String BACKEND_UNAVAILABLE = "unavailable";

    private static final String WAZE_PACKAGE = "com.waze";
    private static final long PIXELCOPY_TIMEOUT_MS = 800L;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    //holds application context so worker threads never retain an Activity.
    WazeFrameCaptureBackend(Context context) {
        this.context = context.getApplicationContext();
    }

    //returns the best currently available in-memory frame for the observed Waze display.
    CaptureResult capture(NavAppDisplayState state) {
        long start = SystemClock.elapsedRealtime();
        if (state != null && state.displayId == 0) {
            Bitmap frame = WazeMediaProjectionController.latestMainFrame();
            if (frame != null) {
                return CaptureResult.ok(BACKEND_MEDIAPROJECTION, frame, start);
            }
            WazeMediaProjectionController.restartFromCachedConsent(context, "main-frame-missing");
            WazeMediaProjectionController.ensureReadyOrPrompt(context, "main-frame-missing");
            return CaptureResult.unavailable("mediaprojection-not-ready", start);
        }
        if (state != null && state.displayId > 0) {
            CaptureResult result = captureDashboardPixelCopy(start);
            if (result.frame != null) {
                return result;
            }
            return result;
        }
        return CaptureResult.unavailable(
                "no-frame-backend display=" + (state == null ? -1 : state.displayId), start);
    }

    //copies pixels from the app-owned dashboard projection surface.
    private CaptureResult captureDashboardPixelCopy(long start) {
        ClusterProjectionService.ProjectedSurface projected =
                ClusterProjectionService.projectedSurfaceForPackage(WAZE_PACKAGE);
        if (projected == null || projected.surface == null) {
            return CaptureResult.unavailable("pixelcopy-no-projected-surface", start);
        }
        Bitmap bitmap = Bitmap.createBitmap(projected.width, projected.height, Bitmap.Config.ARGB_8888);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger resultCode = new AtomicInteger(PixelCopy.ERROR_UNKNOWN);
        mainHandler.post(() -> {
            if (!ClusterProjectionService.isProjectedSurfaceCurrent(projected)) {
                resultCode.set(PixelCopy.ERROR_SOURCE_INVALID);
                latch.countDown();
                return;
            }
            try {
                PixelCopy.request(
                        projected.surface,
                        new Rect(0, 0, projected.width, projected.height),
                        bitmap,
                        result -> {
                            resultCode.set(result);
                            latch.countDown();
                        },
                        mainHandler);
            } catch (RuntimeException e) {
                resultCode.set(PixelCopy.ERROR_SOURCE_INVALID);
                latch.countDown();
            }
        });
        try {
            if (!latch.await(PIXELCOPY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                bitmap.recycle();
                return CaptureResult.unavailable("pixelcopy-timeout", start);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            bitmap.recycle();
            return CaptureResult.unavailable("pixelcopy-interrupted", start);
        }
        if (resultCode.get() != PixelCopy.SUCCESS) {
            bitmap.recycle();
            return CaptureResult.unavailable("pixelcopy-result=" + resultCode.get(), start);
        }
        if (!ClusterProjectionService.isProjectedSurfaceCurrent(projected)) {
            bitmap.recycle();
            return CaptureResult.unavailable("pixelcopy-stale-surface", start);
        }
        return CaptureResult.ok(BACKEND_PIXELCOPY, bitmap, start);
    }

    //carries timing and frame ownership from capture into parser logging.
    static final class CaptureResult {
        final String backend;
        final Bitmap frame;
        final long captureStartMs;
        final long captureEndMs;
        final String reason;

        //keeps result fields immutable so parse logs reflect the actual capture attempt.
        private CaptureResult(String backend, Bitmap frame, long captureStartMs, long captureEndMs,
                String reason) {
            this.backend = backend;
            this.frame = frame;
            this.captureStartMs = captureStartMs;
            this.captureEndMs = captureEndMs;
            this.reason = reason == null ? "" : reason;
        }

        //marks a successful frame capture and leaves bitmap recycling to the caller.
        static CaptureResult ok(String backend, Bitmap frame, long start) {
            return new CaptureResult(backend, frame, start, SystemClock.elapsedRealtime(), "");
        }

        //marks a successful fallback frame when capture timing was measured by the caller.
        static CaptureResult okWithTiming(String backend, Bitmap frame, long start, long end) {
            return new CaptureResult(backend, frame, start, end, "");
        }

        //marks why an in-memory frame was not available.
        static CaptureResult unavailable(String reason, long start) {
            return new CaptureResult(BACKEND_UNAVAILABLE, null, start,
                    SystemClock.elapsedRealtime(), reason);
        }
    }
}
