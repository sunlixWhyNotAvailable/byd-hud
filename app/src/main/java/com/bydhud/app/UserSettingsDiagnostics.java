package com.bydhud.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Session-time settings, independent of bitmap logging and later export-time changes. */
final class UserSettingsDiagnostics {
    static final String FILE_NAME = "user-settings.jsonl";
    static final String[] PREFERENCE_FILES = {
            "byd_hud_prefs", "byd_hud_nav_capture_prefs", "bydhud_steering_transfer",
            "bydhud_dashboard_widget", "bydhud_update_prefs", "bydhud_update_hint_prefs"
    };
    private static UserSettingsDiagnostics instance;
    private final Context context;
    private final String processSession = UUID.randomUUID().toString();
    private final Map<SharedPreferences, String> preferences = new LinkedHashMap<>();
    private final SharedPreferences.OnSharedPreferenceChangeListener listener;
    private String lastSettings;
    private String lastDay;
    private long sequence;

    private UserSettingsDiagnostics(Context context) {
        this.context = context.getApplicationContext();
        listener = (prefs, key) -> record("settings_changed",
                preferences.get(prefs) + ":" + (key == null ? "cleared" : key), "", "");
        for (String name : PREFERENCE_FILES) {
            SharedPreferences prefs = this.context.getSharedPreferences(name, Context.MODE_PRIVATE);
            preferences.put(prefs, name);
            prefs.registerOnSharedPreferenceChangeListener(listener);
        }
    }

    private static synchronized UserSettingsDiagnostics get(Context context) {
        if (instance == null) instance = new UserSettingsDiagnostics(context);
        return instance;
    }

    static void start(Context context) {
        // Bootstrap reads and registration stay off the application main thread.
        Context app = context.getApplicationContext();
        WazeCaptureDebugWriter.get().settingsTask(() -> get(app).record(
                "process_start", "application", "", ""));
    }

    static void sessionStarted(Context context, String source, long generation) {
        get(context).record("session_start", "navigation", source, Long.toString(generation));
    }

    private void record(String event, String trigger, String source, String session) {
        try {
            // Freeze values now: reading them in the disk queue would lose intervening edits.
            JSONObject settings = snapshot(context);
            String value = settings.toString();
            long wallMs = System.currentTimeMillis();
            long elapsedMs = SystemClock.elapsedRealtime();
            String day = NavCaptureStore.todayDir(wallMs);
            synchronized (this) {
                if ("settings_changed".equals(event) && value.equals(lastSettings)
                        && day.equals(lastDay)) return;
                JSONObject record = new JSONObject()
                        .put("schemaVersion", 1).put("hudVersion", BuildConfig.VERSION_NAME)
                        .put("hudVersionCode", BuildConfig.VERSION_CODE)
                        .put("processSession", processSession).put("sequence", ++sequence)
                        .put("capturedAtWallMs", wallMs).put("capturedAtElapsedMs", elapsedMs)
                        .put("event", event).put("trigger", trigger)
                        .put("source", source).put("sessionGeneration", session)
                        .put("settings", settings)
                        .put("navigatorSettings", new JSONObject().put("status", "not_collected")
                                .put("reason", "navigator_owned_settings_not_exposed"));
                if (WazeCaptureDebugWriter.get().userSettings(context, day, record.toString())) {
                    lastSettings = value;
                    lastDay = day;
                }
            }
        } catch (Exception error) {
            WazeCaptureDebugWriter.get().settingsTask(() ->
                    WazeCaptureDebugWriter.recordWriteFailure("user settings capture: " + error));
            Log.w("BydHudSettings", "settings capture failed", error);
        }
    }

    static JSONObject snapshot(Context context) throws Exception {
        JSONObject output = VehicleConfigurationDiagnostics.options(context);
        JSONArray resolvedProfiles = new JSONArray();
        Map<HudMapProfile.Source, HudMapProfile> saved = HudMapProfiles.profiles(context);
        for (HudMapProfile.Source source : HudMapProfile.Source.values()) {
            HudMapProfile profile = saved.get(source);
            boolean explicitlySaved = profile != null;
            if (profile == null) profile = HudMapProfile.defaults(source);
            resolvedProfiles.put(new JSONObject().put("source", source.name())
                    .put("saved", explicitlySaved).put("x", profile.x)
                    .put("y", profile.y).put("scale", profile.scale));
        }
        output.put("effectiveMapImageProfiles", resolvedProfiles);
        UpdateHintAppearance hint = UpdateHintAppearance.Companion.read(context);
        return new JSONObject().put("output", output)
                .put("ui", new JSONObject().put("language", HudPrefs.uiLanguage(context))
                        .put("darkTheme", HudPrefs.isDarkTheme(context)))
                .put("logging", new JSONObject().put("storageLimitGb", HudPrefs.storageLimitGb(context))
                        .put("detailedArtifacts", HudPrefs.isDetailedDebugArtifactsEnabled(context)))
                .put("updates", new JSONObject()
                        .put("autoCheck", AppUpdateManager.INSTANCE.isAutoCheckEnabled(context))
                        .put("betaChannel", AppUpdateManager.INSTANCE.isBetaChannelEnabled(context)))
                .put("dashboardWidget", new JSONObject(DashboardWidgetPreferences.INSTANCE.encode(
                        DashboardWidgetPreferences.INSTANCE.load(context))))
                .put("updateHint", new JSONObject()
                        .put("enabled", UpdateHintAppearance.Companion.isEnabled(context))
                        .put("transparency", hint.getTransparencyPercent())
                        .put("cornerRadiusDp", hint.getCornerRadiusDp())
                        .put("borderWidthDp", hint.getBorderWidthDp())
                        .put("borderArgb", hint.getBorderArgb()).put("sizePercent", hint.getSizePercent()));
    }

    static synchronized void resetForTests() {
        if (instance == null) return;
        for (SharedPreferences prefs : instance.preferences.keySet()) {
            prefs.unregisterOnSharedPreferenceChangeListener(instance.listener);
        }
        instance = null;
    }
}
