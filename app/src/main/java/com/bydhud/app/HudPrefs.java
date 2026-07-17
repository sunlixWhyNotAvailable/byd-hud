package com.bydhud.app;

//keeps shared preferences access narrow so UI and runtime read the same output settings.

import android.content.Context;
import android.content.SharedPreferences;

//defines the HudPrefs module boundary so related behavior stays readable inside one unit.
final class HudPrefs {
    private static volatile int outputOptionsRevision;
    private static final String PREFS_NAME = "byd_hud_prefs";
    private static final String KEY_BOOT_ENABLED = "boot_enabled";
    private static final String KEY_SMALL_DISTANCE_CLAMP = "small_distance_clamp";
    private static final String KEY_ROUNDABOUT_LEFT_HAND_TRAFFIC = "roundabout_left_hand_traffic";
    private static final String KEY_OUTPUT_PNG = "output_png";
    private static final String KEY_OUTPUT_NATIVE = "output_native";
    private static final String KEY_OUTPUT_LANES = "output_lanes";
    private static final String KEY_OUTPUT_DISTANCE = "output_distance";
    private static final String KEY_OUTPUT_STREET = "output_street";
    private static final String KEY_OUTPUT_TEXT_DIRECTION = "output_text_direction";
    private static final String KEY_WAZE_ALERTS = "waze_alerts";
    private static final String KEY_DARK_THEME = "dark_theme";
    private static final String KEY_UA_LANGUAGE = "ua_language";
    private static final String KEY_STORAGE_LIMIT_GB = "storage_limit_gb";
    private static final String KEY_DETAILED_DEBUG_ARTIFACTS = "detailed_debug_artifacts";
    private static final String KEY_OPTIONS_INTRO_VERSION_CODE = "options_intro_version_code";
    private static final String KEY_BG_REMINDER_VERSION = "bg_reminder_version";
    private static final String KEY_BG_REMINDER_TOKEN = "bg_reminder_token";
    private static final String KEY_RUNTIME_SERVICE_RUNNING = "runtime_service_running";
    private static final String KEY_USER_SHUTDOWN_ACTIVE = "user_shutdown_active";

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private HudPrefs() {
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean isBootEnabled(Context context) {
        return prefs(context).getBoolean(KEY_BOOT_ENABLED, false);
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static void setBootEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_BOOT_ENABLED, enabled).apply();
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean isSmallDistanceClampEnabled(Context context) {
        return prefs(context).getBoolean(KEY_SMALL_DISTANCE_CLAMP, false);
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static void setSmallDistanceClampEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_SMALL_DISTANCE_CLAMP, enabled).apply();
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean isRoundaboutLeftHandTraffic(Context context) {
        return prefs(context).getBoolean(KEY_ROUNDABOUT_LEFT_HAND_TRAFFIC, false);
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static void setRoundaboutLeftHandTraffic(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ROUNDABOUT_LEFT_HAND_TRAFFIC, enabled).apply();
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean isPngOutputEnabled(Context context) {
        return prefs(context).getBoolean(KEY_OUTPUT_PNG, true);
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static void setPngOutputEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_OUTPUT_PNG, enabled).apply();
        outputOptionsRevision++;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean isNativeOutputEnabled(Context context) {
        return prefs(context).getBoolean(KEY_OUTPUT_NATIVE, true);
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static void setNativeOutputEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_OUTPUT_NATIVE, enabled).apply();
        outputOptionsRevision++;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean isLaneOutputEnabled(Context context) {
        return prefs(context).getBoolean(KEY_OUTPUT_LANES, true);
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static void setLaneOutputEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_OUTPUT_LANES, enabled).apply();
        outputOptionsRevision++;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean isDistanceOutputEnabled(Context context) {
        return prefs(context).getBoolean(KEY_OUTPUT_DISTANCE, true);
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static void setDistanceOutputEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_OUTPUT_DISTANCE, enabled).apply();
        outputOptionsRevision++;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean isStreetOutputEnabled(Context context) {
        return prefs(context).getBoolean(KEY_OUTPUT_STREET, true);
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static void setStreetOutputEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_OUTPUT_STREET, enabled).apply();
        outputOptionsRevision++;
    }

    static boolean isTextDirectionOutputEnabled(Context context) {
        return prefs(context).getBoolean(KEY_OUTPUT_TEXT_DIRECTION, true);
    }

    static void setTextDirectionOutputEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_OUTPUT_TEXT_DIRECTION, enabled).apply();
        outputOptionsRevision++;
    }

    static boolean isWazeAlertsEnabled(Context context) {
        return prefs(context).getBoolean(KEY_WAZE_ALERTS, true);
    }

    static void setWazeAlertsEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_WAZE_ALERTS, enabled).apply();
    }

    static int outputOptionsRevision() {
        return outputOptionsRevision;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean isDarkTheme(Context context) {
        return prefs(context).getBoolean(KEY_DARK_THEME, true);
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static void setDarkTheme(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_DARK_THEME, enabled).apply();
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean isUaLanguage(Context context) {
        return prefs(context).getBoolean(KEY_UA_LANGUAGE, true);
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static void setUaLanguage(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_UA_LANGUAGE, enabled).apply();
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static int storageLimitGb(Context context) {
        return Math.max(1, Math.min(10, prefs(context).getInt(KEY_STORAGE_LIMIT_GB, 5)));
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static void setStorageLimitGb(Context context, int value) {
        prefs(context).edit()
                .putInt(KEY_STORAGE_LIMIT_GB, Math.max(1, Math.min(10, value)))
                .apply();
    }

    //keeps debug artifact volume user-controlled while preserving operational logs.
    static boolean isDetailedDebugArtifactsEnabled(Context context) {
        return prefs(context).getBoolean(KEY_DETAILED_DEBUG_ARTIFACTS, false);
    }

    //keeps debug artifact volume user-controlled while preserving operational logs.
    static void setDetailedDebugArtifactsEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_DETAILED_DEBUG_ARTIFACTS, enabled).apply();
    }

    //opens Options once for each installed build, then defaults later launches to Apps.
    static boolean takeOptionsIntroForCurrentVersion(Context context) {
        SharedPreferences preferences = prefs(context);
        long currentVersionCode = BuildConfig.VERSION_CODE;
        if (preferences.getLong(KEY_OPTIONS_INTRO_VERSION_CODE, -1L) == currentVersionCode) {
            return false;
        }
        preferences.edit().putLong(KEY_OPTIONS_INTRO_VERSION_CODE, currentVersionCode).apply();
        return true;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean shouldShowBackgroundReminder(Context context) {
        long packageReplaceToken = HudRuntimeUpgradeGuard.packageReplaceToken(context);
        if (packageReplaceToken > 0L) {
            long seenToken = prefs(context).getLong(KEY_BG_REMINDER_TOKEN, 0L);
            return seenToken != packageReplaceToken;
        }
        String seenVersion = prefs(context).getString(KEY_BG_REMINDER_VERSION, "");
        return !BuildConfig.VERSION_NAME.equals(seenVersion);
    }

    //updates shared state here so freshness and lifecycle checks use the same evidence.
    static void markBackgroundReminderSeen(Context context) {
        long packageReplaceToken = HudRuntimeUpgradeGuard.packageReplaceToken(context);
        prefs(context).edit()
                .putString(KEY_BG_REMINDER_VERSION, BuildConfig.VERSION_NAME)
                .putLong(KEY_BG_REMINDER_TOKEN, packageReplaceToken)
                .apply();
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean isRuntimeServiceRunning(Context context) {
        return prefs(context).getBoolean(KEY_RUNTIME_SERVICE_RUNNING, false);
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static void setRuntimeServiceRunning(Context context, boolean running) {
        prefs(context).edit().putBoolean(KEY_RUNTIME_SERVICE_RUNNING, running).apply();
    }

    //guards auto-start after explicit user shutdown until MainActivity is opened again.
    static boolean isUserShutdownActive(Context context) {
        return prefs(context).getBoolean(KEY_USER_SHUTDOWN_ACTIVE, false);
    }

    //records explicit shutdown separately from boot preference so auto-start can be restored on next manual open.
    static void setUserShutdownActive(Context context, boolean active) {
        prefs(context).edit().putBoolean(KEY_USER_SHUTDOWN_ACTIVE, active).apply();
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
