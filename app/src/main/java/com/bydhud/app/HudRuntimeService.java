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

    //starts or schedules work here so lifecycle recovery follows one controlled path.
    static void startPersistent(Context context, String reason) {
        Context appContext = context.getApplicationContext();
        String safeReason = reason == null ? "" : reason.trim();
        if (HudPrefs.isUserShutdownActive(appContext)) {
            HudRuntimeWatchdog.cancel(appContext);
            AppEventLogger.event(appContext,
                    "runtime startPersistent skipped shutdown_active reason=" + safeReason);
            return;
        }
        if (!HudPrefs.isBootEnabled(appContext)) {
            HudRuntimeWatchdog.cancel(appContext);
            AppEventLogger.event(appContext,
                    "runtime startPersistent skipped boot_disabled reason=" + safeReason);
            return;
        }
        boolean hardResetPending = HudRuntimeUpgradeGuard.hasPendingHardReset(appContext);
        if (!hardResetPending
                && HudRuntimeState.isAlive(appContext, android.os.SystemClock.elapsedRealtime())) {
            AppEventLogger.event(appContext,
                    "runtime startPersistent skipped already_alive reason=" + safeReason);
            return;
        }
        if (!START_IN_FLIGHT.compareAndSet(false, true)) {
            AppEventLogger.event(appContext,
                    "runtime startPersistent skipped start_in_flight reason=" + safeReason);
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

    static StartDecision startDecisionForTest(boolean shutdown, boolean bootEnabled,
            boolean alive, boolean inFlight, boolean hardResetPending) {
        if (shutdown) return StartDecision.SHUTDOWN;
        if (!bootEnabled) return StartDecision.BOOT_DISABLED;
        if (alive && !hardResetPending) return StartDecision.ALREADY_ALIVE;
        return inFlight ? StartDecision.IN_FLIGHT : StartDecision.REQUEST;
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
        HudRuntimeWatchdog.cancel(appContext);
        InstrumentProxyManager.get(appContext).shutdown("runtime-stop:" + reason);
        appContext.stopService(new Intent(appContext, HudRuntimeService.class));
        HudPrefs.setRuntimeServiceRunning(appContext, false);
        HudRuntimeState.markStopped(appContext, "stop:" + reason);
    }

    @Override
    //initializes android lifecycle state here so services, UI, and logging start from a known baseline.
    public void onCreate() {
        super.onCreate();
        clearStartRequestGate();
        runtimeStartInitialized = false;
        runtimeActiveWork = false;
        HudRuntimeUpgradeGuard.recordVersionStart(this, "service-create");
        HudGraphicPayload.setContext(this);
        startForeground(NOTIFICATION_ID, buildNotification("Runtime active"));
        HudPrefs.setRuntimeServiceRunning(this, true);
        HudRuntimeState.markStarted(this, "onCreate");
        scheduleHeartbeat();
        requestInitialUiRefresh("runtime-create");
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
        clearStartRequestGate();
        HudPrefs.setRuntimeServiceRunning(this, true);
        HudRuntimeState.markHeartbeat(this, "onStartCommand:" + reason);
        boolean activeWork = HudRuntimeSupervisor.hasActiveRuntimeWork(this);
        if (!runtimeStartInitialized || activeWork != runtimeActiveWork) {
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
        return START_STICKY;
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
        heartbeatHandler.removeCallbacks(heartbeatRunnable);
        clearStartRequestGate();
        runtimeStartInitialized = false;
        runtimeActiveWork = false;
        InstrumentProxyManager.get(this).shutdown("runtime-destroyed");
        HudPrefs.setRuntimeServiceRunning(this, false);
        HudRuntimeState.markStopped(this, "destroyed");
        log("runtime destroyed");
        super.onDestroy();
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
