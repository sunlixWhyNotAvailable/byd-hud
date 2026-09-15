package com.bydhud.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Looper;
import android.provider.Settings;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Serializes boot cleanup before persisted runtime state is consumed. */
final class BootCleanupGate {
    private static final String PREFS = "bydhud_boot_cleanup_gate";
    private static final String KEY_LAST_OBSERVED_BOOT_COUNT = "last_observed_boot_count";
    private static final String KEY_LAST_RESET_BOOT_COUNT = "last_reset_boot_count";
    private static final String QUICKBOOT_ACTION = "android.intent.action.QUICKBOOT_POWERON";
    private static final Object QUEUE_LOCK = new Object();
    private static final ThreadLocal<Boolean> IN_QUEUE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> CURRENT_OPERATION_ADMITTED = new ThreadLocal<>();
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    private static final AtomicInteger PENDING_GATE_OPERATIONS = new AtomicInteger();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "bydhud-boot-cleanup");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile Future<?> latest;
    private static volatile boolean ready;

    interface Completion {
        void finish(boolean admitted);
    }

    private BootCleanupGate() {
    }

    static void initialize(Context context) {
        if (context == null || !INITIALIZED.compareAndSet(false, true)) return;
        enqueueOperation(context.getApplicationContext(), BootCleanupPolicy.Trigger.INITIALIZE, null);
    }

    static boolean handlesBootAction(String action) {
        return Intent.ACTION_BOOT_COMPLETED.equals(action) || QUICKBOOT_ACTION.equals(action);
    }

    static void handleBootAction(Context context, String action, Completion completion) {
        Context appContext = context.getApplicationContext();
        initialize(appContext);
        BootCleanupPolicy.Trigger trigger = Intent.ACTION_BOOT_COMPLETED.equals(action)
                ? BootCleanupPolicy.Trigger.BOOT_COMPLETED
                : BootCleanupPolicy.Trigger.QUICKBOOT;
        enqueueOperation(appContext, trigger, null, completion);
    }

    static void runWhenReady(Context context, Runnable runnable) {
        runWhenReady(context, runnable, null);
    }

    static void runWhenReady(Context context, Runnable runnable, Runnable completion) {
        Context appContext = context.getApplicationContext();
        initialize(appContext);
        enqueue(() -> runWithCompletion(() -> {
            if (!ready) ready = executeOperation(
                    appContext, BootCleanupPolicy.Trigger.INITIALIZE);
            if (!ready) {
                AppEventLogger.event(appContext, "boot_cleanup consumer_fenced");
                return;
            }
            try {
                runnable.run();
            } catch (RuntimeException error) {
                AppEventLogger.event(appContext, "boot_cleanup consumer_failed type="
                        + error.getClass().getSimpleName());
            }
        }, completion));
    }

    static void runWithCompletion(Runnable operation, Runnable completion) {
        try {
            operation.run();
        } finally {
            if (completion != null) completion.run();
        }
    }

    static boolean awaitReady(Context context) {
        if (Boolean.TRUE.equals(IN_QUEUE.get())) {
            Boolean admitted = CURRENT_OPERATION_ADMITTED.get();
            return admitted == null ? ready : admitted;
        }
        Context appContext = context.getApplicationContext();
        initialize(appContext);
        if (Looper.myLooper() == Looper.getMainLooper()) return ready;
        Future<?> barrier = latest;
        if (barrier == null) return ready;
        try {
            barrier.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            AppEventLogger.event(context, "boot_cleanup await_failed type="
                    + failure.getClass().getSimpleName());
        }
        if (!ready) {
            Future<?> retry;
            synchronized (QUEUE_LOCK) {
                latest = WORKER.submit(() -> {
                    IN_QUEUE.set(true);
                    try {
                        ready = executeOperation(
                                appContext, BootCleanupPolicy.Trigger.INITIALIZE);
                    } finally {
                        IN_QUEUE.remove();
                    }
                });
                retry = latest;
            }
            try {
                retry.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception failure) {
                AppEventLogger.event(appContext, "boot_cleanup retry_failed type="
                        + failure.getClass().getSimpleName());
            }
        }
        return ready;
    }

    private static void enqueueOperation(Context context, BootCleanupPolicy.Trigger trigger,
            Runnable completion) {
        enqueueOperation(context, trigger, completion, null);
    }

    private static void enqueueOperation(Context context, BootCleanupPolicy.Trigger trigger,
            Runnable completion, Completion resultCompletion) {
        ready = false;
        PENDING_GATE_OPERATIONS.incrementAndGet();
        enqueue(() -> {
            boolean successful = executeOperation(context, trigger);
            CURRENT_OPERATION_ADMITTED.set(successful);
            int remaining = PENDING_GATE_OPERATIONS.decrementAndGet();
            ready = successful && remaining == 0;
            if (completion != null) completion.run();
            if (resultCompletion != null) resultCompletion.finish(successful);
        });
    }

    private static boolean executeOperation(Context context, BootCleanupPolicy.Trigger trigger) {
        int bootCount = readBootCount(context);
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        BootCleanupPolicy.Outcome outcome;
        try {
            outcome = BootCleanupPolicy.execute(trigger, bootCount,
                    new PreferenceStore(preferences), new BootCleanupPolicy.Clears() {
                        @Override public boolean clearProjection() {
                            return NavAppDisplayController.get(context)
                                    .clearStaleProjectionIntentForBoot(trigger.name());
                        }

                        @Override public boolean clearWaze() {
                            return WazeRouteLifecycleStore.clearForBoot(context, trigger.name());
                        }
                    });
        } catch (RuntimeException failure) {
            outcome = BootCleanupPolicy.Outcome.MARKER_WRITE_FAILED;
            AppEventLogger.event(context, "boot_cleanup failed trigger=" + trigger
                    + " count=" + bootCount + " type="
                    + failure.getClass().getSimpleName());
        }
        AppEventLogger.event(context, "boot_cleanup transition trigger=" + trigger
                + " count=" + bootCount + " outcome=" + outcome);
        return isSuccessful(outcome);
    }

    private static boolean isSuccessful(BootCleanupPolicy.Outcome outcome) {
        return outcome == BootCleanupPolicy.Outcome.RECOVERY_ONLY
                || outcome == BootCleanupPolicy.Outcome.SEEDED
                || outcome == BootCleanupPolicy.Outcome.RESET_COMPLETE;
    }

    private static void enqueue(Runnable operation) {
        synchronized (QUEUE_LOCK) {
            latest = WORKER.submit(() -> {
                IN_QUEUE.set(true);
                try {
                    operation.run();
                } finally {
                    CURRENT_OPERATION_ADMITTED.remove();
                    IN_QUEUE.remove();
                }
            });
        }
    }

    private static int readBootCount(Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.BOOT_COUNT, BootCleanupPolicy.UNKNOWN_BOOT_COUNT);
        } catch (RuntimeException ignored) {
            return BootCleanupPolicy.UNKNOWN_BOOT_COUNT;
        }
    }

    private static final class PreferenceStore implements BootCleanupPolicy.Store,
            BootCleanupPolicy.MarkerBackend {
        private final SharedPreferences preferences;

        PreferenceStore(SharedPreferences preferences) {
            this.preferences = preferences;
        }

        @Override public BootCleanupPolicy.State read() {
            return new BootCleanupPolicy.State(
                    preferences.getInt(KEY_LAST_OBSERVED_BOOT_COUNT,
                            BootCleanupPolicy.UNKNOWN_BOOT_COUNT),
                    preferences.getInt(KEY_LAST_RESET_BOOT_COUNT,
                            BootCleanupPolicy.UNKNOWN_BOOT_COUNT));
        }

        @Override public boolean seedObserved(int bootCount) {
            BootCleanupPolicy.State previous = read();
            return BootCleanupPolicy.writeMarkersDurably(this,
                    new BootCleanupPolicy.State(bootCount, previous.completedBootCount));
        }

        @Override public boolean completeValidReset(int bootCount) {
            return BootCleanupPolicy.writeMarkersDurably(this,
                    new BootCleanupPolicy.State(bootCount, bootCount));
        }

        @Override public boolean completeUnknownReset() {
            return BootCleanupPolicy.writeMarkersDurably(this,
                    new BootCleanupPolicy.State(
                            BootCleanupPolicy.UNKNOWN_BOOT_COUNT,
                            BootCleanupPolicy.UNKNOWN_BOOT_COUNT));
        }

        @Override public boolean write(BootCleanupPolicy.State state) {
            SharedPreferences.Editor editor = preferences.edit();
            if (state.observedBootCount >= 0) {
                editor.putInt(KEY_LAST_OBSERVED_BOOT_COUNT, state.observedBootCount);
            } else {
                editor.remove(KEY_LAST_OBSERVED_BOOT_COUNT);
            }
            if (state.completedBootCount >= 0) {
                editor.putInt(KEY_LAST_RESET_BOOT_COUNT, state.completedBootCount);
            } else {
                editor.remove(KEY_LAST_RESET_BOOT_COUNT);
            }
            return editor.commit();
        }
    }
}
