package com.bydhud.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

/** Durable intent, separate from the diagnostic 'service running' flag. */
final class ShellRuntimeSession {
    private static final String PREFS = "byd_hud_shell_session";
    private static volatile Context application;
    private static volatile boolean serviceRestorePending;
    private static volatile boolean restoreCaptureServices;

    private ShellRuntimeSession() { }

    static boolean arm(Context context) {
        if (HudPrefs.isUserShutdownActive(context)) return false;
        return prefs(context).edit().putBoolean("active", true)
                .putInt("boot", boot(context)).commit();
    }

    static void disarm(Context context) {
        serviceRestorePending = false;
        ShellWorkClient.cancelAll(context);
        // Preserve the writer identity until explicit Stop has closed its descriptor.
        SharedPreferences state = prefs(context);
        SharedPreferences.Editor editor = state.edit().putBoolean("active", false).putBoolean("capture_stop", true)
                .putBoolean("capture_services", false).remove("output").remove("ui_position").remove("configuration_work");
        for (String key : state.getAll().keySet())
            if (key.startsWith("share_work_")) editor.remove(key);
        editor.commit();
    }

    static boolean mayRestore(Context context) {
        SharedPreferences state = prefs(context);
        return mayRestore(state.getBoolean("active", false),
                HudPrefs.isUserShutdownActive(context), state.getInt("boot", -1), boot(context));
    }

    static boolean mayRestore(boolean active, boolean shutdown, int recordedBoot, int currentBoot) {
        return active && !shutdown && recordedBoot >= 0 && recordedBoot == currentBoot;
    }

    static void restore(Context context) {
        application = context.getApplicationContext();
        ShellWorkClient.restorePins(context);
        if (mayRestore(context)) {
            serviceRestorePending = true;
            restoreCaptureServices = hadCaptureServices(context);
            RuntimeUiSession.PROCESS.restore(prefs(context).getString("ui_position", ""));
            MainActivity.restoreRuntimeStatusCache(prefs(context).getString("status", ""));
            MainActivity.restoreAppScanCache(context);
        }
        BootCleanupGate.runWhenReady(context, () -> {
            if (!mayRestore(context)) return;
            UserRuntimeSession.PROCESS.activate();
            InstrumentProxyManager.get(context).awaitExistingRuntime();
            HudRuntimeService.startPersistent(context, "shell-session-recovery");
            NavigatorPatchWorkerClient.restore(context);
            VehicleConfigurationExport.restore(context);
            StorageLogShareWorkflow.restore(context);
        });
    }

    static void cacheStatus(NavRuntimePermissionStatus status, boolean adb) {
        if (application == null || status == null) return;
        try {
            prefs(application).edit().putString("status", status.checkpoint().put("adb", adb).toString()).apply();
        } catch (Exception error) {
            AppEventLogger.event(application, "runtime status checkpoint failed " + error.getClass().getSimpleName());
        }
    }

    static void cacheUiPosition(String state) {
        if (application != null) prefs(application).edit().putString("ui_position", state).apply();
    }

    static String pendingCaptureDay() {
        return application != null && mayRestore(application)
                && !prefs(application).getString("capture_id", "").isEmpty()
                ? prefs(application).getString("capture_day", "") : "";
    }

    static boolean hadCaptureServices(Context context) {
        int currentBoot = boot(context);
        return currentBoot >= 0 && prefs(context).getBoolean("capture_services", false)
                && prefs(context).getInt("capture_services_boot", -1) == currentBoot;
    }

    static void captureServiceConnected(Context context) {
        if (!HudPrefs.isUserShutdownActive(context)) prefs(context).edit()
                .putBoolean("capture_services", true).putInt("capture_services_boot", boot(context)).commit();
    }

    static synchronized boolean takeServiceRestore() {
        boolean pending = serviceRestorePending;
        serviceRestorePending = false;
        return pending;
    }

    static boolean restoreCaptureServices() { return restoreCaptureServices; }

    static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static int boot(Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        } catch (RuntimeException ignored) { return -1; }
    }
}
