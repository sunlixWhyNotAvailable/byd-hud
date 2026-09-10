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
    private static final String KEY_OUTPUT_PNG = "output_png";
    private static final String KEY_OUTPUT_NATIVE = "output_native";
    private static final String KEY_OUTPUT_LANES = "output_lanes";
    private static final String KEY_OUTPUT_DISTANCE = "output_distance";
    private static final String KEY_OUTPUT_STREET = "output_street";
    private static final String KEY_OUTPUT_TEXT_DIRECTION = "output_text_direction";
    static final String KEY_TEXT_TRANSLITERATION = "text_transliteration";
    private static final String KEY_WAZE_ALERTS = "waze_alerts";
    private static final String KEY_WAZE_ALERT_FIELD = "waze_alert_field";
    private static final String KEY_WHOLE_ROUTE_METRICS = "whole_route_metrics";
    private static final String KEY_ROUTE_METRICS_MODE = "route_metrics_mode";
    private static final String KEY_ETA_OUTPUT_FIELD = "eta_output_field";
    private static final String KEY_ETA_STREET_FORMAT = "eta_street_format";
    private static final String KEY_ETA_WAIT_FOR_FULL_TEXT = "eta_wait_for_full_text";
    private static final String KEY_ETA_ARRIVAL_COLOR = "eta_arrival_color";
    private static final String KEY_ETA_DURATION_COLOR = "eta_duration_color";
    private static final String KEY_ETA_REMAINING_DISTANCE_COLOR = "eta_remaining_distance_color";
    private static final String KEY_WAZE_WARNING_DISTANCE_COLOR = "waze_warning_distance_color";
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
    private static final String KEY_WAZE_CUSTOM_SURFACE = "waze_custom_surface";
    //keeps the legacy boolean only as a migration input for the mode selector.
    private static final String KEY_FULLSCREEN_DASHBOARD = "fullscreen_dashboard";
    private static final String KEY_DASHBOARD_SCREEN_MODE = "dashboard_screen_mode";
    private static final String KEY_DASHBOARD_HEIGHT_PERCENT = "dashboard_height_percent";
    private static final String KEY_DASHBOARD_PARTIAL_WIDTH_PERCENT = "dashboard_partial_width_percent";
    private static final String KEY_DASHBOARD_PARTIAL_HEIGHT_PERCENT = "dashboard_partial_height_percent";
    private static final String KEY_DASHBOARD_PARTIAL_OFFSET_PERCENT = "dashboard_partial_offset_percent";
    private static final String KEY_DASHBOARD_PARTIAL_SCALE_PERCENT = "dashboard_partial_scale_percent";
    private static final String KEY_DASHBOARD_FULL_WIDTH_PERCENT = "dashboard_full_width_percent";
    private static final String KEY_DASHBOARD_FULL_HEIGHT_PERCENT = "dashboard_full_height_percent";
    private static final String KEY_DASHBOARD_FULL_OFFSET_PERCENT = "dashboard_full_offset_percent";
    private static final String KEY_DASHBOARD_FULL_SCALE_PERCENT = "dashboard_full_scale_percent";
    private static final String KEY_DASHBOARD_PARTIAL_FORMAT_METHOD = "dashboard_partial_format_method";
    private static final String KEY_DASHBOARD_FULL_FORMAT_METHOD = "dashboard_full_format_method";
    private static final String KEY_TBT_WITHOUT_HUD_OUTPUT = "tbt_without_hud_output";
    private static final String KEY_SWITCH_TO_TBT_ON_HUD_START = "switch_to_tbt_on_hud_start";
    private static final String KEY_DARK_THEME = "dark_theme";
    private static final String KEY_UA_LANGUAGE = "ua_language";
    private static final String KEY_UI_LANGUAGE = "ui_language";

    static final int ROUTE_METRICS_OFF = 0;
    static final int ROUTE_METRICS_NEXT_STOP = 1;
    static final int ROUTE_METRICS_WHOLE_ROUTE = 2;
    static final int WAZE_ALERT_FIELD_MANEUVER = 0;
    static final int WAZE_ALERT_FIELD_EXPERIMENTAL = 1;
    static final int ETA_OUTPUT_FIELD_STREET = 0;
    static final int ETA_OUTPUT_FIELD_EXPERIMENTAL = 1;
    static final int ETA_STREET_FORMAT_PREPEND = 0;
    static final int ETA_STREET_FORMAT_REPLACE = 1;
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
    static final int DASHBOARD_FORMAT_NATIVE = 0;
    static final int DASHBOARD_FORMAT_ALTERNATIVE = 1;
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

    static int wazeAlertField(Context context) {
        return clamp(prefs(context).getInt(KEY_WAZE_ALERT_FIELD,
                WAZE_ALERT_FIELD_MANEUVER),
                WAZE_ALERT_FIELD_MANEUVER, WAZE_ALERT_FIELD_EXPERIMENTAL);
    }

    static void setWazeAlertField(Context context, int field) {
        prefs(context).edit().putInt(KEY_WAZE_ALERT_FIELD,
                clamp(field, WAZE_ALERT_FIELD_MANEUVER,
                        WAZE_ALERT_FIELD_EXPERIMENTAL)).apply();
        markOutputOptionChanged(KEY_WAZE_ALERT_FIELD);
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

    static int etaOutputField(Context context) {
        return clamp(prefs(context).getInt(KEY_ETA_OUTPUT_FIELD,
                ETA_OUTPUT_FIELD_STREET),
                ETA_OUTPUT_FIELD_STREET, ETA_OUTPUT_FIELD_EXPERIMENTAL);
    }

    static void setEtaOutputField(Context context, int field) {
        prefs(context).edit().putInt(KEY_ETA_OUTPUT_FIELD,
                clamp(field, ETA_OUTPUT_FIELD_STREET,
                        ETA_OUTPUT_FIELD_EXPERIMENTAL)).apply();
        markOutputOptionChanged(KEY_ETA_OUTPUT_FIELD);
    }

    static int getEtaStreetFormat(Context context) {
        return clamp(prefs(context).getInt(KEY_ETA_STREET_FORMAT,
                ETA_STREET_FORMAT_PREPEND),
                ETA_STREET_FORMAT_PREPEND, ETA_STREET_FORMAT_REPLACE);
    }

    static void setEtaStreetFormat(Context context, int format) {
        prefs(context).edit().putInt(KEY_ETA_STREET_FORMAT,
                clamp(format, ETA_STREET_FORMAT_PREPEND, ETA_STREET_FORMAT_REPLACE)).apply();
        markOutputOptionChanged(KEY_ETA_STREET_FORMAT);
    }

    static boolean isEtaWaitForFullTextEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ETA_WAIT_FOR_FULL_TEXT, true);
    }

    static void setEtaWaitForFullTextEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ETA_WAIT_FOR_FULL_TEXT, enabled).apply();
        markOutputOptionChanged(KEY_ETA_WAIT_FOR_FULL_TEXT);
    }

    static int getEtaArrivalColor(Context context) {
        return opaqueColor(prefs(context).getInt(KEY_ETA_ARRIVAL_COLOR, 0xFFFFFFFF));
    }

    static void setEtaArrivalColor(Context context, int color) {
        prefs(context).edit().putInt(KEY_ETA_ARRIVAL_COLOR, opaqueColor(color)).apply();
        markOutputOptionChanged(KEY_ETA_ARRIVAL_COLOR);
    }

    static int getEtaDurationColor(Context context) {
        return opaqueColor(prefs(context).getInt(KEY_ETA_DURATION_COLOR, 0xFFFFFFFF));
    }

    static void setEtaDurationColor(Context context, int color) {
        prefs(context).edit().putInt(KEY_ETA_DURATION_COLOR, opaqueColor(color)).apply();
        markOutputOptionChanged(KEY_ETA_DURATION_COLOR);
    }

    static int getEtaRemainingDistanceColor(Context context) {
        return opaqueColor(prefs(context).getInt(KEY_ETA_REMAINING_DISTANCE_COLOR, 0xFFFFFFFF));
    }

    static void setEtaRemainingDistanceColor(Context context, int color) {
        prefs(context).edit().putInt(KEY_ETA_REMAINING_DISTANCE_COLOR,
                opaqueColor(color)).apply();
        markOutputOptionChanged(KEY_ETA_REMAINING_DISTANCE_COLOR);
    }

    static int getWazeWarningDistanceColor(Context context) {
        return opaqueColor(prefs(context).getInt(KEY_WAZE_WARNING_DISTANCE_COLOR,
                0xFFFFFF00));
    }

    static void setWazeWarningDistanceColor(Context context, int color) {
        prefs(context).edit().putInt(KEY_WAZE_WARNING_DISTANCE_COLOR,
                opaqueColor(color)).apply();
        markOutputOptionChanged(KEY_WAZE_WARNING_DISTANCE_COLOR);
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

    static int dashboardFormatMethod(Context context, int dashboardMode) {
        int mode = normalizeDashboardScreenMode(dashboardMode);
        int fallback = defaultDashboardFormatMethod(mode);
        if (mode == DASHBOARD_MODE_NONE) {
            return fallback;
        }
        String key = mode == DASHBOARD_MODE_FULL
                ? KEY_DASHBOARD_FULL_FORMAT_METHOD : KEY_DASHBOARD_PARTIAL_FORMAT_METHOD;
        return normalizeDashboardFormatMethod(mode, prefs(context).getInt(key, fallback));
    }

    static void setDashboardFormatMethod(Context context, int dashboardMode, int method) {
        int mode = normalizeDashboardScreenMode(dashboardMode);
        if (mode == DASHBOARD_MODE_NONE) {
            return;
        }
        String key = mode == DASHBOARD_MODE_FULL
                ? KEY_DASHBOARD_FULL_FORMAT_METHOD : KEY_DASHBOARD_PARTIAL_FORMAT_METHOD;
        prefs(context).edit().putInt(key, normalizeDashboardFormatMethod(mode, method)).apply();
    }

    static int normalizeDashboardFormatMethod(int dashboardMode, int method) {
        return method == DASHBOARD_FORMAT_NATIVE || method == DASHBOARD_FORMAT_ALTERNATIVE
                ? method : defaultDashboardFormatMethod(dashboardMode);
    }

    private static int defaultDashboardFormatMethod(int dashboardMode) {
        return normalizeDashboardScreenMode(dashboardMode) == DASHBOARD_MODE_FULL
                ? DASHBOARD_FORMAT_NATIVE : DASHBOARD_FORMAT_ALTERNATIVE;
    }

    static DashboardProjectionPolicy.Profile dashboardProjectionProfile(Context context, int mode) {
        SharedPreferences preferences = prefs(context);
        int normalizedMode = normalizeDashboardScreenMode(mode);
        if (normalizedMode == DASHBOARD_MODE_NONE) {
            return DashboardProjectionPolicy.defaultProfile();
        }
        boolean full = normalizedMode == DASHBOARD_MODE_FULL;
        DashboardProjectionPolicy.Profile modeDefaults = full
                ? DashboardProjectionPolicy.fullDefaultProfile()
                : DashboardProjectionPolicy.partialDefaultProfile();
        String widthKey = full ? KEY_DASHBOARD_FULL_WIDTH_PERCENT
                : KEY_DASHBOARD_PARTIAL_WIDTH_PERCENT;
        String heightKey = full ? KEY_DASHBOARD_FULL_HEIGHT_PERCENT
                : KEY_DASHBOARD_PARTIAL_HEIGHT_PERCENT;
        String offsetKey = full ? KEY_DASHBOARD_FULL_OFFSET_PERCENT
                : KEY_DASHBOARD_PARTIAL_OFFSET_PERCENT;
        String scaleKey = full ? KEY_DASHBOARD_FULL_SCALE_PERCENT
                : KEY_DASHBOARD_PARTIAL_SCALE_PERCENT;
        int heightDefault = full && preferences.contains(KEY_DASHBOARD_HEIGHT_PERCENT)
                ? preferences.getInt(KEY_DASHBOARD_HEIGHT_PERCENT,
                modeDefaults.heightPercent)
                : modeDefaults.heightPercent;
        return new DashboardProjectionPolicy.Profile(
                preferences.getInt(widthKey, modeDefaults.widthPercent),
                preferences.getInt(heightKey, heightDefault),
                preferences.getInt(offsetKey, modeDefaults.offsetPercent),
                preferences.getInt(scaleKey, modeDefaults.scalePercent));
    }

    static void setDashboardWidthPercent(Context context, int mode, int percent) {
        putDashboardValue(context, mode, KEY_DASHBOARD_PARTIAL_WIDTH_PERCENT,
                KEY_DASHBOARD_FULL_WIDTH_PERCENT,
                DashboardProjectionPolicy.clampWidthPercent(percent));
    }

    static void setDashboardHeightPercent(Context context, int mode, int percent) {
        putDashboardValue(context, mode, KEY_DASHBOARD_PARTIAL_HEIGHT_PERCENT,
                KEY_DASHBOARD_FULL_HEIGHT_PERCENT,
                DashboardProjectionPolicy.clampHeightPercent(percent));
    }

    static void setDashboardOffsetPercent(Context context, int mode, int percent) {
        putDashboardValue(context, mode, KEY_DASHBOARD_PARTIAL_OFFSET_PERCENT,
                KEY_DASHBOARD_FULL_OFFSET_PERCENT,
                DashboardProjectionPolicy.clampOffsetPercent(percent));
    }

    static void setDashboardScalePercent(Context context, int mode, int percent) {
        putDashboardValue(context, mode, KEY_DASHBOARD_PARTIAL_SCALE_PERCENT,
                KEY_DASHBOARD_FULL_SCALE_PERCENT,
                DashboardProjectionPolicy.clampScalePercent(percent));
    }

    private static void putDashboardValue(Context context, int mode,
            String partialKey, String fullKey, int value) {
        int normalizedMode = normalizeDashboardScreenMode(mode);
        if (normalizedMode == DASHBOARD_MODE_NONE) {
            return;
        }
        prefs(context).edit().putInt(
                normalizedMode == DASHBOARD_MODE_FULL ? fullKey : partialKey, value).apply();
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
        return "uk".equals(uiLanguage(context));
    }

    static boolean isRuLanguage(Context context) {
        return "ru".equals(uiLanguage(context));
    }

    static String uiLanguage(Context context) {
        android.content.SharedPreferences values = prefs(context);
        if (!values.contains(KEY_UI_LANGUAGE)) {
            return values.getBoolean(KEY_UA_LANGUAGE, true) ? "uk" : "en";
        }
        String language = values.getString(KEY_UI_LANGUAGE, "uk");
        return "ru".equals(language) || "en".equals(language) ? language : "uk";
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static void setUaLanguage(Context context, boolean enabled) {
        setUiLanguage(context, enabled ? "uk" : "en");
    }

    static void setUiLanguage(Context context, String language) {
        String normalized = "ru".equals(language) || "en".equals(language) ? language : "uk";
        prefs(context).edit()
                .putString(KEY_UI_LANGUAGE, normalized)
                .putBoolean(KEY_UA_LANGUAGE, "uk".equals(normalized))
                .apply();
        markOutputOptionChanged(KEY_UA_LANGUAGE);
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

    private static int opaqueColor(int color) {
        return color | 0xFF000000;
    }
}
