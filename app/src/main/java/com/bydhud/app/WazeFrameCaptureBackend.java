package com.bydhud.app;

//selects the Waze frame source while keeping live capture in memory-only paths.

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.PixelCopy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

//keeps frame acquisition separate from parser decisions so old Waze merge behavior stays intact.
final class WazeFrameCaptureBackend {
    static final String BACKEND_MEDIAPROJECTION = "mediaprojection";
    static final String BACKEND_PIXELCOPY = "pixelcopy";
    static final String BACKEND_UNAVAILABLE = "unavailable";

    private static final String WAZE_PACKAGE = "com.waze";
    private static final long PIXELCOPY_TIMEOUT_MS = 800L;
    private static final int PIXELCOPY_RESTART_TIMEOUTS = 3;
    private static final long PIXELCOPY_RESTART_BACKOFF_MS = 250L;

    private final Context context;
    private final HandlerThread pixelCopyThread = new HandlerThread("BydHudPixelCopy");
    private final Handler pixelCopyHandler;
    private int consecutivePixelCopyTimeouts;

    //holds application context so worker threads never retain an Activity.
    WazeFrameCaptureBackend(Context context) {
        this.context = context.getApplicationContext();
        pixelCopyThread.start();
        pixelCopyHandler = new Handler(pixelCopyThread.getLooper());
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
        AtomicBoolean timedOut = new AtomicBoolean(false);
        AtomicBoolean bitmapRecycled = new AtomicBoolean(false);
        pixelCopyHandler.post(() -> {
            if (timedOut.get()) {
                resultCode.set(PixelCopy.ERROR_TIMEOUT);
                latch.countDown();
                return;
            }
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
                            if (timedOut.get()) {
                                recycleOnce(bitmap, bitmapRecycled);
                            }
                            latch.countDown();
                        },
                        pixelCopyHandler);
            } catch (RuntimeException e) {
                resultCode.set(PixelCopy.ERROR_SOURCE_INVALID);
                latch.countDown();
            }
        });
        try {
            if (!latch.await(PIXELCOPY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                timedOut.set(true);
                recycleOnce(bitmap, bitmapRecycled);
                return pixelCopyTimeout(start);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recycleOnce(bitmap, bitmapRecycled);
            return CaptureResult.unavailable("pixelcopy-interrupted", start);
        }
        if (resultCode.get() != PixelCopy.SUCCESS) {
            recycleOnce(bitmap, bitmapRecycled);
            return CaptureResult.unavailable("pixelcopy-result=" + resultCode.get(), start);
        }
        if (!ClusterProjectionService.isProjectedSurfaceCurrent(projected)) {
            recycleOnce(bitmap, bitmapRecycled);
            return CaptureResult.unavailable("pixelcopy-stale-surface", start);
        }
        resetPixelCopyTimeouts();
        return CaptureResult.ok(BACKEND_PIXELCOPY, bitmap, start);
    }

    //bounds timed-out PixelCopy bitmap lifetime without double-recycling late callbacks.
    private static void recycleOnce(Bitmap bitmap, AtomicBoolean recycled) {
        if (bitmap != null && recycled != null && recycled.compareAndSet(false, true)) {
            bitmap.recycle();
        }
    }

    //drops timed-out dashboard frames and refreshes surface metadata without moving Waze between displays.
    private CaptureResult pixelCopyTimeout(long start) {
        int timeoutStreak = ++consecutivePixelCopyTimeouts;
        AppEventLogger.event(context, "waze_crop pixelcopy_timeout timeout_streak=" + timeoutStreak);
        if (timeoutStreak >= PIXELCOPY_RESTART_TIMEOUTS) {
            AppEventLogger.event(context,
                    "waze_crop dashboard_capture_restart reason=pixelcopy-timeout-streak timeout_streak="
                            + timeoutStreak);
            ClusterProjectionService.recoverProjectedSurface(
                    context,
                    WAZE_PACKAGE,
                    "pixelcopy-timeout-streak");
            sleepQuietly(PIXELCOPY_RESTART_BACKOFF_MS);
            consecutivePixelCopyTimeouts = 0;
        }
        return CaptureResult.unavailable("pixelcopy-timeout", start, timeoutStreak);
    }

    //resets timeout streaks only after a real PixelCopy frame was obtained.
    private void resetPixelCopyTimeouts() {
        if (consecutivePixelCopyTimeouts != 0) {
            AppEventLogger.event(context,
                    "waze_crop pixelcopy_timeout_recovered previous_streak="
                            + consecutivePixelCopyTimeouts);
        }
        consecutivePixelCopyTimeouts = 0;
    }

    //keeps timeout backoff local to dashboard capture so the main capture path remains unaffected.
    private static void sleepQuietly(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    //carries timing and frame ownership from capture into parser logging.
    static final class CaptureResult {
        final String backend;
        final Bitmap frame;
        final long captureStartMs;
        final long captureEndMs;
        final String reason;
        final int pixelCopyTimeoutStreak;

        //keeps result fields immutable so parse logs reflect the actual capture attempt.
        private CaptureResult(String backend, Bitmap frame, long captureStartMs, long captureEndMs,
                String reason, int pixelCopyTimeoutStreak) {
            this.backend = backend;
            this.frame = frame;
            this.captureStartMs = captureStartMs;
            this.captureEndMs = captureEndMs;
            this.reason = reason == null ? "" : reason;
            this.pixelCopyTimeoutStreak = pixelCopyTimeoutStreak;
        }

        //marks a successful frame capture and leaves bitmap recycling to the caller.
        static CaptureResult ok(String backend, Bitmap frame, long start) {
            return new CaptureResult(backend, frame, start, SystemClock.elapsedRealtime(), "", 0);
        }

        //marks a successful fallback frame when capture timing was measured by the caller.
        static CaptureResult okWithTiming(String backend, Bitmap frame, long start, long end) {
            return new CaptureResult(backend, frame, start, end, "", 0);
        }

        //marks why an in-memory frame was not available.
        static CaptureResult unavailable(String reason, long start) {
            return unavailable(reason, start, 0);
        }

        //marks why an in-memory frame was not available with dashboard timeout streak context.
        static CaptureResult unavailable(String reason, long start, int pixelCopyTimeoutStreak) {
            return new CaptureResult(BACKEND_UNAVAILABLE, null, start,
                    SystemClock.elapsedRealtime(), reason, pixelCopyTimeoutStreak);
        }
    }
}
