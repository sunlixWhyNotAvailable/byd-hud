package com.bydhud.app;

import android.content.Context;
import android.content.SharedPreferences;

final class HudPrefs {
    private static final String PREFS_NAME = "byd_hud_prefs";
    private static final String KEY_BOOT_ENABLED = "boot_enabled";
    private static final String KEY_SMALL_DISTANCE_CLAMP = "small_distance_clamp";
    private static final String KEY_ROUNDABOUT_LEFT_HAND_TRAFFIC = "roundabout_left_hand_traffic";
    private static final String KEY_OUTPUT_PNG = "output_png";
    private static final String KEY_OUTPUT_NATIVE = "output_native";
    private static final String KEY_OUTPUT_LANES = "output_lanes";
    private static final String KEY_OUTPUT_DISTANCE = "output_distance";
    private static final String KEY_OUTPUT_STREET = "output_street";
    private static final String KEY_DARK_THEME = "dark_theme";
    private static final String KEY_UA_LANGUAGE = "ua_language";
    private static final String KEY_STORAGE_LIMIT_GB = "storage_limit_gb";
    private static final String KEY_BG_REMINDER_VERSION = "bg_reminder_version";
    private static final String KEY_RUNTIME_SERVICE_RUNNING = "runtime_service_running";

    private HudPrefs() {
    }

    static boolean isBootEnabled(Context context) {
        return prefs(context).getBoolean(KEY_BOOT_ENABLED, false);
    }

    static void setBootEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_BOOT_ENABLED, enabled).apply();
    }

    static boolean isSmallDistanceClampEnabled(Context context) {
        return prefs(context).getBoolean(KEY_SMALL_DISTANCE_CLAMP, false);
    }

    static void setSmallDistanceClampEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_SMALL_DISTANCE_CLAMP, enabled).apply();
    }

    static boolean isRoundaboutLeftHandTraffic(Context context) {
        return prefs(context).getBoolean(KEY_ROUNDABOUT_LEFT_HAND_TRAFFIC, false);
    }

    static void setRoundaboutLeftHandTraffic(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ROUNDABOUT_LEFT_HAND_TRAFFIC, enabled).apply();
    }

    static boolean isPngOutputEnabled(Context context) {
        return prefs(context).getBoolean(KEY_OUTPUT_PNG, true);
    }

    static void setPngOutputEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_OUTPUT_PNG, enabled).apply();
    }

    static boolean isNativeOutputEnabled(Context context) {
        return prefs(context).getBoolean(KEY_OUTPUT_NATIVE, true);
    }

    static void setNativeOutputEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_OUTPUT_NATIVE, enabled).apply();
    }

    static boolean isLaneOutputEnabled(Context context) {
        return prefs(context).getBoolean(KEY_OUTPUT_LANES, true);
    }

    static void setLaneOutputEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_OUTPUT_LANES, enabled).apply();
    }

    static boolean isDistanceOutputEnabled(Context context) {
        return prefs(context).getBoolean(KEY_OUTPUT_DISTANCE, true);
    }

    static void setDistanceOutputEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_OUTPUT_DISTANCE, enabled).apply();
    }

    static boolean isStreetOutputEnabled(Context context) {
        return prefs(context).getBoolean(KEY_OUTPUT_STREET, true);
    }

    static void setStreetOutputEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_OUTPUT_STREET, enabled).apply();
    }

    static boolean isDarkTheme(Context context) {
        return prefs(context).getBoolean(KEY_DARK_THEME, true);
    }

    static void setDarkTheme(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_DARK_THEME, enabled).apply();
    }

    static boolean isUaLanguage(Context context) {
        return prefs(context).getBoolean(KEY_UA_LANGUAGE, true);
    }

    static void setUaLanguage(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_UA_LANGUAGE, enabled).apply();
    }

    static int storageLimitGb(Context context) {
        return Math.max(1, Math.min(10, prefs(context).getInt(KEY_STORAGE_LIMIT_GB, 5)));
    }

    static void setStorageLimitGb(Context context, int value) {
        prefs(context).edit()
                .putInt(KEY_STORAGE_LIMIT_GB, Math.max(1, Math.min(10, value)))
                .apply();
    }

    static boolean shouldShowBackgroundReminder(Context context) {
        String seenVersion = prefs(context).getString(KEY_BG_REMINDER_VERSION, "");
        return !BuildConfig.VERSION_NAME.equals(seenVersion);
    }

    static void markBackgroundReminderSeen(Context context) {
        prefs(context).edit()
                .putString(KEY_BG_REMINDER_VERSION, BuildConfig.VERSION_NAME)
                .apply();
    }

    static boolean isRuntimeServiceRunning(Context context) {
        return prefs(context).getBoolean(KEY_RUNTIME_SERVICE_RUNNING, false);
    }

    static void setRuntimeServiceRunning(Context context, boolean running) {
        prefs(context).edit().putBoolean(KEY_RUNTIME_SERVICE_RUNNING, running).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
