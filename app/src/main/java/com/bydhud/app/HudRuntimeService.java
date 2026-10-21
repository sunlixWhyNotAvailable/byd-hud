package com.bydhud.app;

//keeps the persistent foreground runtime alive so capture and HUD output continue outside the UI.

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

//anchors the HudRuntimeService android entry point so lifecycle recovery stays separate from business logic.
public final class HudRuntimeService extends Service {
    private static final String TAG = "BydHudRuntimeService";
    private static final String CHANNEL_ID = HudForegroundNotification.CHANNEL_ID;
    private static final int LOCATION_ACCESS_NOTIFICATION_ID = 4303;
    static final String ACTION_VEHICLE_POSITION_ACCESS = "com.bydhud.app.action.VEHICLE_POSITION_ACCESS";

    private static final String ACTION_START_PERSISTENT =
            "com.bydhud.app.action.START_PERSISTENT_RUNTIME";
    private static final String EXTRA_REASON = "reason";
    private static final long HEARTBEAT_INTERVAL_MS = 5L * 60L * 1000L;
    private static final long START_REQUEST_TIMEOUT_MS = 15_000L;

    private static final AtomicBoolean START_IN_FLIGHT = new AtomicBoolean(false);
    private static final Handler POSITION_MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static volatile HudRuntimeService activeService;
    private static volatile boolean vehiclePositionPermissionGranted;
    private static volatile String requestedVehiclePositionRouteSource;
    private static volatile Runnable vehiclePositionListener;
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
    private volatile boolean runtimeDestroyed;
    private VehiclePositionController vehiclePositionController;
    private boolean locationForegroundActive;
    private boolean locationFgsStartedWhileUiVisible;
    private boolean locationAccessNotificationVisible;
    private AppOpsManager locationAppOpsManager;
    private volatile boolean vehiclePositionSleeping;
    private String lastVehiclePositionState = "";
    private final AppOpsManager.OnOpChangedListener locationAppOpListener = (op, packageName) -> {
        if (packageName == null || getPackageName().equals(packageName)) {
            POSITION_MAIN_HANDLER.post(() -> {
                if (runtimeDestroyed) return;
                vehiclePositionPermissionGranted = hasVehiclePositionPermissions(HudRuntimeService.this);
                if (vehiclePositionController != null) {
                    vehiclePositionController.setAcquiring(false);
                }
                reconcileVehiclePosition();
                notifyVehiclePositionListener();
            });
        }
    };
    private final BroadcastReceiver locationProviderChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                vehiclePositionSleeping = true;
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                vehiclePositionSleeping = false;
            }
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                reconcileVehiclePosition();
                notifyVehiclePositionListener();
                return;
            }
            if (Intent.ACTION_SCREEN_ON.equals(action)) {
                reconcileVehiclePosition();
                notifyVehiclePositionListener();
                return;
            }
            if (locationForegroundActive && vehiclePositionController != null) {
                vehiclePositionController.refreshProviders();
            }
            reconcileVehiclePosition();
            notifyVehiclePositionListener();
        }
    };

    /** Spatial preparation supplies a route; the output owner also gates acquisition. */
    public static void setVehiclePositionRouteSource(String routeSource) {
        String normalized = routeSource == null || routeSource.trim().isEmpty()
                ? null : routeSource.trim();
        String previous = requestedVehiclePositionRouteSource;
        if (previous == null ? normalized == null : previous.equals(normalized)) return;
        requestedVehiclePositionRouteSource = normalized;
        POSITION_MAIN_HANDLER.post(() -> {
            HudRuntimeService service = activeService;
            if (service == null || service.runtimeDestroyed) {
                notifyVehiclePositionListener();
            } else {
                service.reconcileVehiclePosition();
            }
        });
    }

    static void setWazeSurfaceReentryDisplay(int displayId) {
        POSITION_MAIN_HANDLER.post(() -> HudForegroundNotification.setSurfaceDisplay(displayId));
    }

    public static VehiclePositionController.Snapshot getVehiclePositionSnapshot() {
        HudRuntimeService service = activeService;
        if (service != null && service.vehiclePositionController != null) {
            return service.vehiclePositionController.snapshot();
        }
        return new VehiclePositionController.Snapshot(
                requestedVehiclePositionRouteSource, null);
    }

    /** Cheap snapshot gate; reads lifecycle flags only and never queries Android services. */
    static boolean isVehiclePositionRuntimeSuspended() {
        HudRuntimeService service = activeService;
        return service == null || service.runtimeDestroyed || service.vehiclePositionSleeping;
    }

    static boolean isVehiclePositionPermissionGranted() {
        return vehiclePositionPermissionGranted;
    }

    /** Listener is called on the main thread; consumers should post work to their own worker. */
    public static void setVehiclePositionListener(Runnable listener) {
        vehiclePositionListener = listener;
        POSITION_MAIN_HANDLER.post(() -> {
            if (vehiclePositionListener == listener && listener != null) {
                notifyVehiclePositionListener();
            }
        });
    }

    static void refreshVehiclePosition() {
        refreshVehiclePosition(activeService);
    }

    static void refreshVehiclePosition(Context context) {
        POSITION_MAIN_HANDLER.post(() -> {
            HudRuntimeService service = activeService;
            if (service != null && !service.runtimeDestroyed) {
                service.reconcileVehiclePosition();
            }
            notifyVehiclePositionListener();
        });
    }

    static boolean hasVehiclePositionPermissions(Context context) {
        return hasVehiclePositionForegroundPermission(context);
    }

    static boolean hasVehiclePositionForegroundPermission(Context context) {
        if (context == null) return false;
        return hasLocationPermissionAndAppOp(context,
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    AppOpsManager.OPSTR_FINE_LOCATION)
                || hasLocationPermissionAndAppOp(context,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                    AppOpsManager.OPSTR_COARSE_LOCATION);
    }

    private static void notifyVehiclePositionListener() {
        Runnable listener = vehiclePositionListener;
        if (listener == null) return;
        try {
            listener.run();
        } catch (RuntimeException error) {
            Log.w(TAG, "vehicle position listener failed", error);
        }
    }

    private static void onVehiclePositionProviderStateChanged() {
        POSITION_MAIN_HANDLER.post(() -> {
            HudRuntimeService service = activeService;
            if (service != null && !service.runtimeDestroyed) {
                service.reconcileVehiclePosition();
            }
            notifyVehiclePositionListener();
        });
    }

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
        boolean runtimeAllowed = UserRuntimeSession.allowsRuntime(appContext);
        if (!runtimeAllowed) {
            HudRuntimeWatchdog.cancel(appContext);
            HudRuntimeState.clearServicePresent(appContext,
                    "start-rejected:runtime-disabled:" + safeReason);
            AppEventLogger.event(appContext,
                    "runtime startPersistent skipped runtime_disabled reason=" + safeReason);
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
            appContext.startForegroundService(intent);
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
        RUNTIME_DISABLED,
        ALREADY_ALIVE,
        IN_FLIGHT,
        REQUEST
    }

    //Keeps the executable admission policy shared by production and focused JVM tests.
    static StartDecision startDecision(boolean shutdown, boolean runtimeAllowed,
            boolean servicePresent, boolean inFlight, boolean hardResetPending) {
        if (shutdown) return StartDecision.SHUTDOWN;
        if (!runtimeAllowed) return StartDecision.RUNTIME_DISABLED;
        if (servicePresent && !hardResetPending) return StartDecision.ALREADY_ALIVE;
        return inFlight ? StartDecision.IN_FLIGHT : StartDecision.REQUEST;
    }

    static StartDecision startDecisionForTest(boolean shutdown, boolean runtimeAllowed,
            boolean servicePresent, boolean inFlight, boolean hardResetPending) {
        return startDecision(shutdown, runtimeAllowed, servicePresent, inFlight, hardResetPending);
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
        setVehiclePositionRouteSource(null);
        NavigatorRouteReceiver.stop("runtime-stop:" + reason);
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
        vehiclePositionPermissionGranted = false;
        locationAccessNotificationVisible = true;
        showLocationAccessNotification(false);
        runtimeStartInitialized = false;
        runtimeActiveWork = false;
        runtimeDestroyed = false;
        PowerManager powerManager = getSystemService(PowerManager.class);
        vehiclePositionSleeping = powerManager != null && !powerManager.isInteractive();
        vehiclePositionController = new VehiclePositionController(this,
                HudRuntimeService::notifyVehiclePositionListener,
                HudRuntimeService::onVehiclePositionProviderStateChanged);
        vehiclePositionController.setRouteSource(requestedVehiclePositionRouteSource);
        activeService = this;
        startWatchingLocationAppOps();
        IntentFilter providerFilter = new IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION);
        providerFilter.addAction(Intent.ACTION_SCREEN_OFF);
        providerFilter.addAction(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(locationProviderChangedReceiver, providerFilter,
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(locationProviderChangedReceiver, providerFilter);
        }
        HudRuntimeUpgradeGuard.recordVersionStart(this, "service-create");
        HudGraphicPayload.setContext(this);
        HudForegroundNotification.start(this, "Runtime active",
                ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE);
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
        if ("com.bydhud.app.action.RECOVER_SHELL_RUNTIME".equals(action)) reason = "shell-session-recovery";
        log("runtime onStartCommand action=" + action
                + " reason=" + reason
                + " boot=" + HudPrefs.isBootEnabled(this)
                + " shutdown=" + HudPrefs.isUserShutdownActive(this));
        if (HudPrefs.isUserShutdownActive(this)) {
            stopVehiclePositionRuntime();
            HudRuntimeWatchdog.cancel(this);
            HudRuntimeState.markStopped(this, "shutdown-active:" + reason);
            stopForegroundCompat();
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        // A null intent is Android restarting this previously started sticky service.
        if (intent == null) {
            UserRuntimeSession.PROCESS.activate();
            log("session restored reason=sticky-restart");
        }
        if ("com.bydhud.app.action.RECOVER_SHELL_RUNTIME".equals(action)
                && ShellRuntimeSession.mayRestore(this)) {
            UserRuntimeSession.PROCESS.activate();
            InstrumentProxyManager.get(this).awaitExistingRuntime();
        }
        if (!UserRuntimeSession.allowsRuntime(this)) {
            stopVehiclePositionRuntime();
            HudRuntimeWatchdog.cancel(this);
            HudRuntimeState.markStopped(this, "runtime-disabled:" + reason);
            stopForegroundCompat();
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (HudRuntimeUpgradeGuard.hasPendingHardReset(this)) {
            HudRuntimeSupervisor.hardResetAfterPackageReplace(this, "service-start:" + reason);
            return START_NOT_STICKY;
        }
        final String startReason = reason;
        BootCleanupGate.runWhenReady(this, () -> heartbeatHandler.post(
                () -> completeStartAfterBootGate(startReason)));
        return START_STICKY;
    }

    private void completeStartAfterBootGate(String reason) {
        if (runtimeDestroyed
                || !UserRuntimeSession.allowsRuntime(this)) return;
        NavigatorRouteReceiver.start(this);
        ShellRuntimeSession.armAsync(this);
        AppUpdateManager.onSessionEntry(this);
        HudRuntimeState.publishServicePresent(this, "onStartCommand");
        clearStartRequestGate();
        HudPrefs.setRuntimeServiceRunning(this, true);
        HudRuntimeState.markHeartbeat(this, "onStartCommand:" + reason);
        reconcileVehiclePosition();
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
            if (!runtimeStartInitialized && ShellRuntimeSession.takeServiceRestore()) {
                NavHudLiveSender.get(this).restoreOutputSession();
                NavAppDisplayController displays = NavAppDisplayController.get(this);
                String projected = displays.persistedDashboardPackage();
                if (!projected.isEmpty()) ClusterProjectionService.startProjection(this, projected,
                        displays.persistedDashboardMode(), "shell-session-recovery");
                if (ShellRuntimeSession.restoreCaptureServices() && NavPermissionStatus.check(this).captureGranted()) {
                    NavRuntimePermissionRepair.checkAndRepairAsync(this, "shell-session-recovery", true,
                            LocalAdbBridge.AuthorizationPromptMode.NEVER);
                }
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
                + " shutdown=" + HudPrefs.isUserShutdownActive(this)
                + " runtimeAllowed=" + UserRuntimeSession.allowsRuntime(this));
        HudRuntimeState.recordLifecycleHook(this, "task-removed",
                "boot=" + HudPrefs.isBootEnabled(this)
                        + " shutdown=" + HudPrefs.isUserShutdownActive(this));
        if (UserRuntimeSession.allowsRuntime(this)
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
        vehiclePositionPermissionGranted = false;
        showLocationAccessNotification(false);
        requestedVehiclePositionRouteSource = null;
        if (activeService == this) activeService = null;
        if (vehiclePositionController != null) vehiclePositionController.clear();
        locationFgsStartedWhileUiVisible = false;
        locationForegroundActive = false;
        if (locationAppOpsManager != null) {
            locationAppOpsManager.stopWatchingMode(locationAppOpListener);
            locationAppOpsManager = null;
        }
        try {
            unregisterReceiver(locationProviderChangedReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        notifyVehiclePositionListener();
        NavigatorRouteReceiver.stop("runtime-destroyed");
        heartbeatHandler.removeCallbacks(heartbeatRunnable);
        clearStartRequestGate();
        HudRuntimeState.clearServicePresent(this, "destroyed");
        runtimeStartInitialized = false;
        runtimeActiveWork = false;
        releaseInstrumentRuntime(this, "runtime-destroyed");
        HudPrefs.setRuntimeServiceRunning(this, false);
        HudRuntimeState.markStopped(this, "destroyed");
        recordVehiclePositionState("off/no-source");
        HudForegroundNotification.stop(this);
        log("runtime destroyed");
        super.onDestroy();
    }

    private void stopVehiclePositionRuntime() {
        vehiclePositionPermissionGranted = false;
        showLocationAccessNotification(false);
        requestedVehiclePositionRouteSource = null;
        if (vehiclePositionController != null) vehiclePositionController.clear();
        setLocationForegroundActive(false);
        locationFgsStartedWhileUiVisible = false;
        recordVehiclePositionState("off/no-source");
        notifyVehiclePositionListener();
    }

    private void reconcileVehiclePosition() {
        if (runtimeDestroyed) return;
        vehiclePositionPermissionGranted = hasVehiclePositionPermissions(this);
        VehiclePositionController controller = vehiclePositionController;
        if (controller == null) return;
        String routeSource = requestedVehiclePositionRouteSource;
        if (!HudOutputCoordinator.allowsVehiclePosition(routeSource)
                || !NavCapturePrefs.isHudEnabled(this, routeSource)) routeSource = null;
        controller.setRouteSource(routeSource);
        boolean enabled = HudPrefs.isVehiclePositionEnabled(this)
                && !HudPrefs.isUserShutdownActive(this)
                && UserRuntimeSession.allowsRuntime(this);
        boolean uiVisible = MainActivity.isUiResumed();
        boolean needsUser = enabled && routeSource != null && !vehiclePositionSleeping && !uiVisible;
        if (!enabled) {
            showLocationAccessNotification(false);
            suspendVehiclePosition("off/no-source");
            return;
        }
        if (!vehiclePositionPermissionGranted) {
            suspendVehiclePosition("permission-unavailable");
            showLocationAccessNotification(needsUser);
            return;
        }
        if (vehiclePositionSleeping) {
            showLocationAccessNotification(false);
            pauseVehiclePosition("sleep");
            return;
        }
        boolean backgroundPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
        if (!backgroundPermission && !uiVisible
                && !(locationForegroundActive && locationFgsStartedWhileUiVisible)) {
            suspendVehiclePosition("background-unavailable");
            showLocationAccessNotification(needsUser);
            return;
        }
        if (!locationForegroundActive && !setLocationForegroundActive(true)) {
            suspendVehiclePosition("location-foreground-unavailable");
            showLocationAccessNotification(needsUser);
            return;
        }
        showLocationAccessNotification(false);
        if (uiVisible) locationFgsStartedWhileUiVisible = true;
        if (routeSource == null) {
            pauseVehiclePosition("armed/no-source");
            return;
        }
        if (!controller.hasEnabledProvider()) {
            pauseVehiclePosition("provider-unavailable");
            return;
        }
        if (!controller.setAcquiring(true)) {
            pauseVehiclePosition("provider-unavailable");
            return;
        }
        recordVehiclePositionState("active");
    }

    private void pauseVehiclePosition(String state) {
        // Keep the admitted FGS session across sleep/route gaps without subscribing to location.
        if (vehiclePositionController != null) vehiclePositionController.setAcquiring(false);
        recordVehiclePositionState(state);
    }

    private void suspendVehiclePosition(String state) {
        if (vehiclePositionController != null) vehiclePositionController.setAcquiring(false);
        setLocationForegroundActive(false);
        locationFgsStartedWhileUiVisible = false;
        recordVehiclePositionState(state);
    }

    private void recordVehiclePositionState(String state) {
        if (state.equals(lastVehiclePositionState)) return;
        lastVehiclePositionState = state;
        log("vehicle position state=" + state);
    }

    private void showLocationAccessNotification(boolean needed) {
        if (needed == locationAccessNotificationVisible) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        if (!needed) {
            manager.cancel(LOCATION_ACCESS_NOTIFICATION_ID);
            locationAccessNotificationVisible = false;
            return;
        }
        createNotificationChannel();
        String language = HudPrefs.uiLanguage(this);
        String text = "uk".equals(language) ? "Відкрийте BYD HUD для доступу до геопозиції"
                : "ru".equals(language) ? "Откройте BYD HUD для доступа к геопозиции"
                : "Open BYD HUD to enable location access";
        Intent intent = new Intent(this, MainActivity.class)
                .setAction(ACTION_VEHICLE_POSITION_ACCESS)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(this, LOCATION_ACCESS_NOTIFICATION_ID, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT,
                MainActivityDisplayGuard.tabletOptions());
        manager.notify(LOCATION_ACCESS_NOTIFICATION_ID, new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_hud_notification).setContentTitle("BYD HUD")
                .setContentText(text).setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true)
                .build());
        locationAccessNotificationVisible = true;
        log("vehicle position access_notification");
    }

    private void startWatchingLocationAppOps() {
        locationAppOpsManager = getSystemService(AppOpsManager.class);
        if (locationAppOpsManager == null) {
            log("location permission observer unavailable");
            return;
        }
        try {
            locationAppOpsManager.startWatchingMode(
                    AppOpsManager.OPSTR_FINE_LOCATION, getPackageName(), locationAppOpListener);
            locationAppOpsManager.startWatchingMode(
                    AppOpsManager.OPSTR_COARSE_LOCATION, getPackageName(), locationAppOpListener);
        } catch (RuntimeException error) {
            try {
                locationAppOpsManager.stopWatchingMode(locationAppOpListener);
            } catch (RuntimeException ignored) {
            }
            locationAppOpsManager = null;
            log("location permission observer unavailable error="
                    + error.getClass().getSimpleName());
        }
    }

    private static boolean hasLocationPermissionAndAppOp(
            Context context, String permission, String operation) {
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        if (appOps == null) return false;
        try {
            int mode = appOps.unsafeCheckOpRawNoThrow(operation,
                    android.os.Process.myUid(), context.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_DEFAULT
                    || mode == AppOpsManager.MODE_FOREGROUND;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private boolean setLocationForegroundActive(boolean enabled) {
        if (locationForegroundActive == enabled) return true;
        try {
            int type = enabled ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    : ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE;
            HudForegroundNotification.start(this,
                    runtimeActiveWork ? "Runtime active" : "Runtime idle", type);
            locationForegroundActive = enabled;
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
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

    private void updateNotification(String text) {
        HudForegroundNotification.update(this, text);
    }

    private void createNotificationChannel() {
        HudForegroundNotification.ensureChannel(this);
    }

    private void stopForegroundCompat() {
        HudForegroundNotification.stop(this);
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
