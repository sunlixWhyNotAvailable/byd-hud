package com.bydhud.app;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One-shot, passive snapshots. No runtime initialization, permission repair or vehicle writes. */
final class VehicleConfigurationDiagnostics {
    private static final String[] PACKAGES = {
            "com.bydhud.app", "com.waze", "app.revanced.android.apps.maps",
            "com.google.android.apps.maps", "com.ts.car.someip.service", "com.byd.launchermap",
            "com.example.amapservice", "com.byd.amapservice", "com.byd.containerservice",
            "com.byd.someipsystemservice", "com.byd.clusterdebug", "com.android.launcher3"
    };
    private static final Pattern COMPONENT = Pattern.compile(
            "([A-Za-z][\\w]*(?:\\.[A-Za-z][\\w]*)+)/([.$\\w]+)");
    private static final Pattern TASK_DISPLAY = Pattern.compile("^(\\s*)Display\\s+#(\\d+)\\s+\\(activities[^)]*\\):?\\s*$");
    private static final Pattern ACTIVITY = Pattern.compile("^(\\s*)\\*\\s+Hist\\s+#\\d+:\\s+ActivityRecord\\{"
            + "[^\\s{}]+\\s+u(\\d+)\\s+(" + COMPONENT.pattern() + ")\\s+t(\\d+)\\b[^}]*\\}");
    private static final Pattern ACTIVITY_VISIBLE = Pattern.compile(
            "^(?:mVisibleRequested=(?:true|false)\\s+)?(mVisible|visible)=(true|false)\\b");
    private static final Pattern WINDOW_DISPLAY = Pattern.compile("^(\\s*)Display:\\s+mDisplayId=(\\d+)\\b");
    private static final Pattern WINDOW_FOCUS = Pattern.compile("^\\s*(mCurrentFocus|mFocusedApp)=(.*)$");
    private static final Pattern FOCUSED_COMPONENT = Pattern.compile("^(?:Window|ActivityRecord)\\{[^\\s{}]+\\s+u\\d+\\s+"
            + COMPONENT.pattern());
    private static final Pattern OP = Pattern.compile(
            "^\\s*([A-Z_]+):\\s*(allow|ignore|deny|default|foreground|errored)\\b");
    private static final Set<String> OPS = new LinkedHashSet<>(Arrays.asList(
            "SYSTEM_ALERT_WINDOW", "WRITE_SETTINGS", "GET_USAGE_STATS", "READ_EXTERNAL_STORAGE",
            "WRITE_EXTERNAL_STORAGE", "MANAGE_EXTERNAL_STORAGE", "REQUEST_INSTALL_PACKAGES",
            "RUN_IN_BACKGROUND", "RUN_ANY_IN_BACKGROUND", "ACCESS_RESTRICTED_SETTINGS",
            "POST_NOTIFICATION"));
    private static final int MAX_ITEMS = 128;

    private VehicleConfigurationDiagnostics() { }

    static String[] packageNames() { return PACKAGES.clone(); }

    static Map<String, String> adbCommands() {
        Map<String, String> commands = new LinkedHashMap<>();
        commands.put("notification_listeners", "settings get secure enabled_notification_listeners");
        commands.put("accessibility_services", "settings get secure enabled_accessibility_services");
        commands.put("accessibility_enabled", "settings get secure accessibility_enabled");
        commands.put("accessibility", "dumpsys accessibility");
        commands.put("appops", "appops get com.bydhud.app");
        commands.put("tasks", "dumpsys activity activities");
        commands.put("focus", "dumpsys window displays");
        commands.put("audio", "dumpsys audio");
        commands.put("thermal", "dumpsys thermalservice");
        commands.put("memory", "dumpsys meminfo com.bydhud.app");
        commands.put("cpu", "dumpsys cpuinfo");
        commands.put("instrument_display_setting", "settings get global instrument_navigation_display_config");
        return commands;
    }

    static JSONObject collect(Context context) throws Exception {
        return collect(context, VehicleConfigurationReadback.SESSION_TIMEOUT_MS);
    }

    static JSONObject collect(Context context, long remainingBudgetMs) throws Exception {
        Context app = context.getApplicationContext();
        JSONObject document = new JSONObject().put("source", "application-passive")
                .put("callerUid", Process.myUid()).put("callerUserId", Process.myUid() / 100000)
                .put("capturedAtMs", System.currentTimeMillis());
        JSONArray records = new JSONArray();
        try (NativeReads reads = new NativeReads(records, remainingBudgetMs, Process.myUid())) {
            for (String setting : new String[]{"enabled_notification_listeners",
                    "enabled_accessibility_services", "accessibility_enabled"}) {
                reads.add("permissions." + setting, "Settings.Secure.getString", () ->
                        nullable(Settings.Secure.getString(app.getContentResolver(), setting)));
            }
            reads.add("permissions.components", "PackageManager.getServiceInfo", () -> components(app));
            reads.add("permissions.grants", "Context.checkSelfPermission", () -> grants(app));
            reads.add("permissions.appOps", "AppOpsManager.checkOpNoThrow", () -> appOps(app));
            reads.add("permissions.callbacks", "existing process callbacks", () -> new JSONObject()
                    .put("notificationListenerConnected", NavNotificationListenerService.isConnectedForRuntimeCheck())
                    .put("notificationListenerDetail", NavNotificationListenerService.runtimeDetailForRuntimeCheck())
                    .put("accessibilityConnected", NavAccessibilityService.isConnectedForRuntimeCheck())
                    .put("accessibilityCrashed", NavAccessibilityService.isCrashedForRuntimeCheck())
                    .put("accessibilityDetail", NavAccessibilityService.runtimeDetailForRuntimeCheck()));
            reads.add("permissions.repair", "existing repair state", NavRuntimePermissionRepair::configurationSnapshot);
            reads.add("adb.passiveState", "existing authorization cache and key-file presence", () ->
                    LocalAdbBridge.configurationExportAdbState(app));
            reads.add("displays", "DisplayManager.getDisplays", () -> displays(app));
            reads.add("movement", "existing app movement state, not device readback",
                    NavAppDisplayController::configurationSnapshot);
            reads.add("navigation.output", "existing HUD output state", HudOutputCoordinator::configurationSnapshot);
            reads.add("navigation.channels", "existing Waze and GMaps state", NavHudLiveSender::configurationSnapshot);
            reads.add("navigation.instrumentHelper", "existing helper state; no ping/readiness call",
                    InstrumentProxyManager::configurationSnapshot);
            reads.add("options", "effective preferences with product defaults, not vehicle readback", () -> options(app));
            reads.add("firmware.locale", "Resources.Configuration and java.util", () -> new JSONObject()
                    .put("locales", app.getResources().getConfiguration().getLocales().toLanguageTags())
                    .put("timeZone", TimeZone.getDefault().getID()).put("sdk", Build.VERSION.SDK_INT));
            reads.add("instrument.navigationDisplayConfig", "android.provider.CarSettings.Global.getString", () ->
                    carSetting(app, "instrument_navigation_display_config"));
            reads.add("audio.navigationToneLowerMedia", "android.provider.CarSettings.Global.getString", () ->
                    carSetting(app, "navigation_tone_lower_media_tone"));
            reads.add("audio.modeAndVolume", "AudioManager read APIs", () -> audio(app));
            reads.add("audio.outputDeviceTypes", "AudioManager.getDevices; no addresses/names", () -> audioDevices(app));
            reads.add("system.health", "ActivityManager.MemoryInfo, filesystem capacity and clocks", () -> health(app));
            reads.add("system.thermal", "PowerManager.getCurrentThermalStatus", () -> {
                if (Build.VERSION.SDK_INT < 29) throw new UnsupportedOperationException("requires Android 10");
                return required(app.getSystemService(PowerManager.class)).getCurrentThermalStatus();
            });
        }
        return document.put("records", records);
    }

    static JSONObject runtime(Context context) throws Exception {
        return new JSONObject().put("source", "existing process and cached runtime state")
                .put("capturedAtMs", System.currentTimeMillis())
                .put("runtimeSummary", HudRuntimeState.summary(context, SystemClock.elapsedRealtime()))
                .put("serviceRunningPreference", HudPrefs.isRuntimeServiceRunning(context))
                .put("userShutdownPreference", HudPrefs.isUserShutdownActive(context))
                .put("deliveryStatus", HudDeliveryStatus.uiStatus())
                .put("adb", LocalAdbBridge.configurationExportAdbState(context))
                .put("detailedSnapshots", "app/diagnostics.json");
    }

    static JSONObject someIp(Context context) throws Exception {
        ComponentName service = new ComponentName("com.ts.car.someip.service", "com.ts.car.someip.service.manager.SomeIpServerService");
        return new JSONObject().put("source", "PackageManager lookup only; no bind or subscription")
                .put("serviceId", SomeIpHudClient.HUD_NAVI_INFO_SERVICE_ID)
                .put("roadTopic", SomeIpHudClient.HUD_ROAD_INFO_TOPIC)
                .put("identifierSource", "configured constants, not device readback")
                .put("service", resolveComponent(context, service, PackageManager.MATCH_DISABLED_COMPONENTS))
                .put("bindingSnapshot", "app/diagnostics.json: navigation.output")
                .put("networkEvidence", "adb/network/")
                .put("subscriptionReadback", "unsupported: no audited read-only subscription API");
    }

    private static JSONObject options(Context context) throws Exception {
        NavCapturePrefs.IngressPreferences ingress = NavCapturePrefs.ingressPreferences(context);
        JSONObject navigators = new JSONObject();
        for (String name : new String[]{"com.waze", "app.revanced.android.apps.maps", "com.google.android.apps.maps"}) {
            navigators.put(name, new JSONObject().put("hudSelected", name.equals(ingress.hudPackage))
                    .put("logSelected", ingress.logOnlyPackages.contains(name))
                    .put("outputOptions", "shared"));
        }
        //These two public getters migrate preferences. Derive their current defaults without writing them.
        SharedPreferences prefs = context.getSharedPreferences("byd_hud_prefs", Context.MODE_PRIVATE);
        int mode = prefs.contains("dashboard_screen_mode") ? HudPrefs.normalizeDashboardScreenMode(
                prefs.getInt("dashboard_screen_mode", HudPrefs.DASHBOARD_MODE_FULL))
                : prefs.getBoolean("fullscreen_dashboard", true) ? HudPrefs.DASHBOARD_MODE_FULL : HudPrefs.DASHBOARD_MODE_NONE;
        boolean anyMetric = HudPrefs.isEtaOutputEnabled(context) || HudPrefs.isRemainingTimeOutputEnabled(context)
                || HudPrefs.isRemainingDistanceOutputEnabled(context);
        int metrics = prefs.contains("route_metrics_mode") ? Math.max(0, Math.min(2, prefs.getInt("route_metrics_mode", 0)))
                : !anyMetric ? 0 : prefs.getBoolean("whole_route_metrics", false) ? 2 : 1;
        return new JSONObject().put("navigators", navigators)
                .put("selectedHudPackage", ingress.hudPackage)
                .put("autoStart", HudPrefs.isBootEnabled(context))
                .put("png", HudPrefs.isPngOutputEnabled(context)).put("native", HudPrefs.isNativeOutputEnabled(context))
                .put("lanes", HudPrefs.isLaneOutputEnabled(context)).put("distance", HudPrefs.isDistanceOutputEnabled(context))
                .put("street", HudPrefs.isStreetOutputEnabled(context)).put("textDirection", HudPrefs.isTextDirectionOutputEnabled(context))
                .put("transliteration", HudPrefs.transliterationMode(context))
                .put("nearDistanceClamp", HudPrefs.isSmallDistanceClampEnabled(context))
                .put("wazeAlerts", HudPrefs.isWazeAlertsEnabled(context)).put("wazeSurface", HudPrefs.isWazeCustomSurfaceEnabled(context))
                .put("routeMetricsMode", metrics).put("eta", HudPrefs.isEtaOutputEnabled(context))
                .put("remainingTime", HudPrefs.isRemainingTimeOutputEnabled(context))
                .put("remainingDistance", HudPrefs.isRemainingDistanceOutputEnabled(context))
                .put("speedLimitMode", HudPrefs.speedLimitMode(context)).put("speedLimitFallback", HudPrefs.speedLimitFreeFallback(context))
                .put("speedLimitOverlaySeconds", HudPrefs.speedLimitOverlaySeconds(context))
                .put("speedLimitPlacement", HudPrefs.speedLimitCompositePlacement(context))
                .put("speedLimitManeuverSize", HudPrefs.speedLimitManeuverOverlaySize(context))
                .put("speedLimitLaneSize", HudPrefs.speedLimitLaneOverlaySize(context))
                .put("tbtWithoutHud", HudPrefs.isTbtWithoutHudOutputEnabled(context))
                .put("switchToTbtOnStart", HudPrefs.isSwitchToTbtOnHudStartEnabled(context))
                .put("dashboardMode", mode)
                .put("miniProfile", profile(HudPrefs.dashboardProjectionProfile(context, HudPrefs.DASHBOARD_MODE_PARTIAL)))
                .put("fullProfile", profile(HudPrefs.dashboardProjectionProfile(context, HudPrefs.DASHBOARD_MODE_FULL)));
    }

    private static JSONObject profile(DashboardProjectionPolicy.Profile profile) throws Exception {
        return new JSONObject().put("widthPercent", profile.widthPercent).put("heightPercent", profile.heightPercent)
                .put("offsetPercent", profile.offsetPercent).put("scalePercent", profile.scalePercent);
    }

    private static JSONArray components(Context context) throws Exception {
        JSONArray result = new JSONArray();
        for (String key : new String[]{"enabled_notification_listeners", "enabled_accessibility_services"}) {
            String raw = Settings.Secure.getString(context.getContentResolver(), key);
            String[] parts = raw == null ? new String[0] : raw.split(":", -1);
            for (int i = 0; i < Math.min(parts.length, MAX_ITEMS); i++) {
                String entry = bounded(parts[i], 1024);
                ComponentName component = ComponentName.unflattenFromString(entry);
                JSONObject row = component == null ? new JSONObject().put("status", "error")
                        .put("error", "malformed_component") : resolveComponent(context, component, 0);
                result.put(row.put("setting", key).put("entry", entry));
            }
            if (parts.length > MAX_ITEMS) result.put(new JSONObject().put("setting", key)
                    .put("omittedCount", parts.length - MAX_ITEMS));
        }
        for (Class<?> own : new Class<?>[]{NavAccessibilityService.class, NavNotificationListenerService.class}) {
            result.put(resolveComponent(context, new ComponentName(context, own), PackageManager.MATCH_DISABLED_COMPONENTS)
                    .put("lookup", "own_service_including_disabled"));
        }
        return result;
    }

    private static JSONObject resolveComponent(Context context, ComponentName component, int flags) throws Exception {
        JSONObject row = new JSONObject().put("component", component.flattenToString()).put("lookupFlags", flags);
        try {
            PackageManager pm = context.getPackageManager();
            ServiceInfo info = pm.getServiceInfo(component, flags);
            row.put("status", "ok").put("declaredEnabled", info.enabled)
                    .put("applicationEnabled", info.applicationInfo.enabled).put("exported", info.exported)
                    .put("requiredPermission", nullable(info.permission))
                    .put("componentOverride", pm.getComponentEnabledSetting(component))
                    .put("applicationOverride", pm.getApplicationEnabledSetting(component.getPackageName()));
        } catch (Exception | LinkageError error) { failure(row, error); }
        return row;
    }

    private static JSONObject grants(Context context) throws Exception {
        JSONObject grants = new JSONObject();
        for (String permission : new String[]{"READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE",
                "SYSTEM_ALERT_WINDOW", "WRITE_SECURE_SETTINGS", "WRITE_SETTINGS", "READ_LOGS", "DUMP",
                "PACKAGE_USAGE_STATS", "REQUEST_INSTALL_PACKAGES", "MANAGE_EXTERNAL_STORAGE"}) {
            grants.put(permission, context.checkSelfPermission("android.permission." + permission));
        }
        return grants.put("overlayAllowed", Settings.canDrawOverlays(context))
                .put("installPackagesAllowed", context.getPackageManager().canRequestPackageInstalls())
                .put("resultMeaning", "PackageManager.PERMISSION_GRANTED=0; individual grants are not complete readiness");
    }

    private static JSONObject appOps(Context context) throws Exception {
        AppOpsManager manager = required(context.getSystemService(AppOpsManager.class));
        JSONObject result = new JSONObject();
        for (String op : new String[]{AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, AppOpsManager.OPSTR_WRITE_SETTINGS,
                AppOpsManager.OPSTR_GET_USAGE_STATS, AppOpsManager.OPSTR_READ_EXTERNAL_STORAGE,
                AppOpsManager.OPSTR_WRITE_EXTERNAL_STORAGE}) {
            JSONObject record = new JSONObject();
            try { record.put("status", "ok").put("mode", manager.checkOpNoThrow(op, Process.myUid(), context.getPackageName())); }
            catch (Exception | LinkageError error) { failure(record, error); }
            result.put(op, record);
        }
        return result;
    }

    private static JSONArray displays(Context context) throws Exception {
        JSONArray result = new JSONArray();
        for (Display display : required(context.getSystemService(DisplayManager.class)).getDisplays()) {
            DisplayMetrics metrics = new DisplayMetrics();
            display.getRealMetrics(metrics);
            JSONObject row = new JSONObject().put("id", display.getDisplayId()).put("state", display.getState())
                    .put("flags", display.getFlags()).put("rotation", display.getRotation())
                    .put("widthPx", metrics.widthPixels).put("heightPx", metrics.heightPixels)
                    .put("densityDpi", metrics.densityDpi);
            try { row.put("type", Display.class.getMethod("getType").invoke(display)); }
            catch (Exception | LinkageError unavailable) { row.put("type", JSONObject.NULL).put("typeReason", "not_exposed_by_platform"); }
            result.put(row);
        }
        return result;
    }

    private static Object carSetting(Context context, String key) throws Exception {
        Class<?> global = Class.forName("android.provider.CarSettings$Global", false, context.getClassLoader());
        return nullable(global.getMethod("getString", ContentResolver.class, String.class)
                .invoke(null, context.getContentResolver(), key));
    }

    private static JSONObject audio(Context context) throws Exception {
        AudioManager audio = required(context.getSystemService(AudioManager.class));
        JSONObject streams = new JSONObject();
        for (int stream : new int[]{AudioManager.STREAM_MUSIC, AudioManager.STREAM_SYSTEM,
                AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_VOICE_CALL}) {
            streams.put(Integer.toString(stream), new JSONObject().put("volume", audio.getStreamVolume(stream))
                    .put("maximum", audio.getStreamMaxVolume(stream)).put("muted", audio.isStreamMute(stream)));
        }
        return new JSONObject().put("mode", audio.getMode()).put("musicActive", audio.isMusicActive()).put("streams", streams);
    }

    private static JSONArray audioDevices(Context context) {
        JSONArray result = new JSONArray();
        for (AudioDeviceInfo device : required(context.getSystemService(AudioManager.class)).getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            result.put(device.getType());
        }
        return result;
    }

    private static JSONObject health(Context context) throws Exception {
        ActivityManager manager = required(context.getSystemService(ActivityManager.class));
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(memory);
        ActivityManager.RunningAppProcessInfo process = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(process);
        return new JSONObject().put("uptimeMs", SystemClock.uptimeMillis()).put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
                .put("availableMemoryBytes", memory.availMem).put("totalMemoryBytes", memory.totalMem)
                .put("lowMemory", memory.lowMemory).put("thresholdBytes", memory.threshold)
                .put("appImportance", process.importance).put("appTrimLevel", process.lastTrimLevel)
                .put("privateUsableBytes", context.getFilesDir().getUsableSpace())
                .put("sharedUsableBytes", Environment.getExternalStorageDirectory().getUsableSpace());
    }

    static JSONObject summarizeAdb(String name, LocalAdbBridge.ShellResult result) throws Exception {
        JSONObject record = new JSONObject().put("parameter", name).put("command", adbCommands().get(name))
                .put("source", "isolated read-only adb shell").put("callerUid", 2000)
                .put("observedAtMs", result == null || result.observedAtMs < 0 ? JSONObject.NULL : result.observedAtMs)
                .put("durationMs", result == null || result.durationMs < 0 ? JSONObject.NULL : result.durationMs);
        if (result == null || !result.success()) return record.put("status", result == null ? "error" : result.status)
                .put("error", result == null ? "no_result" : result.error);
        if (result.truncated) return record.put("status", "error").put("error", "truncated_source_not_parsed");
        return record.put("status", "ok").put("value", parseAdb(name, result.output));
    }

    /** Parse only allowlisted structural fields; never export raw task, notification or audio payloads. */
    static JSONObject parseAdb(String name, String text) throws Exception {
        String source = text == null ? "" : text;
        if (name.equals("tasks")) return parseTasks(source);
        if (name.equals("focus")) return parseFocus(source);
        JSONObject result = new JSONObject();
        JSONArray entries = new JSONArray();
        if (name.equals("notification_listeners") || name.equals("accessibility_services")
                || name.equals("accessibility_enabled") || name.equals("instrument_display_setting")) {
            String value = source.trim();
            return result.put("raw", value.equals("null") ? JSONObject.NULL : bounded(value, 16384))
                    .put("namespace", name.equals("instrument_display_setting") ? "Android Settings.Global; not OEM CarSettings" : "Android Settings.Secure")
                    .put("truncated", value.length() > 16384);
        }
        boolean boundSection = false;
        Set<String> boundComponents = new LinkedHashSet<>();
        for (String line : source.split("\\r?\\n")) {
            if (entries.length() >= MAX_ITEMS) { result.put("recordsTruncated", true); break; }
            Matcher component = COMPONENT.matcher(line);
            switch (name) {
                case "accessibility":
                    if (Pattern.compile("(?i)(?:m?boundServices|bound services)\\s*[:=]").matcher(line).find()) {
                        boundSection = true;
                        while (component.find()) boundComponents.add(component.group());
                    }
                    break;
                case "appops":
                    Matcher op = OP.matcher(line);
                    if (op.find() && OPS.contains(op.group(1))) entries.put(new JSONObject().put("op", op.group(1)).put("mode", op.group(2)));
                    break;
                case "audio":
                    Matcher mode = Pattern.compile("\\b(?:mMode|mode)\\s*[:=]\\s*(MODE_[A-Z_]+|\\d+)\\b").matcher(line);
                    if (mode.find()) entries.put(new JSONObject().put("mode", mode.group(1)));
                    Matcher pack = Pattern.compile("\\bpack(?:age)?\\s*[:=]\\s*([\\w.]+)").matcher(line);
                    if (pack.find() && relevant(pack.group(1))) {
                        entries.put(new JSONObject().put("focusPackage", pack.group(1))
                                .put("gain", matchedValue(line, "\\bgain(?:Request)?\\s*[:=]\\s*(-?\\d+)")));
                    }
                    break;
                case "thermal":
                    Matcher thermal = Pattern.compile("(?i)(?:thermal status|mThermalStatus)\\s*[:=]\\s*(\\d+)").matcher(line);
                    if (thermal.find()) entries.put(new JSONObject().put("thermalStatus", Integer.parseInt(thermal.group(1))));
                    break;
                case "memory":
                    Matcher memory = Pattern.compile("\\b(TOTAL PSS|TOTAL RSS|TOTAL SWAP PSS):\\s*(\\d+)").matcher(line);
                    while (memory.find()) entries.put(new JSONObject().put("field", memory.group(1)).put("kilobytes", Long.parseLong(memory.group(2))));
                    break;
                case "cpu":
                    Matcher cpu = Pattern.compile("^\\s*([\\d.]+)%\\s+(\\d+)/([\\w.]+)(?::|\\s)").matcher(line);
                    if (cpu.find() && relevant(cpu.group(3))) entries.put(new JSONObject().put("package", cpu.group(3))
                            .put("pid", Integer.parseInt(cpu.group(2))).put("percent", Double.parseDouble(cpu.group(1))));
                    break;
                default: return result.put("status", "unsupported").put("reason", "unknown_diagnostic_name");
            }
        }
        if (name.equals("accessibility")) {
            return result.put("status", boundSection ? "ok" : "unsupported")
                    .put("boundSectionObserved", boundSection ? true : JSONObject.NULL)
                    .put("boundComponents", new JSONArray(boundComponents))
                    .put("interpretation", "Only explicit component names are parsed; absent names or labels do not prove unbound");
        }
        return result.put("status", entries.length() == 0 ? "unsupported" : "ok").put("records", entries)
                .put("interpretation", "Allowlisted structural fields only; missing/unparsed values remain unknown");
    }

    private static JSONObject parseTasks(String source) throws Exception {
        JSONObject result = new JSONObject();
        Map<String, JSONObject> records = new LinkedHashMap<>();
        Integer displayId = null;
        int displayIndent = -1;
        JSONObject activity = null;
        int activityIndent = -1;
        for (String line : source.split("\\r?\\n")) {
            if (line.trim().isEmpty()) continue;
            int indent = indentation(line);
            Matcher display = TASK_DISPLAY.matcher(line);
            if (display.matches()) {
                displayId = parsedInteger(display.group(2));
                displayIndent = indent;
                activity = null;
                continue;
            }
            if (indent <= displayIndent) { displayId = null; displayIndent = -1; }
            if (indent <= activityIndent) activity = null;
            // Only a Hist header owns activity state; resumed/paused/orientation references do not.
            Matcher header = ACTIVITY.matcher(line);
            if (header.find()) {
                activity = null;
                Integer user = parsedInteger(header.group(2));
                Integer task = parsedInteger(header.group(6));
                String component = header.group(3);
                String packageName = header.group(4);
                String className = header.group(5);
                if (user == null || task == null || component.length() > 1024 || !relevant(packageName)) continue;
                String identity = user + ":" + task + ":" + packageName + "/"
                        + (className.startsWith(".") ? packageName + className : className);
                activity = records.get(identity);
                if (activity == null) {
                    if (records.size() >= MAX_ITEMS) { result.put("recordsTruncated", true); break; }
                    activity = new JSONObject().put("component", component).put("userId", user).put("taskId", task)
                            .put("displayId", JSONObject.NULL).put("visible", JSONObject.NULL);
                    records.put(identity, activity);
                }
                activityIndent = indent;
                mergeTaskField(activity, "displayId", displayId);
                continue;
            }
            // ponytail: accept the captured two-space activity field indentation; extend only for
            // evidenced layouts. Nested window, Task and Intent visibility must remain excluded.
            if (activity != null && indent == activityIndent + 2) {
                Matcher visible = ACTIVITY_VISIBLE.matcher(line.trim());
                if (visible.find()) mergeTaskField(activity, "visible", Boolean.valueOf(visible.group(2)));
            }
        }
        return result.put("status", records.isEmpty() ? "unsupported" : "ok")
                .put("records", new JSONArray(records.values()))
                .put("interpretation", "Activity history blocks only; missing or conflicting display/visibility remains unknown");
    }

    private static void mergeTaskField(JSONObject record, String field, Object value) throws Exception {
        if (value == null || record.has(field + "Reason")) return;
        if (record.isNull(field)) record.put(field, value);
        else if (!record.get(field).equals(value)) record.put(field, JSONObject.NULL)
                .put(field + "Reason", "conflicting_activity_blocks");
    }

    private static JSONObject parseFocus(String source) throws Exception {
        JSONObject result = new JSONObject();
        JSONArray records = new JSONArray();
        Integer displayId = null;
        int displayIndent = -1;
        boolean displaySection = true;
        for (String line : source.split("\\r?\\n")) {
            if (line.trim().isEmpty()) continue;
            if (line.startsWith("WINDOW MANAGER ")) {
                displaySection = line.contains("(dumpsys window displays)");
                displayId = null;
                displayIndent = -1;
                continue;
            }
            if (!displaySection) continue;
            int indent = indentation(line);
            Matcher display = WINDOW_DISPLAY.matcher(line);
            if (display.find()) {
                displayId = parsedInteger(display.group(2));
                displayIndent = indent;
                continue;
            }
            if (indent < displayIndent) { displayId = null; displayIndent = -1; }
            Matcher focus = WINDOW_FOCUS.matcher(line);
            if (!focus.matches() || (displayIndent >= 0 && indent != displayIndent)) continue;
            if (records.length() >= MAX_ITEMS) { result.put("recordsTruncated", true); break; }
            String value = focus.group(2).trim();
            Matcher component = FOCUSED_COMPONENT.matcher(value);
            boolean parsed = component.find() && component.group(1).length() + component.group(2).length() < 1024;
            boolean known = parsed && relevant(component.group(1));
            boolean explicitNull = value.equals("null");
            records.put(new JSONObject().put("field", focus.group(1).equals("mCurrentFocus") ? "currentFocus" : "focusedApp")
                    .put("displayId", nullable(displayId))
                    .put("relevantComponent", known ? component.group(1) + "/" + component.group(2) : JSONObject.NULL)
                    .put("present", explicitNull ? false : parsed ? true : JSONObject.NULL)
                    .put("reason", explicitNull ? "explicit_null" : known ? "parsed" : parsed ? "other_component" : "unparsed"));
        }
        return result.put("status", records.length() == 0 ? "unsupported" : "ok").put("records", records)
                .put("interpretation", "Display-scoped focus fields only; explicit null is not missing/unparsed focus");
    }

    private static int indentation(String line) {
        int length = 0;
        while (length < line.length() && Character.isWhitespace(line.charAt(length))) length++;
        return length;
    }

    private static Integer parsedInteger(String value) {
        try { return Integer.valueOf(value); }
        catch (NumberFormatException malformed) { return null; }
    }

    private static Object matchedValue(String line, String regex) {
        Matcher match = Pattern.compile(regex).matcher(line);
        return match.find() ? match.group(1) : JSONObject.NULL;
    }

    private static boolean relevant(String packageName) { return Arrays.asList(PACKAGES).contains(packageName); }
    private static Object nullable(Object value) { return value == null ? JSONObject.NULL : value; }
    private static String bounded(String value, int limit) { return value.substring(0, Math.min(value.length(), limit)); }
    private static <T> T required(T value) {
        if (value == null) throw new UnsupportedOperationException("service unavailable");
        return value;
    }

    private static void failure(JSONObject record, Throwable throwable) throws Exception {
        Throwable error = throwable;
        while ((error instanceof ExecutionException || error instanceof InvocationTargetException) && error.getCause() != null) error = error.getCause();
        record.put("status", error instanceof SecurityException ? "denied" : error instanceof TimeoutException ? "timeout"
                : error instanceof ClassNotFoundException || error instanceof NoSuchMethodException || error instanceof UnsupportedOperationException ? "unsupported" : "error")
                .put("error", error.getClass().getName() + ": " + bounded(String.valueOf(error.getMessage()), 2048));
    }

    static final class NativeReads implements AutoCloseable {
        private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "BydHudConfigNativeRead");
            thread.setDaemon(true);
            return thread;
        });
        private final JSONArray records;
        private final long deadline;
        private final int callerUid;
        private boolean timedOut;

        NativeReads(JSONArray records, long budgetMs, int callerUid) {
            this.records = records;
            this.callerUid = callerUid;
            this.deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, Math.min(60000, budgetMs)));
        }

        void add(String name, String api, Callable<?> read) throws Exception {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("configuration export cancelled");
            long start = System.nanoTime();
            JSONObject record = new JSONObject().put("parameter", name).put("api", api)
                    .put("source", "application-passive").put("callerUid", callerUid)
                    .put("observedAtMs", System.currentTimeMillis());
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - start);
            Future<?> pending = null;
            try {
                if (timedOut || remaining <= 0) throw new TimeoutException(timedOut ? "earlier_native_read_timed_out" : "acquisition_budget_exhausted");
                pending = worker.submit(read);
                Object value = pending.get(Math.min(1500, remaining), TimeUnit.MILLISECONDS);
                record.put("status", "ok").put("value", nullable(value))
                        .put("valueType", value == null || value == JSONObject.NULL ? "null" : value.getClass().getSimpleName());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw error;
            } catch (TimeoutException error) {
                timedOut = true;
                failure(record, error);
            } catch (ExecutionException error) { failure(record, error); }
            finally {
                if (pending != null && !pending.isDone()) pending.cancel(true);
                record.put("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                records.put(record);
            }
        }

        @Override public void close() { worker.shutdownNow(); }
    }
}
