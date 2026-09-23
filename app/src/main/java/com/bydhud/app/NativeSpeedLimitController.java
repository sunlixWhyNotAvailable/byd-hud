package com.bydhud.app;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Android adapter; lifecycle is owned exclusively by the active HUD output session. */
final class NativeSpeedLimitController {
    private static final class BitmapFallback {
        final String owner;
        final int mode;
        BitmapFallback(String owner, int mode) { this.owner = owner; this.mode = mode; }
    }
    private static volatile BitmapFallback bitmap = new BitmapFallback("", 0);
    private final Context context;
    private final Handler worker;
    private final BooleanSupplier outputActive;
    private final Consumer<String> bitmapChanged;
    private final NativeSpeedLimitEngine engine;
    private String owner = "";
    private int mode, clearMode, limit;
    private boolean fallback;

    NativeSpeedLimitController(Context context, Handler worker, BooleanSupplier outputActive,
            Consumer<String> bitmapChanged) {
        this.context = context;
        this.worker = worker;
        this.outputActive = outputActive;
        this.bitmapChanged = bitmapChanged;
        engine = new NativeSpeedLimitEngine(new NativeSpeedLimitEngine.Port() {
            @Override public long now() { return SystemClock.elapsedRealtime(); }
            @Override public void post(Runnable task, long delayMs) {
                worker.postAtTime(task, NativeSpeedLimitController.this,
                        SystemClock.uptimeMillis() + delayMs);
            }
            @Override public void cancelTasks() {
                worker.removeCallbacksAndMessages(NativeSpeedLimitController.this);
            }
            @Override public void call(int operation, int value, BooleanSupplier current,
                    Consumer<NativeSpeedLimitEngine.Result> callback) {
                if (!allowed()) {
                    stop("output-or-input-changed");
                    return;
                }
                InstrumentProxyManager.get(context).nativeSpeedOperation(operation, value,
                        () -> current.getAsBoolean() && allowed(),
                        result -> worker.post(() -> callback.accept(result)));
            }
            @Override public void fallback(boolean enabled) {
                fallback = enabled;
                publishBitmap();
            }
            @Override public void log(String message) { AppEventLogger.event(context, message); }
        });
    }

    void refresh(String owner, long session, int limit) {
        int primary = HudPrefs.speedLimitMode(context);
        int clearing = HudPrefs.getNativeSpeedLimitClearMode(context);
        if (!this.owner.equals(owner)) stop("owner-changed");
        this.owner = owner;
        this.mode = primary;
        this.clearMode = clearing;
        this.limit = limit;
        int target = NativeSpeedLimitEngine.requestedTarget(primary == HudPrefs.SPEED_LIMIT_NATIVE,
                clearing, limit);
        if (!outputActive.getAsBoolean() || target < 0) {
            stop("no-native-operation");
            return;
        }
        engine.configure(owner + ":" + session + ":" + primary + ":" + clearing,
                target, primary != HudPrefs.SPEED_LIMIT_NATIVE);
        publishBitmap();
    }

    private boolean allowed() {
        return outputActive.getAsBoolean() && !ShanghaiOutputGate.isSuspended()
                && !HudPrefs.isUserShutdownActive(context)
                && HudPrefs.speedLimitMode(context) == mode
                && HudPrefs.getNativeSpeedLimitClearMode(context) == clearMode
                && ("manual".equals(owner) || DirectSpeedLimitStore.snapshot(owner).getKph() == limit);
    }

    void stop(String reason) {
        engine.stop(reason);
        fallback = false;
        publishBitmap();
    }

    private void publishBitmap() {
        int nextMode = fallback ? HudPrefs.getNativeSpeedLimitFallbackMode(context) : 0;
        BitmapFallback previous = bitmap;
        if (previous.mode == nextMode && previous.owner.equals(owner)) return;
        bitmap = new BitmapFallback(owner, nextMode);
        if (previous.mode != 0 && !previous.owner.equals(owner)) bitmapChanged.accept(previous.owner);
        bitmapChanged.accept(owner);
    }

    static DirectTbtPayload.Options outputOptions(Context context, String owner) {
        DirectTbtPayload.Options options = DirectTbtPayload.Options.from(context);
        if (options.speedLimitMode != HudPrefs.SPEED_LIMIT_NATIVE) return options;
        BitmapFallback current = bitmap;
        return options.withSpeedLimitMode(NativeSpeedLimitEngine.bitmapMode(options.speedLimitMode,
                current.mode, current.owner.equals(owner)));
    }
}
