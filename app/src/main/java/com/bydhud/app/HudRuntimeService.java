package com.bydhud.app;

//keeps the persistent foreground runtime alive so capture and HUD output continue outside the UI.

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

//anchors the HudRuntimeService android entry point so lifecycle recovery stays separate from business logic.
public final class HudRuntimeService extends Service {
    private static final String TAG = "BydHudRuntimeService";
    private static final String CHANNEL_ID = "byd_hud_runtime";
    private static final int NOTIFICATION_ID = 4302;

    private static final String ACTION_START_PERSISTENT =
            "com.bydhud.app.action.START_PERSISTENT_RUNTIME";
    private static final String EXTRA_REASON = "reason";
    private static final long HEARTBEAT_INTERVAL_MS = 5L * 60L * 1000L;
    private static final long START_REQUEST_TIMEOUT_MS = 15_000L;

    private static final AtomicBoolean START_IN_FLIGHT = new AtomicBoolean(false);
    private static Handler startGateHandler;
    private static final Runnable START_REQUEST_TIMEOUT = () -> {
        if (START_IN_FLIGHT.compareAndSet(true, false)) {
            Log.w(TAG, "runtime start request timed out before service confirmation");
        }
    };

    private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        //keeps this HUD step isolated so cluster payload behavior stays predictable.
        public void run() {
            boolean activeWork = HudRuntimeSupervisor.hasActiveRuntimeWork(HudRuntimeService.this);
            HudRuntimeState.markHeartbeat(HudRuntimeService.this,
                    activeWork ? "periodic" : "idle-periodic");
            requestRuntimeUiRefresh(false, "runtime-heartbeat");
            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
        }
    };

    private boolean runtimeStartInitialized;
    private boolean runtimeActiveWork;
    private boolean runtimeDestroyed;

    //starts or schedules work here so lifecycle recovery follows one controlled path.
    static void startPersistent(Context context, String reason) {
        Context appContext = context.getApplicationContext();
        BootCleanupGate.runWhenReady(appContext,
                () -> startPersistentAfterBootGate(appContext, reason));
    }

    private static void startPersistentAfterBootGate(Context context, String reason) {
        Context appContext = context.getApplicationContext();
        String safeReason = reason == null ? "" : reason.trim();
        boolean shutdownActive = HudPrefs.isUserShutdownActive(appContext);
        if (shutdownActive) {
            HudRuntimeWatchdog.cancel(appContext);
            HudRuntimeState.clearServicePresent(appContext,
                    "start-rejected:shutdown-active:" + safeReason);
            AppEventLogger.event(appContext,
                    "runtime startPersistent skipped shutdown_active reason=" + safeReason);
            return;
        }
        boolean bootEnabled = HudPrefs.isBootEnabled(appContext);
        if (!bootEnabled) {
            HudRuntimeWatchdog.cancel(appContext);
            HudRuntimeState.clearServicePresent(appContext,
                    "start-rejected:boot-disabled:" + safeReason);
            AppEventLogger.event(appContext,
                    "runtime startPersistent skipped boot_disabled reason=" + safeReason);
            return;
        }
        boolean hardResetPending = HudRuntimeUpgradeGuard.hasPendingHardReset(appContext);
        StartDecision decision = startDecision(
                false,
                true,
                HudRuntimeState.isServicePresent(),
                START_IN_FLIGHT.get(),
                hardResetPending);
        if (decision == StartDecision.ALREADY_ALIVE) {
            AppEventLogger.event(appContext,
                    "runtime startPersistent skipped already_present reason=" + safeReason);
            return;
        }
        if (decision == StartDecision.IN_FLIGHT) {
            AppEventLogger.event(appContext,
                    "runtime startPersistent skipped start_in_flight reason=" + safeReason);
            return;
        }
        if (!tryAcquireStartRequest()) {
            AppEventLogger.event(appContext,
                    "runtime startPersistent skipped start_in_flight reason=" + safeReason);
            return;
        }
        //The service may have published while this request crossed the first
        //presence check. Do not issue a duplicate start after acquiring the gate.
        if (shouldSkipStartAfterGate(hardResetPending)) {
            clearStartRequestGate();
            AppEventLogger.event(appContext,
                    "runtime startPersistent skipped already_present_after_gate reason="
                            + safeReason);
            return;
        }
        startGateHandler().removeCallbacks(START_REQUEST_TIMEOUT);
        startGateHandler().postDelayed(START_REQUEST_TIMEOUT, START_REQUEST_TIMEOUT_MS);
        Intent intent = new Intent(appContext, HudRuntimeService.class);
        intent.setAction(ACTION_START_PERSISTENT);
        intent.putExtra(EXTRA_REASON, safeReason);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent);
            } else {
                appContext.startService(intent);
            }
            AppEventLogger.event(appContext,
                    "runtime startPersistent requested reason=" + safeReason);
        } catch (RuntimeException error) {
            startGateHandler().removeCallbacks(START_REQUEST_TIMEOUT);
            START_IN_FLIGHT.set(false);
            AppEventLogger.event(appContext,
                    "runtime startPersistent failed reason=" + safeReason
                            + " error=" + error.getClass().getSimpleName());
            throw error;
        }
    }

    enum StartDecision {
        SHUTDOWN,
        BOOT_DISABLED,
        ALREADY_ALIVE,
        IN_FLIGHT,
        REQUEST
    }

    //Keeps the executable admission policy shared by production and focused JVM tests.
    static StartDecision startDecision(boolean shutdown, boolean bootEnabled,
            boolean servicePresent, boolean inFlight, boolean hardResetPending) {
        if (shutdown) return StartDecision.SHUTDOWN;
        if (!bootEnabled) return StartDecision.BOOT_DISABLED;
        if (servicePresent && !hardResetPending) return StartDecision.ALREADY_ALIVE;
        return inFlight ? StartDecision.IN_FLIGHT : StartDecision.REQUEST;
    }

    static StartDecision startDecisionForTest(boolean shutdown, boolean bootEnabled,
            boolean servicePresent, boolean inFlight, boolean hardResetPending) {
        return startDecision(shutdown, bootEnabled, servicePresent, inFlight, hardResetPending);
    }

    //Acquires the one startup gate used by all production start callers.
    static boolean tryAcquireStartRequest() {
        return START_IN_FLIGHT.compareAndSet(false, true);
    }

    //Rechecks the production marker after the gate closes the check/start race.
    static boolean shouldSkipStartAfterGate(boolean hardResetPending) {
        return !hardResetPending && HudRuntimeState.isServicePresent();
    }

    static void clearStartRequestForTest() {
        clearStartRequestGate();
    }

    private static void clearStartRequestGate() {
        if (startGateHandler != null) {
            startGateHandler.removeCallbacks(START_REQUEST_TIMEOUT);
        }
        START_IN_FLIGHT.set(false);
    }

    private static synchronized Handler startGateHandler() {
        if (startGateHandler == null) {
            startGateHandler = new Handler(Looper.getMainLooper());
        }
        return startGateHandler;
    }

    //stops or releases work here so stale capture and HUD output cannot keep running silently.
    static void stopPersistent(Context context, String reason) {
        Context appContext = context.getApplicationContext();
        clearStartRequestGate();
        HudRuntimeState.clearServicePresent(appContext, "stop:" + reason);
        HudRuntimeWatchdog.cancel(appContext);
        releaseInstrumentRuntime(appContext, "runtime-stop:" + reason);
        HudRuntimeState.markStopped(appContext, "stop:" + reason);
        appContext.stopService(new Intent(appContext, HudRuntimeService.class));
        HudPrefs.setRuntimeServiceRunning(appContext, false);
    }

    @Override
    //initializes android lifecycle state here so services, UI, and logging start from a known baseline.
    public void onCreate() {
        super.onCreate();
        runtimeStartInitialized = false;
        runtimeActiveWork = false;
        runtimeDestroyed = false;
        HudRuntimeUpgradeGuard.recordVersionStart(this, "service-create");
        HudGraphicPayload.setContext(this);
        startForeground(NOTIFICATION_ID, buildNotification("Runtime active"));
        HudPrefs.setRuntimeServiceRunning(this, true);
        HudRuntimeState.markStarted(this, "onCreate");
        HudRuntimeState.publishServicePresent(this, "onCreate");
        //Publish service presence before releasing the request gate. A second
        //start must observe the live service, not a persisted heartbeat.
        clearStartRequestGate();
        scheduleHeartbeat();
        log("runtime foreground active version=" + BuildConfig.VERSION_NAME
                + "/" + BuildConfig.VERSION_CODE
                + " logDir=" + AppEventLogger.logDir(this).getAbsolutePath());
    }

    @Override
    //handles service start intents here so boot, watchdog, and UI paths share one runtime entry point.
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        String reason = intent == null ? "sticky-restart" : intent.getStringExtra(EXTRA_REASON);
        log("runtime onStartCommand action=" + action
                + " reason=" + reason
                + " boot=" + HudPrefs.isBootEnabled(this)
                + " shutdown=" + HudPrefs.isUserShutdownActive(this));
        if (HudPrefs.isUserShutdownActive(this)) {
            HudRuntimeWatchdog.cancel(this);
            HudRuntimeState.markStopped(this, "shutdown-active:" + reason);
            stopForegroundCompat();
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!HudPrefs.isBootEnabled(this)) {
            HudRuntimeWatchdog.cancel(this);
            HudRuntimeState.markStopped(this, "boot-disabled:" + reason);
            stopForegroundCompat();
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (HudRuntimeUpgradeGuard.hasPendingHardReset(this)) {
            HudRuntimeSupervisor.hardResetAfterPackageReplace(this, "service-start:" + reason);
            return START_NOT_STICKY;
        }
        BootCleanupGate.runWhenReady(this, () -> heartbeatHandler.post(
                () -> completeStartAfterBootGate(reason)));
        return START_STICKY;
    }

    private void completeStartAfterBootGate(String reason) {
        if (runtimeDestroyed
                || HudPrefs.isUserShutdownActive(this)
                || !HudPrefs.isBootEnabled(this)) return;
        AppUpdateManager.onSessionEntry(this);
        HudRuntimeState.publishServicePresent(this, "onStartCommand");
        clearStartRequestGate();
        HudPrefs.setRuntimeServiceRunning(this, true);
        HudRuntimeState.markHeartbeat(this, "onStartCommand:" + reason);
        DashboardWidgetController.onRuntimeStart(this);
        boolean activeWork = HudRuntimeSupervisor.hasActiveRuntimeWork(this);
        if (!runtimeStartInitialized || activeWork != runtimeActiveWork) {
            if (!runtimeStartInitialized) requestInitialUiRefresh("runtime-create");
            InstrumentProxyManager.get(this).ensureStarted("runtime-service:" + reason);
            updateNotification(activeWork ? "Runtime active" : "Runtime idle");
            String hudPackage = NavCapturePrefs.getHudPackage(this);
            if (activeWork && !hudPackage.isEmpty()
                    && NavCapturePrefs.isHudEnabled(this, hudPackage)) {
                NavHudLiveSender.get(this).start(hudPackage, "runtime-service:" + reason);
            }
            if (activeWork && HudPrefs.isTbtWithoutHudOutputEnabled(this)) {
                NavHudLiveSender.get(this).refreshTbtObservers();
            }
            runtimeStartInitialized = true;
            runtimeActiveWork = activeWork;
        } else {
            log("runtime duplicate start ignored reason=" + reason);
        }
        if (activeWork) {
            HudRuntimeWatchdog.schedule(this, "service-start");
        } else {
            HudRuntimeWatchdog.cancel(this);
        }
        if (HudRuntimeUpgradeGuard.isPendingReinit(this)) {
            NavRuntimePermissionRepair.checkAndRepairAsync(
                    this,
                    "service-start-after-package-replace",
                    true,
                    LocalAdbBridge.AuthorizationPromptMode.NEVER);
        }
    }

    @Override
    //cleans up lifecycle state here so Android teardown does not leave stale runtime markers behind.
    public void onTaskRemoved(Intent rootIntent) {
        log("runtime task removed boot=" + HudPrefs.isBootEnabled(this)
                + " shutdown=" + HudPrefs.isUserShutdownActive(this));
        HudRuntimeState.recordLifecycleHook(this, "task-removed",
                "boot=" + HudPrefs.isBootEnabled(this)
                        + " shutdown=" + HudPrefs.isUserShutdownActive(this));
        if (HudPrefs.isBootEnabled(this)
                && !HudPrefs.isUserShutdownActive(this)
                && HudRuntimeSupervisor.hasActiveRuntimeWork(this)) {
            HudRuntimeWatchdog.schedule(this, "task-removed");
            startPersistent(this, "task-removed");
        } else {
            HudRuntimeWatchdog.cancel(this);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    //cleans up lifecycle state here so Android teardown does not leave stale runtime markers behind.
    public void onDestroy() {
        runtimeDestroyed = true;
        heartbeatHandler.removeCallbacks(heartbeatRunnable);
        clearStartRequestGate();
        HudRuntimeState.clearServicePresent(this, "destroyed");
        runtimeStartInitialized = false;
        runtimeActiveWork = false;
        releaseInstrumentRuntime(this, "runtime-destroyed");
        HudPrefs.setRuntimeServiceRunning(this, false);
        HudRuntimeState.markStopped(this, "destroyed");
        log("runtime destroyed");
        super.onDestroy();
    }

    private static void releaseInstrumentRuntime(Context context, String reason) {
        InstrumentProxyManager.get(context).onRuntimeStopped(reason,
                HudPrefs.isUserShutdownActive(context)
                        || HudRuntimeUpgradeGuard.hasPendingHardReset(context));
    }

    @Override
    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    public IBinder onBind(Intent intent) {
        return null;
    }

    //builds this artifact here so callers do not duplicate protocol or UI construction details.
    private Notification buildNotification(String text) {
        createNotificationChannel();
        Intent launchIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_hud_notification)
                .setContentTitle("BYD HUD")
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    //updates shared state here so freshness and lifecycle checks use the same evidence.
    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    //builds this artifact here so callers do not duplicate protocol or UI construction details.
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "BYD HUD runtime",
                NotificationManager.IMPORTANCE_LOW);
        manager.createNotificationChannel(channel);
    }

    //stops or releases work here so stale capture and HUD output cannot keep running silently.
    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    //starts or schedules work here so lifecycle recovery follows one controlled path.
    private void scheduleHeartbeat() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable);
        heartbeatHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS);
    }

    private void requestInitialUiRefresh(String reason) {
        MainActivity.requestBackgroundUiStateRefresh(this, reason);
    }

    private void requestRuntimeUiRefresh(boolean force, String reason) {
        //The periodic path is local-only; authoritative app scans are UI/event driven.
        MainActivity.requestRuntimeStatusRefresh(this, false, reason);
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    private void log(String line) {
        Log.i(TAG, line);
        AppEventLogger.event(this, "runtime " + line);
    }
}
