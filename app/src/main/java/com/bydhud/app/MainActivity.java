package com.bydhud.app;

import android.annotation.SuppressLint;
//hosts the Compose UI so diagnostics, permissions, logs, and update controls share one app surface.

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.FileProvider;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

//anchors MainActivity UI orchestration so controls and diagnostics are wired from one place.
public final class MainActivity extends ComponentActivity {
    private static final String TAG = "BydHudTest";
    private static final long NAV_RUNTIME_RECHECK_DELAY_MS = 1500L;
    private static final long NAV_PERMISSION_SELF_CHECK_DELAY_MS = 600L;
    private static final long STORAGE_SCAN_CACHE_TTL_MS = 30000L;
    private static final long RUNTIME_UI_REFRESH_INTERVAL_MS = 5L * 60L * 1000L;
    private static final long RUNTIME_STATUS_REFRESH_INTERVAL_MS = 5L * 60L * 1000L;
    private static final long PATCH_UI_REFRESH_INTERVAL_MS = 1000L;
    private static final long ASSET_UI_REFRESH_INTERVAL_MS = 1000L;
    private static final long LOGCAT_STOP_TIMEOUT_MS = 90000L;
    private static final boolean BACKGROUND_MODE = true;
    private static final String GMAPS_OFFICIAL_PACKAGE = "com.google.android.apps.maps";
    private static final String GMAPS_REVANCED_PACKAGE = "app.revanced.android.apps.maps";
    private static final String WAZE_PACKAGE = "com.waze";
    private static final AtomicBoolean STORAGE_DELETE_OPERATION = new AtomicBoolean(false);
    private static final AtomicBoolean SHARE_OPERATION = new AtomicBoolean(false);
    private static final AtomicReference<PendingShare> PENDING_SHARE = new AtomicReference<>();
    private static final AtomicReference<ShareLaunchEvent> SHARE_LAUNCH_EVENT =
            new AtomicReference<>(ShareLaunchEvent.empty());
    private static final AtomicLong SHARE_LAUNCH_SEQUENCE = new AtomicLong();
    private static final AtomicReference<MainActivity> RESUMED_ACTIVITY = new AtomicReference<>();
    private static final AtomicBoolean STORAGE_SCAN_IN_PROGRESS = new AtomicBoolean(false);
    private static final AtomicBoolean STORAGE_FORCE_REFRESH_PENDING = new AtomicBoolean(false);
    private static final AtomicBoolean APP_SCAN_IN_PROGRESS = new AtomicBoolean(false);
    private static final AtomicBoolean APP_FORCE_REFRESH_PENDING = new AtomicBoolean(false);
    private static final AtomicBoolean RUNTIME_STATUS_REFRESH_IN_PROGRESS =
            new AtomicBoolean(false);
    private static final AtomicBoolean RUNTIME_STATUS_REFRESH_PENDING =
            new AtomicBoolean(false);
    private static final AtomicBoolean PATCH_REFRESH_IN_PROGRESS = new AtomicBoolean(false);
    private static final AtomicBoolean ASSET_REFRESH_IN_PROGRESS = new AtomicBoolean(false);
    private static final AtomicBoolean ASSET_FORCE_REFRESH_PENDING = new AtomicBoolean(false);
    private static final AtomicBoolean PATCH_FORCE_REFRESH_PENDING = new AtomicBoolean(false);
    private static final AtomicBoolean MAP_PROFILE_CACHE_REFRESHING = new AtomicBoolean(false);
    private static final Object PATCH_REFRESH_GATE_LOCK = new Object();
    private static final Object PACKAGE_METADATA_CACHE_LOCK = new Object();
    private static final Map<String, String> APP_LABEL_CACHE = new HashMap<>();
    private static final Map<String, Boolean> INSTALLED_PACKAGE_CACHE = new HashMap<>();
    private static final Object NAV_RUNTIME_PERMISSION_CACHE_LOCK = new Object();
    private static final Object MAP_PROFILE_CACHE_LOCK = new Object();
    private static volatile StorageCacheState storageCacheState = StorageCacheState.empty();
    private static volatile Map<String, String> appVersionNames = Collections.emptyMap();
    private static volatile List<ComposeNavigatorPatchRow> navigatorPatchRows =
            Collections.emptyList();
    private static volatile Map<HudMapProfile.Source, HudMapProfile> mapProfileCache =
            Collections.emptyMap();
    private static volatile long mapProfileCacheRevision;
    private static volatile boolean mapProfileCacheReady;
    private static volatile List<NavigatorAssetManager.AssetSnapshot> navigatorAssetSnapshots =
            Collections.emptyList();
    private static volatile List<InstalledTransferAppCatalog.Entry> installedTransferApps =
            Collections.emptyList();
    private static volatile boolean navigatorPatchRowsReady;
    private static volatile boolean appScanCacheAvailable;
    private static volatile String appScanStatus = "";
    private static volatile NavRuntimePermissionStatus cachedNavRuntimePermissionStatus;
    private static volatile boolean cachedAdbAuthorizationKnown;
    private static volatile boolean cachedAdbAuthorizationStatusAvailable;
    private static volatile boolean cachedAdbKeySnapshotAvailable;
    private static volatile boolean cachedAdbKeyAvailable;
    private static volatile boolean cachedAdbKeyVerifiedThisProcess;
    private static volatile String cachedAdbKeyFingerprint = "";
    private static volatile long lastRuntimeUiRefreshAtMs;
    private static volatile long lastRuntimeStatusRefreshAtMs;
    private static volatile long lastPatchUiRefreshAtMs;
    private static volatile long lastAssetUiRefreshAtMs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final MainActivityDisplayGuard mainDisplayGuard = new MainActivityDisplayGuard();
    private volatile Runnable composeSnapshotInvalidationListener;
    private HudOutputCoordinator hudOutput;
    private boolean destroyed;
    private boolean adbGrantInProgress;
    private long adbGrantGeneration;
    private boolean pendingManualAdbRetry;
    private long pendingManualAdbRetryGeneration;
    private long pendingAdbCancellationClearGeneration;
    private Runnable pendingAdbCancellationClearRunnable;
    private AdbAuthorizationUiPolicy.AutoState autoAdbAuthorizationState =
            AdbAuthorizationUiPolicy.AutoState.IDLE;
    private boolean mainUiReady;
    private boolean activityResumed;
    private boolean activityWindowFocused;
    private boolean dashboardMoveInProgress;
    private String composeBlockingUiFlow = "compose-starting";
    private boolean overlayPermissionFlow;
    private boolean overlayPermissionManualPending;
    private boolean overlayPermissionAttempted;
    private boolean vehiclePositionPermissionPending;
    private boolean vehiclePositionPermissionCancelled;
    private boolean vehiclePositionPermissionAttempted;
    private final ActivityResultLauncher<String[]> vehiclePositionPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), permissions -> {
                boolean acceptResult = vehiclePositionPermissionPending
                        && !vehiclePositionPermissionCancelled && !this.exitRequested
                        && !HudPrefs.isUserShutdownActive(this)
                        && HudPrefs.isVehiclePositionEnabled(this);
                vehiclePositionPermissionPending = false;
                vehiclePositionPermissionCancelled = false;
                if ("vehicle-position-permission".equals(composeBlockingUiFlow)) composeBlockingUiFlow = "";
                // Passive permission loss is not a denial; only a completed foreground request disables intent.
                boolean denied = !permissions.isEmpty()
                        && !Boolean.TRUE.equals(permissions.get(android.Manifest.permission.ACCESS_FINE_LOCATION))
                        && !Boolean.TRUE.equals(permissions.get(android.Manifest.permission.ACCESS_COARSE_LOCATION));
                if (acceptResult && denied) HudPrefs.setVehiclePositionEnabled(this, false);
                AppEventLogger.event(this, "vehicle_position permission_result accepted=" + acceptResult
                        + " denied=" + denied + " granted=" + HudRuntimeService.hasVehiclePositionPermissions(this));
                HudRuntimeService.refreshVehiclePosition(this);
                invalidateComposeSnapshot();
                maybeStartPendingAdbAuthorization();
            });
    private final Runnable overlayPermissionRetry = this::maybeRequestOverlayPermission;
    private final ActivityResultLauncher<Intent> overlayPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                overlayPermissionFlow = false;
                composeBlockingUiFlow = "";
                AppEventLogger.event(this, "overlay_permission returned granted=" + Settings.canDrawOverlays(this));
                requestRuntimeStatusRefresh(this, true, "overlay-permission-return");
                maybeStartPendingAdbAuthorization();
            });
    private final Map<String, ActivityResultLauncher<Intent>> archiveDocumentLaunchers = new HashMap<>();
    private boolean exitRequested;
    private Runnable pendingNavPermissionSelfCheckRunnable;
    private Runnable pendingAutoAdbStartRunnable;

    @Override
    //initializes android lifecycle state here so services, UI, and logging start from a known baseline.
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        restoreArchiveDocumentLaunchers();
        mainDisplayGuard.check(this, "create");
        overlayPermissionFlow = savedInstanceState != null
                && savedInstanceState.getBoolean("overlay-permission-flow", false);
        if (savedInstanceState != null) {
            vehiclePositionPermissionPending = savedInstanceState.getBoolean("vehicle-position-permission-pending");
            vehiclePositionPermissionCancelled = savedInstanceState.getBoolean("vehicle-position-permission-cancelled");
            vehiclePositionPermissionAttempted = savedInstanceState.getBoolean("vehicle-position-permission-attempted");
        }
        if (!vehiclePositionPermissionPending && getIntent() != null
                && HudRuntimeService.ACTION_VEHICLE_POSITION_ACCESS.equals(getIntent().getAction())) {
            vehiclePositionPermissionAttempted = false;
        }
        destroyed = false;
        HudRuntimeUpgradeGuard.recordVersionStart(this, "activity-create");
        if (HudPrefs.isUserShutdownActive(this)) {
            HudPrefs.setUserShutdownActive(this, false);
            AppEventLogger.event(this, "shutdown cleared reason=activity-create");
            NavNotificationListenerService.resumeAfterUserShutdown(
                    this, "activity-create");
        }
        NavHudLiveSender.activateUserRuntime(this);
        HudGraphicPayload.setContext(this);
        LogShareZip.cleanupStaleArtifactsAsync(this);
        NavigationLogStorage.cleanupRetiredStorageDaysAsync(this);
        BootCleanupGate.runWhenReady(this,
                () -> HudRuntimeSupervisor.ensureStarted(this, "activity-create"));
        hudOutput = HudOutputCoordinator.get(this);
        NavAppDisplayController displayController = NavAppDisplayController.get(this);
        dashboardMoveInProgress = displayController.setListener(moveInProgress -> runOnUiThread(() -> {
            if (destroyed) {
                return;
            }
            boolean moveFinished = dashboardMoveInProgress && !moveInProgress;
            dashboardMoveInProgress = moveInProgress;
            if (moveInProgress || moveFinished || displayController.usesSurfaceTarget(WAZE_PACKAGE)) {
                //The Apps rows are cache-backed; publish only the move gate/status change here.
                publishSharedUiStateChange();
            }
            if (moveFinished) {
                scheduleAppScan();
            }
        }));
        requestActivityLocalStatusRefresh("activity-create");
        requestInitialUiStateRefresh(this, "activity-create");
        BydHudRuntimeCompose.install(this);
        appendStatus("idle: Boot controls automatic startup; Apps tab controls Start HUD / Start only log");
        showBackgroundReminderIfNeeded();
        requestAutomaticAdbAuthorization("activity-create");
    }

    @Override
    //keeps this step explicit so callers can rely on one documented behavior boundary.
    protected void onStart() {
        super.onStart();
        DashboardWidgetController.onAppOpened(this);
        BootCleanupGate.runWhenReady(this,
                () -> HudRuntimeSupervisor.ensureStarted(this, "activity-start"));
        maybeStartPendingAdbAuthorization();
    }

    @Override
    //keeps this step explicit so callers can rely on one documented behavior boundary.
    protected void onResume() {
        super.onResume();
        mainDisplayGuard.check(this, "resume");
        ShanghaiTestController.get(this).recoverOwned("activity-open");
        if (!exitRequested) {
            NavHudLiveSender.get(this).resumeUserRuntime("activity-resume");
            AppUpdateManager.onSessionEntry(this);
        }
        activityResumed = true;
        RESUMED_ACTIVITY.set(this);
        HudRuntimeService.refreshVehiclePosition(this);
        DashboardWidgetController.refresh(this);
        maybeStartPendingAdbAuthorization();
        invalidateComposeSnapshot();
        requestActivityLocalStatusRefresh("activity-resume");
        requestRuntimeUiStateRefresh(this, true, "activity-resume");
        NavAccessibilityService.resumeSteeringRuntime(this, "activity-resume");
        notifyPendingShare();
        notifyArchiveDocumentPicker();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        mainDisplayGuard.check(this, "new-intent");
        if (HudRuntimeService.ACTION_VEHICLE_POSITION_ACCESS.equals(intent.getAction())) {
            vehiclePositionPermissionAttempted = false;
            HudRuntimeService.refreshVehiclePosition(this);
            maybeRequestVehiclePositionPermission();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        mainDisplayGuard.check(this, "configuration");
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(overlayPermissionRetry);
        activityResumed = false;
        activityWindowFocused = false;
        if (NavAccessibilityService.isKeyLearning()) {
            NavAccessibilityService.cancelKeyLearning();
        }
        cancelPendingAutoAdbStart();
        RESUMED_ACTIVITY.compareAndSet(this, null);
        HudRuntimeService.refreshVehiclePosition(this);
        Context app = getApplicationContext();
        ShellRuntimeSession.afterPersistence(() -> {
            if (!HudPrefs.flush(app)) AppEventLogger.event(app, "UI settings persistence failed on pause");
        });
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        activityWindowFocused = hasFocus;
        if (hasFocus) {
            maybeStartPendingAdbAuthorization();
        } else {
            cancelPendingAutoAdbStart();
        }
    }

    @Override
    //keeps this step explicit so callers can rely on one documented behavior boundary.
    protected void onStop() {
        super.onStop();
        if (!vehiclePositionPermissionPending) vehiclePositionPermissionAttempted = false;
        NavHudLiveSender.stopBasicHudCheckOnBackground();
        if (exitRequested || isFinishing()) {
            appendStatus("onStop after explicit exit");
            return;
        }
        appendStatus("onStop background keepalive");
    }

    @Override
    //cleans up lifecycle state here so Android teardown does not leave stale runtime markers behind.
    protected void onDestroy() {
        destroyed = true;
        for (ActivityResultLauncher<Intent> launcher : archiveDocumentLaunchers.values()) launcher.unregister();
        archiveDocumentLaunchers.clear();
        handler.removeCallbacks(overlayPermissionRetry);
        if (NavAccessibilityService.isKeyLearning()) {
            NavAccessibilityService.cancelKeyLearning();
        }
        if (pendingNavPermissionSelfCheckRunnable != null) {
            handler.removeCallbacks(pendingNavPermissionSelfCheckRunnable);
            pendingNavPermissionSelfCheckRunnable = null;
        }
        cancelPendingAutoAdbStart();
        NavAppDisplayController.get(this).setListener(null);
        appendStatus((exitRequested || HudPrefs.isUserShutdownActive(this))
                ? "destroy after explicit exit"
                : "destroy without explicit exit: keep sender state");
        super.onDestroy();
    }

    @Override
    @SuppressLint("MissingSuperCall")
    //keeps this step explicit so callers can rely on one documented behavior boundary.
    public void onBackPressed() {
        if (getOnBackPressedDispatcher().hasEnabledCallbacks()) {
            getOnBackPressedDispatcher().onBackPressed();
            return;
        }
        appendStatus("back pressed: moving task to background");
        moveTaskToBack(true);
    }

    private void reportLogcatResult(String action, LogcatRecorder.Result result) {
        handler.post(() -> {
            appendStatus("system recorder " + action + " " + result.detail
                    + " file=" + filePath(result.file));
        });
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private String logPathsText() {
        return "System recorder:\n"
                + NavigationLogStorage.publicLogcatPath()
                + "\nFiles: system_*/manifest.json, logcat-*.log, snapshots"
                + "\n\nDaily logs:\n"
                + NavigationLogStorage.publicLogsPath()
                + "\nFiles: events.log, raw_nav_events.jsonl, nav_snapshots.jsonl, "
                + "someip_tx.jsonl, tbt_tx.jsonl"
                + "\n\nHistorical Waze crop sessions:\n"
                + NavigationLogStorage.publicWazeCropPath()
                + "\nProbe channels: notification_large_icon, unsupported_start_hud, nav_app_display";
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static StorageCacheState scanStorageState(Context context) {
        NavigationLogStorage.StorageSnapshot snapshot =
                NavigationLogStorage.snapshotAccessibleStorage(context);
        List<ComposeStorageDay> days = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        SimpleDateFormat labelFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        for (NavigationLogStorage.StorageRoot root : snapshot.roots) {
            paths.add(root.dir.getAbsolutePath());
        }
        for (NavigationLogStorage.StorageDay day : snapshot.days) {
            days.add(new ComposeStorageDay(
                    day.name,
                    labelFormat.format(new Date(day.lastModified)),
                    day.cropSessions,
                    day.bytes,
                    day.active,
                    day.hasPublicStorage,
                    day.hasPrivateStorage));
        }
        Collections.sort(days, (a, b) -> b.name.compareTo(a.name));
        return StorageCacheState.scanned(
                snapshot.totalBytes,
                snapshot.totalSessions,
                paths,
                days,
                SystemClock.elapsedRealtime());
    }

    //guards Storage tab responsiveness by moving recursive folder scans off the UI snapshot path.
    public void composeRequestStorageRefresh(boolean force) {
        requestStorageRefresh(getApplicationContext(), force, "compose");
    }

    private static void requestStorageRefresh(Context context, boolean force, String reason) {
        Context appContext = context.getApplicationContext();
        StorageCacheState current = storageCacheState;
        long now = SystemClock.elapsedRealtime();
        boolean stale = current.scannedAtMs <= 0L
                || now - current.scannedAtMs >= STORAGE_SCAN_CACHE_TTL_MS;
        if (!force && current.calculating) {
            return;
        }
        if (!force && !stale) {
            return;
        }
        if (!STORAGE_SCAN_IN_PROGRESS.compareAndSet(false, true)) {
            if (force) {
                STORAGE_FORCE_REFRESH_PENDING.set(true);
            }
            if (!current.hasCache()) {
                storageCacheState = current.withCalculating(true);
                publishSharedUiStateChange();
            }
            return;
        }
        if (!current.hasCache()) {
            storageCacheState = current.withCalculating(true);
            publishSharedUiStateChange();
        }
        Thread worker = new Thread(() -> {
            StorageCacheState next;
            try {
                next = scanStorageState(appContext);
            } catch (RuntimeException e) {
                String detail = e.getClass().getSimpleName() + ": "
                        + String.valueOf(e.getMessage()).replace('\n', ' ').replace('\r', ' ');
                next = storageCacheState.withScanError(detail);
                AppEventLogger.event(appContext, "storage_scan failed "
                        + e.getClass().getSimpleName() + " "
                        + String.valueOf(e.getMessage()).replace('\n', ' ').replace('\r', ' '));
            } finally {
                STORAGE_SCAN_IN_PROGRESS.set(false);
            }
            storageCacheState = next;
            AppEventLogger.event(appContext, "ui_storage_refresh reason=" + reason
                    + " roots=" + next.rootPaths.size()
                    + " days=" + next.storageDays.size());
            publishSharedUiStateChange();
            if (STORAGE_FORCE_REFRESH_PENDING.getAndSet(false)) {
                requestStorageRefresh(appContext, true, "pending-force");
            }
        }, "BydHudStorageScan");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    public ComposeSnapshot composeSnapshot() {
        NavRuntimePermissionStatus permissionStatus = navRuntimePermissionStatus();
        String uiLanguage = HudPrefs.uiLanguage(this);
        NavAppDisplayController displayController = NavAppDisplayController.get(this);
        Set<String> capturePackages = NavCapturePrefs.getCapturePackages(this);
        Set<String> observedPackages = NavCapturePrefs.getObservedPackages(this);
        String hudPackage = NavCapturePrefs.getHudPackage(this);
        Set<String> logOnlyPackages = NavCapturePrefs.getLogOnlyPackages(this);
        NavAppTaskScanner.Snapshot appScan = NavAppTaskScanner.get(this).currentSnapshot();
        boolean appCacheAvailable = appScanCacheAvailable
                || (appScan.scannedAtMs > 0L && !"initial".equals(appScan.status));
        String effectiveAppScanStatus = appScanStatusForTest(
                APP_SCAN_IN_PROGRESS.get(), appScanStatus, appScan);
        List<ActiveAppRow> rawApps = activeRowsFromScan(appScan.rows);
        List<ActiveAppRow> supportedApps = loadCuratedApps(rawApps, capturePackages, observedPackages);
        List<ComposeAppRow> supportedRows = composeRows(
                supportedApps, hudPackage, logOnlyPackages, observedPackages, true);
        List<ComposeNavigatorPatchRow> patchRows = composeNavigatorPatchRows();
        Set<HudMapProfile.Source> installedMapProfileSources =
                installedMapProfileSources(patchRows);
        List<ComposeAppRow> allRows = new ArrayList<>();
        for (ActiveAppRow app : rawApps) {
            if (!NavAppFilter.isCuratedNavigationPackage(app.packageName)) {
                allRows.add(composeRow(app, hudPackage, logOnlyPackages, observedPackages, false));
            }
        }
        StorageCacheState storage = storageCacheState;
        AdbUiStatus adbStatus = cachedAdbUiStatus();
        ShareLaunchEvent shareLaunchEvent = SHARE_LAUNCH_EVENT.get();
        Map<HudMapProfile.Source, HudMapProfile> mapProfiles;
        long mapProfilesRevision;
        synchronized (MAP_PROFILE_CACHE_LOCK) {
            mapProfiles = mapProfileCache;
            mapProfilesRevision = mapProfileCacheRevision;
        }
        int dashboardScreenMode = HudPrefs.dashboardScreenMode(this);
        DashboardProjectionPolicy.Profile dashboardProfile =
                HudPrefs.dashboardProjectionProfile(this, dashboardScreenMode);
        return new ComposeSnapshot(
                HudPrefs.uiLanguage(this),
                HudPrefs.isDarkTheme(this),
                HudPrefs.isBootEnabled(this),
                HudPrefs.isVehiclePositionEnabled(this),
                HudPrefs.isDetailedDebugArtifactsEnabled(this),
                HudPrefs.isPngOutputEnabled(this),
                HudPrefs.isNativeOutputEnabled(this),
                HudPrefs.maneuverCompatibilityProfile(this),
                HudPrefs.isLaneOutputEnabled(this),
                HudPrefs.isDistanceOutputEnabled(this),
                HudPrefs.isStreetOutputEnabled(this),
                HudPrefs.transliterationMode(this),
                HudPrefs.isTextDirectionOutputEnabled(this),
                HudPrefs.isWazeAlertsEnabled(this),
                HudPrefs.wazeAlertField(this),
                HudPrefs.isTbtWithoutHudOutputEnabled(this),
                HudPrefs.isSwitchToTbtOnHudStartEnabled(this),
                HudPrefs.isWholeRouteMetricsEnabled(this),
                HudPrefs.etaOutputMode(this),
                HudPrefs.routeMetricsMode(this),
                HudPrefs.etaOutputField(this),
                HudPrefs.getEtaStreetFormat(this),
                HudPrefs.isEtaWaitForFullTextEnabled(this),
                HudPrefs.getEtaArrivalColor(this),
                HudPrefs.getEtaDurationColor(this),
                HudPrefs.getEtaRemainingDistanceColor(this),
                HudPrefs.getWazeWarningDistanceColor(this),
                HudPrefs.isEtaOutputEnabled(this),
                HudPrefs.isRemainingTimeOutputEnabled(this),
                HudPrefs.isRemainingDistanceOutputEnabled(this),
                HudPrefs.speedLimitMode(this),
                HudPrefs.isNativeSpeedLimitDelayEnabled(this),
                HudPrefs.getNativeSpeedLimitFallbackMode(this),
                HudPrefs.speedLimitFreeFallback(this),
                HudPrefs.speedLimitOverlaySeconds(this),
                HudPrefs.speedLimitCompositePlacement(this),
                HudPrefs.speedLimitManeuverOverlaySize(this),
                HudPrefs.speedLimitLaneOverlaySize(this),
                HudPrefs.mapSettings(this),
                HudPrefs.isWazeCustomSurfaceEnabled(this),
                dashboardScreenMode,
                HudPrefs.isDashboardKeepBlackBackgroundEnabled(this),
                HudPrefs.dashboardFormatMethod(this, dashboardScreenMode),
                dashboardProfile.widthPercent,
                dashboardProfile.heightPercent,
                dashboardProfile.offsetPercent,
                dashboardProfile.scalePercent,
                DashboardWidgetController.snapshot(this),
                DashboardWidgetController.hasOverlayPermission(),
                installedTransferApps,
                SteeringTransferPreferences.profiles(this),
                SteeringTransferPreferences.revision(this),
                NavAccessibilityService.isKeyLearning(),
                NavAccessibilityService.capturedKeyCode(),
                HudPrefs.isSmallDistanceClampEnabled(this),
                permissionStatus.settingsGranted(),
                adbStatus.available,
                adbStatus.authorized,
                permissionStatus.readyForCapture(),
                permissionStatus.summary(),
                adbStatus.fingerprint,
                hudStatus(),
                hudPackage,
                formatCapturePackages(logOnlyPackages),
                formatCapturePackages(observedPackages),
                displayController.confirmedDashboardPackage(),
                displayController.isMoveInProgress(),
                LogcatRecorder.isRecording(),
                LogcatRecorder.statusText(),
                logPathsText(),
                composeApplicationState(permissionStatus),
                NavHudLiveSender.hudCheckSnapshot(),
                NavHudLiveSender.mapLiveSnapshot(),
                NavHudLiveSender.hudCheckStatus(uiLanguage),
                appScan.lastScanText,
                appScan.hasAuthoritativeTaskState(),
                APP_SCAN_IN_PROGRESS.get(),
                appCacheAvailable,
                effectiveAppScanStatus,
                HudPrefs.storageLimitGb(this),
                storage.rootPaths,
                storage.calculating,
                storage.hasCache(),
                storage.scanError,
                storage.totalSessions,
                storage.navCaptureFolderBytes,
                storage.storageDays,
                supportedRows,
                allRows,
                patchRows,
                localizedNavigatorAssetSnapshots(uiLanguage),
                composePatchOperations(),
                shareLaunchEvent.id,
                shareLaunchEvent.storageDays,
                mapProfiles,
                mapProfilesRevision,
                installedMapProfileSources,
                NavHudLiveSender.mapProfileSnapshot());
    }

    private static void requestMapProfileCacheRefresh(Context context) {
        if (mapProfileCacheReady
                || !MAP_PROFILE_CACHE_REFRESHING.compareAndSet(false, true)) return;
        Context appContext = context.getApplicationContext();
        new Thread(() -> {
            try {
                synchronized (MAP_PROFILE_CACHE_LOCK) {
                    if (!mapProfileCacheReady) reloadMapProfileCacheLocked(appContext);
                }
            } finally {
                MAP_PROFILE_CACHE_REFRESHING.set(false);
                publishSharedUiStateChange();
            }
        }, "BydHudMapProfileCache").start();
    }

    private static void reloadMapProfileCacheLocked(Context context) {
        mapProfileCache = HudMapProfiles.profiles(context);
        mapProfileCacheRevision = HudMapProfiles.revision(context);
        mapProfileCacheReady = true;
    }

    private static Set<HudMapProfile.Source> installedMapProfileSources(
            List<ComposeNavigatorPatchRow> rows) {
        Set<HudMapProfile.Source> result = EnumSet.noneOf(HudMapProfile.Source.class);
        for (HudMapProfile.Source source : HudMapProfile.Source.values()) {
            String packageName = source.packageName();
            for (ComposeNavigatorPatchRow row : rows) {
                if (!row.installed) continue;
                // Availability follows the package the live receiver can actually target.
                if (packageName.equals(normalizePackage(row.packageName))) {
                    result.add(source);
                    break;
                }
            }
        }
        return Collections.unmodifiableSet(result);
    }

    public boolean composeSaveMapProfile(
            HudMapProfile.Source originalSource, HudMapProfile profile) {
        Context appContext = getApplicationContext();
        if (profile == null) {
            AppEventLogger.event(appContext, "map_profile save failed reason=missing-profile");
            return false;
        }
        try {
            synchronized (MAP_PROFILE_CACHE_LOCK) {
                if (!HudMapProfiles.save(appContext, originalSource, profile)) {
                    AppEventLogger.event(appContext, "map_profile save rejected source="
                            + profile.source + " reason=store-rejected");
                    return false;
                }
                reloadMapProfileCacheLocked(appContext);
            }
        } catch (RuntimeException error) {
            AppEventLogger.event(appContext, FailureDiagnostics.format(
                    error, "map_profile", "save"));
            return false;
        }
        NavigatorMapCapture.refreshProfiles();
        AppEventLogger.event(appContext, "map_profile saved source=" + profile.source
                + " x=" + profile.x + " y=" + profile.y + " scale=" + profile.scale
                + " revision=" + mapProfileCacheRevision);
        publishSharedUiStateChange();
        return true;
    }

    public boolean composeDeleteMapProfile(HudMapProfile.Source source) {
        Context appContext = getApplicationContext();
        if (source == null) {
            AppEventLogger.event(appContext, "map_profile delete failed reason=missing-source");
            return false;
        }
        try {
            synchronized (MAP_PROFILE_CACHE_LOCK) {
                if (!HudMapProfiles.delete(appContext, source)) {
                    AppEventLogger.event(appContext, "map_profile delete rejected source="
                            + source + " reason=store-rejected");
                    return false;
                }
                reloadMapProfileCacheLocked(appContext);
            }
        } catch (RuntimeException error) {
            AppEventLogger.event(appContext, FailureDiagnostics.format(
                    error, "map_profile", "delete"));
            return false;
        }
        NavigatorMapCapture.refreshProfiles();
        AppEventLogger.event(appContext, "map_profile deleted source=" + source
                + " revision=" + mapProfileCacheRevision);
        publishSharedUiStateChange();
        return true;
    }

    public void composeStartMapProfile(HudMapProfile profile) {
        if (!activityResumed || destroyed || exitRequested
                || HudPrefs.mapSettings(this).mode == HudMapSettings.OFF
                || ShanghaiTestController.snapshot().isBusy()) return;
        NavHudLiveSender.startMapProfile(getApplicationContext(), profile);
    }

    public void composeUpdateMapProfile(HudMapProfile profile) {
        NavHudLiveSender.updateMapProfile(profile);
    }

    public void composeStopMapProfile(String reason) {
        NavHudLiveSender.stopMapProfile(reason == null ? "ui-stop" : reason);
    }

    private static List<NavigatorAssetManager.AssetSnapshot> localizedNavigatorAssetSnapshots(
            String language) {
        List<NavigatorAssetManager.AssetSnapshot> cached = navigatorAssetSnapshots;
        if (cached.isEmpty()) {
            return Collections.emptyList();
        }
        List<NavigatorAssetManager.AssetSnapshot> localized = new ArrayList<>(cached.size());
        for (NavigatorAssetManager.AssetSnapshot asset : cached) {
            localized.add(asset.localized(language));
        }
        return Collections.unmodifiableList(localized);
    }

    public void composeStartNavigatorAssetDownload(String assetId) throws Exception {
        NavigatorAssetManager.startDownload(this, assetId);
        requestPatchUiStateRefresh(this, true, "asset-download-start");
    }

    public boolean composeNavigatorAssetRequiresDestructiveConfirm(String assetId)
            throws Exception {
        return NavigatorAssetManager.requiresDestructiveConfirmation(this, assetId);
    }

    public void composeInstallNavigatorAsset(String assetId, boolean destructiveApproved)
            throws Exception {
        NavigatorAssetManager.install(this, assetId, destructiveApproved);
        requestPatchUiStateRefresh(this, true, "asset-install");
    }

    public void composeRestoreNavigatorAsset(String assetId) throws Exception {
        NavigatorAssetManager.restore(this, assetId);
        requestPatchUiStateRefresh(this, true, "asset-restore");
    }

    public String composePatchSourceDisplayName(Uri uri) {
        if (uri == null) return "selected";
        try (android.database.Cursor cursor = getContentResolver().query(
                uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String value = cursor.getString(index);
                    if (value != null && !value.trim().isEmpty()) return value.trim();
                }
            }
        } catch (RuntimeException ignored) {
            // The pipeline will report a source failure if the provider cannot be read.
        }
        return "selected";
    }

    public String composeSelectPatchSource(
            String profileId, Uri uri, String displayName) throws Exception {
        NavigatorPatchStore.Profile expected = NavigatorPatchStore.Profile.fromId(profileId);
        if (expected == null) throw new IllegalArgumentException("Unknown patch profile");
        String name = displayName == null || displayName.trim().isEmpty()
                ? "selected" : displayName.trim();
        NavigatorPatchStore.Profile actual = NavigatorPatchPipeline.inspectSelectedPackage(
                this, expected, uri, name);
        requestPatchUiStateRefresh(this, true, "patch-source-selected");
        return actual.id;
    }

    public void composeClearPatchSource(String profileId) {
        NavigatorPatchStore.Profile profile = NavigatorPatchStore.Profile.fromId(profileId);
        if (profile != null) {
            NavigatorPatchStore.clearExternal(this, profile);
            NavigatorPatchStore.transition(this, profile, NavigatorPatchStore.IDLE, "");
            requestPatchUiStateRefresh(this, true, "patch-source-cleared");
        }
    }

    public void composeCheckNavigatorPatch(String profileId) throws Exception {
        NavigatorPatchStore.Profile profile = NavigatorPatchStore.Profile.fromId(profileId);
        if (profile == null) throw new IllegalArgumentException("Unknown patch profile");
        NavigatorPatchPipeline.scanViaWorker(this, profile);
        requestPatchUiStateRefresh(this, true, "patch-scan");
    }

    public void composeApplyNavigatorPatch(String profileId, boolean destructiveApproved)
            throws Exception {
        NavigatorPatchStore.Profile profile = NavigatorPatchStore.Profile.fromId(profileId);
        if (profile == null) throw new IllegalArgumentException("Unknown patch profile");
        if (NavigatorPatchStore.awaitingDestructiveApproval(this, profile)) {
            if (!destructiveApproved) throw new NavigatorPatchStore.DestructiveApprovalRequiredException();
            try { NavigatorPackageInstaller.approveQueuedDestructiveInstall(this, profile); }
            finally { requestPatchUiStateRefresh(this, true, "patch-approval"); }
            return;
        }
        NavigatorPatchPipeline.PreparedPatch prepared =
                NavigatorPatchPipeline.prepareViaWorker(this, profile);
        if (prepared.destructive && !destructiveApproved) {
            NavigatorPatchPipeline.discardPrepared(this, prepared,
                    "Installed signer changed; destructive confirmation is required");
            throw new IllegalStateException(
                    "Installed signer changed; review the destructive warning and retry");
        }
        try {
            if (prepared.destructive) NavigatorPatchStore.approveDestructiveInstall(this, prepared);
            NavigatorPackageInstaller.begin(this, prepared);
        } catch (Exception error) {
            String phase = NavigatorPatchStore.operation(this, profile).phase;
            if (NavigatorPatchStore.isCancellationRequested(this, profile)
                    || NavigatorPatchStore.CANCEL_REQUESTED.equals(phase)
                    || NavigatorPatchStore.CANCELLED.equals(phase)
                    || error instanceof NavigatorPatchPipeline.OperationCancelledException) {
                if (!NavigatorPatchStore.CANCELLED.equals(phase)) {
                    NavigatorPatchPipeline.finishQueuedCancellation(
                            this, profile, "Patch installation cancelled");
                }
            } else {
                NavigatorPatchStore.transition(this, profile,
                        NavigatorPatchStore.requiresRecovery(this, profile)
                                ? NavigatorPatchStore.RECOVERY_REQUIRED
                                : NavigatorPatchStore.FAILED,
                        error.getMessage());
            }
            throw error;
        } finally {
            requestPatchUiStateRefresh(this, true, "patch-apply");
        }
    }

    public void composeRestoreNavigator(String profileId) throws Exception {
        NavigatorPatchStore.Profile profile = NavigatorPatchStore.Profile.fromId(profileId);
        if (profile == null) throw new IllegalArgumentException("Unknown patch profile");
        NavigatorPackageInstaller.beginRestore(this, profile);
        requestPatchUiStateRefresh(this, true, "patch-restore");
    }

    public boolean composeCancelNavigatorPatch(String profileId) {
        NavigatorPatchStore.Profile profile = NavigatorPatchStore.Profile.fromId(profileId);
        boolean cancelled = profile != null && NavigatorPatchPipeline.cancel(this, profile);
        if (cancelled) requestPatchUiStateRefresh(this, true, "patch-cancel");
        return cancelled;
    }

    public boolean composeDismissNavigatorPatch(String profileId) {
        NavigatorPatchStore.Profile profile = NavigatorPatchStore.Profile.fromId(profileId);
        boolean dismissed = profile != null && NavigatorPatchPipeline.dismiss(this, profile);
        if (dismissed) requestPatchUiStateRefresh(this, true, "patch-dismiss");
        return dismissed;
    }

    public boolean composeCanInstallNavigatorApks() {
        return NavigatorPackageInstaller.canInstall(this);
    }

    public void composeOpenNavigatorInstallPermission() {
        Intent intent = new Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    public boolean composeNavigatorPatchIsDestructive(String profileId) {
        NavigatorPatchStore.Profile profile = NavigatorPatchStore.Profile.fromId(profileId);
        if (profile != null && NavigatorPatchStore.awaitingDestructiveApproval(this, profile)) return true;
        if (profile == null || !NavigatorPackageInstaller.isInstalled(this, profile.packageName)) {
            return false;
        }
        return !NavigatorSigningKey.installedUsesLocalKey(this, profile.packageName);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private List<ActiveAppRow> activeRowsFromScan(List<NavAppTaskScanner.Row> scanRows) {
        List<ActiveAppRow> rows = new ArrayList<>();
        if (scanRows == null) {
            return rows;
        }
        for (NavAppTaskScanner.Row row : scanRows) {
            if (row == null || row.packageName == null || row.packageName.isEmpty()) {
                continue;
            }
            rows.add(new ActiveAppRow(
                    appLabel(row.packageName),
                    row.packageName,
                    row.processName,
                    row.importance,
                    row.hasProcess,
                    row.hasTask,
                    row.taskId,
                    row.displayId,
                    row.visible));
        }
        return rows;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private List<ComposeAppRow> composeRows(List<ActiveAppRow> apps, String hudPackage,
            Set<String> logOnlyPackages, Set<String> observedPackages, boolean supportedSection) {
        List<ComposeAppRow> rows = new ArrayList<>();
        for (ActiveAppRow app : apps) {
            rows.add(composeRow(app, hudPackage, logOnlyPackages, observedPackages, supportedSection));
        }
        return rows;
    }

    //renders this UI section here so screen structure stays traceable during preview and car testing.
    private ComposeAppRow composeRow(ActiveAppRow app, String hudPackage,
            Set<String> logOnlyPackages, Set<String> observedPackages, boolean supportedSection) {
        NavAppDisplayController controller = NavAppDisplayController.get(this);
        NavAppDisplayState displayState = controller.transferStateForUi(new NavAppDisplayState(
                app.packageName, app.taskId, app.displayId, app.visible, "task-scan"));
        DashboardProjectionPolicy.ObservedDisplay observedDisplay =
                DashboardProjectionPolicy.classifyObservedDisplay(
                        app.packageName,
                        displayState,
                        controller.confirmedDashboardPackage(),
                        controller.confirmedDashboardDisplayId());
        boolean runtimeBacked = app.isRuntimeBacked();
        boolean observed = isObservedPackage(app.packageName, observedPackages);
        boolean onDashboard = observedDisplay == DashboardProjectionPolicy.ObservedDisplay.DASHBOARD;
        boolean canReturnToMain = SteeringTransferPolicy.canReturnToMain(observedDisplay);
        boolean dashboardStateKnown = SteeringTransferPolicy.canToggleTask(displayState, observedDisplay);
        boolean supportedHud = isSupportedHudPackage(app.packageName);
        boolean installed = isInstalledPackage(app.packageName);
        return new ComposeAppRow(
                app.label,
                app.packageName,
                composePackageVersions(app.packageName, appVersionNames),
                installed,
                runtimeBacked,
                observed,
                supportedHud,
                supportedSection,
                isSelectedPackage(app.packageName, hudPackage),
                containsSelectedPackage(app.packageName, logOnlyPackages),
                onDashboard,
                canReturnToMain,
                dashboardStateKnown,
                controller.isMoveInProgress(),
                app.processName,
                app.importance);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private boolean isPackageInstalled(String packageName) {
        String normalized = normalizePackage(packageName);
        synchronized (PACKAGE_METADATA_CACHE_LOCK) {
            Boolean cached = INSTALLED_PACKAGE_CACHE.get(normalized);
            if (cached != null) {
                return cached;
            }
        }
        boolean installed;
        try {
            getPackageManager().getApplicationInfo(packageName, 0);
            installed = true;
        } catch (PackageManager.NameNotFoundException ignored) {
            installed = false;
        }
        synchronized (PACKAGE_METADATA_CACHE_LOCK) {
            INSTALLED_PACKAGE_CACHE.put(normalized, installed);
        }
        return installed;
    }

    private List<ComposeNavigatorPatchRow> composeNavigatorPatchRows() {
        return navigatorPatchRows;
    }

    private List<ComposePatchOperation> composePatchOperations() {
        List<ComposePatchOperation> result = new ArrayList<>();
        for (NavigatorPatchStore.OperationSnapshot value : NavigatorPatchStore.uiOperations()) {
            if (value == null || value.profile == null || value.kind.isEmpty()) continue;
            result.add(new ComposePatchOperation(
                    value.profile.id, value.kind, value.phase, value.detail,
                    value.operationToken, value.startedAt, value.progress, value.error,
                    value.readyAt, value.destructive, value.busy(),
                    NavigatorPatchStore.RECOVERY_REQUIRED.equals(value.phase),
                    NavigatorPatchStore.canCancel(value), value.terminal(), value.acknowledged,
                    value.awaitingDestructiveApproval));
        }
        result.sort((left, right) -> Long.compare(left.startedAt, right.startedAt));
        return Collections.unmodifiableList(result);
    }

    //keeps Google Maps variants as one UI target while leaving parser package support unchanged.
    private boolean isInstalledPackage(String packageName) {
        String normalized = normalizePackage(packageName);
        synchronized (PACKAGE_METADATA_CACHE_LOCK) {
            Boolean cached = INSTALLED_PACKAGE_CACHE.get(normalized);
            if (cached != null) {
                return cached;
            }
        }
        boolean installed = isGoogleMapsAlias(normalized)
                ? isPackageInstalled(GMAPS_REVANCED_PACKAGE)
                || isPackageInstalled(GMAPS_OFFICIAL_PACKAGE)
                : isPackageInstalled(normalized);
        synchronized (PACKAGE_METADATA_CACHE_LOCK) {
            INSTALLED_PACKAGE_CACHE.put(normalized, installed);
        }
        return installed;
    }

    private NavRuntimePermissionStatus navRuntimePermissionStatus() {
        synchronized (NAV_RUNTIME_PERMISSION_CACHE_LOCK) {
            if (cachedNavRuntimePermissionStatus != null) {
                return cachedNavRuntimePermissionStatus;
            }
            cachedNavRuntimePermissionStatus = NavRuntimePermissionStatus.check(this);
            return cachedNavRuntimePermissionStatus;
        }
    }

    private static AdbUiStatus cachedAdbUiStatus() {
        synchronized (NAV_RUNTIME_PERMISSION_CACHE_LOCK) {
            boolean available = cachedAdbKeySnapshotAvailable
                    && cachedAdbKeyAvailable
                    && cachedAdbAuthorizationStatusAvailable;
            return new AdbUiStatus(available,
                    available && cachedAdbAuthorizationKnown,
                    cachedAdbKeySnapshotAvailable ? cachedAdbKeyFingerprint : "",
                    available && cachedAdbKeyVerifiedThisProcess);
        }
    }

    private static boolean cachedAdbKeyVerifiedForCapture() {
        return cachedAdbUiStatus().verifiedThisProcess;
    }

    private static String cachedAdbKeyFingerprintForUi() {
        return cachedAdbUiStatus().fingerprint;
    }

    private static final class AdbUiStatus {
        final boolean available;
        final boolean authorized;
        final String fingerprint;
        final boolean verifiedThisProcess;

        private AdbUiStatus(boolean available, boolean authorized,
                String fingerprint, boolean verifiedThisProcess) {
            this.available = available;
            this.authorized = authorized;
            this.fingerprint = fingerprint == null ? "" : fingerprint;
            this.verifiedThisProcess = verifiedThisProcess;
        }
    }

    //keeps alias checks centralized so HUD/log row state cannot drift between Maps variants.
    private static boolean isSelectedPackage(String rowPackage, String selectedPackage) {
        String row = normalizePackage(rowPackage);
        String selected = normalizePackage(selectedPackage);
        if (isGoogleMapsAlias(row) && isGoogleMapsAlias(selected)) {
            return true;
        }
        return row.equals(selected);
    }

    //keeps multi-package row state aligned with persisted log-only package sets.
    private static boolean containsSelectedPackage(String rowPackage, Set<String> selectedPackages) {
        String row = normalizePackage(rowPackage);
        if (selectedPackages == null || selectedPackages.isEmpty()) {
            return false;
        }
        if (!isGoogleMapsAlias(row)) {
            return selectedPackages.contains(row);
        }
        return selectedPackages.contains(GMAPS_REVANCED_PACKAGE)
                || selectedPackages.contains(GMAPS_OFFICIAL_PACKAGE);
    }

    //keeps observed package evidence alias-aware for the collapsed Google Maps row.
    private static boolean isObservedPackage(String rowPackage, Set<String> observedPackages) {
        String row = normalizePackage(rowPackage);
        if (observedPackages == null || observedPackages.isEmpty()) {
            return false;
        }
        if (!isGoogleMapsAlias(row)) {
            return observedPackages.contains(row);
        }
        return observedPackages.contains(GMAPS_REVANCED_PACKAGE)
                || observedPackages.contains(GMAPS_OFFICIAL_PACKAGE);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private String hudStatus() {
        return HudDeliveryStatus.uiStatus();
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private String composeApplicationState(NavRuntimePermissionStatus permissionStatus) {
        String hudPackage = NavCapturePrefs.getHudPackage(this);
        return "HUD package: " + (hudPackage.isEmpty() ? "(none)" : hudPackage)
                + "\nLog-only: " + formatCapturePackages(NavCapturePrefs.getLogOnlyPackages(this))
                + "\nObserved: " + formatCapturePackages(NavCapturePrefs.getObservedPackages(this))
                + "\nRuntime service: "
                + HudRuntimeState.summary(this, SystemClock.elapsedRealtime())
                + "\nPermissions: " + permissionStatus.summary();
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    public void composeGrantAdb() {
        requestAdbPermissionGrant(
                "manual-grant-button",
                LocalAdbBridge.AuthorizationPromptMode.FORCE);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    public boolean composeShouldShowBackgroundReminder() {
        return HudPrefs.shouldShowBackgroundReminder(this);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    public void composeDismissBackgroundReminder() {
        HudPrefs.markBackgroundReminderSeen(this);
        maybeStartPendingAdbAuthorization();
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    public void composeOpenBackgroundSettingsFromReminder() {
        HudPrefs.markBackgroundReminderSeen(this);
        BgSettingsLauncher.open(this);
    }

    public void composeReportMainUiState(String blockingFlow) {
        mainUiReady = true;
        String normalizedFlow = vehiclePositionPermissionPending ? "vehicle-position-permission"
                : overlayPermissionFlow ? "overlay-permission" : normalizeBlockingUiFlow(blockingFlow);
        if (!normalizedFlow.equals(composeBlockingUiFlow)) {
            AppEventLogger.event(this, "adb_auto ui_blocker="
                    + (normalizedFlow.isEmpty() ? "none" : normalizedFlow)
                    + " state=" + autoAdbAuthorizationState);
        }
        composeBlockingUiFlow = normalizedFlow;
        if (composeBlockingUiFlow.isEmpty()) {
            maybeStartPendingAdbAuthorization();
        } else {
            cancelPendingAutoAdbStart();
        }
    }

    public boolean composeTryStartBlockingUiFlow(String flow) {
        String nextFlow = normalizeBlockingUiFlow(flow);
        if (nextFlow.isEmpty()) {
            return false;
        }
        if (!activityResumed || !activityWindowFocused || overlayPermissionFlow) {
            return false;
        }
        if (AdbAuthorizationUiPolicy.shouldCancelAuthorizationForFlow(
                autoAdbAuthorizationState,
                adbGrantInProgress
                        || LocalAdbBridge.isPermissionGrantInProgress()
                        || NavRuntimePermissionRepair.isRunning(),
                nextFlow)) {
            cancelActiveAdbAuthorization("hard-system-flow-" + nextFlow, true);
        }
        if (!composeBlockingUiFlow.isEmpty()
                && !"compose-starting".equals(composeBlockingUiFlow)
                && !nextFlow.equals(composeBlockingUiFlow)) {
            return false;
        }
        composeBlockingUiFlow = nextFlow;
        cancelPendingAutoAdbStart();
        return true;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    public void composeSetBootEnabled(boolean enabled) {
        setBootMode(enabled);
    }

    public void composeSetVehiclePositionEnabled(boolean enabled) {
        boolean requested = enabled && !exitRequested
                && !HudPrefs.isUserShutdownActive(this);
        if (!requested && vehiclePositionPermissionPending) vehiclePositionPermissionCancelled = true;
        if (requested) vehiclePositionPermissionAttempted = false;
        if (HudPrefs.isVehiclePositionEnabled(this) != requested) {
            HudPrefs.setVehiclePositionEnabled(this, requested);
            AppEventLogger.event(this, "ui vehicle_position=" + requested);
        }
        HudRuntimeService.refreshVehiclePosition(this);
        invalidateComposeSnapshot();
        if (requested) maybeRequestVehiclePositionPermission();
    }

    private void maybeRequestVehiclePositionPermission() {
        if (destroyed || exitRequested || !mainUiReady || !activityResumed || !activityWindowFocused
                || vehiclePositionPermissionPending || vehiclePositionPermissionAttempted
                || overlayPermissionFlow || !composeBlockingUiFlow.isEmpty()
                || adbGrantInProgress || LocalAdbBridge.isPermissionGrantInProgress()
                || NavRuntimePermissionRepair.isRunning() || HudPrefs.isUserShutdownActive(this)
                || !HudPrefs.isVehiclePositionEnabled(this)
                || HudRuntimeService.hasVehiclePositionPermissions(this)) return;
        vehiclePositionPermissionPending = true;
        vehiclePositionPermissionCancelled = false;
        vehiclePositionPermissionAttempted = true;
        composeBlockingUiFlow = "vehicle-position-permission";
        cancelPendingAutoAdbStart();
        try {
            // targetSdk29 supports this system choice; accept foreground-only/one-time grants too.
            vehiclePositionPermissionLauncher.launch(new String[] {
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                    android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
            });
            AppEventLogger.event(this, "vehicle_position permission_request");
        } catch (RuntimeException error) {
            vehiclePositionPermissionPending = false;
            composeBlockingUiFlow = "";
            AppEventLogger.event(this, "vehicle_position permission_request_failed error="
                    + error.getClass().getSimpleName());
        }
    }

    static boolean isUiResumed() {
        MainActivity activity = RESUMED_ACTIVITY.get();
        return activity != null && activity.activityResumed && !activity.destroyed;
    }

    //keeps this Compose helper focused so UI state changes remain easy to audit.
    public void composeSetDetailedDebugArtifactsEnabled(boolean enabled) {
        HudPrefs.setDetailedDebugArtifactsEnabled(this, enabled);
        TbtTxLog.onDetailedModeChanged(this, enabled);
        appendStatus("Detailed debug artifacts " + (enabled ? "enabled" : "disabled"));
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    public void composeSetPngOutputEnabled(boolean enabled) {
        setPngOutputEnabled(enabled);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    public void composeSetNativeOutputEnabled(boolean enabled) {
        setNativeOutputEnabled(enabled);
    }

    public void composeSetManeuverCompatibilityProfile(int profile) {
        HudPrefs.setManeuverCompatibilityProfile(this, profile);
        publishSharedUiStateChange();
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    public void composeSetLaneOutputEnabled(boolean enabled) {
        setLaneOutputEnabled(enabled);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    public void composeSetDistanceOutputEnabled(boolean enabled) {
        setDistanceOutputEnabled(enabled);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    public void composeSetStreetOutputEnabled(boolean enabled) {
        setStreetOutputEnabled(enabled);
    }

    public void composeSetTransliterationMode(int mode) {
        setTransliterationMode(mode);
    }

    public void composeSetTextDirectionOutputEnabled(boolean enabled) {
        setTextDirectionOutputEnabled(enabled);
    }

    public void composeSetWazeAlertsEnabled(boolean enabled) {
        setWazeAlertsEnabled(enabled);
    }

    public void composeSetWazeAlertField(int field) {
        HudPrefs.setWazeAlertField(this, field);
        AppEventLogger.event(this, "ui waze_alert_field=" + HudPrefs.wazeAlertField(this));
    }

    public void composeSetTbtWithoutHudOutputEnabled(boolean enabled) {
        if (enabled && !NavHudLiveSender.activateUserRuntime(this)) return;
        HudPrefs.setTbtWithoutHudOutputEnabled(this, enabled);
        NavHudLiveSender.get(this).refreshTbtObservers();
        appendStatus("TBT without HUD output " + (enabled ? "enabled" : "disabled"));
    }

    public void composeSetSwitchToTbtOnHudStartEnabled(boolean enabled) {
        HudPrefs.setSwitchToTbtOnHudStartEnabled(this, enabled);
        appendStatus("Switch to TBT on HUD start " + (enabled ? "enabled" : "disabled"));
    }


    public void composeSetRouteMetricsMode(int mode) {
        setRouteMetricsMode(mode);
    }

    public void composeSetEtaOutputMode(int mode) {
        setEtaOutputMode(mode);
    }

    public void composeSetEtaOutputField(int field) {
        HudPrefs.setEtaOutputField(this, field);
        AppEventLogger.event(this, "ui eta_output_field=" + HudPrefs.etaOutputField(this));
    }

    public void composeSetEtaStreetFormat(int format) {
        HudPrefs.setEtaStreetFormat(this, format);
        AppEventLogger.event(this, "ui eta_street_format=" + HudPrefs.getEtaStreetFormat(this));
    }

    public void composeSetEtaWaitForFullTextEnabled(boolean enabled) {
        HudPrefs.setEtaWaitForFullTextEnabled(this, enabled);
        AppEventLogger.event(this, "ui eta_wait_for_full_text=" + enabled);
    }

    public void composeSetEtaArrivalColor(int color) {
        HudPrefs.setEtaArrivalColor(this, color);
        AppEventLogger.event(this, "ui eta_arrival_color=" + HudPrefs.getEtaArrivalColor(this));
    }

    public void composeSetEtaDurationColor(int color) {
        HudPrefs.setEtaDurationColor(this, color);
        AppEventLogger.event(this, "ui eta_duration_color=" + HudPrefs.getEtaDurationColor(this));
    }

    public void composeSetEtaRemainingDistanceColor(int color) {
        HudPrefs.setEtaRemainingDistanceColor(this, color);
        AppEventLogger.event(this, "ui eta_remaining_distance_color="
                + HudPrefs.getEtaRemainingDistanceColor(this));
    }

    public void composeSetWazeWarningDistanceColor(int color) {
        HudPrefs.setWazeWarningDistanceColor(this, color);
        AppEventLogger.event(this, "ui waze_warning_distance_color="
                + HudPrefs.getWazeWarningDistanceColor(this));
    }

    public void composeSetEtaOutputEnabled(boolean enabled) {
        setEtaOutputEnabled(enabled);
    }

    public void composeSetRemainingTimeOutputEnabled(boolean enabled) {
        setRemainingTimeOutputEnabled(enabled);
    }

    public void composeSetRemainingDistanceOutputEnabled(boolean enabled) {
        setRemainingDistanceOutputEnabled(enabled);
    }

    public void composeSetSpeedLimitMode(int mode) {
        setSpeedLimitMode(mode);
    }

    public void composeSetNativeSpeedLimitDelayEnabled(boolean enabled) {
        setNativeSpeedLimitDelayEnabled(enabled);
    }

    public void composeSetNativeSpeedLimitFallbackMode(int mode) {
        setNativeSpeedLimitFallbackMode(mode);
    }

    public void composeSetSpeedLimitFreeFallback(int mode) {
        setSpeedLimitFreeFallback(mode);
    }

    public void composeSetSpeedLimitOverlaySeconds(int seconds) {
        setSpeedLimitOverlaySeconds(seconds);
    }

    public void composeSetSpeedLimitCompositePlacement(int placement) {
        setSpeedLimitCompositePlacement(placement);
    }

    public void composeSetSpeedLimitManeuverOverlaySize(int size) {
        setSpeedLimitManeuverOverlaySize(size);
    }

    public void composeSetSpeedLimitLaneOverlaySize(int size) {
        setSpeedLimitLaneOverlaySize(size);
    }

    public void composeSetMapMode(int mode) {
        saveMapSettings(HudPrefs.mapSettings(this).withMode(mode), "map-mode-change");
    }

    public void composeSetMapPreset(int preset) {
        saveMapSettings(HudPrefs.mapSettings(this).withPreset(preset), "map-preset-change");
    }

    public void composeSetMapUpdateRate(int hz) {
        saveMapSettings(HudPrefs.mapSettings(this).withUpdateRateHz(hz), "map-update-rate-change");
    }

    public void composeSetMapValue(int control, float value) {
        saveMapSettings(HudPrefs.mapSettings(this).withValue(control, value), "map-geometry-change");
    }

    public void composeStartMapLive() {
        if (!activityResumed || destroyed || exitRequested
                || ShanghaiTestController.snapshot().isBusy()) return;
        NavHudLiveSender.startMapLive(this);
    }

    public void composeStopMapLive() {
        NavHudLiveSender.stopMapLiveIfRunning("map-live-user-stop");
    }

    public void composeSetWazeCustomSurfaceEnabled(boolean enabled) {
        HudPrefs.setWazeCustomSurfaceEnabled(this, enabled);
        appendStatus("Waze custom surface " + (enabled ? "ON" : "OFF"));
        AppEventLogger.event(this, "ui waze_custom_surface=" + enabled);
    }

    public void composeSetDashboardScreenMode(int mode) {
        int persistedMode = HudPrefs.normalizeDashboardScreenMode(mode);
        HudPrefs.setDashboardScreenMode(this, persistedMode);
        appendStatus("Dashboard screen mode " + persistedMode);
    }

    public void composeSetDashboardKeepBlackBackgroundEnabled(boolean enabled) {
        HudPrefs.setDashboardKeepBlackBackgroundEnabled(this, enabled);
        AppEventLogger.event(this, "ui dashboard_keep_black_background=" + enabled);
        invalidateComposeSnapshot();
    }

    public void composeSetDashboardFormatMethod(int editedMode, int method) {
        int mode = HudPrefs.normalizeDashboardScreenMode(editedMode);
        if (mode == HudPrefs.DASHBOARD_MODE_NONE) {
            return;
        }
        HudPrefs.setDashboardFormatMethod(this, mode, method);
        int persistedMethod = HudPrefs.dashboardFormatMethod(this, mode);
        AppEventLogger.event(this, "ui dashboard_format_method mode=" + mode
                + " method=" + persistedMethod);
        invalidateComposeSnapshot();
    }

    public void composeSetDashboardWidthPercent(int editedMode, int percent) {
        int mode = HudPrefs.normalizeDashboardScreenMode(editedMode);
        if (mode == HudPrefs.DASHBOARD_MODE_NONE) {
            return;
        }
        HudPrefs.setDashboardWidthPercent(this, mode, percent);
        finishDashboardProfileChange(mode, "width",
                HudPrefs.dashboardProjectionProfile(this, mode).widthPercent);
    }

    public void composeSetDashboardHeightPercent(int editedMode, int percent) {
        int mode = HudPrefs.normalizeDashboardScreenMode(editedMode);
        if (mode == HudPrefs.DASHBOARD_MODE_NONE) {
            return;
        }
        HudPrefs.setDashboardHeightPercent(this, mode, percent);
        finishDashboardProfileChange(mode, "height",
                HudPrefs.dashboardProjectionProfile(this, mode).heightPercent);
    }

    public void composeSetDashboardOffsetPercent(int editedMode, int percent) {
        int mode = HudPrefs.normalizeDashboardScreenMode(editedMode);
        if (mode == HudPrefs.DASHBOARD_MODE_NONE) {
            return;
        }
        HudPrefs.setDashboardOffsetPercent(this, mode, percent);
        finishDashboardProfileChange(mode, "offset",
                HudPrefs.dashboardProjectionProfile(this, mode).offsetPercent);
    }

    public void composeSetDashboardScalePercent(int editedMode, int percent) {
        int mode = HudPrefs.normalizeDashboardScreenMode(editedMode);
        if (mode == HudPrefs.DASHBOARD_MODE_NONE) {
            return;
        }
        HudPrefs.setDashboardScalePercent(this, mode, percent);
        finishDashboardProfileChange(mode, "scale",
                HudPrefs.dashboardProjectionProfile(this, mode).scalePercent);
    }

    private void finishDashboardProfileChange(int mode, String field, int persistedValue) {
        ClusterProjectionService.applyDashboardProfile(this, mode,
                "options-" + field + "-release");
        appendStatus("Dashboard " + field + " " + persistedValue + "%");
        invalidateComposeSnapshot();
    }

    public void composeSetDashboardWidgetState(DashboardWidgetState state) {
        DashboardWidgetController.updateSettings(this, state);
    }

    public void composeRequestDashboardWidgetPermission() {
        overlayPermissionManualPending = true;
        maybeRequestOverlayPermission();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean("overlay-permission-flow", overlayPermissionFlow);
        outState.putBoolean("vehicle-position-permission-pending", vehiclePositionPermissionPending);
        outState.putBoolean("vehicle-position-permission-cancelled", vehiclePositionPermissionCancelled);
        outState.putBoolean("vehicle-position-permission-attempted", vehiclePositionPermissionAttempted);
        super.onSaveInstanceState(outState);
    }

    private void maybeRequestOverlayPermission() {
        handler.removeCallbacks(overlayPermissionRetry);
        if (destroyed || exitRequested || overlayPermissionFlow) return;
        android.content.SharedPreferences prefs = getSharedPreferences("overlay-permission", MODE_PRIVATE);
        boolean alreadyAsked = overlayPermissionAttempted || prefs.getBoolean("initial-setup-handled", false);
        if (!overlayPermissionManualPending && alreadyAsked) return;
        if (Settings.canDrawOverlays(this)) {
            overlayPermissionManualPending = false;
            prefs.edit().putBoolean("initial-setup-handled", true).apply();
            return;
        }
        boolean busy = adbGrantInProgress || LocalAdbBridge.isPermissionGrantInProgress()
                || NavRuntimePermissionRepair.isRunning();
        if (!AdbAuthorizationUiPolicy.canRequestOverlay(autoAdbAuthorizationState,
                mainUiReady, activityResumed, activityWindowFocused, composeBlockingUiFlow, busy)) {
            if (mainUiReady && activityResumed && activityWindowFocused
                    && composeBlockingUiFlow.isEmpty() && busy) {
                handler.postDelayed(overlayPermissionRetry, NAV_RUNTIME_RECHECK_DELAY_MS);
            }
            return;
        }
        overlayPermissionManualPending = false;
        overlayPermissionAttempted = true;
        overlayPermissionFlow = true;
        composeBlockingUiFlow = "overlay-permission";
        try {
            overlayPermissionLauncher.launch(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            prefs.edit().putBoolean("initial-setup-handled", true).apply();
            AppEventLogger.event(this, "overlay_permission opened");
        } catch (RuntimeException error) {
            overlayPermissionFlow = false;
            composeBlockingUiFlow = "";
            AppEventLogger.event(this, "overlay_permission open_failed error=" + error.getClass().getSimpleName());
        }
    }

    public boolean composeBeginSteeringButtonLearning() {
        boolean started = NavAccessibilityService.beginKeyLearning(this);
        if (!started) {
            Toast.makeText(
                    this,
                    "uk".equals(HudPrefs.uiLanguage(this))
                            ? "Служба спеціальних можливостей недоступна"
                            : "ru".equals(HudPrefs.uiLanguage(this))
                                    ? "Служба специальных возможностей недоступна"
                                    : "Accessibility service is unavailable",
                    Toast.LENGTH_LONG).show();
        }
        invalidateComposeSnapshot();
        return started;
    }

    public void composeCancelSteeringButtonLearning() {
        NavAccessibilityService.cancelKeyLearning();
        invalidateComposeSnapshot();
    }

    public boolean composeSaveSteeringTransferProfile(String id, int keyCode,
            String pressMode, String packageName, String windowProfile) {
        return SteeringTransferPreferences.saveProfile(this, new SteeringTransferProfile(
                id, keyCode, pressMode, packageName, windowProfile));
    }

    public boolean composeDeleteSteeringTransferProfile(String id) {
        return SteeringTransferPreferences.deleteProfile(this, id);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    public void composeSetSmallDistanceClamp(boolean enabled) {
        setSmallDistanceClamp(enabled);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.

    public void composeSetUiLanguage(String language) {
        HudPrefs.setUiLanguage(this, language);
        appendStatus("UI language " + HudPrefs.uiLanguage(this).toUpperCase(java.util.Locale.ROOT));
        DashboardWidgetController.refresh(this);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    public void composeSetDarkTheme(boolean dark) {
        setUiTheme(dark);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    public void composeSetStorageLimitGb(int value) {
        int current = HudPrefs.storageLimitGb(this);
        int next = Math.max(1, Math.min(10, value));
        if (next == current) {
            return;
        }
        HudPrefs.setStorageLimitGb(this, next);
        appendStatus("Storage limit " + HudPrefs.storageLimitGb(this) + " GB");
        if (!storageCacheState.hasCache()) {
            storageCacheState = storageCacheState.withCalculating(true);
        }
        NavigationLogStorage.forceNavCaptureRetention(
                this,
                "storage-limit-changed",
                () -> handler.post(() -> composeRequestStorageRefresh(true)));
    }

    //exposes one-folder deletion so Compose can show progress instead of blocking the UI.
    public ComposeDeleteDayResult composeDeleteStorageDay(String day) {
        if (day == null || !day.matches("\\d{8}")) {
            return new ComposeDeleteDayResult(day, false, false, "invalid day");
        }

        if (ShanghaiTestController.protectsStorageDay(day)) {
            return new ComposeDeleteDayResult(day, false, false, "Shanghai capture is using this day");
        }
        boolean restartLogcat = LogcatRecorder.isRecording()
                && day.equals(LogcatRecorder.activeStartDay());
        boolean logcatUsesDay = LogcatRecorder.hasSessionForDay(day);
        boolean logcatStopped = false;
        if (logcatUsesDay) {
            CountDownLatch logcatStop = new CountDownLatch(1);
            LogcatRecorder.stopAsync(this, logcatStop::countDown);
            try {
                if (!logcatStop.await(LOGCAT_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        || LogcatRecorder.hasSessionForDay(day)) {
                    return new ComposeDeleteDayResult(
                            day, false, false, "logcat did not stop; deletion aborted");
                }
                logcatStopped = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new ComposeDeleteDayResult(
                        day, false, false, "logcat stop wait interrupted; deletion aborted");
            }
        }

        NavigationLogStorage.DayRetirement retirement;
        LogcatRecorder.Result logcatRestart = null;
        try {
            retirement = NavigationLogStorage.retireStorageDay(this, day);
        } finally {
            if (restartLogcat && logcatStopped) {
                logcatRestart = LogcatRecorder.restartAfterRebase(this);
            }
        }
        if (!retirement.ok) {
            return new ComposeDeleteDayResult(
                    day,
                    false,
                    false,
                    appendLogcatRestartFailure(retirement.detail, logcatRestart));
        }

        CountDownLatch complete = new CountDownLatch(1);
        if (!NavigationLogStorage.deleteRetiredStorageDayAsync(retirement, complete::countDown)) {
            return new ComposeDeleteDayResult(day, false, false, "delete was not queued");
        }
        try {
            complete.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ComposeDeleteDayResult(day, false, false, "delete wait interrupted");
        }
        boolean deleted = true;
        for (File tombstone : retirement.tombstones) {
            deleted &= tombstone != null && !tombstone.exists();
        }
        return new ComposeDeleteDayResult(day, deleted, false,
                appendLogcatRestartFailure(
                        deleted ? "deleted" : "delete incomplete", logcatRestart));
    }

    //Runs one retained batch so Activity recreation cannot abandon the remaining selected days.
    public List<ComposeDeleteDayResult> composeDeleteStorageDays(List<String> days) {
        return composeDeleteStorageDays(days, null);
    }

    public List<ComposeDeleteDayResult> composeDeleteStorageDays(
            List<String> days,
            ComposeDeleteProgressListener progressListener) {
        if (days == null || days.isEmpty()) {
            return Collections.emptyList();
        }
        if (!STORAGE_DELETE_OPERATION.compareAndSet(false, true)) {
            return Collections.singletonList(new ComposeDeleteDayResult(
                    "", false, false, "delete already running"));
        }
        try {
            List<ComposeDeleteDayResult> results = new ArrayList<>();
            int step = 0;
            for (String day : days) {
                step++;
                if (progressListener != null) {
                    progressListener.onDay(day, step, days.size());
                }
                results.add(composeDeleteStorageDay(day));
            }
            return results;
        } finally {
            STORAGE_DELETE_OPERATION.set(false);
            requestStorageRefreshAfterMutation(this, "delete");
        }
    }

    private static String appendLogcatRestartFailure(
            String detail,
            LogcatRecorder.Result restart) {
        String base = detail == null ? "" : detail;
        return restart == null || restart.ok
                ? base
                : base + "; logcat restart failed: " + restart.detail;
    }

    //keeps long-running Compose workflows from reaching into legacy TextView internals directly.
    public void composeAppendStatus(String text) {
        appendStatus(text);
    }

    public void composeSetSnapshotInvalidationListener(Runnable listener) {
        composeSnapshotInvalidationListener = listener;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    public void composeRefreshApps() {
        requestRuntimeUiStateRefresh(this, true, "apps-manual");
    }


    public void composeRequestRuntimeUiStateRefresh(boolean force, String reason) {
        requestRuntimeUiStateRefresh(this, force, reason);
    }

    public void composeRequestPatchUiStateRefresh(String reason) {
        requestPatchUiStateRefresh(this, true, reason);
    }

    public void composeRequestPatchUiStateRefresh(boolean force, String reason) {
        requestPatchUiStateRefresh(this, force, reason);
    }

    public void composeRequestNavigatorAssetUiStateRefresh(String reason) {
        requestNavigatorAssetUiStateRefresh(this, reason);
    }

    static String appScanStatusForTest(boolean scanInProgress, String localStatus,
            NavAppTaskScanner.Snapshot snapshot) {
        String safeLocal = localStatus == null ? "" : localStatus;
        if (scanInProgress || snapshot == null || snapshot.scannedAtMs <= 0L
                || "initial".equals(snapshot.status)) {
            return safeLocal;
        }
        return "ok".equals(snapshot.status) ? "" : snapshot.status;
    }

    public String composeHudDeliveryStatus() {
        return HudDeliveryStatus.uiStatus();
    }

    private static int lowerCurrentThreadPriority() {
        int tid = Process.myTid();
        int previous = Process.getThreadPriority(tid);
        if (previous != Process.THREAD_PRIORITY_BACKGROUND) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        }
        return previous;
    }

    private static void restoreCurrentThreadPriority(int previous) {
        if (Process.getThreadPriority(Process.myTid()) != previous) {
            Process.setThreadPriority(previous);
        }
    }

    private void invalidateComposeSnapshot() {
        Runnable listener = composeSnapshotInvalidationListener;
        if (listener != null) {
            handler.post(listener);
        }
    }

    static void publishSharedUiStateChange() {
        MainActivity activity = RESUMED_ACTIVITY.get();
        if (activity != null && !activity.destroyed) {
            activity.invalidateComposeSnapshot();
        }
    }

    //starts or schedules work here so lifecycle recovery follows one controlled path.
    private void scheduleAppScan() {
        requestRuntimeUiStateRefresh(this, true, "activity-app-scan");
    }

    //uses the shared local-only status path for every Activity visibility transition.
    private void requestActivityLocalStatusRefresh(String reason) {
        requestRuntimeStatusRefresh(this, true, reason);
    }

    static void requestInitialUiStateRefresh(Context context, String reason) {
        requestMapProfileCacheRefresh(context);
        requestStorageRefresh(context, false, reason + "-storage");
        requestRuntimeStatusRefresh(context, false, reason + "-status");
        requestRuntimeUiStateRefresh(context, false, reason + "-runtime");
        requestPatchUiStateRefresh(context, false, reason + "-patch");
    }

    //boot/runtime service bootstrap keeps mutable status local-only; app scans belong to UI open.
    static void requestBackgroundUiStateRefresh(Context context, String reason) {
        requestStorageRefresh(context, false, reason + "-storage");
        requestRuntimeStatusRefresh(context, false, reason + "-status");
        requestPatchUiStateRefresh(context, false, reason + "-patch");
    }

    static void requestStorageRefreshAfterMutation(Context context, String reason) {
        requestStorageRefresh(context, true, "mutation-" + reason);
    }

    //refreshes only current key evidence and Android grants; this path never opens ADB.
    static void requestRuntimeStatusRefresh(Context context, boolean force, String reason) {
        Context appContext = context.getApplicationContext();
        long now = SystemClock.elapsedRealtime();
        boolean cacheFresh = cachedNavRuntimePermissionStatus != null
                && cachedAdbAuthorizationStatusAvailable
                && cachedAdbKeySnapshotAvailable
                && lastRuntimeStatusRefreshAtMs > 0L
                && now - lastRuntimeStatusRefreshAtMs < RUNTIME_STATUS_REFRESH_INTERVAL_MS;
        if (!force && cacheFresh) {
            return;
        }
        if (!RUNTIME_STATUS_REFRESH_IN_PROGRESS.compareAndSet(false, true)) {
            if (force) {
                RUNTIME_STATUS_REFRESH_PENDING.set(true);
            }
            return;
        }
        new Thread(() -> {
            try {
                NavRuntimePermissionStatus refreshed =
                        NavRuntimePermissionStatus.check(appContext);
                LocalAdbBridge.AuthorizationSnapshot adbSnapshot =
                        LocalAdbBridge.authorizationSnapshot(appContext);
                updateRuntimeStatusCache(refreshed, adbSnapshot);
                DashboardWidgetController.onRuntimePermissionsRefreshed(appContext);
                lastRuntimeStatusRefreshAtMs = SystemClock.elapsedRealtime();
                AppEventLogger.event(appContext, "ui_runtime_status_refresh reason=" + reason
                        + " adbAuthorized=" + adbSnapshot.authorized
                        + " adbKeyAvailable=" + adbSnapshot.keyAvailable
                        + " settingsGranted=" + refreshed.settingsGranted());
            } catch (RuntimeException error) {
                AppEventLogger.event(appContext, FailureDiagnostics.format(
                        error, "runtime_status", "refresh"));
                DashboardWidgetController.onRuntimePermissionsRefreshFailed(appContext);
            } finally {
                RUNTIME_STATUS_REFRESH_IN_PROGRESS.set(false);
                if (RUNTIME_STATUS_REFRESH_PENDING.getAndSet(false)) {
                    requestRuntimeStatusRefresh(appContext, true, "pending-force");
                }
            }
        }, "bydhud-runtime-status").start();
    }

    //Uses the existing asynchronous permission cache; widget UI does not query AppOps itself.
    static DashboardWidgetLifecyclePolicy.Permission cachedDashboardOverlayPermission() {
        NavRuntimePermissionStatus status = cachedNavRuntimePermissionStatus;
        if (status == null || status.settings == null) {
            return DashboardWidgetLifecyclePolicy.Permission.UNKNOWN;
        }
        return status.settings.dashboardOverlayEnabled
                ? DashboardWidgetLifecyclePolicy.Permission.GRANTED
                : DashboardWidgetLifecyclePolicy.Permission.DENIED;
    }

    //Publishes the same cache transition that the visible Compose snapshot must observe.
    static void updateRuntimeStatusCache(
            NavRuntimePermissionStatus refreshed,
            LocalAdbBridge.AuthorizationSnapshot adbSnapshot) {
        boolean changed;
        synchronized (NAV_RUNTIME_PERMISSION_CACHE_LOCK) {
            changed = !sameRuntimePermissionStatus(cachedNavRuntimePermissionStatus, refreshed)
                    || !cachedAdbAuthorizationStatusAvailable
                    || cachedAdbAuthorizationKnown != adbSnapshot.authorized
                    || !cachedAdbKeySnapshotAvailable
                    || cachedAdbKeyAvailable != adbSnapshot.keyAvailable
                    || cachedAdbKeyVerifiedThisProcess != adbSnapshot.verifiedThisProcess
                    || !Objects.equals(cachedAdbKeyFingerprint, adbSnapshot.fingerprint);
            cachedNavRuntimePermissionStatus = refreshed;
            cachedAdbAuthorizationKnown = adbSnapshot.authorized;
            cachedAdbAuthorizationStatusAvailable = adbSnapshot.keyAvailable;
            cachedAdbKeySnapshotAvailable = true;
            cachedAdbKeyAvailable = adbSnapshot.keyAvailable;
            cachedAdbKeyVerifiedThisProcess = adbSnapshot.verifiedThisProcess;
            cachedAdbKeyFingerprint = adbSnapshot.fingerprint;
        }
        if (changed) {
            ShellRuntimeSession.cacheStatus(refreshed,
                    adbSnapshot.keyAvailable && adbSnapshot.authorized);
            publishSharedUiStateChange();
        }
    }

    static void restoreRuntimeStatusCache(String checkpoint) {
        if (checkpoint == null || checkpoint.isEmpty()) return;
        try {
            org.json.JSONObject saved = new org.json.JSONObject(checkpoint);
            synchronized (NAV_RUNTIME_PERMISSION_CACHE_LOCK) {
                if (cachedNavRuntimePermissionStatus != null) return;
                cachedNavRuntimePermissionStatus = NavRuntimePermissionStatus.restore(saved);
                cachedAdbAuthorizationKnown = saved.optBoolean("adb");
                cachedAdbAuthorizationStatusAvailable = true;
            }
        } catch (Exception error) { Log.w(TAG, "Invalid runtime status checkpoint", error); }
    }

    static void restoreAppScanCache(Context context) {
        if (ShellRuntimeSession.prefs(context).getString("apps", "").isEmpty()) return;
        NavAppTaskScanner.Snapshot cached = NavAppTaskScanner.get(context).currentSnapshot();
        appScanCacheAvailable = !cached.rows.isEmpty();
        appScanStatus = "ok".equals(cached.status) ? "" : cached.status;
    }

    //keeps the cache comparison local so a status heartbeat publishes only actual changes.
    private static boolean sameRuntimePermissionStatus(
            NavRuntimePermissionStatus left, NavRuntimePermissionStatus right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        if (left.settings == null || right.settings == null) {
            return left.settings == right.settings
                    && left.notificationListenerConnected == right.notificationListenerConnected
                    && left.accessibilityServiceConnected == right.accessibilityServiceConnected
                    && left.accessibilityServiceCrashed == right.accessibilityServiceCrashed;
        }
        return left.settingsGranted() == right.settingsGranted()
                && left.notificationListenerConnected == right.notificationListenerConnected
                && left.accessibilityServiceConnected == right.accessibilityServiceConnected
                && left.accessibilityServiceCrashed == right.accessibilityServiceCrashed
                && left.settings.notificationListenerEnabled
                == right.settings.notificationListenerEnabled
                && left.settings.accessibilityServiceEnabled
                == right.settings.accessibilityServiceEnabled
                && left.settings.accessibilityMasterEnabled
                == right.settings.accessibilityMasterEnabled
                && left.settings.dashboardOverlayEnabled
                == right.settings.dashboardOverlayEnabled
                && left.settings.storageReadEnabled == right.settings.storageReadEnabled
                && left.settings.storageWriteEnabled == right.settings.storageWriteEnabled;
    }

    static void requestRuntimeUiStateRefresh(Context context, boolean force, String reason) {
        Context appContext = context.getApplicationContext();
        long now = SystemClock.elapsedRealtime();
        boolean cacheFresh = cachedNavRuntimePermissionStatus != null
                && lastRuntimeUiRefreshAtMs > 0L
                && now - lastRuntimeUiRefreshAtMs < RUNTIME_UI_REFRESH_INTERVAL_MS;
        if (!force && cacheFresh) {
            return;
        }
        if (!APP_SCAN_IN_PROGRESS.compareAndSet(false, true)) {
            if (force) {
                APP_FORCE_REFRESH_PENDING.set(true);
            }
            return;
        }
        appScanStatus = "scanning";
        if (!appScanCacheAvailable) {
            publishSharedUiStateChange();
        }
        new Thread(() -> {
            try {
                NavAppTaskScanner scanner = NavAppTaskScanner.get(appContext);
                long previousRevision = scanner.revision();
                NavAppTaskScanner.Snapshot scan = scanner.forceScanIfIdle();
                if (scan == null) {
                    for (int attempt = 0; attempt < 100
                            && scanner.revision() == previousRevision; attempt++) {
                        Thread.sleep(100L);
                    }
                    scan = scanner.currentSnapshot();
                }
                appVersionNames = scanInstalledAppVersions(appContext, scan);
                refreshPackageMetadataCache(appContext, scan);
                installedTransferApps = new InstalledTransferAppCatalog(appContext)
                        .load(installedTransferApps);
                synchronized (NAV_RUNTIME_PERMISSION_CACHE_LOCK) {
                    cachedNavRuntimePermissionStatus =
                            NavRuntimePermissionStatus.check(appContext);
                }
                appScanCacheAvailable = appScanCacheAvailable
                        || scan.hasAuthoritativeTaskState() || !scan.rows.isEmpty();
                appScanStatus = "ok".equals(scan.status) ? "" : scan.status;
                lastRuntimeUiRefreshAtMs = SystemClock.elapsedRealtime();
                AppEventLogger.event(appContext, "ui_runtime_refresh reason=" + reason
                        + " source=" + scan.source
                        + " rows=" + scan.rows.size()
                        + " status=" + scan.status);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                appScanStatus = "scan interrupted";
            } catch (RuntimeException e) {
                String detail = e.getMessage() == null ? "" : ": "
                        + e.getMessage().replace('\n', ' ').replace('\r', ' ');
                appScanStatus = "failed: " + e.getClass().getSimpleName()
                        + detail;
                AppEventLogger.event(appContext, "apps_refresh failed "
                        + e.getClass().getSimpleName());
            } finally {
                APP_SCAN_IN_PROGRESS.set(false);
                publishSharedUiStateChange();
                if (APP_FORCE_REFRESH_PENDING.getAndSet(false)) {
                    requestRuntimeUiStateRefresh(appContext, true, "pending-force");
                }
            }
        }, "bydhud-app-scan").start();
    }

    static void requestPatchUiStateRefresh(Context context, boolean force, String reason) {
        Context appContext = context.getApplicationContext();
        long now = SystemClock.elapsedRealtime();
        boolean cacheFresh = navigatorPatchRowsReady
                && lastPatchUiRefreshAtMs > 0L
                && now - lastPatchUiRefreshAtMs < PATCH_UI_REFRESH_INTERVAL_MS;
        if (!force && cacheFresh) {
            return;
        }
        synchronized (PATCH_REFRESH_GATE_LOCK) {
            if (ASSET_REFRESH_IN_PROGRESS.get()
                    || !PATCH_REFRESH_IN_PROGRESS.compareAndSet(false, true)) {
                if (force) {
                    PATCH_FORCE_REFRESH_PENDING.set(true);
                }
                return;
            }
            lastPatchUiRefreshAtMs = now;
        }
        new Thread(() -> {
            int previousPriority = lowerCurrentThreadPriority();
            try {
                NavAppTaskScanner.Snapshot scan =
                        NavAppTaskScanner.get(appContext).currentSnapshot();
                appVersionNames = scanInstalledAppVersions(appContext, scan);
                refreshPackageMetadataCache(appContext, scan);
                NavigatorPackageInstaller.reconcile(appContext);
                NavigatorAssetManager.reconcile(appContext);
                NavigatorAssetManager.refreshInstalledMatches(appContext);
                navigatorAssetSnapshots = NavigatorAssetManager.snapshots(appContext, false);
                navigatorPatchRows = scanNavigatorPatchRows(appContext);
                navigatorPatchRowsReady = true;
                for (NavigatorPatchStore.OperationSnapshot operation
                        : NavigatorPatchStore.operations(appContext)) {
                    if (NavigatorPatchStore.VERIFIED.equals(operation.phase)) {
                        NavigatorPatchPipeline.dismiss(appContext, operation.profile);
                    }
                }
                AppEventLogger.event(appContext, "ui_patch_refresh reason=" + reason
                        + " rows=" + navigatorPatchRows.size());
            } catch (RuntimeException e) {
                AppEventLogger.event(appContext, "ui_patch_refresh failed reason=" + reason
                        + " error=" + e.getClass().getSimpleName());
            } finally {
                try {
                    NavigatorPatchStore.refreshUiOperationCache(appContext);
                    synchronized (PATCH_REFRESH_GATE_LOCK) {
                        PATCH_REFRESH_IN_PROGRESS.set(false);
                    }
                    publishSharedUiStateChange();
                    if (PATCH_FORCE_REFRESH_PENDING.getAndSet(false)) {
                        requestPatchUiStateRefresh(appContext, true, "pending-force");
                    }
                    drainNavigatorAssetCompletionRefresh(appContext);
                } finally {
                    restoreCurrentThreadPriority(previousPriority);
                }
            }
        }, "BydHudPatchState").start();
    }

    static void requestNavigatorAssetUiStateRefresh(Context context, String reason) {
        requestNavigatorAssetUiStateRefresh(context, reason, false);
    }

    static void requestNavigatorAssetCompletionRefresh(Context context) {
        requestNavigatorAssetUiStateRefresh(context, "asset-completion", true);
    }

    private static void drainNavigatorAssetCompletionRefresh(Context context) {
        if (ASSET_FORCE_REFRESH_PENDING.getAndSet(false)) {
            requestNavigatorAssetCompletionRefresh(context);
        }
    }

    private static void requestNavigatorAssetUiStateRefresh(
            Context context, String reason, boolean force) {
        Context appContext = context.getApplicationContext();
        long now = SystemClock.elapsedRealtime();
        if (!force && lastAssetUiRefreshAtMs > 0L
                && now - lastAssetUiRefreshAtMs < ASSET_UI_REFRESH_INTERVAL_MS) {
            return;
        }
        synchronized (PATCH_REFRESH_GATE_LOCK) {
            if (PATCH_REFRESH_IN_PROGRESS.get()
                    || !ASSET_REFRESH_IN_PROGRESS.compareAndSet(false, true)) {
                if (force) ASSET_FORCE_REFRESH_PENDING.set(true);
                return;
            }
            lastAssetUiRefreshAtMs = now;
        }
        new Thread(() -> {
            int previousPriority = lowerCurrentThreadPriority();
            try {
                List<NavigatorAssetManager.AssetSnapshot> next =
                        NavigatorAssetManager.snapshots(appContext, false);
                if (!next.equals(navigatorAssetSnapshots)) {
                    navigatorAssetSnapshots = next;
                    AppEventLogger.event(appContext,
                            "ui_asset_refresh reason=" + reason);
                    publishSharedUiStateChange();
                }
            } catch (RuntimeException e) {
                AppEventLogger.event(appContext, "ui_asset_refresh failed reason=" + reason
                        + " error=" + e.getClass().getSimpleName());
            } finally {
                try {
                    synchronized (PATCH_REFRESH_GATE_LOCK) {
                        ASSET_REFRESH_IN_PROGRESS.set(false);
                    }
                    if (PATCH_FORCE_REFRESH_PENDING.getAndSet(false)) {
                        requestPatchUiStateRefresh(appContext, true, "pending-force");
                    }
                    drainNavigatorAssetCompletionRefresh(appContext);
                } finally {
                    restoreCurrentThreadPriority(previousPriority);
                }
            }
        }, "BydHudAssetState").start();
    }

    private static Map<String, String> scanInstalledAppVersions(
            Context context, NavAppTaskScanner.Snapshot scan) {
        Set<String> packages = new HashSet<>(NavAppFilter.curatedNavigationPackages());
        if (scan != null) {
            for (NavAppTaskScanner.Row row : scan.rows) {
                packages.add(row.packageName);
            }
        }
        Map<String, String> versions = new HashMap<>();
        PackageManager packageManager = context.getPackageManager();
        for (String packageName : packages) {
            try {
                PackageInfo packageInfo = packageManager.getPackageInfo(packageName, 0);
                versions.put(packageName, packageInfo.versionName == null ? "" : packageInfo.versionName);
            } catch (PackageManager.NameNotFoundException ignored) {
                // Packages can disappear between the task scan and metadata lookup.
            }
        }
        return Collections.unmodifiableMap(versions);
    }

    private static void refreshPackageMetadataCache(
            Context context, NavAppTaskScanner.Snapshot scan) {
        Set<String> packages = new HashSet<>(NavAppFilter.curatedNavigationPackages());
        if (scan != null) {
            for (NavAppTaskScanner.Row row : scan.rows) {
                packages.add(row.packageName);
            }
        }
        Map<String, String> labels = new HashMap<>();
        Map<String, Boolean> installed = new HashMap<>();
        PackageManager packageManager = context.getPackageManager();
        for (String packageName : packages) {
            String normalized = normalizePackage(packageName);
            try {
                ApplicationInfo info = packageManager.getApplicationInfo(packageName, 0);
                CharSequence label = packageManager.getApplicationLabel(info);
                labels.put(normalized,
                        label == null || label.length() == 0 ? packageName : label.toString());
                installed.put(normalized, true);
            } catch (PackageManager.NameNotFoundException ignored) {
                labels.put(normalized, packageName);
                installed.put(normalized, false);
            }
        }
        synchronized (PACKAGE_METADATA_CACHE_LOCK) {
            APP_LABEL_CACHE.clear();
            APP_LABEL_CACHE.putAll(labels);
            INSTALLED_PACKAGE_CACHE.clear();
            INSTALLED_PACKAGE_CACHE.putAll(installed);
        }
    }

    private static List<ComposeNavigatorPatchRow> scanNavigatorPatchRows(Context context) {
        List<ComposeNavigatorPatchRow> rows = new ArrayList<>();
        for (NavigatorPatchStore.Profile profile : NavigatorPatchStore.Profile.values()) {
            NavigatorPatchStore.ProfileSnapshot value =
                    NavigatorPatchStore.snapshot(context, profile);
            if (!value.installed && !value.externalSource) continue;
            rows.add(new ComposeNavigatorPatchRow(
                    profile.id, value.label, profile.packageName,
                    value.installedVersion, value.installed,
                    value.externalSource, value.sourceName, value.sourceVersion,
                    value.directState, value.gmsCoreState, profile.gmsCoreLabel,
                    value.optionalState, profile.optionalLabel,
                    value.alertState, profile.alertLabel,
                    value.reason, value.patchEnabled, value.mapState, value.mapReason));
        }
        return Collections.unmodifiableList(rows);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    public void composeSetHudForPackage(String packageName, boolean enabled) {
        setNavHudForPackage(packageName, enabled);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    public void composeSetLogOnlyForPackage(String packageName, boolean enabled) {
        setNavLogOnlyForPackage(packageName, enabled);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    public void composeToggleDashboard(String packageName) {
        toggleIndependentDashboardDisplay(packageName);
    }

    public ComposeStorageShareSummary composeDescribeStorageShareDays(List<String> days) {
        LogShareZip.SelectionSummary summary = LogShareZip.summarize(this, days);
        return new ComposeStorageShareSummary(summary.ok, summary.dayCount,
                summary.fileCount, summary.sourceBytes, summary.detail);
    }

    public boolean composeBeginStorageShare(List<String> days, boolean toDeveloper,
            int selectedFileCount, long selectedBytes, SentryLogReport report,
            int selectionRevision) {
        return StorageLogShareWorkflow.start(getApplicationContext(),
                immutableStorageDays(days), toDeveloper, selectedFileCount, selectedBytes,
                report, selectionRevision);
    }

    public boolean composeBeginStorageShare(List<String> days, ArchiveExportDestination destination,
            int selectedFileCount, long selectedBytes, SentryLogReport report,
            int selectionRevision) {
        return StorageLogShareWorkflow.startWithDestination(getApplicationContext(),
                immutableStorageDays(days), destination, selectedFileCount, selectedBytes,
                report, selectionRevision);
    }

    public void composeCancelStorageShare(String operationId) {
        StorageLogShareWorkflow.cancel(operationId);
    }

    public void composeDismissStorageShare(String operationId) {
        StorageLogShareWorkflow.dismiss(operationId);
    }

    public boolean composeShareOversizedStorageArchive(String operationId) {
        return StorageLogShareWorkflow.shareOversized(operationId);
    }

    public boolean composeBeginConfigurationExport() {
        return VehicleConfigurationExport.start(getApplicationContext());
    }

    public boolean composeBeginConfigurationExport(boolean saveAsAfterExport) {
        return VehicleConfigurationExport.startWithDestination(getApplicationContext(),
                saveAsAfterExport ? ArchiveExportDestination.SAVE_AS : ArchiveExportDestination.SHARE);
    }

    public boolean composeSaveConfigurationExport(String operationId) {
        return VehicleConfigurationExport.saveAsReady(operationId);
    }

    public void composeCancelConfigurationExport() { VehicleConfigurationExport.cancel(); }

    public void composeDismissConfigurationExport() { VehicleConfigurationExport.dismiss(); }

    public void composeShareConfigurationExport() { VehicleConfigurationExport.shareReady(); }

    static boolean claimShareOperation() { return SHARE_OPERATION.compareAndSet(false, true); }

    static void releaseShareOperation() { SHARE_OPERATION.set(false); }

    static void queueConfigurationShare(List<File> files, String operationId) {
        queuePendingShare(files, Collections.emptyList(), ShareOwner.CONFIGURATION, operationId);
    }

    static void queueStorageShare(File file, List<String> storageDays, String operationId) {
        queuePendingShare(Collections.singletonList(file), immutableStorageDays(storageDays), ShareOwner.STORAGE_LOGS,
                operationId);
    }

    static boolean queueArchiveDocumentSave(String operationId, boolean configuration,
            String displayName) {
        boolean accepted = ArchiveDocumentSave.queuePicker(operationId, configuration, displayName);
        if (accepted) notifyArchiveDocumentPicker();
        return accepted;
    }


    static void refreshAfterStorageShare(Context context, String reason) {
        requestStorageRefreshAfterMutation(context, reason);
    }

    private static List<String> immutableStorageDays(List<String> days) {
        if (days == null || days.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(days));
    }

    private static void queuePendingShare(List<File> files, List<String> storageDays,
            ShareOwner owner, String operationId) {
        if (files == null || files.isEmpty()) {
            return;
        }
        PENDING_SHARE.set(new PendingShare(
                files,
                SHARE_LAUNCH_SEQUENCE.incrementAndGet(),
                immutableStorageDays(storageDays),
                owner,
                operationId));
        notifyPendingShare();
    }

    //Delivers a completed archive to the current Activity after recreation or foreground return.
    private void deliverPendingShare() {
        PendingShare pending = PENDING_SHARE.get();
        if (pending == null) {
            return;
        }
        MainActivity current = RESUMED_ACTIVITY.get();
        if (destroyed || current != this) {
            if (current != null && current != this) {
                current.handler.post(current::deliverPendingShare);
            }
            return;
        }
        if ((pending.owner == ShareOwner.CONFIGURATION
                && !VehicleConfigurationExport.canShare(pending.operationId))
                || pending.files.stream().anyMatch(file -> !file.isFile())) {
            PENDING_SHARE.compareAndSet(pending, null);
            notifyShareFailed(pending, "Archive is missing", true);
            return;
        }
        try {
            ArrayList<android.net.Uri> uris = new ArrayList<>();
            for (File file : pending.files) {
                uris.add(FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file));
            }
            Intent send = new Intent(uris.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE)
                    .setType(uris.size() == 1 ? "application/zip" : "application/octet-stream")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (uris.size() == 1) send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            else send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            ClipData clips = ClipData.newRawUri(pending.files.get(0).getName(), uris.get(0));
            for (int index = 1; index < uris.size(); index++) clips.addItem(new ClipData.Item(uris.get(index)));
            send.setClipData(clips);
            startActivity(Intent.createChooser(send, null));
            PENDING_SHARE.compareAndSet(pending, null);
            boolean accepted = notifyShareLaunched(pending);
            if (accepted && pending.owner == ShareOwner.STORAGE_LOGS) {
                publishShareCompletion(pending.launchId, pending.storageDays);
            }
        } catch (RuntimeException e) {
            PENDING_SHARE.compareAndSet(pending, null);
            String detail = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
            notifyShareFailed(pending, detail, false);
            AppEventLogger.event(this, "share_chooser_failed error=" +
                    detail.replace('\n', ' ').replace('\r', ' '));
        }
    }

    private static boolean notifyShareLaunched(PendingShare pending) {
        if (pending.owner == ShareOwner.STORAGE_LOGS) {
            return StorageLogShareWorkflow.androidShareLaunched(pending.operationId);
        }
        if (pending.owner == ShareOwner.CONFIGURATION) {
            return VehicleConfigurationExport.androidShareLaunched(pending.operationId);
        }
        return false;
    }

    private static void notifyShareFailed(
            PendingShare pending, String detail, boolean archiveMissing) {
        if (pending.owner == ShareOwner.STORAGE_LOGS) {
            StorageLogShareWorkflow.androidShareFailed(pending.operationId, detail);
        } else if (pending.owner == ShareOwner.CONFIGURATION) {
            VehicleConfigurationExport.androidShareFailed(
                    pending.operationId, detail, archiveMissing);
        }
    }

    private static void publishShareCompletion(List<String> storageDays) {
        publishShareCompletion(SHARE_LAUNCH_SEQUENCE.incrementAndGet(), storageDays);
    }

    private static void publishShareCompletion(long eventId, List<String> storageDays) {
        SHARE_LAUNCH_EVENT.set(new ShareLaunchEvent(eventId, storageDays));
        publishSharedUiStateChange();
    }

    public void composeAcknowledgeShareLaunch(long launchId) {
        ShareLaunchEvent current = SHARE_LAUNCH_EVENT.get();
        if (current.id == launchId && launchId != 0L
                && SHARE_LAUNCH_EVENT.compareAndSet(current, ShareLaunchEvent.empty())) {
            publishSharedUiStateChange();
        }
    }

    private static void notifyPendingShare() {
        MainActivity current = RESUMED_ACTIVITY.get();
        if (current != null) {
            current.handler.post(current::deliverPendingShare);
        }
    }

    private static void notifyArchiveDocumentPicker() {
        MainActivity current = RESUMED_ACTIVITY.get();
        if (current != null) current.handler.post(current::launchPendingArchiveDocumentPicker);
    }

    private void restoreArchiveDocumentLaunchers() {
        for (ArchiveDocumentSave.PickerRequest orphan : ArchiveDocumentSave.orphanedPickers(this))
            registerArchiveDocumentLauncher(orphan);
        ArchiveDocumentSave.PickerRequest pending = ArchiveDocumentSave.pendingPicker(this);
        if (pending != null) {
            registerArchiveDocumentLauncher(pending);
            ArchiveDocumentSave.abandonPriorProcessPicker(this, pending);
        }
    }

    private ActivityResultLauncher<Intent> registerArchiveDocumentLauncher(
            ArchiveDocumentSave.PickerRequest request) {
        String key = ArchiveDocumentSave.activityResultKey(request);
        ActivityResultLauncher<Intent> existing = archiveDocumentLaunchers.get(key);
        if (existing != null) return existing;
        // The key belongs to this attempt, including results delivered after process recreation.
        ActivityResultLauncher<Intent> launcher = getActivityResultRegistry().register(key,
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    try { onArchiveDocumentResult(request, result); }
                    finally {
                        // register() can synchronously deliver a result before it returns its launcher.
                        handler.post(() -> {
                            ActivityResultLauncher<Intent> finished = archiveDocumentLaunchers.remove(key);
                            if (finished != null) finished.unregister();
                        });
                    }
                });
        archiveDocumentLaunchers.put(key, launcher);
        return launcher;
    }

    private void launchPendingArchiveDocumentPicker() {
        if (!activityResumed || destroyed || exitRequested || RESUMED_ACTIVITY.get() != this) return;
        ArchiveDocumentSave.PickerRequest pending = ArchiveDocumentSave.pendingPicker(this);
        if (pending == null || !"QUEUED".equals(pending.phase)
                || !ArchiveDocumentSave.processSession().equals(pending.processSession)) return;
        ActivityResultLauncher<Intent> launcher = registerArchiveDocumentLauncher(pending);
        if (!ArchiveDocumentSave.markPickerLaunched(this, pending)) return;
        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip")
                .putExtra(Intent.EXTRA_TITLE, pending.displayName)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            launcher.launch(create);
        } catch (RuntimeException failure) {
            ArchiveDocumentSave.clearPicker(this, pending);
            failArchiveDocumentSave(pending, "Unable to open destination picker");
            archiveDocumentLaunchers.remove(ArchiveDocumentSave.activityResultKey(pending));
            launcher.unregister();
            AppEventLogger.event(this, "archive_document_picker_launch_failed id=" + pending.id);
        }
    }

    private void onArchiveDocumentResult(ArchiveDocumentSave.PickerRequest pending, ActivityResult result) {
        Intent data = result.getData();
        Uri uri = data == null ? null : data.getData();
        int flags = data == null ? 0 : data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        ArchiveDocumentSave.abandonPriorProcessPicker(this, pending);
        if (!ArchiveDocumentSave.isCurrentPicker(this, pending)) {
            if (result.getResultCode() == RESULT_OK && uri != null
                    && ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
                ArchiveDocumentSave.cleanupOrphanedCreatedDocument(this, pending, uri, flags);
            } else ArchiveDocumentSave.clearOrphanedPicker(this, pending);
            return;
        }
        if (result.getResultCode() != RESULT_OK || uri == null) {
            ArchiveDocumentSave.clearPicker(this, pending);
            cancelArchiveDocumentSave(pending);
            return;
        }
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            ArchiveDocumentSave.clearPicker(this, pending);
            failArchiveDocumentSave(pending, "Destination provider returned an unsupported document");
            return;
        }
        if (!ArchiveDocumentSave.recordPickerResult(this, pending, uri, flags)
                || !ArchiveDocumentSave.persistPickerPermission(this, pending, uri, flags)) {
            ArchiveDocumentSave.cleanupCreatedDocument(this, pending, uri, 0, true);
            return;
        }
        boolean accepted = pending.configuration
                ? VehicleConfigurationExport.saveAsDestination(pending.id, uri)
                : StorageLogShareWorkflow.saveAsDestination(pending.id, uri);
        if (!accepted) {
            ArchiveDocumentSave.PickerRequest saved = ArchiveDocumentSave.pendingPicker(this);
            ArchiveDocumentSave.cleanupCreatedDocument(this, pending, uri,
                    saved == null ? 0 : saved.persistedFlags, true);
        }
    }

    static void showArchiveDocumentCleanupWarning(Context context) {
        Context app = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> {
            String language = HudPrefs.uiLanguage(app);
            String message = "uk".equals(language)
                    ? "Не вдалося видалити незавершений файл. Видаліть його вручну."
                    : "ru".equals(language)
                    ? "Не удалось удалить незавершённый файл. Удалите его вручную."
                    : "The incomplete file could not be removed. Delete it manually.";
            Toast.makeText(app, message, Toast.LENGTH_LONG).show();
        });
    }

    private static void cancelArchiveDocumentSave(ArchiveDocumentSave.PickerRequest pending) {
        if (pending.configuration) VehicleConfigurationExport.saveAsCancelled(pending.id);
        else StorageLogShareWorkflow.saveAsCancelled(pending.id);
    }

    private static void failArchiveDocumentSave(ArchiveDocumentSave.PickerRequest pending, String detail) {
        if (pending.configuration) VehicleConfigurationExport.saveAsFailed(pending.id, detail);
        else StorageLogShareWorkflow.saveAsFailed(pending.id, detail);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    public void composeStartLogcat() {
        if (ShanghaiTestController.snapshot().isBusy()) return;
        reportLogcatResult("start", LogcatRecorder.start(this));
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    public void composeStopLogcat() {
        if (ShanghaiTestController.snapshot().isBusy()) return;
        reportLogcatResult("stop", LogcatRecorder.stop(this));
    }

    //stops all active runtime work after explicit user shutdown so auto-start stays blocked until the next manual open.
    public void composeShutdownAndExit() {
        NavHudLiveSender.stopMapLiveIfRunning("map-live-shutdown");
        shutdownAndExit("ui-shutdown");
    }

    public void composeHudCheckSelectMode(HudCheckState.Mode mode) {
        if (ShanghaiTestController.snapshot().isBusy()) return;
        NavHudLiveSender.get(this).updateHudCheck(
                current -> current.selectMode(mode), "hud-check-mode");
    }

    public void composeHudCheckStep(HudCheckState.Field field, int delta) {
        NavHudLiveSender.get(this).updateHudCheck(
                current -> current.step(field, delta), "hud-check-step");
    }

    public void composeHudCheckSelectFormat(HudCheckState.Format format) {
        if (!activityResumed || destroyed || exitRequested || ShanghaiTestController.snapshot().isBusy()) return;
        NavHudLiveSender.get(this).updateHudCheck(
                current -> current.selectFormat(format), "hud-check-format");
    }

    public void composeHudCheckSelectCodeTable(HudCheckState.CodeTable table) {
        if (!activityResumed || destroyed || exitRequested || ShanghaiTestController.snapshot().isBusy()) return;
        NavHudLiveSender.get(this).updateHudCheck(
                current -> current.selectCodeTable(table), "hud-check-code-table");
    }

    public void composeHudCheckSelectChannel(HudCheckState.Channel channel) {
        if (!activityResumed || destroyed || exitRequested || ShanghaiTestController.snapshot().isBusy()) return;
        NavHudLiveSender.get(this).updateHudCheck(
                current -> current.selectChannel(channel), "hud-check-channel");
    }

    public void composeHudCheckSelectAddSomeIp(boolean enabled) {
        if (!activityResumed || destroyed || exitRequested || ShanghaiTestController.snapshot().isBusy()) return;
        NavHudLiveSender.get(this).updateHudCheck(
                current -> current.selectAddSomeIp(enabled), "hud-check-add-someip");
    }

    public void composeHudCheckSetDenzaOem(boolean enabled) {
        if (!activityResumed || destroyed || exitRequested || ShanghaiTestController.snapshot().isBusy()) return;
        NavHudLiveSender.get(this).updateHudCheck(
                current -> current.selectDenzaOem(enabled), "hud-check-denza-oem");
    }

    public void composeHudCheckToggleRunning() {
        if (ShanghaiTestController.snapshot().isBusy()) return;
        // A delayed visual click must not restart a test after the Activity left the foreground.
        if (!activityResumed || destroyed || exitRequested) return;
        if (!NavHudLiveSender.activateUserRuntime(this)) return;
        HudCheckState current = NavHudLiveSender.hudCheckSnapshot();
        if (current == null || !current.running) {
            NavHudLiveSender.stopMapLiveIfRunning("hud-check-start");
        }
        NavHudLiveSender.get(this).updateHudCheck(
                HudCheckState::toggleRun, "hud-check-run");
    }

    public void composeHudCheckSetAutomatic(boolean automatic) {
        NavHudLiveSender.get(this).updateHudCheck(
                current -> current.withAutomatic(automatic), "hud-check-auto-mode");
    }

    public void composeHudCheckSetManeuverBitmap(boolean bitmap) {
        NavHudLiveSender.get(this).updateHudCheck(
                current -> current.withManeuverBitmap(bitmap), "hud-check-maneuver-mode");
    }

    public void composeHudCheckSetLaneBitmap(boolean bitmap) {
        NavHudLiveSender.get(this).updateHudCheck(
                current -> current.withLaneBitmap(bitmap), "hud-check-lane-mode");
    }

    public void composeHudCheckSetTransliterate(boolean transliterate) {
        NavHudLiveSender.get(this).updateHudCheck(
                current -> current.withTransliterate(transliterate), "hud-check-transliteration");
    }

    public void composeHudCheckSetStreetTestDestination(boolean enabled) {
        updateHudCheckStreetTestField(
                current -> current.withStreetTestDestination(enabled), "hud-check-street-destination");
    }

    public void composeHudCheckSetStreetTestGuidePoint(boolean enabled) {
        updateHudCheckStreetTestField(
                current -> current.withStreetTestGuidePoint(enabled), "hud-check-street-guide-point");
    }

    public void composeHudCheckSetStreetTestMapState(boolean enabled) {
        updateHudCheckStreetTestField(
                current -> current.withStreetTestMapState(enabled), "hud-check-street-map-state");
    }

    public void composeHudCheckSetStreetTestRoadClass(boolean enabled) {
        updateHudCheckStreetTestField(
                current -> current.withStreetTestRoadClass(enabled), "hud-check-street-road-class");
    }

    private void updateHudCheckStreetTestField(UnaryOperator<HudCheckState> update, String reason) {
        if (!activityResumed || destroyed || exitRequested || RESUMED_ACTIVITY.get() != this
                || ShanghaiTestController.snapshot().isBusy()) return;
        NavHudLiveSender.get(this).updateHudCheck(current -> {
            if (!activityResumed || destroyed || exitRequested || RESUMED_ACTIVITY.get() != this
                    || ShanghaiTestController.snapshot().isBusy()
                    || current.mode != HudCheckState.Mode.BASIC) return current;
            return update.apply(current);
        }, reason);
    }

    public void composeHudCheckStepExtended(int delta) {
        NavHudLiveSender.get(this).updateHudCheck(
                current -> current.stepExtended(delta), "hud-check-extended-step");
    }

    public void composeHudCheckStop(String reason) {
        NavHudLiveSender.stopHudCheckIfRunning(reason);
    }

    public void composeShanghaiStart() {
        if (!activityResumed || destroyed || exitRequested) return;
        NavHudLiveSender.stopMapLiveIfRunning("shanghai-start");
        NavHudLiveSender.stopHudCheckIfRunning("shanghai-start");
        ShanghaiTestController.get(this).start();
    }

    public void composeShanghaiStop() {
        ShanghaiTestController.get(this).stop("user-stop", null);
    }

    public void composeShanghaiResetMockGps() {
        if (!activityResumed || destroyed || exitRequested) return;
        ShanghaiTestController.get(this).resetAnyMockGps();
    }

    //normalizes values here so malformed app text cannot leak into HUD payloads.
    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private enum ShareOwner { STORAGE_LOGS, CONFIGURATION }

    private static final class PendingShare {
        final List<File> files;
        final long launchId;
        final List<String> storageDays;
        final ShareOwner owner;
        final String operationId;

        PendingShare(List<File> files, long launchId, List<String> storageDays,
                ShareOwner owner, String operationId) {
            this.files = Collections.unmodifiableList(new ArrayList<>(files));
            this.launchId = launchId;
            this.storageDays = immutableStorageDays(storageDays);
            this.owner = owner;
            this.operationId = operationId == null ? "" : operationId;
        }
    }

    public static final class ShareLaunchEvent {
        public final long id;
        public final List<String> storageDays;

        ShareLaunchEvent(long id, List<String> storageDays) {
            this.id = Math.max(0L, id);
            this.storageDays = immutableStorageDays(storageDays);
        }

        static ShareLaunchEvent empty() {
            return new ShareLaunchEvent(0L, Collections.emptyList());
        }
    }

    //models ComposeSnapshot data here so transport and parser layers share a stable contract.
    public static final class ComposeSnapshot {
        public final String uiLanguage;
        public final boolean uaLanguage;
        public final boolean darkTheme;
        public final boolean bootEnabled;
        public final boolean vehiclePositionEnabled;
        public final boolean detailedDebugArtifactsEnabled;
        public final boolean pngOutputEnabled;
        public final boolean nativeOutputEnabled;
        public final int maneuverCompatibilityProfile;
        public final boolean laneOutputEnabled;
        public final boolean distanceOutputEnabled;
        public final boolean streetOutputEnabled;
        public final int transliterationMode;
        public final boolean textDirectionOutputEnabled;
        public final boolean wazeAlertsEnabled;
        public final int wazeAlertField;
        public final boolean tbtWithoutHudOutputEnabled;
        public final boolean switchToTbtOnHudStartEnabled;
        public final boolean wholeRouteMetricsEnabled;
        public final int etaOutputMode;
        public final int routeMetricsMode;
        public final int etaOutputField;
        public final int etaStreetFormat;
        public final boolean etaWaitForFullTextEnabled;
        public final int etaArrivalColor;
        public final int etaDurationColor;
        public final int etaRemainingDistanceColor;
        public final int wazeWarningDistanceColor;
        public final boolean etaOutputEnabled;
        public final boolean remainingTimeOutputEnabled;
        public final boolean remainingDistanceOutputEnabled;
        public final int speedLimitMode;
        public final boolean nativeSpeedLimitDelayEnabled;
        public final int nativeSpeedLimitFallbackMode;
        public final int speedLimitFreeFallback;
        public final int speedLimitOverlaySeconds;
        public final int speedLimitCompositePlacement;
        public final int speedLimitManeuverOverlaySize;
        public final int speedLimitLaneOverlaySize;
        public final HudMapSettings mapSettings;
        public final boolean wazeCustomSurfaceEnabled;
        public final int dashboardScreenMode;
        public final boolean dashboardKeepBlackBackground;
        public final int dashboardFormatMethod;
        public final int dashboardWidthPercent;
        public final int dashboardHeightPercent;
        public final int dashboardOffsetPercent;
        public final int dashboardScalePercent;
        public final DashboardWidgetState dashboardWidgetState;
        public final boolean dashboardWidgetOverlayPermission;
        public final List<InstalledTransferAppCatalog.Entry> steeringTransferApps;
        public final List<SteeringTransferProfile> steeringTransferProfiles;
        public final long steeringTransferRevision;
        public final boolean steeringButtonLearning;
        public final int steeringCapturedKeyCode;
        public final boolean smallDistanceClampEnabled;
        public final boolean settingsPermissionsGranted;
        public final boolean adbAuthorizationStatusAvailable;
        public final boolean adbAuthorized;
        public final boolean captureReady;
        public final String permissionSummary;
        public final String adbKeyFingerprint;
        public final String hudStatus;
        public final String hudPackage;
        public final String logOnlyPackages;
        public final String observedPackages;
        public final String activeDashboardPackage;
        public final boolean dashboardMoveInProgress;
        public final boolean logcatRecording;
        public final String logcatStatus;
        public final String logPaths;
        public final String applicationState;
        public final HudCheckState hudCheck;
        public final HudMapLiveState mapLive;
        public final ShanghaiTestState shanghai;
        public final String hudCheckStatus;
        public final String lastScanText;
        public final boolean appRuntimeStatusKnown;
        public final boolean appScanInProgress;
        public final boolean appScanCacheAvailable;
        public final String appScanStatus;
        public final int storageLimitGb;
        public final List<String> navCaptureFolderPaths;
        public final boolean storageCalculating;
        public final boolean storageCacheAvailable;
        public final String storageScanError;
        public final int storageSessionCount;
        public final long navCaptureFolderBytes;
        public final List<ComposeStorageDay> storageDays;
        public final List<ComposeAppRow> supportedApps;
        public final List<ComposeAppRow> allApps;
        public final List<ComposeNavigatorPatchRow> patchRows;
        public final List<NavigatorAssetManager.AssetSnapshot> navigatorAssets;
        public final List<ComposePatchOperation> patchOperations;
        public final ComposePatchOperation patchOperation;
        public final long shareLaunchId;
        public final List<String> shareLaunchDays;
        public final Map<HudMapProfile.Source, HudMapProfile> mapProfiles;
        public final long mapProfileRevision;
        public final Set<HudMapProfile.Source> installedMapProfileSources;
        public final HudMapProfileCalibrationState mapProfileCalibration;

        ComposeSnapshot(String uiLanguage, boolean darkTheme, boolean bootEnabled,
                boolean vehiclePositionEnabled,
                boolean detailedDebugArtifactsEnabled,
                boolean pngOutputEnabled, boolean nativeOutputEnabled,
                int maneuverCompatibilityProfile, boolean laneOutputEnabled,
                boolean distanceOutputEnabled, boolean streetOutputEnabled,
                int transliterationMode,
                boolean textDirectionOutputEnabled, boolean wazeAlertsEnabled,
                int wazeAlertField,
                boolean tbtWithoutHudOutputEnabled,
                boolean switchToTbtOnHudStartEnabled,
                boolean wholeRouteMetricsEnabled, int etaOutputMode,
                int routeMetricsMode, int etaOutputField,
                int etaStreetFormat, boolean etaWaitForFullTextEnabled,
                int etaArrivalColor, int etaDurationColor, int etaRemainingDistanceColor,
                int wazeWarningDistanceColor,
                boolean etaOutputEnabled, boolean remainingTimeOutputEnabled,
                boolean remainingDistanceOutputEnabled, int speedLimitMode,
                boolean nativeSpeedLimitDelayEnabled,
                int nativeSpeedLimitFallbackMode,
                int speedLimitFreeFallback, int speedLimitOverlaySeconds,
                int speedLimitCompositePlacement, int speedLimitManeuverOverlaySize,
                int speedLimitLaneOverlaySize, HudMapSettings mapSettings,
                boolean wazeCustomSurfaceEnabled,
                int dashboardScreenMode,
                boolean dashboardKeepBlackBackground,
                int dashboardFormatMethod,
                int dashboardWidthPercent, int dashboardHeightPercent,
                int dashboardOffsetPercent, int dashboardScalePercent,
                DashboardWidgetState dashboardWidgetState,
                boolean dashboardWidgetOverlayPermission,
                List<InstalledTransferAppCatalog.Entry> steeringTransferApps,
                List<SteeringTransferProfile> steeringTransferProfiles,
                long steeringTransferRevision, boolean steeringButtonLearning,
                int steeringCapturedKeyCode,
                boolean smallDistanceClampEnabled,
                boolean settingsPermissionsGranted,
                boolean adbAuthorizationStatusAvailable,
                boolean adbAuthorized,
                boolean captureReady, String permissionSummary, String adbKeyFingerprint,
                String hudStatus, String hudPackage, String logOnlyPackages,
                String observedPackages, String activeDashboardPackage,
                boolean dashboardMoveInProgress, boolean logcatRecording, String logcatStatus,
                String logPaths, String applicationState, HudCheckState hudCheck,
                HudMapLiveState mapLive,
                String hudCheckStatus, String lastScanText, boolean appRuntimeStatusKnown,
                boolean appScanInProgress, boolean appScanCacheAvailable, String appScanStatus,
                int storageLimitGb, List<String> navCaptureFolderPaths, boolean storageCalculating,
                boolean storageCacheAvailable, String storageScanError,
                int storageSessionCount, long navCaptureFolderBytes, List<ComposeStorageDay> storageDays,
                List<ComposeAppRow> supportedApps, List<ComposeAppRow> allApps,
                List<ComposeNavigatorPatchRow> patchRows,
                List<NavigatorAssetManager.AssetSnapshot> navigatorAssets,
                List<ComposePatchOperation> patchOperations,
                long shareLaunchId, List<String> shareLaunchDays,
                Map<HudMapProfile.Source, HudMapProfile> mapProfiles,
                long mapProfileRevision,
                Set<HudMapProfile.Source> installedMapProfileSources,
                HudMapProfileCalibrationState mapProfileCalibration) {
            this.uiLanguage = uiLanguage == null ? "uk" : uiLanguage;
            this.uaLanguage = "uk".equals(this.uiLanguage);
            this.darkTheme = darkTheme;
            this.bootEnabled = bootEnabled;
            this.vehiclePositionEnabled = vehiclePositionEnabled;
            this.detailedDebugArtifactsEnabled = detailedDebugArtifactsEnabled;
            this.pngOutputEnabled = pngOutputEnabled;
            this.nativeOutputEnabled = nativeOutputEnabled;
            this.maneuverCompatibilityProfile = ManeuverCompatibilityProfile.normalize(
                    maneuverCompatibilityProfile);
            this.laneOutputEnabled = laneOutputEnabled;
            this.distanceOutputEnabled = distanceOutputEnabled;
            this.streetOutputEnabled = streetOutputEnabled;
            this.transliterationMode = HudPrefs.normalizeTransliterationMode(transliterationMode);
            this.textDirectionOutputEnabled = textDirectionOutputEnabled;
            this.wazeAlertsEnabled = wazeAlertsEnabled;
            this.wazeAlertField = Math.max(HudPrefs.WAZE_ALERT_FIELD_MANEUVER,
                    Math.min(HudPrefs.WAZE_ALERT_FIELD_EXPERIMENTAL, wazeAlertField));
            this.tbtWithoutHudOutputEnabled = tbtWithoutHudOutputEnabled;
            this.switchToTbtOnHudStartEnabled = switchToTbtOnHudStartEnabled;
            this.wholeRouteMetricsEnabled = wholeRouteMetricsEnabled;
            this.etaOutputMode = etaOutputMode;
            this.routeMetricsMode = routeMetricsMode;
            this.etaOutputField = Math.max(HudPrefs.ETA_OUTPUT_FIELD_STREET,
                    Math.min(HudPrefs.ETA_OUTPUT_FIELD_EXPERIMENTAL, etaOutputField));
            this.etaStreetFormat = Math.max(HudPrefs.ETA_STREET_FORMAT_PREPEND,
                    Math.min(HudPrefs.ETA_STREET_FORMAT_REPLACE, etaStreetFormat));
            this.etaWaitForFullTextEnabled = etaWaitForFullTextEnabled;
            this.etaArrivalColor = etaArrivalColor | 0xFF000000;
            this.etaDurationColor = etaDurationColor | 0xFF000000;
            this.etaRemainingDistanceColor = etaRemainingDistanceColor | 0xFF000000;
            this.wazeWarningDistanceColor = wazeWarningDistanceColor | 0xFF000000;
            this.etaOutputEnabled = etaOutputEnabled;
            this.remainingTimeOutputEnabled = remainingTimeOutputEnabled;
            this.remainingDistanceOutputEnabled = remainingDistanceOutputEnabled;
            this.speedLimitMode = speedLimitMode;
            this.nativeSpeedLimitDelayEnabled = nativeSpeedLimitDelayEnabled;
            this.nativeSpeedLimitFallbackMode = HudPrefs.normalizeNativeSpeedLimitFallbackMode(
                    nativeSpeedLimitFallbackMode);
            this.speedLimitFreeFallback = speedLimitFreeFallback;
            this.speedLimitOverlaySeconds = speedLimitOverlaySeconds;
            this.speedLimitCompositePlacement = speedLimitCompositePlacement;
            this.speedLimitManeuverOverlaySize = speedLimitManeuverOverlaySize;
            this.speedLimitLaneOverlaySize = speedLimitLaneOverlaySize;
            this.mapSettings = mapSettings == null ? HudMapSettings.defaults() : mapSettings;
            this.wazeCustomSurfaceEnabled = wazeCustomSurfaceEnabled;
            this.dashboardScreenMode = HudPrefs.normalizeDashboardScreenMode(dashboardScreenMode);
            this.dashboardKeepBlackBackground = dashboardKeepBlackBackground;
            this.dashboardFormatMethod = HudPrefs.normalizeDashboardFormatMethod(
                    this.dashboardScreenMode, dashboardFormatMethod);
            this.dashboardWidthPercent = dashboardWidthPercent;
            this.dashboardHeightPercent = dashboardHeightPercent;
            this.dashboardOffsetPercent = dashboardOffsetPercent;
            this.dashboardScalePercent = dashboardScalePercent;
            this.dashboardWidgetState = dashboardWidgetState;
            this.dashboardWidgetOverlayPermission = dashboardWidgetOverlayPermission;
            this.steeringTransferApps = steeringTransferApps == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(steeringTransferApps));
            this.steeringTransferProfiles = steeringTransferProfiles == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(steeringTransferProfiles));
            this.steeringTransferRevision = Math.max(0L, steeringTransferRevision);
            this.steeringButtonLearning = steeringButtonLearning;
            this.steeringCapturedKeyCode = steeringCapturedKeyCode;
            this.smallDistanceClampEnabled = smallDistanceClampEnabled;
            this.settingsPermissionsGranted = settingsPermissionsGranted;
            this.adbAuthorizationStatusAvailable = adbAuthorizationStatusAvailable;
            this.adbAuthorized = adbAuthorized;
            this.captureReady = captureReady;
            this.permissionSummary = permissionSummary == null ? "" : permissionSummary;
            this.adbKeyFingerprint = adbKeyFingerprint == null ? "" : adbKeyFingerprint;
            this.hudStatus = hudStatus == null ? "idle" : hudStatus;
            this.hudPackage = hudPackage == null ? "" : hudPackage;
            this.logOnlyPackages = logOnlyPackages == null ? "" : logOnlyPackages;
            this.observedPackages = observedPackages == null ? "" : observedPackages;
            this.activeDashboardPackage = activeDashboardPackage == null ? "" : activeDashboardPackage;
            this.dashboardMoveInProgress = dashboardMoveInProgress;
            this.logcatRecording = logcatRecording;
            this.logcatStatus = logcatStatus == null ? "" : logcatStatus;
            this.logPaths = logPaths == null ? "" : logPaths;
            this.applicationState = applicationState == null ? "" : applicationState;
            this.hudCheck = hudCheck;
            this.mapLive = mapLive;
            this.shanghai = ShanghaiTestController.snapshot();
            this.hudCheckStatus = hudCheckStatus;
            this.lastScanText = lastScanText == null ? "--:--:--" : lastScanText;
            this.appRuntimeStatusKnown = appRuntimeStatusKnown;
            this.appScanInProgress = appScanInProgress;
            this.appScanCacheAvailable = appScanCacheAvailable;
            this.appScanStatus = appScanStatus == null ? "" : appScanStatus;
            this.storageLimitGb = Math.max(1, Math.min(10, storageLimitGb));
            this.navCaptureFolderPaths = navCaptureFolderPaths == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(navCaptureFolderPaths));
            this.storageCalculating = storageCalculating;
            this.storageCacheAvailable = storageCacheAvailable;
            this.storageScanError = storageScanError == null ? "" : storageScanError;
            this.storageSessionCount = Math.max(0, storageSessionCount);
            this.navCaptureFolderBytes = Math.max(0L, navCaptureFolderBytes);
            this.storageDays = storageDays == null ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(storageDays));
            this.supportedApps = supportedApps == null ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(supportedApps));
            this.allApps = allApps == null ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(allApps));
            this.patchRows = patchRows == null ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(patchRows));
            this.navigatorAssets = navigatorAssets == null ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(navigatorAssets));
            this.patchOperations = patchOperations == null ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(patchOperations));
            this.patchOperation = this.patchOperations.isEmpty()
                    ? ComposePatchOperation.idle()
                    : this.patchOperations.get(this.patchOperations.size() - 1);
            this.shareLaunchId = Math.max(0L, shareLaunchId);
            this.shareLaunchDays = shareLaunchDays == null ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(shareLaunchDays));
            this.mapProfiles = mapProfiles == null ? Collections.emptyMap()
                    : Collections.unmodifiableMap(new HashMap<>(mapProfiles));
            this.mapProfileRevision = Math.max(0L, mapProfileRevision);
            this.installedMapProfileSources = installedMapProfileSources == null
                    || installedMapProfileSources.isEmpty()
                    ? Collections.emptySet()
                    : Collections.unmodifiableSet(new HashSet<>(installedMapProfileSources));
            this.mapProfileCalibration = mapProfileCalibration;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof ComposeSnapshot)) return false;
            ComposeSnapshot other = (ComposeSnapshot) value;
            return Objects.equals(uiLanguage, other.uiLanguage)
                    && uaLanguage == other.uaLanguage
                    && darkTheme == other.darkTheme
                    && bootEnabled == other.bootEnabled
                    && vehiclePositionEnabled == other.vehiclePositionEnabled
                    && detailedDebugArtifactsEnabled == other.detailedDebugArtifactsEnabled
                    && pngOutputEnabled == other.pngOutputEnabled
                    && nativeOutputEnabled == other.nativeOutputEnabled
                    && maneuverCompatibilityProfile == other.maneuverCompatibilityProfile
                    && laneOutputEnabled == other.laneOutputEnabled
                    && distanceOutputEnabled == other.distanceOutputEnabled
                    && streetOutputEnabled == other.streetOutputEnabled
                    && transliterationMode == other.transliterationMode
                    && textDirectionOutputEnabled == other.textDirectionOutputEnabled
                    && wazeAlertsEnabled == other.wazeAlertsEnabled
                    && wazeAlertField == other.wazeAlertField
                    && tbtWithoutHudOutputEnabled == other.tbtWithoutHudOutputEnabled
                    && switchToTbtOnHudStartEnabled == other.switchToTbtOnHudStartEnabled
                    && wholeRouteMetricsEnabled == other.wholeRouteMetricsEnabled
                    && etaOutputMode == other.etaOutputMode
                    && routeMetricsMode == other.routeMetricsMode
                    && etaOutputField == other.etaOutputField
                    && etaStreetFormat == other.etaStreetFormat
                    && etaWaitForFullTextEnabled == other.etaWaitForFullTextEnabled
                    && etaArrivalColor == other.etaArrivalColor
                    && etaDurationColor == other.etaDurationColor
                    && etaRemainingDistanceColor == other.etaRemainingDistanceColor
                    && wazeWarningDistanceColor == other.wazeWarningDistanceColor
                    && etaOutputEnabled == other.etaOutputEnabled
                    && remainingTimeOutputEnabled == other.remainingTimeOutputEnabled
                    && remainingDistanceOutputEnabled == other.remainingDistanceOutputEnabled
                    && speedLimitMode == other.speedLimitMode
                    && nativeSpeedLimitDelayEnabled == other.nativeSpeedLimitDelayEnabled
                    && nativeSpeedLimitFallbackMode == other.nativeSpeedLimitFallbackMode
                    && speedLimitFreeFallback == other.speedLimitFreeFallback
                    && speedLimitOverlaySeconds == other.speedLimitOverlaySeconds
                    && speedLimitCompositePlacement == other.speedLimitCompositePlacement
                    && speedLimitManeuverOverlaySize == other.speedLimitManeuverOverlaySize
                    && speedLimitLaneOverlaySize == other.speedLimitLaneOverlaySize
                    && Objects.equals(mapSettings, other.mapSettings)
                    && Objects.equals(mapLive, other.mapLive)
                    && wazeCustomSurfaceEnabled == other.wazeCustomSurfaceEnabled
                    && dashboardScreenMode == other.dashboardScreenMode
                    && dashboardKeepBlackBackground == other.dashboardKeepBlackBackground
                    && dashboardFormatMethod == other.dashboardFormatMethod
                    && dashboardWidthPercent == other.dashboardWidthPercent
                    && dashboardHeightPercent == other.dashboardHeightPercent
                    && dashboardOffsetPercent == other.dashboardOffsetPercent
                    && dashboardScalePercent == other.dashboardScalePercent
                    && Objects.equals(dashboardWidgetState, other.dashboardWidgetState)
                    && dashboardWidgetOverlayPermission == other.dashboardWidgetOverlayPermission
                    && Objects.equals(steeringTransferApps, other.steeringTransferApps)
                    && Objects.equals(steeringTransferProfiles, other.steeringTransferProfiles)
                    && steeringTransferRevision == other.steeringTransferRevision
                    && steeringButtonLearning == other.steeringButtonLearning
                    && steeringCapturedKeyCode == other.steeringCapturedKeyCode
                    && smallDistanceClampEnabled == other.smallDistanceClampEnabled
                    && settingsPermissionsGranted == other.settingsPermissionsGranted
                    && adbAuthorizationStatusAvailable == other.adbAuthorizationStatusAvailable
                    && adbAuthorized == other.adbAuthorized
                    && captureReady == other.captureReady
                    && dashboardMoveInProgress == other.dashboardMoveInProgress
                    && logcatRecording == other.logcatRecording
                    && Objects.equals(hudCheck, other.hudCheck)
                    && Objects.equals(hudCheckStatus, other.hudCheckStatus)
                    && appRuntimeStatusKnown == other.appRuntimeStatusKnown
                    && appScanInProgress == other.appScanInProgress
                    && appScanCacheAvailable == other.appScanCacheAvailable
                    && storageLimitGb == other.storageLimitGb
                    && storageCalculating == other.storageCalculating
                    && storageCacheAvailable == other.storageCacheAvailable
                    && storageSessionCount == other.storageSessionCount
                    && navCaptureFolderBytes == other.navCaptureFolderBytes
                    && Objects.equals(permissionSummary, other.permissionSummary)
                    && Objects.equals(adbKeyFingerprint, other.adbKeyFingerprint)
                    && Objects.equals(hudStatus, other.hudStatus)
                    && Objects.equals(hudPackage, other.hudPackage)
                    && Objects.equals(logOnlyPackages, other.logOnlyPackages)
                    && Objects.equals(observedPackages, other.observedPackages)
                    && Objects.equals(activeDashboardPackage, other.activeDashboardPackage)
                    && Objects.equals(logcatStatus, other.logcatStatus)
                    && Objects.equals(logPaths, other.logPaths)
                    && Objects.equals(applicationState, other.applicationState)
                    && Objects.equals(lastScanText, other.lastScanText)
                    && Objects.equals(appScanStatus, other.appScanStatus)
                    && Objects.equals(navCaptureFolderPaths, other.navCaptureFolderPaths)
                    && Objects.equals(storageScanError, other.storageScanError)
                    && Objects.equals(storageDays, other.storageDays)
                    && Objects.equals(supportedApps, other.supportedApps)
                    && Objects.equals(allApps, other.allApps)
                    && Objects.equals(patchRows, other.patchRows)
                    && Objects.equals(navigatorAssets, other.navigatorAssets)
                    && Objects.equals(patchOperations, other.patchOperations)
                    && shareLaunchId == other.shareLaunchId
                    && Objects.equals(shareLaunchDays, other.shareLaunchDays)
                    && mapProfileRevision == other.mapProfileRevision
                    && Objects.equals(mapProfiles, other.mapProfiles)
                    && Objects.equals(installedMapProfileSources, other.installedMapProfileSources)
                    && Objects.equals(mapProfileCalibration, other.mapProfileCalibration);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    uiLanguage, uaLanguage, darkTheme, bootEnabled, vehiclePositionEnabled,
                    detailedDebugArtifactsEnabled,
                    pngOutputEnabled, nativeOutputEnabled, maneuverCompatibilityProfile,
                    laneOutputEnabled,
                    distanceOutputEnabled, streetOutputEnabled, transliterationMode,
                    textDirectionOutputEnabled,
                    wazeAlertsEnabled, wazeAlertField, tbtWithoutHudOutputEnabled,
                    switchToTbtOnHudStartEnabled, wholeRouteMetricsEnabled, etaOutputMode,
                    routeMetricsMode,
                    etaOutputField, etaStreetFormat, etaWaitForFullTextEnabled,
                    etaArrivalColor, etaDurationColor, etaRemainingDistanceColor,
                    wazeWarningDistanceColor,
                    etaOutputEnabled, remainingTimeOutputEnabled, remainingDistanceOutputEnabled,
                    speedLimitMode, nativeSpeedLimitDelayEnabled, nativeSpeedLimitFallbackMode,
                    speedLimitFreeFallback, speedLimitOverlaySeconds,
                    speedLimitCompositePlacement, speedLimitManeuverOverlaySize,
                    speedLimitLaneOverlaySize, mapSettings, mapLive, wazeCustomSurfaceEnabled,
                    dashboardScreenMode,
                    dashboardKeepBlackBackground,
                    dashboardFormatMethod, dashboardWidthPercent, dashboardHeightPercent,
                    dashboardOffsetPercent,
                    dashboardScalePercent, dashboardWidgetState, dashboardWidgetOverlayPermission,
                    steeringTransferApps, steeringTransferProfiles,
                    steeringTransferRevision, steeringButtonLearning, steeringCapturedKeyCode,
                    smallDistanceClampEnabled,
                    settingsPermissionsGranted, adbAuthorizationStatusAvailable,
                    adbAuthorized, captureReady,
                    permissionSummary, adbKeyFingerprint, hudStatus, hudPackage,
                    logOnlyPackages, observedPackages, activeDashboardPackage,
                    dashboardMoveInProgress, logcatRecording, logcatStatus, logPaths,
                    applicationState, hudCheck, hudCheckStatus,
                    lastScanText, appRuntimeStatusKnown, appScanInProgress,
                    appScanCacheAvailable, appScanStatus, storageLimitGb,
                    navCaptureFolderPaths, storageCalculating, storageCacheAvailable,
                    storageScanError, storageSessionCount, navCaptureFolderBytes, storageDays,
                    supportedApps, allApps, patchRows, navigatorAssets, patchOperations,
                    shareLaunchId, shareLaunchDays, mapProfiles, mapProfileRevision,
                    installedMapProfileSources, mapProfileCalibration);
        }
    }

    //models one storage deletion result so Compose can report progress without rescanning first.
    public static final class ComposeDeleteDayResult {
        public final String day;
        public final boolean deleted;
        public final boolean skippedActive;
        public final String message;

        ComposeDeleteDayResult(String day, boolean deleted, boolean skippedActive, String message) {
            this.day = day == null ? "" : day;
            this.deleted = deleted;
            this.skippedActive = skippedActive;
            this.message = message == null ? "" : message;
        }
    }

    //anchors ComposeStorageDay UI orchestration so controls and diagnostics are wired from one place.
    public static final class ComposeStorageDay {
        public final String name;
        public final String createdLabel;
        public final int sessions;
        public final long bytes;
        public final boolean active;
        public final boolean hasPublicStorage;
        public final boolean hasPrivateStorage;

        ComposeStorageDay(String name, String createdLabel, int sessions, long bytes,
                boolean active, boolean hasPublicStorage, boolean hasPrivateStorage) {
            this.name = name == null ? "" : name;
            this.createdLabel = createdLabel == null ? "" : createdLabel;
            this.sessions = Math.max(0, sessions);
            this.bytes = Math.max(0L, bytes);
            this.active = active;
            this.hasPublicStorage = hasPublicStorage;
            this.hasPrivateStorage = hasPrivateStorage;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof ComposeStorageDay)) return false;
            ComposeStorageDay other = (ComposeStorageDay) value;
            return sessions == other.sessions && bytes == other.bytes && active == other.active
                    && hasPublicStorage == other.hasPublicStorage
                    && hasPrivateStorage == other.hasPrivateStorage
                    && Objects.equals(name, other.name)
                    && Objects.equals(createdLabel, other.createdLabel);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, createdLabel, sessions, bytes, active,
                    hasPublicStorage, hasPrivateStorage);
        }
    }

    public interface ComposeDeleteProgressListener {
        void onDay(String day, int step, int total);
    }

    //keeps recursive Storage tab scan output immutable so Compose can read it without blocking.
    private static final class StorageCacheState {
        final long navCaptureFolderBytes;
        final List<ComposeStorageDay> storageDays;
        final List<String> rootPaths;
        final int totalSessions;
        final long scannedAtMs;
        final boolean calculating;
        final String scanError;

        private StorageCacheState(long navCaptureFolderBytes, int totalSessions,
                List<String> rootPaths, List<ComposeStorageDay> storageDays,
                long scannedAtMs, boolean calculating, String scanError) {
            this.navCaptureFolderBytes = Math.max(0L, navCaptureFolderBytes);
            this.storageDays = storageDays == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(storageDays));
            this.rootPaths = rootPaths == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(rootPaths));
            this.totalSessions = Math.max(0, totalSessions);
            this.scannedAtMs = Math.max(0L, scannedAtMs);
            this.calculating = calculating;
            this.scanError = scanError == null ? "" : scanError;
        }

        static StorageCacheState empty() {
            return new StorageCacheState(
                    0L, 0, Collections.emptyList(), Collections.emptyList(), 0L, false, "");
        }

        static StorageCacheState scanned(long navCaptureFolderBytes, int totalSessions,
                List<String> rootPaths, List<ComposeStorageDay> storageDays, long scannedAtMs) {
            return new StorageCacheState(navCaptureFolderBytes, totalSessions,
                    rootPaths, storageDays, scannedAtMs, false, "");
        }

        StorageCacheState withCalculating(boolean nextCalculating) {
            return new StorageCacheState(
                    navCaptureFolderBytes, totalSessions,
                    rootPaths, storageDays, scannedAtMs, nextCalculating,
                    nextCalculating ? "" : scanError);
        }

        StorageCacheState withScanError(String error) {
            return new StorageCacheState(
                    navCaptureFolderBytes, totalSessions,
                    rootPaths, storageDays, scannedAtMs, false, error);
        }

        boolean hasCache() {
            return scannedAtMs > 0L;
        }
    }

    public static final class ComposeNavigatorPatchRow {
        public final String profileId;
        public final String label;
        public final String packageName;
        public final String installedVersion;
        public final boolean installed;
        public final boolean externalSource;
        public final String sourceName;
        public final String sourceVersion;
        public final String directState;
        public final String gmsCoreState;
        public final String gmsCoreLabel;
        public final String optionalState;
        public final String optionalLabel;
        public final String alertState;
        public final String alertLabel;
        public final String reason;
        public final String mapState;
        public final String mapReason;
        public final boolean patchEnabled;

        ComposeNavigatorPatchRow(String profileId, String label, String packageName,
                String installedVersion, boolean installed, boolean externalSource,
                String sourceName, String sourceVersion, String directState,
                String optionalState, String optionalLabel, String alertState,
                String alertLabel, String reason, boolean patchEnabled) {
            this(profileId, label, packageName, installedVersion, installed, externalSource,
                    sourceName, sourceVersion, directState, NavigatorPatchStore.NOT_CHECKED,
                    "", optionalState, optionalLabel, alertState, alertLabel, reason,
                    patchEnabled, NavigatorPatchStore.NOT_CHECKED, "");
        }

        ComposeNavigatorPatchRow(String profileId, String label, String packageName,
                String installedVersion, boolean installed, boolean externalSource,
                String sourceName, String sourceVersion, String directState,
                String gmsCoreState, String gmsCoreLabel,
                String optionalState, String optionalLabel, String alertState,
                String alertLabel, String reason,
                boolean patchEnabled) {
            this(profileId, label, packageName, installedVersion, installed, externalSource,
                    sourceName, sourceVersion, directState, gmsCoreState, gmsCoreLabel,
                    optionalState, optionalLabel, alertState, alertLabel, reason,
                    patchEnabled, NavigatorPatchStore.NOT_CHECKED, "");
        }

        ComposeNavigatorPatchRow(String profileId, String label, String packageName,
                String installedVersion, boolean installed, boolean externalSource,
                String sourceName, String sourceVersion, String directState,
                String gmsCoreState, String gmsCoreLabel,
                String optionalState, String optionalLabel, String alertState,
                String alertLabel, String reason, boolean patchEnabled,
                String mapState, String mapReason) {
            this.profileId = profileId == null ? "" : profileId;
            this.label = label == null ? "" : label;
            this.packageName = packageName == null ? "" : packageName;
            this.installedVersion = installedVersion == null ? "" : installedVersion;
            this.installed = installed;
            this.externalSource = externalSource;
            this.sourceName = sourceName == null ? "" : sourceName;
            this.sourceVersion = sourceVersion == null ? "" : sourceVersion;
            this.directState = directState == null ? NavigatorPatchStore.NOT_CHECKED : directState;
            this.gmsCoreState = gmsCoreState == null ? NavigatorPatchStore.NOT_CHECKED : gmsCoreState;
            this.gmsCoreLabel = gmsCoreLabel == null ? "" : gmsCoreLabel;
            this.optionalState = optionalState == null ? NavigatorPatchStore.NOT_CHECKED : optionalState;
            this.optionalLabel = optionalLabel == null ? "" : optionalLabel;
            this.alertState = alertState == null ? NavigatorPatchStore.NOT_CHECKED : alertState;
            this.alertLabel = alertLabel == null ? "" : alertLabel;
            this.reason = reason == null ? "" : reason;
            this.mapState = mapState == null ? NavigatorPatchStore.NOT_CHECKED : mapState;
            this.mapReason = mapReason == null ? "" : mapReason;
            this.patchEnabled = patchEnabled;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof ComposeNavigatorPatchRow)) return false;
            ComposeNavigatorPatchRow other = (ComposeNavigatorPatchRow) value;
            return installed == other.installed && externalSource == other.externalSource
                    && patchEnabled == other.patchEnabled
                    && Objects.equals(profileId, other.profileId)
                    && Objects.equals(label, other.label)
                    && Objects.equals(packageName, other.packageName)
                    && Objects.equals(installedVersion, other.installedVersion)
                    && Objects.equals(sourceName, other.sourceName)
                    && Objects.equals(sourceVersion, other.sourceVersion)
                    && Objects.equals(directState, other.directState)
                    && Objects.equals(gmsCoreState, other.gmsCoreState)
                    && Objects.equals(gmsCoreLabel, other.gmsCoreLabel)
                    && Objects.equals(optionalState, other.optionalState)
                    && Objects.equals(optionalLabel, other.optionalLabel)
                    && Objects.equals(alertState, other.alertState)
                    && Objects.equals(alertLabel, other.alertLabel)
                    && Objects.equals(reason, other.reason)
                    && Objects.equals(mapState, other.mapState)
                    && Objects.equals(mapReason, other.mapReason);
        }

        @Override
        public int hashCode() {
            return Objects.hash(profileId, label, packageName, installedVersion, installed,
                    externalSource, sourceName, sourceVersion, directState, gmsCoreState,
                    gmsCoreLabel, optionalState, optionalLabel, alertState, alertLabel,
                    reason, mapState, mapReason, patchEnabled);
        }
    }

    public static final class ComposeStorageShareSummary {
        public final boolean ok;
        public final int dayCount;
        public final int fileCount;
        public final long sourceBytes;
        public final String detail;

        ComposeStorageShareSummary(boolean ok, int dayCount, int fileCount,
                long sourceBytes, String detail) {
            this.ok = ok;
            this.dayCount = Math.max(0, dayCount);
            this.fileCount = Math.max(0, fileCount);
            this.sourceBytes = Math.max(0L, sourceBytes);
            this.detail = detail == null ? "" : detail;
        }
    }

    public static final class ComposePatchOperation {
        public final String profileId;
        public final String kind;
        public final String phase;
        public final String detail;
        public final String operationToken;
        public final long startedAt;
        public final int progress;
        public final String error;
        public final long readyAt;
        public final boolean destructive;
        public final boolean busy;
        public final boolean recoveryRequired;
        public final boolean cancelAllowed;
        public final boolean terminal;
        public final boolean acknowledged;
        public final boolean approvalRequired;

        ComposePatchOperation(String profileId, String kind, String phase, String detail,
                String operationToken, long startedAt, int progress, String error, long readyAt,
                boolean destructive, boolean busy, boolean recoveryRequired,
                boolean cancelAllowed, boolean terminal) {
            this(profileId, kind, phase, detail, operationToken, startedAt, progress, error,
                    readyAt, destructive, busy, recoveryRequired, cancelAllowed, terminal, false);
        }

        ComposePatchOperation(String profileId, String kind, String phase, String detail,
                String operationToken, long startedAt, int progress, String error, long readyAt,
                boolean destructive, boolean busy, boolean recoveryRequired,
                boolean cancelAllowed, boolean terminal, boolean acknowledged) {
            this(profileId, kind, phase, detail, operationToken, startedAt, progress, error,
                    readyAt, destructive, busy, recoveryRequired, cancelAllowed, terminal, acknowledged, false);
        }

        ComposePatchOperation(String profileId, String kind, String phase, String detail,
                String operationToken, long startedAt, int progress, String error, long readyAt,
                boolean destructive, boolean busy, boolean recoveryRequired,
                boolean cancelAllowed, boolean terminal, boolean acknowledged, boolean approvalRequired) {
            this.profileId = profileId == null ? "" : profileId;
            this.kind = kind == null ? "" : kind;
            this.phase = phase == null ? NavigatorPatchStore.IDLE : phase;
            this.detail = detail == null ? "" : detail;
            this.operationToken = operationToken == null ? "" : operationToken;
            this.startedAt = startedAt;
            this.progress = Math.max(0, Math.min(100, progress));
            this.error = error == null ? "" : error;
            this.readyAt = readyAt;
            this.destructive = destructive;
            this.busy = busy;
            this.recoveryRequired = recoveryRequired;
            this.cancelAllowed = cancelAllowed;
            this.terminal = terminal;
            this.acknowledged = acknowledged;
            this.approvalRequired = approvalRequired;
        }

        static ComposePatchOperation idle() {
            return new ComposePatchOperation("", "", NavigatorPatchStore.IDLE, "",
                    "", 0L, 0, "", 0L, false, false, false, false, true);
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof ComposePatchOperation)) return false;
            ComposePatchOperation other = (ComposePatchOperation) value;
            return startedAt == other.startedAt && progress == other.progress
                    && readyAt == other.readyAt
                    && destructive == other.destructive && busy == other.busy
                    && recoveryRequired == other.recoveryRequired
                    && cancelAllowed == other.cancelAllowed && terminal == other.terminal
                    && acknowledged == other.acknowledged
                    && approvalRequired == other.approvalRequired
                    && Objects.equals(profileId, other.profileId)
                    && Objects.equals(kind, other.kind)
                    && Objects.equals(phase, other.phase)
                    && Objects.equals(detail, other.detail)
                    && Objects.equals(operationToken, other.operationToken)
                    && Objects.equals(error, other.error);
        }

        @Override
        public int hashCode() {
            return Objects.hash(profileId, kind, phase, detail, operationToken, startedAt,
                    progress, error, readyAt, destructive, busy, recoveryRequired,
                    cancelAllowed, terminal, acknowledged, approvalRequired);
        }
    }

    //anchors ComposeAppRow UI orchestration so controls and diagnostics are wired from one place.
    public static final class ComposeAppRow {
        public final String label;
        public final String packageName;
        public final List<ComposePackageVersion> packageVersions;
        public final boolean installed;
        public final boolean runtimeBacked;
        public final boolean observed;
        public final boolean supportedHud;
        public final boolean supportedSection;
        public final boolean hudEnabled;
        public final boolean logOnlyEnabled;
        public final boolean onDashboard;
        public final boolean canReturnToMain;
        public final boolean dashboardStateKnown;
        public final boolean dashboardMoveInProgress;
        public final String processName;
        public final int importance;

        ComposeAppRow(String label, String packageName, List<ComposePackageVersion> packageVersions,
                boolean installed,
                boolean runtimeBacked, boolean observed, boolean supportedHud,
                boolean supportedSection, boolean hudEnabled, boolean logOnlyEnabled,
                boolean onDashboard, boolean canReturnToMain, boolean dashboardStateKnown,
                boolean dashboardMoveInProgress, String processName, int importance) {
            this.label = label == null ? "" : label;
            this.packageName = packageName == null ? "" : packageName;
            this.packageVersions = packageVersions == null
                    ? Collections.<ComposePackageVersion>emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(packageVersions));
            this.installed = installed;
            this.runtimeBacked = runtimeBacked;
            this.observed = observed;
            this.supportedHud = supportedHud;
            this.supportedSection = supportedSection;
            this.hudEnabled = hudEnabled;
            this.logOnlyEnabled = logOnlyEnabled;
            this.onDashboard = onDashboard;
            this.canReturnToMain = canReturnToMain;
            this.dashboardStateKnown = dashboardStateKnown;
            this.dashboardMoveInProgress = dashboardMoveInProgress;
            this.processName = processName == null ? "" : processName;
            this.importance = importance;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof ComposeAppRow)) return false;
            ComposeAppRow other = (ComposeAppRow) value;
            return installed == other.installed && runtimeBacked == other.runtimeBacked
                    && observed == other.observed && supportedHud == other.supportedHud
                    && supportedSection == other.supportedSection
                    && hudEnabled == other.hudEnabled && logOnlyEnabled == other.logOnlyEnabled
                    && onDashboard == other.onDashboard
                    && canReturnToMain == other.canReturnToMain
                    && dashboardStateKnown == other.dashboardStateKnown
                    && dashboardMoveInProgress == other.dashboardMoveInProgress
                    && importance == other.importance
                    && Objects.equals(label, other.label)
                    && Objects.equals(packageName, other.packageName)
                    && Objects.equals(packageVersions, other.packageVersions)
                    && Objects.equals(processName, other.processName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(label, packageName, packageVersions, installed, runtimeBacked,
                    observed, supportedHud, supportedSection, hudEnabled, logOnlyEnabled,
                    onDashboard, canReturnToMain, dashboardStateKnown, dashboardMoveInProgress, processName,
                    importance);
        }
    }

    public static final class ComposePackageVersion {
        public final String packageName;
        public final String versionName;

        ComposePackageVersion(String packageName, String versionName) {
            this.packageName = packageName == null ? "" : packageName;
            this.versionName = versionName == null ? "" : versionName;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof ComposePackageVersion)) return false;
            ComposePackageVersion other = (ComposePackageVersion) value;
            return Objects.equals(packageName, other.packageName)
                    && Objects.equals(versionName, other.versionName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(packageName, versionName);
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private String formatCapturePackages(Set<String> packages) {
        if (packages == null || packages.isEmpty()) {
            return "none";
        }
        StringBuilder builder = new StringBuilder();
        for (String packageName : packages) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(packageName);
        }
        return builder.toString();
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private List<ActiveAppRow> loadCuratedApps(
            List<ActiveAppRow> rawApps,
            Set<String> capturePackages,
            Set<String> observedPackages) {
        Map<String, ActiveAppRow> rawRows = new HashMap<>();
        for (ActiveAppRow row : rawApps) {
            rawRows.put(row.packageName, row);
        }
        Set<String> candidates = new HashSet<>(NavAppFilter.curatedNavigationPackages());
        candidates.addAll(capturePackages);
        candidates.addAll(observedPackages);
        List<ActiveAppRow> curated = new ArrayList<>();
        ActiveAppRow googleMapsRow = googleMapsCuratedRow(rawRows, candidates, observedPackages);
        if (googleMapsRow != null) {
            curated.add(googleMapsRow);
        }
        for (String packageName : candidates) {
            String normalized = normalizePackage(packageName);
            if (isGoogleMapsAlias(normalized)) {
                continue;
            }
            if (!NavAppFilter.isCuratedNavigationPackage(normalized)
                    || NavAppFilter.shouldHideFromCaptureList(this, normalized)) {
                continue;
            }
            ActiveAppRow existing = rawRows.get(normalized);
            if (existing != null) {
                curated.add(existing);
            } else {
                boolean observed = observedPackages.contains(normalized);
                curated.add(new ActiveAppRow(
                        appLabel(normalized),
                        normalized,
                        observed ? "observed" : "not running",
                        Integer.MAX_VALUE));
            }
        }
        Collections.sort(curated, new Comparator<ActiveAppRow>() {
            @Override
            //keeps this step explicit so callers can rely on one documented behavior boundary.
            public int compare(ActiveAppRow left, ActiveAppRow right) {
                return left.label.compareToIgnoreCase(right.label);
            }
        });
        return curated;
    }

    //collapses official and ReVanced Google Maps into one supported row with one action package.
    private ActiveAppRow googleMapsCuratedRow(
            Map<String, ActiveAppRow> rawRows,
            Set<String> candidates,
            Set<String> observedPackages) {
        if (candidates == null
                || (!candidates.contains(GMAPS_REVANCED_PACKAGE)
                && !candidates.contains(GMAPS_OFFICIAL_PACKAGE))) {
            return null;
        }
        ActiveAppRow row = firstRuntimeGoogleMapsRow(rawRows);
        if (row != null) {
            return googleMapsRowFrom(row);
        }
        String observed = observedPackages != null && observedPackages.contains(GMAPS_REVANCED_PACKAGE)
                ? GMAPS_REVANCED_PACKAGE
                : observedPackages != null && observedPackages.contains(GMAPS_OFFICIAL_PACKAGE)
                ? GMAPS_OFFICIAL_PACKAGE
                : "";
        if (!observed.isEmpty()) {
            return new ActiveAppRow("Google Maps", observed, "observed", Integer.MAX_VALUE);
        }
        String installed = isPackageInstalled(GMAPS_REVANCED_PACKAGE)
                ? GMAPS_REVANCED_PACKAGE
                : isPackageInstalled(GMAPS_OFFICIAL_PACKAGE)
                ? GMAPS_OFFICIAL_PACKAGE
                : GMAPS_REVANCED_PACKAGE;
        return new ActiveAppRow("Google Maps", installed, "not running", Integer.MAX_VALUE);
    }

    //prefers the active ReVanced task when both Google Maps variants are present.
    private static ActiveAppRow firstRuntimeGoogleMapsRow(Map<String, ActiveAppRow> rawRows) {
        if (rawRows == null || rawRows.isEmpty()) {
            return null;
        }
        ActiveAppRow revanced = rawRows.get(GMAPS_REVANCED_PACKAGE);
        ActiveAppRow official = rawRows.get(GMAPS_OFFICIAL_PACKAGE);
        if (revanced != null && revanced.isRuntimeBacked()) {
            return revanced;
        }
        if (official != null && official.isRuntimeBacked()) {
            return official;
        }
        return revanced != null ? revanced : official;
    }

    //keeps the displayed label stable while preserving the resolved action package/display state.
    private static ActiveAppRow googleMapsRowFrom(ActiveAppRow source) {
        return new ActiveAppRow(
                "Google Maps",
                source.packageName,
                source.processName,
                source.importance,
                source.hasProcess,
                source.hasTask,
                source.taskId,
                source.displayId,
                source.visible);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static List<ComposePackageVersion> composePackageVersions(
            String packageName,
            Map<String, String> versions) {
        String normalized = normalizePackage(packageName);
        Map<String, String> safeVersions = versions == null
                ? Collections.<String, String>emptyMap()
                : versions;
        List<ComposePackageVersion> result = new ArrayList<>();
        if (!isGoogleMapsAlias(normalized)) {
            result.add(new ComposePackageVersion(normalized, safeVersions.get(normalized)));
            return result;
        }
        if (safeVersions.containsKey(GMAPS_OFFICIAL_PACKAGE)) {
            result.add(new ComposePackageVersion(
                    GMAPS_OFFICIAL_PACKAGE,
                    safeVersions.get(GMAPS_OFFICIAL_PACKAGE)));
        }
        if (safeVersions.containsKey(GMAPS_REVANCED_PACKAGE)) {
            result.add(new ComposePackageVersion(
                    GMAPS_REVANCED_PACKAGE,
                    safeVersions.get(GMAPS_REVANCED_PACKAGE)));
        }
        if (result.isEmpty()) {
            result.add(new ComposePackageVersion(GMAPS_OFFICIAL_PACKAGE, ""));
            result.add(new ComposePackageVersion(GMAPS_REVANCED_PACKAGE, ""));
        }
        return result;
    }

    //The row is cached; the controller resolves this toggle from its fresh task lookup.
    private void toggleIndependentDashboardDisplay(String packageName) {
        String normalized = normalizePackage(packageName);
        if (normalized.isEmpty()) {
            appendStatus("display move ignored: empty package");
            return;
        }
        NavAppDisplayController controller = NavAppDisplayController.get(this);
        if (controller.isMoveInProgress()) {
            appendStatus("display move already running");
            return;
        }
        controller.requestUiToggle(normalized, "ui-independent-dashboard-explicit");
        appendStatus("display transfer requested for " + normalized);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private void setNavHudForPackage(String packageName, boolean enabled) {
        String normalized = normalizePackage(packageName);
        if (normalized.isEmpty()) {
            appendStatus("nav capture ignored: empty package");
            return;
        }
        if (NavAppFilter.shouldHideFromCaptureList(this, normalized)) {
            appendStatus("nav capture ignored: system package " + normalized);
            return;
        }
        if (enabled && !isSupportedHudPackage(normalized)) {
            NavCapturePrefs.setLogOnlyEnabled(this, normalized, true);
            NavCapturePrefs.setHudEnabled(this, normalized, false);
            NavNotificationListenerService.requestActiveNotificationScan(
                    this, "unsupported-start-hud-" + normalized);
            appendStatus("unsupported app: logging only " + normalized);
            NavCaptureStore.rawEvent(this, "unsupported_start_hud", normalized,
                    "Start HUD requested; logging only");
            return;
        }
        String previousHudPackage = NavCapturePrefs.getHudPackage(this);
        if (enabled && !NavHudLiveSender.activateUserRuntime(this)) return;
        if (enabled && !previousHudPackage.isEmpty() && !previousHudPackage.equals(normalized)) {
            returnPreviousHudAppToMain(previousHudPackage, normalized);
            appendStatus("nav live HUD switched off: " + previousHudPackage);
        }
        if (enabled && hudOutput.isBound()) {
            stopImmediately("nav-hud-ui-start", false, true);
        }
        NavCapturePrefs.setHudEnabled(this, normalized, enabled);
        appendStatus("nav HUD " + (enabled ? "start " : "stop ") + normalized);
        AppEventLogger.event(this, "nav_hud " + (enabled ? "start " : "stop ") + normalized);
        if (enabled) {
            NavHudLiveSender.get(this).start(normalized, "ui-start");
            NavNotificationListenerService.requestActiveNotificationScan(
                    this, "ui-start-hud-" + normalized);
            appendStatus("nav live HUD start: waiting for navigation payload");
            NavHudLiveSender.get(this).refreshTbtObservers();
        } else {
            NavHudLiveSender.get(this).demoteHudToTbtObserver(
                    normalized, "ui-stop", true);
            appendStatus("nav live HUD stop requested");
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private void returnPreviousHudAppToMain(String previousPackage, String nextPackage) {
        String previous = normalizePackage(previousPackage);
        if (previous.isEmpty()) {
            return;
        }
        NavAppDisplayController controller = NavAppDisplayController.get(this);
        appendStatus("return previous HUD app to main: " + previous);
        controller.moveToMain(previous, "hud-switch-to-" + normalizePackage(nextPackage));
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean isSupportedHudPackage(String packageName) {
        return NavCapturePrefs.isSupportedHudPackage(packageName);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private void setNavLogOnlyForPackage(String packageName, boolean enabled) {
        String normalized = normalizePackage(packageName);
        if (normalized.isEmpty()) {
            appendStatus("nav log-only ignored: empty package");
            return;
        }
        if (NavAppFilter.shouldHideFromCaptureList(this, normalized)) {
            appendStatus("nav log-only ignored: system package " + normalized);
            return;
        }
        if (enabled && !ensureNavCaptureRuntimeReadyForStart("log-only", normalized)) {
            return;
        }
        boolean demoteHudToObserver = enabled
                && NavCapturePrefs.isHudEnabled(this, normalized);
        NavCapturePrefs.setLogOnlyEnabled(this, normalized, enabled);
        appendStatus("nav log-only " + (enabled ? "start " : "stop ") + normalized);
        AppEventLogger.event(this, "nav_log_only " + (enabled ? "start " : "stop ") + normalized);
        if (enabled) {
            NavNotificationListenerService.requestActiveNotificationScan(
                    this, "ui-start-log-only-" + normalized);
        }
        if (demoteHudToObserver) {
            NavHudLiveSender.get(this).demoteHudToTbtObserver(
                    normalized, "ui-log-only", true);
        } else {
            NavHudLiveSender.get(this).refreshTbtObservers();
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private String appLabel(String packageName) {
        String normalized = normalizePackage(packageName);
        synchronized (PACKAGE_METADATA_CACHE_LOCK) {
            String cached = APP_LABEL_CACHE.get(normalized);
            if (cached != null) {
                return cached;
            }
        }
        PackageManager packageManager = getPackageManager();
        String resolved = packageName;
        try {
            ApplicationInfo info = packageManager.getApplicationInfo(packageName, 0);
            CharSequence label = packageManager.getApplicationLabel(info);
            if (label != null && label.length() > 0) {
                resolved = label.toString();
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            //guards label lookup because packages can disappear between process listing and UI rendering.
        }
        synchronized (PACKAGE_METADATA_CACHE_LOCK) {
            APP_LABEL_CACHE.put(normalized, resolved);
        }
        return resolved;
    }

    //normalizes values here so malformed app text cannot leak into HUD payloads.
    private static String normalizePackage(String packageName) {
        return packageName == null ? "" : packageName.trim().toLowerCase(Locale.ROOT);
    }

    //keeps the two supported Google Maps package names behind one UI row.
    private static boolean isGoogleMapsAlias(String packageName) {
        String normalized = normalizePackage(packageName);
        return GMAPS_REVANCED_PACKAGE.equals(normalized)
                || GMAPS_OFFICIAL_PACKAGE.equals(normalized);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private String filePath(java.io.File file) {
        return file == null ? "" : file.getAbsolutePath();
    }

    //opens the external boundary here so connection setup remains observable and retryable.
    private void stopImmediately(String reason, boolean clearHud, boolean unbindClient) {
        stopImmediately(reason, clearHud, unbindClient, null);
    }

    private void stopImmediately(String reason, boolean clearHud, boolean unbindClient,
            Runnable completion) {
        if ("send-error".equals(reason)) {
            HudDeliveryStatus.recordFailure();
        } else {
            HudDeliveryStatus.reset();
        }
        NavHudLiveSender sender = NavHudLiveSender.get(this);
        String stopReason = reason + (clearHud ? "-clear" : "");
        if (unbindClient) {
            sender.stopManual(stopReason, false, () -> {
                if (!sender.isRunning()) hudOutput.shutdown(reason);
                if (completion != null) handler.post(completion);
            });
        } else {
            sender.stopManual(stopReason, false,
                    () -> { if (completion != null) handler.post(completion); });
        }
    }

    private void stopRecorderAsync(String action, Runnable continuation) {
        ShanghaiTestController.get(this).stop(action, () -> stopRecorderAfterShanghai(action, continuation));
    }

    private void stopRecorderAfterShanghai(String action, Runnable continuation) {
        boolean hadSession = !LogcatRecorder.activeStartDay().isEmpty();
        LogcatRecorder.stopAsync(this, () -> {
            if (hadSession) {
                appendStatus("logcat saved on " + action + " " + LogcatRecorder.statusText());
            }
            if (continuation != null) {
                continuation.run();
            }
        });
    }

    //stops or releases work here so stale capture and HUD output cannot keep running silently.

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private void shutdownAndExit(String reason) {
        if (exitRequested) {
            return;
        }
        String safeReason = reason == null ? "shutdown" : reason;
        appendStatus("shutdown requested reason=" + safeReason);
        NavHudLiveSender.stopMapProfile("shutdown");
        exitRequested = true;
        RuntimeUiSession.PROCESS.clear();
        UserRuntimeSession.PROCESS.shutdown();
        HudPrefs.setUserShutdownActive(this, true);
        UpdateHintManager.shutdown();
        AppUpdateManager.resetForShutdown();
        VehicleConfigurationExport.shutdown();
        StorageLogShareWorkflow.shutdown();
        NavAppDisplayController.get(this).cancelWidgetModeForShutdown();
        DashboardWidgetController.shutdown(this);
        AppEventLogger.event(this, "shutdown requested reason=" + safeReason);
        NavNotificationListenerService.suspendForUserShutdown(this, safeReason);
        NavAccessibilityService.suspendForUserShutdown(this, safeReason);

        if (pendingNavPermissionSelfCheckRunnable != null) {
            handler.removeCallbacks(pendingNavPermissionSelfCheckRunnable);
            pendingNavPermissionSelfCheckRunnable = null;
        }
        cancelPendingAutoAdbStart();
        cancelActiveAdbAuthorization("shutdown-" + safeReason, false);
        autoAdbAuthorizationState = AdbAuthorizationUiPolicy.AutoState.IDLE;

        String hudPackage = NavCapturePrefs.getHudPackage(this);
        NavHudLiveSender sender = NavHudLiveSender.get(this);
        NavAppDisplayController.get(this).shutdownDashboardProjection(safeReason);
        sender.stop(hudPackage, safeReason, true, () -> handler.post(() -> {
            sender.refreshTbtObservers();
            stopRecorderAsync("shutdown", () -> {
                stopImmediately(safeReason, true, true, () -> {
                    HudRuntimeWatchdog.cancel(this);
                    HudRuntimeService.stopPersistent(this, safeReason);
                    HudPrefs.setRuntimeServiceRunning(this, false);
                    HudRuntimeState.markStopped(this, safeReason);
                    ShellRuntimeSession.afterPersistence(() -> handler.post(this::finishAfterStop));
                });
            });
        }));
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private void finishAfterStop() {
        finishAndRemoveTask();
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private void setBootMode(boolean enabled) {
        HudPrefs.setBootEnabled(this, enabled);
        appendStatus("Boot " + (enabled ? "ON" : "OFF"));
        AppEventLogger.event(this, "ui boot=" + enabled);
        BootCleanupGate.runWhenReady(this,
                () -> HudRuntimeSupervisor.ensureStarted(this, enabled ? "boot-on" : "boot-off"));
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private void setSmallDistanceClamp(boolean enabled) {
        HudPrefs.setSmallDistanceClampEnabled(this, enabled);
        appendStatus("Small distance clamp " + (enabled
                ? "ON: HUD displays 11m for active 0..10m"
                : "OFF: HUD sends raw 0..10m"));
        AppEventLogger.event(this, "ui small_distance_clamp=" + enabled);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private void setPngOutputEnabled(boolean enabled) {
        HudPrefs.setPngOutputEnabled(this, enabled);
        appendStatus("PNG output " + (enabled ? "ON" : "OFF"));
        AppEventLogger.event(this, "ui output_png=" + enabled);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private void setNativeOutputEnabled(boolean enabled) {
        HudPrefs.setNativeOutputEnabled(this, enabled);
        appendStatus("Native output " + (enabled ? "ON" : "OFF"));
        AppEventLogger.event(this, "ui output_native=" + enabled);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private void setLaneOutputEnabled(boolean enabled) {
        HudPrefs.setLaneOutputEnabled(this, enabled);
        appendStatus("Lane output " + (enabled ? "ON" : "OFF"));
        AppEventLogger.event(this, "ui output_lanes=" + enabled);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private void setDistanceOutputEnabled(boolean enabled) {
        HudPrefs.setDistanceOutputEnabled(this, enabled);
        appendStatus("Distance output " + (enabled ? "ON" : "OFF"));
        AppEventLogger.event(this, "ui output_distance=" + enabled);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private void setStreetOutputEnabled(boolean enabled) {
        HudPrefs.setStreetOutputEnabled(this, enabled);
        appendStatus("Street output " + (enabled ? "ON" : "OFF"));
        AppEventLogger.event(this, "ui output_street=" + enabled);
    }

    private void setTransliterationMode(int mode) {
        HudPrefs.setTransliterationMode(this, mode);
        int persisted = HudPrefs.transliterationMode(this);
        appendStatus("Text transliteration mode " + persisted);
        AppEventLogger.event(this, "ui text_transliteration=" + persisted);
    }

    private void setTextDirectionOutputEnabled(boolean enabled) {
        HudPrefs.setTextDirectionOutputEnabled(this, enabled);
        appendStatus("Text direction output " + (enabled ? "ON" : "OFF"));
        AppEventLogger.event(this, "ui output_text_direction=" + enabled);
    }

    private void setWazeAlertsEnabled(boolean enabled) {
        HudPrefs.setWazeAlertsEnabled(this, enabled);
        NavHudLiveSender.get(this).onWazeAlertsPreferenceChanged(enabled);
        appendStatus("Waze alerts " + (enabled ? "ON" : "OFF"));
        AppEventLogger.event(this, "ui waze_alerts=" + enabled);
    }

    private void setRouteMetricsMode(int mode) {
        HudPrefs.setRouteMetricsMode(this, mode);
        int persisted = HudPrefs.routeMetricsMode(this);
        appendStatus("Route metrics mode " + persisted);
        AppEventLogger.event(this, "ui route_metrics_mode=" + persisted);
    }

    private void setEtaOutputMode(int mode) {
        HudPrefs.setEtaOutputMode(this, mode);
        int persisted = HudPrefs.etaOutputMode(this);
        appendStatus("ETA output mode " + persisted);
        AppEventLogger.event(this, "ui eta_output_mode=" + persisted);
    }

    private void setEtaOutputEnabled(boolean enabled) {
        HudPrefs.setEtaOutputEnabled(this, enabled);
        appendStatus("ETA output " + (enabled ? "ON" : "OFF"));
        AppEventLogger.event(this, "ui output_eta=" + enabled);
    }

    private void setRemainingTimeOutputEnabled(boolean enabled) {
        HudPrefs.setRemainingTimeOutputEnabled(this, enabled);
        appendStatus("Remaining time output " + (enabled ? "ON" : "OFF"));
        AppEventLogger.event(this, "ui output_remaining_time=" + enabled);
    }

    private void setRemainingDistanceOutputEnabled(boolean enabled) {
        HudPrefs.setRemainingDistanceOutputEnabled(this, enabled);
        appendStatus("Remaining distance output " + (enabled ? "ON" : "OFF"));
        AppEventLogger.event(this, "ui output_remaining_distance=" + enabled);
    }

    private void setSpeedLimitMode(int mode) {
        HudPrefs.setSpeedLimitMode(this, mode);
        int persisted = HudPrefs.speedLimitMode(this);
        appendStatus("Speed limit mode " + persisted);
        AppEventLogger.event(this, "ui speed_limit_mode=" + persisted);
    }

    private void setNativeSpeedLimitDelayEnabled(boolean enabled) {
        HudPrefs.setNativeSpeedLimitDelayEnabled(this, enabled);
        boolean persisted = HudPrefs.isNativeSpeedLimitDelayEnabled(this);
        appendStatus("Native speed limit delay " + (persisted ? "ON" : "OFF"));
        AppEventLogger.event(this, "ui speed_limit_native_delay_enabled=" + persisted);
    }

    private void setNativeSpeedLimitFallbackMode(int mode) {
        HudPrefs.setNativeSpeedLimitFallbackMode(this, mode);
        int persisted = HudPrefs.getNativeSpeedLimitFallbackMode(this);
        appendStatus("Native speed limit fallback mode " + persisted);
        AppEventLogger.event(this, "ui speed_limit_native_fallback_mode=" + persisted);
    }

    private void setSpeedLimitFreeFallback(int mode) {
        HudPrefs.setSpeedLimitFreeFallback(this, mode);
        int persisted = HudPrefs.speedLimitFreeFallback(this);
        appendStatus("Speed limit free fallback " + persisted);
        AppEventLogger.event(this, "ui speed_limit_free_fallback=" + persisted);
    }

    private void setSpeedLimitOverlaySeconds(int seconds) {
        HudPrefs.setSpeedLimitOverlaySeconds(this, seconds);
        int persisted = HudPrefs.speedLimitOverlaySeconds(this);
        appendStatus("Speed limit overlay seconds " + persisted);
        AppEventLogger.event(this, "ui speed_limit_overlay_seconds=" + persisted);
    }

    private void setSpeedLimitCompositePlacement(int placement) {
        HudPrefs.setSpeedLimitCompositePlacement(this, placement);
        int persisted = HudPrefs.speedLimitCompositePlacement(this);
        appendStatus("Speed limit composite placement " + persisted);
        AppEventLogger.event(this, "ui speed_limit_composite_placement=" + persisted);
    }

    private void setSpeedLimitManeuverOverlaySize(int size) {
        HudPrefs.setSpeedLimitManeuverOverlaySize(this, size);
        int persisted = HudPrefs.speedLimitManeuverOverlaySize(this);
        appendStatus("Speed limit maneuver overlay size " + persisted);
        AppEventLogger.event(this, "ui speed_limit_maneuver_overlay_size=" + persisted);
    }

    private void setSpeedLimitLaneOverlaySize(int size) {
        HudPrefs.setSpeedLimitLaneOverlaySize(this, size);
        int persisted = HudPrefs.speedLimitLaneOverlaySize(this);
        appendStatus("Speed limit lane overlay size " + persisted);
        AppEventLogger.event(this, "ui speed_limit_lane_overlay_size=" + persisted);
    }

    private void saveMapSettings(HudMapSettings settings, String reason) {
        HudMapSettings current = HudPrefs.mapSettings(this);
        if (current.equals(settings)) return;
        HudPrefs.setMapSettings(this, settings);
        if (settings.mode == HudMapSettings.EXPERIMENTAL) {
            NavHudLiveSender.refreshMapLiveSettings(reason);
        } else if (current.mode != settings.mode) {
            NavHudLiveSender.stopMapLiveIfRunning(reason);
        }
        AppEventLogger.event(this, "ui " + settings.diagnostics());
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private void setUiTheme(boolean dark) {
        HudPrefs.setDarkTheme(this, dark);
        appendStatus("UI theme " + (dark ? "dark" : "light"));
        invalidateComposeSnapshot();
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private boolean showBackgroundReminderIfNeeded() {
        long token = HudRuntimeUpgradeGuard.packageReplaceToken(this);
        if (!HudPrefs.shouldShowBackgroundReminder(this)) {
            AppEventLogger.event(this, "bg_settings_reminder_skipped version="
                    + BuildConfig.VERSION_NAME + " token=" + token);
            return false;
        }
        AppEventLogger.event(this, "bg_settings_reminder_shown version="
                + BuildConfig.VERSION_NAME + " token=" + token);
        return true;
    }

    //starts or schedules work here so lifecycle recovery follows one controlled path.
    private void scheduleNavPermissionSelfCheck(boolean autoGrant) {
        scheduleNavPermissionSelfCheck(autoGrant, NAV_PERMISSION_SELF_CHECK_DELAY_MS);
    }

    //starts or schedules work here so lifecycle recovery follows one controlled path.
    private void scheduleNavPermissionSelfCheck(boolean autoGrant, long delayMs) {
        if (pendingNavPermissionSelfCheckRunnable != null) {
            handler.removeCallbacks(pendingNavPermissionSelfCheckRunnable);
        }
        pendingNavPermissionSelfCheckRunnable = () -> {
            pendingNavPermissionSelfCheckRunnable = null;
            if (!destroyed) {
                runNavPermissionSelfCheck(autoGrant);
            }
        };
        handler.postDelayed(pendingNavPermissionSelfCheckRunnable, delayMs);
    }

    private void requestAutomaticAdbAuthorization(String reason) {
        if (autoAdbAuthorizationState != AdbAuthorizationUiPolicy.AutoState.IDLE) {
            return;
        }
        autoAdbAuthorizationState = AdbAuthorizationUiPolicy.AutoState.PENDING_UI;
        AppEventLogger.event(this, "adb_auto pending reason=" + reason);
        maybeStartPendingAdbAuthorization();
    }

    private void maybeStartPendingAdbAuthorization() {
        maybeRequestVehiclePositionPermission();
        maybeRequestOverlayPermission();
        boolean grantInProgress = adbGrantInProgress
                || LocalAdbBridge.isPermissionGrantInProgress()
                || NavRuntimePermissionRepair.isRunning()
                || LocalAdbBridge.isAuthorizationCancellationPending();
        if (!AdbAuthorizationUiPolicy.canStart(
                autoAdbAuthorizationState,
                mainUiReady,
                activityResumed,
                activityWindowFocused,
                composeBlockingUiFlow,
                false)) {
            cancelPendingAutoAdbStart();
            return;
        }
        if (pendingAutoAdbStartRunnable != null) {
            return;
        }
        long startDelayMs = grantInProgress
                ? NAV_RUNTIME_RECHECK_DELAY_MS
                : NAV_PERMISSION_SELF_CHECK_DELAY_MS;
        pendingAutoAdbStartRunnable = () -> {
            pendingAutoAdbStartRunnable = null;
            boolean stillBusy = adbGrantInProgress
                    || LocalAdbBridge.isPermissionGrantInProgress()
                    || NavRuntimePermissionRepair.isRunning()
                    || LocalAdbBridge.isAuthorizationCancellationPending();
            if (stillBusy) {
                maybeStartPendingAdbAuthorization();
                return;
            }
            if (!AdbAuthorizationUiPolicy.canStart(
                    autoAdbAuthorizationState,
                    mainUiReady,
                    activityResumed,
                    activityWindowFocused,
                    composeBlockingUiFlow,
                    stillBusy)) {
                return;
            }
            runNavPermissionSelfCheck(true);
        };
        AppEventLogger.event(this, "adb_auto scheduled delay_ms="
                + startDelayMs
                + " waiting_for_grant=" + grantInProgress);
        handler.postDelayed(pendingAutoAdbStartRunnable, startDelayMs);
    }

    private void cancelPendingAutoAdbStart() {
        if (pendingAutoAdbStartRunnable == null) {
            return;
        }
        handler.removeCallbacks(pendingAutoAdbStartRunnable);
        pendingAutoAdbStartRunnable = null;
    }

    private static String normalizeBlockingUiFlow(String flow) {
        return flow == null ? "" : flow.trim();
    }

    private void cancelActiveAdbAuthorization(String reason, boolean retryAfterFlow) {
        boolean wasRunning = adbGrantInProgress
                || LocalAdbBridge.isPermissionGrantInProgress()
                || NavRuntimePermissionRepair.isRunning()
                || autoAdbAuthorizationState == AdbAuthorizationUiPolicy.AutoState.RUNNING;
        if (!wasRunning) {
            return;
        }
        adbGrantGeneration++;
        LocalAdbBridge.AuthorizationCancellation cancellation =
                LocalAdbBridge.cancelPendingAuthorization();
        scheduleAdbCancellationClear(cancellation.generation);
        pendingManualAdbRetry = false;
        pendingManualAdbRetryGeneration = 0L;
        cancelPendingAutoAdbStart();
        autoAdbAuthorizationState = retryAfterFlow
                ? AdbAuthorizationUiPolicy.AutoState.PENDING_UI
                : AdbAuthorizationUiPolicy.AutoState.IDLE;
        AppEventLogger.event(this, "adb_bridge cancelled reason=" + reason
                + " socket_closed=" + cancellation.socketClosed
                + " retry=" + retryAfterFlow);
    }

    private void scheduleAdbCancellationClear(long generation) {
        if (pendingAdbCancellationClearRunnable != null) {
            handler.removeCallbacks(pendingAdbCancellationClearRunnable);
        }
        pendingAdbCancellationClearGeneration = generation;
        pendingAdbCancellationClearRunnable = () -> {
            if (generation != pendingAdbCancellationClearGeneration) {
                return;
            }
            boolean busy = adbGrantInProgress
                    || LocalAdbBridge.isPermissionGrantInProgress()
                    || NavRuntimePermissionRepair.isRunning();
            if (busy) {
                handler.postDelayed(
                        pendingAdbCancellationClearRunnable,
                        NAV_RUNTIME_RECHECK_DELAY_MS);
                return;
            }
            LocalAdbBridge.clearPendingAuthorizationCancellation(generation);
            pendingAdbCancellationClearGeneration = 0L;
            pendingAdbCancellationClearRunnable = null;
            if (!destroyed && !exitRequested
                    && autoAdbAuthorizationState
                    == AdbAuthorizationUiPolicy.AutoState.PENDING_UI) {
                maybeStartPendingAdbAuthorization();
            }
        };
        handler.postDelayed(
                pendingAdbCancellationClearRunnable,
                NAV_RUNTIME_RECHECK_DELAY_MS);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private void runNavPermissionSelfCheck(boolean autoGrant) {
        NavRuntimePermissionStatus status = navRuntimePermissionStatus();
        String adbKey = cachedAdbKeyFingerprintForUi();
        boolean keyKnown = cachedAdbUiStatus().authorized;
        if (status.readyForCapture()
                && cachedAdbKeyVerifiedForCapture()) {
            autoAdbAuthorizationState = AdbAuthorizationUiPolicy.AutoState.AUTHORIZED;
            appendStatus("nav permission self-check: " + status.summary());
            AppEventLogger.event(this, "nav_permission_self_check "
                    + status.summary() + " keyKnown=true");
            maybeRequestOverlayPermission();
            return;
        }

        appendStatus("nav permission self-check: " + status.summary() + " adbKey=" + adbKey);
        AppEventLogger.event(this, "nav_permission_self_check "
                + status.summary() + " adbKey=" + adbKey
                + " keyKnown=" + keyKnown
                + " autoGrant=" + autoGrant
                + " state=" + autoAdbAuthorizationState);
        if (autoGrant
                && autoAdbAuthorizationState == AdbAuthorizationUiPolicy.AutoState.PENDING_UI) {
            requestAdbPermissionGrant(
                    "launch-self-check",
                    LocalAdbBridge.AuthorizationPromptMode.AUTO_ONCE,
                    true);
        }
    }

    //starts or schedules work here so lifecycle recovery follows one controlled path.
    private boolean ensureNavCaptureRuntimeReadyForStart(String mode, String packageName) {
        NavRuntimePermissionStatus status = navRuntimePermissionStatus();
        if (status.readyForCapture()) {
            if (!cachedAdbKeyVerifiedForCapture()) {
                requestAdbPermissionGrant(
                        "start-key-check-" + (mode == null ? "capture" : mode),
                        LocalAdbBridge.AuthorizationPromptMode.AUTO_ONCE);
            }
            return true;
        }
        String safeMode = mode == null ? "capture" : mode;
        appendStatus("nav " + safeMode + " blocked: capture services not ready: "
                + status.summary());
        AppEventLogger.event(this, "nav_capture_start_blocked mode=" + safeMode
                + " package=" + packageName + " status=" + status.summary());
        requestAdbPermissionGrant(
                "start-" + safeMode + "-" + packageName,
                LocalAdbBridge.AuthorizationPromptMode.AUTO_ONCE);
        return false;
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private void requestAdbPermissionGrant(
            String reason,
            LocalAdbBridge.AuthorizationPromptMode authorizationPromptMode) {
        requestAdbPermissionGrant(reason, authorizationPromptMode, false);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private void requestAdbPermissionGrant(
            String reason,
            LocalAdbBridge.AuthorizationPromptMode authorizationPromptMode,
            boolean automatic) {
        if (automatic && !AdbAuthorizationUiPolicy.canStart(
                autoAdbAuthorizationState,
                mainUiReady,
                activityResumed,
                activityWindowFocused,
                composeBlockingUiFlow,
                adbGrantInProgress
                        || LocalAdbBridge.isPermissionGrantInProgress()
                        || NavRuntimePermissionRepair.isRunning()
                        || LocalAdbBridge.isAuthorizationCancellationPending())) {
            maybeStartPendingAdbAuthorization();
            return;
        }
        if (adbGrantInProgress) {
            if (authorizationPromptMode == LocalAdbBridge.AuthorizationPromptMode.FORCE) {
                if (!pendingManualAdbRetry) {
                    pendingManualAdbRetry = true;
                    LocalAdbBridge.AuthorizationCancellation cancellation =
                            LocalAdbBridge.cancelPendingAuthorization();
                    pendingManualAdbRetryGeneration = cancellation.generation;
                    AppEventLogger.event(this, "adb_bridge auth_retry_queued"
                            + " pending_auth_cancelled=" + cancellation.socketClosed);
                }
                return;
            }
            appendStatus("adb bridge grant already running");
            return;
        }
        if (authorizationPromptMode == LocalAdbBridge.AuthorizationPromptMode.AUTO_ONCE
                && LocalAdbBridge.isAuthorizationCancellationPending()) {
            autoAdbAuthorizationState = AdbAuthorizationUiPolicy.AutoState.PENDING_UI;
            AppEventLogger.event(this, "adb_auto deferred reason=cancellation_pending request=" + reason);
            maybeStartPendingAdbAuthorization();
            return;
        }
        adbGrantInProgress = true;
        long grantGeneration = ++adbGrantGeneration;
        long authorizationGeneration = LocalAdbBridge.authorizationCancellationGeneration();
        cancelPendingAutoAdbStart();
        if (authorizationPromptMode != LocalAdbBridge.AuthorizationPromptMode.NEVER) {
            autoAdbAuthorizationState = AdbAuthorizationUiPolicy.AutoState.RUNNING;
        }
        //Grant decisions must not consume the cache transition before it is published.
        NavRuntimePermissionStatus runningStatus = NavRuntimePermissionStatus.check(this);
        appendStatus("adb bridge grant start: " + reason);
        AppEventLogger.event(this, "adb_bridge_start reason=" + reason
                + " mode=" + authorizationPromptMode
                + " autoState=" + autoAdbAuthorizationState);
        Context appContext = getApplicationContext();
        Handler mainHandler = handler;
        Thread thread = new Thread(() -> {
            LocalAdbBridge.Result result;
            try {
                result = NavRuntimePermissionRepair.checkAndRepairBlocking(
                        appContext,
                        reason,
                        true,
                        authorizationPromptMode,
                        authorizationGeneration);
            } catch (RuntimeException e) {
                AppEventLogger.event(appContext, FailureDiagnostics.format(
                        e, "adb_permission_grant", "repair"));
                result = LocalAdbBridge.Result.failed(FailureDiagnostics.formatBounded(
                        e, "adb_permission_grant", "repair", 2048));
            }
            NavRuntimePermissionStatus refreshedStatus = runningStatus;
            try {
                refreshedStatus = NavRuntimePermissionStatus.check(appContext);
            } catch (RuntimeException error) {
                AppEventLogger.event(appContext, FailureDiagnostics.format(
                        error, "adb_permission_grant", "refresh_runtime_status"));
            }
            LocalAdbBridge.AuthorizationSnapshot authorizationSnapshot =
                    LocalAdbBridge.authorizationSnapshot(appContext);
            updateRuntimeStatusCache(refreshedStatus, authorizationSnapshot);
            DashboardWidgetController.onRuntimePermissionsRefreshed(appContext);
            lastRuntimeStatusRefreshAtMs = SystemClock.elapsedRealtime();
            LocalAdbBridge.Result finalResult = result;
            NavRuntimePermissionStatus finalStatus = refreshedStatus;
            LocalAdbBridge.AuthorizationSnapshot finalAuthorizationSnapshot = authorizationSnapshot;
            mainHandler.post(() -> handleAdbGrantResult(
                    finalResult,
                    grantGeneration,
                    automatic,
                    finalStatus,
                    finalAuthorizationSnapshot));
        }, "BydHudAdbGrant");
        thread.start();
    }

    //handles this branch here so source-specific edge cases stay out of the main flow.
    private void handleAdbGrantResult(
            LocalAdbBridge.Result result,
            long grantGeneration,
            boolean automatic,
            NavRuntimePermissionStatus status,
            LocalAdbBridge.AuthorizationSnapshot authorizationSnapshot) {
        boolean stale = grantGeneration != adbGrantGeneration;
        if (stale || destroyed || exitRequested) {
            adbGrantInProgress = false;
            if (pendingManualAdbRetry) {
                pendingManualAdbRetry = false;
                LocalAdbBridge.clearPendingAuthorizationCancellation(
                        pendingManualAdbRetryGeneration);
                pendingManualAdbRetryGeneration = 0L;
            }
            AppEventLogger.event(getApplicationContext(), "adb_bridge_result_ignored"
                    + " stale=" + stale
                    + " destroyed=" + destroyed
                    + " exit=" + exitRequested
                    + " code=" + result.code
                    + " message=" + result.message);
            if (!destroyed && !exitRequested
                    && autoAdbAuthorizationState
                    == AdbAuthorizationUiPolicy.AutoState.PENDING_UI) {
                maybeStartPendingAdbAuthorization();
            }
            return;
        }
        adbGrantInProgress = false;
        boolean keyVerified = authorizationSnapshot.verifiedThisProcess;
        if (keyVerified) {
            InstrumentProxyManager.get(this).onAuthorizationVerified();
        }
        boolean repairCollision = automatic
                && result.code == LocalAdbBridge.Result.Code.PARTIAL
                && NavRuntimePermissionRepair.RESULT_ALREADY_RUNNING.equals(result.message);
        if (keyVerified) {
            autoAdbAuthorizationState = AdbAuthorizationUiPolicy.AutoState.AUTHORIZED;
        } else if (repairCollision) {
            autoAdbAuthorizationState = AdbAuthorizationUiPolicy.AutoState.PENDING_UI;
        } else if (autoAdbAuthorizationState == AdbAuthorizationUiPolicy.AutoState.RUNNING) {
            autoAdbAuthorizationState = AdbAuthorizationUiPolicy.AutoState.FAILED;
        }
        boolean grantCompleted = keyVerified || (status.readyForCapture()
                && (result.code == LocalAdbBridge.Result.Code.GRANTED
                || result.code == LocalAdbBridge.Result.Code.ALREADY_GRANTED));
        if (pendingManualAdbRetry && !grantCompleted) {
            pendingManualAdbRetry = false;
            pendingManualAdbRetryGeneration = 0L;
            AppEventLogger.event(this, "adb_bridge auth_retry_started");
            requestAdbPermissionGrant(
                    "manual-grant-retry",
                    LocalAdbBridge.AuthorizationPromptMode.FORCE);
            return;
        }
        if (pendingManualAdbRetry) {
            LocalAdbBridge.clearPendingAuthorizationCancellation(
                    pendingManualAdbRetryGeneration);
        }
        pendingManualAdbRetry = false;
        pendingManualAdbRetryGeneration = 0L;
        appendStatus("adb bridge " + result.code + ": " + result.message);
        AppEventLogger.event(this, "adb_bridge_result "
                + result.code + " " + result.message
                + " keyVerified=" + keyVerified
                + " repairCollision=" + repairCollision
                + " autoState=" + autoAdbAuthorizationState);
        requestRuntimeStatusRefresh(this, true, "adb-grant-result");
        if (!status.readyForCapture() && status.settingsGranted()
                && result.shouldRecheckPermissions()) {
            NavNotificationListenerService.requestRuntimeRebind(this, "adb-result-recheck");
            scheduleNavPermissionSelfCheck(false, NAV_RUNTIME_RECHECK_DELAY_MS);
        }
        maybeStartPendingAdbAuthorization();
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    //sends encoded data here so transport side effects stay behind a single boundary.
    private void appendStatus(String line) {
        Log.i(TAG, line);
    }

    //defines the ActiveAppRow module boundary so related behavior stays readable inside one unit.
    private static final class ActiveAppRow {
        final String label;
        final String packageName;
        final String processName;
        final int importance;
        final boolean hasProcess;
        final boolean hasTask;
        final int taskId;
        final int displayId;
        final boolean visible;

        ActiveAppRow(String label, String packageName, String processName, int importance) {
            this(label, packageName, processName, importance,
                    importance < Integer.MAX_VALUE - 2
                            && !"installed".equals(processName)
                            && !"observed".equals(processName)
                            && !"not running".equals(processName),
                    false,
                    -1,
                    0,
                    false);
        }

        ActiveAppRow(String label, String packageName, String processName, int importance,
                boolean hasProcess, boolean hasTask, int taskId, int displayId, boolean visible) {
            this.label = label;
            this.packageName = packageName;
            this.processName = processName;
            this.importance = importance;
            this.hasProcess = hasProcess;
            this.hasTask = hasTask;
            this.taskId = taskId;
            this.displayId = displayId;
            this.visible = visible;
        }

        //keeps this predicate explicit so safety checks can be audited without tracing callers.
        boolean isRuntimeBacked() {
            return hasTask || hasProcess;
        }
    }
}
