package com.bydhud.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

final class HudRuntimeState {
    private static final String PREFS_NAME = "byd_hud_runtime_state";
    private static final String KEY_RUNNING = "running";
    private static final String KEY_STARTED_AT = "started_at";
    private static final String KEY_LAST_HEARTBEAT = "last_heartbeat";
    private static final String KEY_REASON = "reason";
    private static final String KEY_STOP_REASON = "stop_reason";
    private static final long LIVE_TTL_MS = 90_000L;

    private HudRuntimeState() {
    }

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

    static void markHeartbeat(Context context, String reason) {
        long now = SystemClock.elapsedRealtime();
        prefs(context).edit()
                .putBoolean(KEY_RUNNING, true)
                .putLong(KEY_LAST_HEARTBEAT, now)
                .putString(KEY_REASON, safe(reason))
                .apply();
        recordLifecycleHook(context, "heartbeat", reason);
    }

    static void markStopped(Context context, String reason) {
        prefs(context).edit()
                .putBoolean(KEY_RUNNING, false)
                .putString(KEY_STOP_REASON, safe(reason))
                .apply();
        recordLifecycleHook(context, "stopped", reason);
    }

    static boolean isAlive(Context context, long nowElapsedMs) {
        SharedPreferences prefs = prefs(context);
        if (!prefs.getBoolean(KEY_RUNNING, false)) {
            return false;
        }
        long lastHeartbeat = prefs.getLong(KEY_LAST_HEARTBEAT, 0L);
        long ageMs = nowElapsedMs - lastHeartbeat;
        return lastHeartbeat > 0L && ageMs >= 0L && ageMs <= LIVE_TTL_MS;
    }

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

    static void recordLifecycleHook(Context context, String lifecycle, String reason) {
        recordLifecycleHook(context, lifecycle, reason, SystemClock.elapsedRealtime());
    }

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

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String safeLog(String value) {
        return safe(value).replace(' ', '_');
    }
}
