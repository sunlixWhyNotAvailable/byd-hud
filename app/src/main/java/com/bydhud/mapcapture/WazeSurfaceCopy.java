package com.bydhud.mapcapture;

import android.graphics.Bitmap;
import android.os.Handler;
import android.view.PixelCopy;
import android.view.Surface;
import java.util.function.BooleanSupplier;

/** Waze 5.25 presents outside the old Java EGL swap; copy its actual output queue. */
final class WazeSurfaceCopy {
    interface Result { void complete(Bitmap bitmap, String status); }
    private static volatile Pending pending;
    private static final class Pending {
        Bitmap bitmap;
        volatile boolean delivered;
        Runnable timeout;
    }
    static boolean inFlight() {
        Pending p = pending;
        return p != null && !p.delivered;
    }

    // Invocations, timeout and PixelCopy callbacks all run on the main handler.
    static void copy(Surface surface, int width, int height, long timeoutMs,
            Handler handler, BooleanSupplier current, Result result) {
        if (pending != null) { result.complete(null, "surface_copy_busy"); return; }
        if (!current.getAsBoolean() || !surface.isValid()) {
            result.complete(null, "surface_changed"); return;
        }
        Pending p = new Pending();
        try {
            p.bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            pending = p;
            p.timeout = () -> {
                if (p.delivered) return;
                p.delivered = true;
                // PixelCopy may still own the bitmap. Keep one allocation until its callback.
                result.complete(null, "surface_copy_timeout");
            };
            handler.postDelayed(p.timeout, timeoutMs);
            PixelCopy.request(surface, p.bitmap, code -> {
                handler.removeCallbacks(p.timeout);
                if (pending == p) pending = null;
                if (p.delivered) { p.bitmap.recycle(); return; }
                p.delivered = true;
                String status = !current.getAsBoolean() || !surface.isValid()
                        ? "surface_changed" : code == PixelCopy.SUCCESS ? "ok" : "pixelcopy_error_" + code;
                Bitmap bitmap = p.bitmap;
                if (!"ok".equals(status)) { bitmap.recycle(); bitmap = null; }
                result.complete(bitmap, status);
            }, handler);
        } catch (RuntimeException | OutOfMemoryError error) {
            if (p.timeout != null) handler.removeCallbacks(p.timeout);
            if (pending == p) pending = null;
            if (p.bitmap != null) p.bitmap.recycle();
            result.complete(null, "surface_copy_exception_" + error.getClass().getSimpleName());
        }
    }
}
