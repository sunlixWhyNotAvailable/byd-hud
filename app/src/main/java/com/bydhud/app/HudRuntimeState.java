package com.bydhud.app;

//records process-local service presence plus persisted heartbeat diagnostics.

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import java.util.concurrent.atomic.AtomicBoolean;

//models HudRuntimeState data here so transport and parser layers share a stable contract.
final class HudRuntimeState {
    private static final String PREFS_NAME = "byd_hud_runtime_state";
    private static final String KEY_RUNNING = "running";
    private static final String KEY_STARTED_AT = "started_at";
    private static final String KEY_LAST_HEARTBEAT = "last_heartbeat";
    private static final String KEY_REASON = "reason";
    private static final String KEY_STOP_REASON = "stop_reason";
    //Process-local presence is the only admission signal. Persisted heartbeat
    //state remains diagnostic because it cannot prove that this process exists.
    private static final AtomicBoolean SERVICE_PRESENT = new AtomicBoolean(false);

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private HudRuntimeState() {
    }

    //updates shared state here so freshness and lifecycle checks use the same evidence.
    static void markStarted(Context context, String reason) {
        long now = SystemClock.elapsedRealtime();
        prefs(context).edit()
                .putBoolean(KEY_RUNNING, true)
                .putLong(KEY_STARTED_AT, now)
                .putLong(KEY_LAST_HEARTBEAT, now)
                .putString(KEY_REASON, safe(reason))
                .apply();
        recordLifecycleHook(context, "started", reason);
    }

    //updates shared state here so freshness and lifecycle checks use the same evidence.
    static void markHeartbeat(Context context, String reason) {
        long now = SystemClock.elapsedRealtime();
        prefs(context).edit()
                .putBoolean(KEY_RUNNING, true)
                .putLong(KEY_LAST_HEARTBEAT, now)
                .putString(KEY_REASON, safe(reason))
                .apply();
        recordLifecycleHook(context, "heartbeat", reason);
    }

    //updates shared state here so freshness and lifecycle checks use the same evidence.
    static void markStopped(Context context, String reason) {
        clearServicePresent(context, reason);
        prefs(context).edit()
                .putBoolean(KEY_RUNNING, false)
                .putString(KEY_STOP_REASON, safe(reason))
                .apply();
        recordLifecycleHook(context, "stopped", reason);
    }

    //guards package-replace restart from losing the stopped flag when the process is killed immediately.
    static boolean markPackageReplaceReset(Context context, String reason) {
        clearServicePresent(context, "package-replace-hard-reset:" + safe(reason));
        boolean persisted = prefs(context).edit()
                .putBoolean(KEY_RUNNING, false)
                .putString(KEY_STOP_REASON, safe(reason))
                .commit();
        recordLifecycleHook(context, "stopped", "package-replace-hard-reset:" + safe(reason));
        return persisted;
    }

    //publishes the real Android service lifecycle to every thread in this process.
    static boolean publishServicePresent(Context context, String reason) {
        boolean changed = SERVICE_PRESENT.compareAndSet(false, true);
        if (changed) {
            recordLifecycleHook(context, "service-present", reason);
        }
        return changed;
    }

    //clears process-local service presence before teardown or a rejected start can race in.
    static boolean clearServicePresent(Context context, String reason) {
        boolean wasPresent = SERVICE_PRESENT.getAndSet(false);
        if (wasPresent) {
            recordLifecycleHook(context, "service-absent", reason);
        }
        return wasPresent;
    }

    //keeps admission independent from persisted heartbeat state across process recreation.
    static boolean isServicePresent() {
        return SERVICE_PRESENT.get();
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static String summary(Context context, long nowElapsedMs) {
        SharedPreferences prefs = prefs(context);
        long lastHeartbeat = prefs.getLong(KEY_LAST_HEARTBEAT, 0L);
        long ageMs = lastHeartbeat <= 0L ? -1L : nowElapsedMs - lastHeartbeat;
        String ageText = ageMs < 0L ? "clock-reset" : Long.toString(ageMs);
        boolean persistedRunning = prefs.getBoolean(KEY_RUNNING, false);
        return "servicePresent=" + isServicePresent()
                + " persistedRunning=" + persistedRunning
                + " heartbeatAgeMs=" + ageText
                + " ageMs=" + ageText
                + " reason=" + prefs.getString(KEY_REASON, "")
                + " stopReason=" + prefs.getString(KEY_STOP_REASON, "");
    }

    //updates shared state here so freshness and lifecycle checks use the same evidence.
    static void recordLifecycleHook(Context context, String lifecycle, String reason) {
        if (context == null) {
            return;
        }
        recordLifecycleHook(context, lifecycle, reason, SystemClock.elapsedRealtime());
    }

    //updates shared state here so freshness and lifecycle checks use the same evidence.
    static void recordLifecycleHook(
            Context context,
            String lifecycle,
            String reason,
            long nowElapsedMs) {
        if (context == null) {
            return;
        }
        AppEventLogger.event(
                context,
                "runtime_liveness lifecycle=" + safeLog(lifecycle)
                        + " reason=" + safeLog(reason)
                        + " nowMs=" + Math.max(0L, nowElapsedMs)
                        + " state=" + safeLog(summary(context, nowElapsedMs)));
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    //normalizes values here so malformed app text cannot leak into HUD payloads.
    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    //normalizes values here so malformed app text cannot leak into HUD payloads.
    private static String safeLog(String value) {
        return safe(value).replace(' ', '_');
    }
}
