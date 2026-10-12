package com.bydhud.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Durable intent, separate from the diagnostic 'service running' flag. */
final class ShellRuntimeSession {
    private static final String PREFS = "byd_hud_shell_session";
    private static volatile Context application;
    private static volatile boolean serviceRestorePending;
    private static volatile boolean restoreCaptureServices;
    private static volatile Thread persistenceThread;
    private static final ScheduledThreadPoolExecutor PERSISTENCE = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "BydHudSessionPersistence");
        thread.setDaemon(true);
        persistenceThread = thread;
        return thread;
    });
    private static final Object CHECKPOINT_LOCK = new Object();
    private static final Map<String, String> pendingCheckpoints = new LinkedHashMap<>();
    private static final ArrayList<Runnable> checkpointCompletions = new ArrayList<>();
    private static ScheduledFuture<?> checkpointTask;
    private static Context checkpointContext;
    private static long generation;

    private ShellRuntimeSession() { }

    static boolean arm(Context context) {
        if (HudPrefs.isUserShutdownActive(context)) return false;
        if (Thread.currentThread() == persistenceThread) return armOnIo(context);
        try { return PERSISTENCE.submit(() -> armOnIo(context)).get(); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); return false; }
        catch (java.util.concurrent.ExecutionException error) {
            AppEventLogger.event(context, "runtime session arm failed " + error.getCause());
            return false;
        }
    }

    private static boolean armOnIo(Context context) {
        if (HudPrefs.isUserShutdownActive(context)) return false;
        SharedPreferences state = prefs(context);
        int currentBoot = boot(context);
        if (state.getBoolean("active", false) && state.getInt("boot", -1) == currentBoot) return true;
        return state.edit().putBoolean("active", true).putInt("boot", currentBoot).commit();
    }

    static void armAsync(Context context) {
        Context app = context.getApplicationContext();
        PERSISTENCE.execute(() -> {
            if (!armOnIo(app) && !HudPrefs.isUserShutdownActive(app))
                AppEventLogger.event(app, "runtime session intent persistence failed");
        });
    }

    static void disarm(Context context) {
        serviceRestorePending = false;
        synchronized (CHECKPOINT_LOCK) {
            generation++;
            pendingCheckpoints.clear();
        }
        Context app = context.getApplicationContext();
        PERSISTENCE.execute(() -> disarmOnIo(app));
    }

    private static void disarmOnIo(Context context) {
        ShellWorkClient.cancelAll(context);
        // Preserve the writer identity until explicit Stop has closed its descriptor.
        SharedPreferences state = prefs(context);
        SharedPreferences.Editor editor = state.edit().putBoolean("active", false).putBoolean("capture_stop", true)
                .putBoolean("capture_services", false).remove("output").remove("ui_position").remove("configuration_work");
        for (String key : state.getAll().keySet())
            if (key.startsWith("share_work_")) editor.remove(key);
        if (!editor.commit()) AppEventLogger.event(context, "runtime session disarm persistence failed");
    }

    static void persistShutdown(Context context, boolean active) {
        Context app = context.getApplicationContext();
        // An empty commit waits for the already-published preference value without replaying an old value.
        PERSISTENCE.execute(() -> {
            if (!HudPrefs.flush(app)) AppEventLogger.event(app, "runtime shutdown persistence failed");
        });
        if (active) disarm(app);
    }

    static void afterPersistence(Runnable completion) {
        PERSISTENCE.execute(() -> {
            flushCheckpoints();
            if (completion != null) completion.run();
        });
    }

    static void checkpointOutput(Context context, String state, Runnable completion) {
        checkpoint(context, "output", state, completion);
    }

    private static void checkpoint(Context context, String key, String state, Runnable completion) {
        synchronized (CHECKPOINT_LOCK) {
            checkpointContext = context.getApplicationContext();
            if (!HudPrefs.isUserShutdownActive(context)) pendingCheckpoints.put(key, state);
            if (completion != null) checkpointCompletions.add(completion);
            if (checkpointTask == null || completion != null) {
                if (checkpointTask != null) checkpointTask.cancel(false);
                checkpointTask = PERSISTENCE.schedule(ShellRuntimeSession::flushCheckpoints,
                        completion == null ? 100L : 0L, TimeUnit.MILLISECONDS);
            }
        }
    }

    private static void flushCheckpoints() {
        Map<String, String> values;
        ArrayList<Runnable> completions;
        Context app;
        long revision;
        synchronized (CHECKPOINT_LOCK) {
            if (checkpointTask != null) checkpointTask.cancel(false);
            checkpointTask = null;
            values = new LinkedHashMap<>(pendingCheckpoints);
            completions = new ArrayList<>(checkpointCompletions);
            pendingCheckpoints.clear();
            checkpointCompletions.clear();
            app = checkpointContext;
            revision = generation;
        }
        try {
            if (app != null && !values.isEmpty() && !HudPrefs.isUserShutdownActive(app)) {
                synchronized (CHECKPOINT_LOCK) { if (revision != generation) return; }
                SharedPreferences.Editor editor = prefs(app).edit();
                for (Map.Entry<String, String> entry : values.entrySet())
                    editor.putString(entry.getKey(), entry.getValue());
                if (!editor.commit()) AppEventLogger.event(app, "runtime checkpoint persistence failed keys=" + values.keySet());
            }
        } catch (RuntimeException error) {
            AppEventLogger.event(app, "runtime checkpoint persistence failed " + error);
        } finally {
            for (Runnable completion : completions) {
                try { completion.run(); }
                catch (RuntimeException error) { AppEventLogger.event(app, "runtime checkpoint completion failed " + error); }
            }
        }
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
        afterPersistence(() -> {
            NavigatorPatchWorkerClient.reconcileBoot(context);
            BootCleanupGate.runWhenReady(context, () -> {
                if (!mayRestore(context)) return;
                UserRuntimeSession.PROCESS.activate();
                InstrumentProxyManager.get(context).awaitExistingRuntime();
                HudRuntimeService.startPersistent(context, "shell-session-recovery");
                NavigatorPatchWorkerClient.restore(context);
                VehicleConfigurationExport.restore(context);
                StorageLogShareWorkflow.restore(context);
            });
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
        if (application != null) checkpoint(application, "ui_position", state, null);
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
        Context app = context.getApplicationContext();
        final long current;
        synchronized (CHECKPOINT_LOCK) { current = generation; }
        PERSISTENCE.execute(() -> {
            synchronized (CHECKPOINT_LOCK) { if (current != generation) return; }
            if (!HudPrefs.isUserShutdownActive(app) && !prefs(app).edit()
                    .putBoolean("capture_services", true).putInt("capture_services_boot", boot(app)).commit())
                AppEventLogger.event(app, "runtime capture intent persistence failed");
        });
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
