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
    static final String KEY_TEXT_TRANSLITERATION = "text_transliteration";
    private static final String KEY_WAZE_ALERTS = "waze_alerts";
    private static final String KEY_WHOLE_ROUTE_METRICS = "whole_route_metrics";
    private static final String KEY_ROUTE_METRICS_MODE = "route_metrics_mode";
    private static final String KEY_OUTPUT_ETA = "output_eta";
    private static final String KEY_OUTPUT_REMAINING_TIME = "output_remaining_time";
    private static final String KEY_OUTPUT_REMAINING_DISTANCE = "output_remaining_distance";
    private static final String KEY_SPEED_LIMIT_MODE = "speed_limit_mode";
    private static final String KEY_SPEED_LIMIT_FREE_FALLBACK = "speed_limit_free_fallback";
    private static final String KEY_SPEED_LIMIT_OVERLAY_SECONDS = "speed_limit_overlay_seconds";
    private static final String KEY_SPEED_LIMIT_COMPOSITE_PLACEMENT =
            "speed_limit_composite_placement";
    private static final String KEY_SPEED_LIMIT_MANEUVER_OVERLAY_SIZE =
            "speed_limit_maneuver_overlay_size";
    private static final String KEY_SPEED_LIMIT_LANE_OVERLAY_SIZE =
            "speed_limit_lane_overlay_size";
    private static final String KEY_WAZE_SCREEN_CAPTURE = "waze_screen_capture";
    private static final String KEY_WAZE_CUSTOM_SURFACE = "waze_custom_surface";
    //keeps the legacy boolean only as a migration input for the mode selector.
    private static final String KEY_FULLSCREEN_DASHBOARD = "fullscreen_dashboard";
    private static final String KEY_DASHBOARD_SCREEN_MODE = "dashboard_screen_mode";
    private static final String KEY_DASHBOARD_HEIGHT_PERCENT = "dashboard_height_percent";
    private static final String KEY_TBT_WITHOUT_HUD_OUTPUT = "tbt_without_hud_output";
    private static final String KEY_SWITCH_TO_TBT_ON_HUD_START = "switch_to_tbt_on_hud_start";
    private static final String KEY_DARK_THEME = "dark_theme";
    private static final String KEY_UA_LANGUAGE = "ua_language";

    static final int ROUTE_METRICS_OFF = 0;
    static final int ROUTE_METRICS_NEXT_STOP = 1;
    static final int ROUTE_METRICS_WHOLE_ROUTE = 2;
    static final int SPEED_LIMIT_OFF = 0;
    static final int SPEED_LIMIT_MANEUVER = 1;
    static final int SPEED_LIMIT_LANES = 2;
    static final int SPEED_LIMIT_FREE = 3;
    static final int SPEED_LIMIT_COMPOSITE = 4;
    static final int SPEED_LIMIT_FALLBACK_OFF = 0;
    static final int SPEED_LIMIT_FALLBACK_MANEUVER = 1;
    static final int SPEED_LIMIT_FALLBACK_LANES = 2;
    static final int SPEED_LIMIT_COMPOSITE_MANEUVER_ONLY = 0;
    static final int SPEED_LIMIT_COMPOSITE_LANES_ONLY = 1;
    static final int SPEED_LIMIT_COMPOSITE_FREE_OR_MANEUVER = 2;
    static final int SPEED_LIMIT_COMPOSITE_FREE_OR_LANES = 3;
    static final int TRANSLITERATION_OFF = HudTextTransliterator.OFF;
    static final int TRANSLITERATION_UKRAINIAN = HudTextTransliterator.UKRAINIAN;
    static final int TRANSLITERATION_UNIVERSAL = HudTextTransliterator.UNIVERSAL;
    static final int DASHBOARD_MODE_NONE = 0;
    static final int DASHBOARD_MODE_PARTIAL = 1;
    static final int DASHBOARD_MODE_FULL = 2;
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
        return prefs(context).getBoolean(KEY_BOOT_ENABLED, true);
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
        markOutputOptionChanged(KEY_SMALL_DISTANCE_CLAMP);
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
        markOutputOptionChanged(KEY_OUTPUT_PNG);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean isNativeOutputEnabled(Context context) {
        return prefs(context).getBoolean(KEY_OUTPUT_NATIVE, true);
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static void setNativeOutputEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_OUTPUT_NATIVE, enabled).apply();
        markOutputOptionChanged(KEY_OUTPUT_NATIVE);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean isLaneOutputEnabled(Context context) {
        return prefs(context).getBoolean(KEY_OUTPUT_LANES, true);
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static void setLaneOutputEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_OUTPUT_LANES, enabled).apply();
        markOutputOptionChanged(KEY_OUTPUT_LANES);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean isDistanceOutputEnabled(Context context) {
        return prefs(context).getBoolean(KEY_OUTPUT_DISTANCE, true);
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static void setDistanceOutputEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_OUTPUT_DISTANCE, enabled).apply();
        markOutputOptionChanged(KEY_OUTPUT_DISTANCE);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean isStreetOutputEnabled(Context context) {
        return prefs(context).getBoolean(KEY_OUTPUT_STREET, true);
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static void setStreetOutputEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_OUTPUT_STREET, enabled).apply();
        markOutputOptionChanged(KEY_OUTPUT_STREET);
    }

    static int transliterationMode(Context context) {
        return normalizeTransliterationMode(
                prefs(context).getInt(KEY_TEXT_TRANSLITERATION, TRANSLITERATION_OFF));
    }

    static void setTransliterationMode(Context context, int mode) {
        prefs(context).edit().putInt(KEY_TEXT_TRANSLITERATION,
                normalizeTransliterationMode(mode)).apply();
        markOutputOptionChanged(KEY_TEXT_TRANSLITERATION);
    }

    static int normalizeTransliterationMode(int mode) {
        return clamp(mode, TRANSLITERATION_OFF, TRANSLITERATION_UNIVERSAL);
    }

    static boolean isTextDirectionOutputEnabled(Context context) {
        return prefs(context).getBoolean(KEY_OUTPUT_TEXT_DIRECTION, true);
    }

    static void setTextDirectionOutputEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_OUTPUT_TEXT_DIRECTION, enabled).apply();
        markOutputOptionChanged(KEY_OUTPUT_TEXT_DIRECTION);
    }

    static boolean isWazeAlertsEnabled(Context context) {
        return prefs(context).getBoolean(KEY_WAZE_ALERTS, true);
    }

    static void setWazeAlertsEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_WAZE_ALERTS, enabled).apply();
        markOutputOptionChanged(KEY_WAZE_ALERTS);
    }

    static boolean isWholeRouteMetricsEnabled(Context context) {
        return routeMetricsMode(context) == ROUTE_METRICS_WHOLE_ROUTE;
    }

    static void setWholeRouteMetricsEnabled(Context context, boolean enabled) {
        int current = routeMetricsMode(context);
        setRouteMetricsMode(context, enabled ? ROUTE_METRICS_WHOLE_ROUTE
                : current == ROUTE_METRICS_OFF ? ROUTE_METRICS_OFF
                : ROUTE_METRICS_NEXT_STOP);
    }

    static int routeMetricsMode(Context context) {
        SharedPreferences preferences = prefs(context);
        if (!preferences.contains(KEY_ROUTE_METRICS_MODE)) {
            boolean anyMetric = preferences.getBoolean(KEY_OUTPUT_ETA, false)
                    || preferences.getBoolean(KEY_OUTPUT_REMAINING_TIME, false)
                    || preferences.getBoolean(KEY_OUTPUT_REMAINING_DISTANCE, false);
            int migrated = !anyMetric ? ROUTE_METRICS_OFF
                    : preferences.getBoolean(KEY_WHOLE_ROUTE_METRICS, false)
                    ? ROUTE_METRICS_WHOLE_ROUTE : ROUTE_METRICS_NEXT_STOP;
            preferences.edit().putInt(KEY_ROUTE_METRICS_MODE, migrated).apply();
            return migrated;
        }
        return clamp(preferences.getInt(KEY_ROUTE_METRICS_MODE, ROUTE_METRICS_OFF),
                ROUTE_METRICS_OFF, ROUTE_METRICS_WHOLE_ROUTE);
    }

    static void setRouteMetricsMode(Context context, int mode) {
        prefs(context).edit().putInt(KEY_ROUTE_METRICS_MODE,
                clamp(mode, ROUTE_METRICS_OFF, ROUTE_METRICS_WHOLE_ROUTE)).apply();
        markOutputOptionChanged(KEY_ROUTE_METRICS_MODE);
    }

    static boolean isEtaOutputEnabled(Context context) {
        return prefs(context).getBoolean(KEY_OUTPUT_ETA, false);
    }

    static void setEtaOutputEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_OUTPUT_ETA, enabled).apply();
        markOutputOptionChanged(KEY_OUTPUT_ETA);
    }

    static boolean isRemainingTimeOutputEnabled(Context context) {
        return prefs(context).getBoolean(KEY_OUTPUT_REMAINING_TIME, false);
    }

    static void setRemainingTimeOutputEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_OUTPUT_REMAINING_TIME, enabled).apply();
        markOutputOptionChanged(KEY_OUTPUT_REMAINING_TIME);
    }

    static boolean isRemainingDistanceOutputEnabled(Context context) {
        return prefs(context).getBoolean(KEY_OUTPUT_REMAINING_DISTANCE, false);
    }

    static void setRemainingDistanceOutputEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_OUTPUT_REMAINING_DISTANCE, enabled).apply();
        markOutputOptionChanged(KEY_OUTPUT_REMAINING_DISTANCE);
    }

    static int speedLimitMode(Context context) {
        return normalizeSpeedLimitMode(
                prefs(context).getInt(KEY_SPEED_LIMIT_MODE, SPEED_LIMIT_OFF));
    }

    static void setSpeedLimitMode(Context context, int mode) {
        prefs(context).edit().putInt(KEY_SPEED_LIMIT_MODE,
                normalizeSpeedLimitMode(mode)).apply();
        markOutputOptionChanged(KEY_SPEED_LIMIT_MODE);
    }

    static int speedLimitFreeFallback(Context context) {
        return clamp(prefs(context).getInt(
                KEY_SPEED_LIMIT_FREE_FALLBACK, SPEED_LIMIT_FALLBACK_OFF),
                SPEED_LIMIT_FALLBACK_OFF, SPEED_LIMIT_FALLBACK_LANES);
    }

    static void setSpeedLimitFreeFallback(Context context, int mode) {
        prefs(context).edit().putInt(KEY_SPEED_LIMIT_FREE_FALLBACK,
                clamp(mode, SPEED_LIMIT_FALLBACK_OFF, SPEED_LIMIT_FALLBACK_LANES)).apply();
        markOutputOptionChanged(KEY_SPEED_LIMIT_FREE_FALLBACK);
    }

    static int speedLimitOverlaySeconds(Context context) {
        return clamp(prefs(context).getInt(KEY_SPEED_LIMIT_OVERLAY_SECONDS, 5), 1, 10);
    }

    static void setSpeedLimitOverlaySeconds(Context context, int seconds) {
        prefs(context).edit().putInt(
                KEY_SPEED_LIMIT_OVERLAY_SECONDS, clamp(seconds, 1, 10)).apply();
        markOutputOptionChanged(KEY_SPEED_LIMIT_OVERLAY_SECONDS);
    }

    static int speedLimitCompositePlacement(Context context) {
        return normalizeSpeedLimitCompositePlacement(prefs(context).getInt(
                KEY_SPEED_LIMIT_COMPOSITE_PLACEMENT,
                SPEED_LIMIT_COMPOSITE_MANEUVER_ONLY));
    }

    static void setSpeedLimitCompositePlacement(Context context, int placement) {
        prefs(context).edit().putInt(KEY_SPEED_LIMIT_COMPOSITE_PLACEMENT,
                normalizeSpeedLimitCompositePlacement(placement)).apply();
        markOutputOptionChanged(KEY_SPEED_LIMIT_COMPOSITE_PLACEMENT);
    }

    static int speedLimitManeuverOverlaySize(Context context) {
        return normalizeSpeedLimitManeuverOverlaySize(
                prefs(context).getInt(KEY_SPEED_LIMIT_MANEUVER_OVERLAY_SIZE, 64));
    }

    static void setSpeedLimitManeuverOverlaySize(Context context, int size) {
        prefs(context).edit().putInt(KEY_SPEED_LIMIT_MANEUVER_OVERLAY_SIZE,
                normalizeSpeedLimitManeuverOverlaySize(size)).apply();
        markOutputOptionChanged(KEY_SPEED_LIMIT_MANEUVER_OVERLAY_SIZE);
    }

    static int speedLimitLaneOverlaySize(Context context) {
        return normalizeSpeedLimitLaneOverlaySize(
                prefs(context).getInt(KEY_SPEED_LIMIT_LANE_OVERLAY_SIZE, 36));
    }

    static void setSpeedLimitLaneOverlaySize(Context context, int size) {
        prefs(context).edit().putInt(KEY_SPEED_LIMIT_LANE_OVERLAY_SIZE,
                normalizeSpeedLimitLaneOverlaySize(size)).apply();
        markOutputOptionChanged(KEY_SPEED_LIMIT_LANE_OVERLAY_SIZE);
    }

    static int normalizeSpeedLimitMode(int mode) {
        return clamp(mode, SPEED_LIMIT_OFF, SPEED_LIMIT_COMPOSITE);
    }

    static int normalizeSpeedLimitCompositePlacement(int placement) {
        return clamp(placement, SPEED_LIMIT_COMPOSITE_MANEUVER_ONLY,
                SPEED_LIMIT_COMPOSITE_FREE_OR_LANES);
    }

    static int normalizeSpeedLimitManeuverOverlaySize(int size) {
        return clamp(size, 1, 103);
    }

    static int normalizeSpeedLimitLaneOverlaySize(int size) {
        return clamp(size, 1, 36);
    }

    static boolean isWazeScreenCaptureEnabled(Context context) {
        return prefs(context).getBoolean(KEY_WAZE_SCREEN_CAPTURE, false);
    }

    static void setWazeScreenCaptureEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_WAZE_SCREEN_CAPTURE, enabled).apply();
    }

    static boolean isWazeCustomSurfaceEnabled(Context context) {
        return prefs(context).getBoolean(KEY_WAZE_CUSTOM_SURFACE, false);
    }

    static void setWazeCustomSurfaceEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_WAZE_CUSTOM_SURFACE, enabled).apply();
    }

    static int dashboardScreenMode(Context context) {
        SharedPreferences preferences = prefs(context);
        if (!preferences.contains(KEY_DASHBOARD_SCREEN_MODE)) {
            int migrated = !preferences.contains(KEY_FULLSCREEN_DASHBOARD)
                    || preferences.getBoolean(KEY_FULLSCREEN_DASHBOARD, true)
                    ? DASHBOARD_MODE_FULL : DASHBOARD_MODE_NONE;
            preferences.edit().putInt(KEY_DASHBOARD_SCREEN_MODE, migrated).apply();
            return migrated;
        }
        return normalizeDashboardScreenMode(
                preferences.getInt(KEY_DASHBOARD_SCREEN_MODE, DASHBOARD_MODE_FULL));
    }

    static void setDashboardScreenMode(Context context, int mode) {
        prefs(context).edit().putInt(KEY_DASHBOARD_SCREEN_MODE,
                normalizeDashboardScreenMode(mode)).apply();
    }

    static int normalizeDashboardScreenMode(int mode) {
        return clamp(mode, DASHBOARD_MODE_NONE, DASHBOARD_MODE_FULL);
    }

    static int dashboardHeightPercent(Context context) {
        return DashboardProjectionPolicy.clampHeightPercent(prefs(context).getInt(
                KEY_DASHBOARD_HEIGHT_PERCENT,
                DashboardProjectionPolicy.DEFAULT_HEIGHT_PERCENT));
    }

    static void setDashboardHeightPercent(Context context, int percent) {
        prefs(context).edit().putInt(
                KEY_DASHBOARD_HEIGHT_PERCENT,
                DashboardProjectionPolicy.clampHeightPercent(percent)).apply();
    }

    static boolean isTbtWithoutHudOutputEnabled(Context context) {
        return prefs(context).getBoolean(KEY_TBT_WITHOUT_HUD_OUTPUT, true);
    }

    static void setTbtWithoutHudOutputEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_TBT_WITHOUT_HUD_OUTPUT, enabled).apply();
    }

    static boolean isSwitchToTbtOnHudStartEnabled(Context context) {
        return prefs(context).getBoolean(KEY_SWITCH_TO_TBT_ON_HUD_START, true);
    }

    static void setSwitchToTbtOnHudStartEnabled(Context context, boolean enabled) {
        prefs(context).edit()
                .putBoolean(KEY_SWITCH_TO_TBT_ON_HUD_START, enabled)
                .apply();
    }

    static int outputOptionsRevision() {
        return outputOptionsRevision;
    }

    private static void markOutputOptionChanged(String key) {
        outputOptionsRevision++;
        NavHudLiveSender.onOutputPreferenceChanged(key);
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
        SomeIpTxLog.onDetailedModeChanged(context, enabled);
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

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
