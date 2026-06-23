package com.bydhud.app;

//records runtime heartbeat state so supervisor decisions can distinguish alive and stale services.

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

//models HudRuntimeState data here so transport and parser layers share a stable contract.
final class HudRuntimeState {
    private static final String PREFS_NAME = "byd_hud_runtime_state";
    private static final String KEY_RUNNING = "running";
    private static final String KEY_STARTED_AT = "started_at";
    private static final String KEY_LAST_HEARTBEAT = "last_heartbeat";
    private static final String KEY_REASON = "reason";
    private static final String KEY_STOP_REASON = "stop_reason";
    private static final long LIVE_TTL_MS = 90_000L;

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
        prefs(context).edit()
                .putBoolean(KEY_RUNNING, false)
                .putString(KEY_STOP_REASON, safe(reason))
                .apply();
        recordLifecycleHook(context, "stopped", reason);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean isAlive(Context context, long nowElapsedMs) {
        SharedPreferences prefs = prefs(context);
        if (!prefs.getBoolean(KEY_RUNNING, false)) {
            return false;
        }
        long lastHeartbeat = prefs.getLong(KEY_LAST_HEARTBEAT, 0L);
        long ageMs = nowElapsedMs - lastHeartbeat;
        return lastHeartbeat > 0L && ageMs >= 0L && ageMs <= LIVE_TTL_MS;
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static String summary(Context context, long nowElapsedMs) {
        SharedPreferences prefs = prefs(context);
        long lastHeartbeat = prefs.getLong(KEY_LAST_HEARTBEAT, 0L);
        long ageMs = lastHeartbeat <= 0L ? -1L : nowElapsedMs - lastHeartbeat;
        String ageText = ageMs < 0L ? "clock-reset" : Long.toString(ageMs);
        if (isAlive(context, nowElapsedMs)) {
            return "alive ageMs=" + ageText + " reason=" + prefs.getString(KEY_REASON, "");
        }
        if (prefs.getBoolean(KEY_RUNNING, false)) {
            return "stale ageMs=" + ageText + " reason=" + prefs.getString(KEY_REASON, "");
        }
        return "stopped reason=" + prefs.getString(KEY_STOP_REASON, "");
    }

    //updates shared state here so freshness and lifecycle checks use the same evidence.
    static void recordLifecycleHook(Context context, String lifecycle, String reason) {
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
