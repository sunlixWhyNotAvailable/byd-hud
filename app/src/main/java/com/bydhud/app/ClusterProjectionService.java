package com.bydhud.app;

//keeps the legacy projection service entry point available for systems that bind to it directly.

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

//anchors the ClusterProjectionService android entry point so lifecycle recovery stays separate from business logic.
public final class ClusterProjectionService extends Service
        implements SurfaceHolder.Callback {
    static final String VIRTUAL_DISPLAY_NAME = "bydhud_remote_dashboard";

    private static final String TAG = "BydHudClusterProjection";
    private static final String CHANNEL_ID = "byd_hud_cluster_projection";
    private static final int NOTIFICATION_ID = 4304;
    private static final int VIRTUAL_WIDTH = DashboardProjectionPolicy.VIRTUAL_WIDTH;
    private static final int VIRTUAL_HEIGHT = DashboardProjectionPolicy.VIRTUAL_BASE_HEIGHT;
    private static final int VIRTUAL_DENSITY = DashboardProjectionPolicy.VIRTUAL_DENSITY;
    private static final int VIRTUAL_DISPLAY_FLAGS = 320;
    private static final int MAIN_DISPLAY_ID = 0;
    private static final String ACTION_PROJECT =
            "com.bydhud.app.action.CLUSTER_PROJECT";
    private static final String ACTION_RETURN =
            "com.bydhud.app.action.CLUSTER_RETURN";
    private static final String EXTRA_PACKAGE = "package";
    private static final String EXTRA_MODE = "dashboard_mode";
    private static final String EXTRA_REASON = "reason";
    private static final String EXTRA_SHUTDOWN_TOKEN = "shutdownToken";
    private static final String EXTRA_RETURN_GENERATION = "returnGeneration";
    private static final String EXTRA_RETURN_OWNER_TOKEN = "returnOwnerToken";
    private static final AtomicLong NEXT_PROJECTION_TOKEN = new AtomicLong();
    private static ClusterProjectionService instance;

    private final Object lock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WindowManager overlayWindowManager;
    private FrameLayout overlayRoot;
    private SurfaceView overlaySurfaceView;
    private View projectionCoverView;
    private Surface projectionSurface;
    private VirtualDisplay virtualDisplay;
    private String projectedPackage = "";
    private String pendingPackage = "";
    private int projectionMode = HudPrefs.DASHBOARD_MODE_FULL;
    private boolean projectionRequested;
    private int projectionGeneration;
    //Process-wide token prevents a recreated service from reusing an old per-instance generation.
    private long projectionOwnerToken;
    private long pendingShutdownReleaseToken;
    private int surfaceGeneration;
    private int projectionWidth = VIRTUAL_WIDTH;
    private int projectionHeight = VIRTUAL_HEIGHT;
    private int projectionBufferWidth = VIRTUAL_WIDTH;
    private int projectionBufferHeight = VIRTUAL_HEIGHT;
    private int projectionLeft;
    private int projectionTop;
    private boolean projectionGeometryValid = true;
    private boolean projectionPlacementReady;
    private boolean projectionCoverVisible = true;
    private boolean projectionContentVisible;
    private boolean projectionRecoveryInProgress;

    //starts or schedules work here so lifecycle recovery follows one controlled path.
    static void startProjection(Context context, String packageName, int dashboardMode, String reason) {
        Intent intent = new Intent(context, ClusterProjectionService.class);
        intent.setAction(ACTION_PROJECT);
        intent.putExtra(EXTRA_PACKAGE, safe(packageName));
        intent.putExtra(EXTRA_MODE, HudPrefs.normalizeDashboardScreenMode(dashboardMode));
        intent.putExtra(EXTRA_REASON, safe(reason));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static void returnToMain(Context context, String packageName, String reason) {
        returnToMain(context, packageName, reason, 0L);
    }

    static void returnToMain(
            Context context, String packageName, String reason, long shutdownToken) {
        Intent intent = new Intent(context, ClusterProjectionService.class);
        intent.setAction(ACTION_RETURN);
        intent.putExtra(EXTRA_PACKAGE, safe(packageName));
        intent.putExtra(EXTRA_REASON, safe(reason));
        intent.putExtra(EXTRA_SHUTDOWN_TOKEN, shutdownToken);
        ClusterProjectionService service = instance;
        if (service != null) {
            synchronized (service.lock) {
                intent.putExtra(EXTRA_RETURN_GENERATION, service.projectionGeneration);
                intent.putExtra(EXTRA_RETURN_OWNER_TOKEN, service.projectionOwnerToken);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    static void applyDashboardProfile(Context context, int dashboardMode, String reason) {
        ClusterProjectionService service = instance;
        if (service == null) {
            AppEventLogger.event(context,
                    "cluster_projection profile_deferred service=missing mode="
                            + HudPrefs.normalizeDashboardScreenMode(dashboardMode));
            return;
        }
        int normalizedMode = HudPrefs.normalizeDashboardScreenMode(dashboardMode);
        DashboardProjectionPolicy.Profile profile =
                HudPrefs.dashboardProjectionProfile(context, normalizedMode);
        service.mainHandler.post(() -> service.resizeActiveProjection(
                normalizedMode,
                profile,
                reason));
    }

    //Applies a widget-selected profile to the exact live owner without moving or restarting it.
    static String applyDashboardProfileForWidget(
            Context context,
            String packageName,
            int dashboardMode,
            String reason,
            long expectedProjectionGeneration,
            BooleanSupplier stillCurrent) {
        ClusterProjectionService service = instance;
        String normalizedPackage = safe(packageName);
        int normalizedMode = HudPrefs.normalizeDashboardScreenMode(dashboardMode);
        if (normalizedPackage.isEmpty()
                || (normalizedMode != HudPrefs.DASHBOARD_MODE_PARTIAL
                && normalizedMode != HudPrefs.DASHBOARD_MODE_FULL)) {
            return "invalid widget profile target";
        }
        if (service == null) {
            return "projection service unavailable";
        }
        DashboardProjectionPolicy.Profile profile =
                HudPrefs.dashboardProjectionProfile(service, normalizedMode);
        return service.applyDashboardProfileForWidgetBlocking(
                normalizedPackage,
                normalizedMode,
                profile,
                reason,
                expectedProjectionGeneration,
                stillCurrent);
    }

    //returns a read-only snapshot of the app-owned projection surface for PixelCopy.
    static ProjectedSurface projectedSurfaceForPackage(String packageName) {
        ClusterProjectionService service = instance;
        return service == null ? null : service.currentProjectedSurface(packageName);
    }

    //checks that a borrowed surface snapshot still belongs to the active dashboard projection.
    static boolean isProjectedSurfaceCurrent(ProjectedSurface surface) {
        ClusterProjectionService service = instance;
        return service != null && service.isCurrentProjectedSurface(surface);
    }

    //checks projection ownership without borrowing a Surface, used by the low-cadence watchdog.
    static boolean isProjectedPackageCurrent(String packageName) {
        ClusterProjectionService service = instance;
        return service != null && service.hasCurrentProjection(packageName);
    }

    //Captures an app-owned projection generation for widget work; service recreation yields a new token.
    static long projectedGenerationTokenForWidget(String packageName) {
        ClusterProjectionService service = instance;
        return service == null ? 0L : service.currentProjectionToken(packageName);
    }

    //The commit fence intentionally depends on owner and request liveness, not transient geometry state.
    static boolean widgetResizeCommitAllowedForTest(
            boolean expectedOwner, boolean requestCurrent) {
        return expectedOwner && requestCurrent;
    }

    //exposes whether any app still owns the compositor projection, regardless of package.
    static boolean hasProjectionOwner() {
        ClusterProjectionService service = instance;
        return service != null && service.hasProjectionOwnerUnsafe();
    }

    //exposes the real virtual display id so callers confirm the physical move against the created target.
    static int projectedDisplayIdForPackage(String packageName) {
        ClusterProjectionService service = instance;
        return service == null
                ? NavAppDisplayState.DISPLAY_UNKNOWN
                : service.currentProjectedDisplayId(packageName);
    }

    //refreshes borrowed surface metadata after PixelCopy stalls without moving the app between displays.
    static boolean recoverProjectedSurface(Context context, String packageName, String reason) {
        ClusterProjectionService service = instance;
        if (service == null) {
            AppEventLogger.event(context,
                    "cluster_projection surface_recover_skipped reason=service-missing package="
                            + safe(packageName));
            return false;
        }
        return service.recoverProjectedSurface(packageName, reason);
    }

    //Reveals only the exact projection generation whose target task was confirmed visible.
    static void confirmProjectionVisible(
            String packageName, int expectedDisplayId, long expectedOwnerToken) {
        ClusterProjectionService service = instance;
        if (service == null) return;
        service.mainHandler.post(() -> service.confirmProjectionVisibleOnMain(
                packageName, expectedDisplayId, expectedOwnerToken, "task-confirmed-visible"));
    }

    //Explicit app shutdown may release a retained idle allocation; active work always wins.
    static void releaseIdleProjectionForShutdown(Context context, String reason) {
        ClusterProjectionService service = instance;
        if (service == null) {
            AppEventLogger.event(context,
                    "cluster_projection shutdown_release_skipped reason=service-missing requestReason="
                            + safe(reason));
            return;
        }
        long shutdownToken = UserRuntimeSession.PROCESS.shutdownToken();
        service.mainHandler.post(() -> service.releaseIdleProjectionForShutdownOnMain(
                shutdownToken, reason));
    }

    @Override
    //initializes android lifecycle state here so services, UI, and logging start from a known baseline.
    public void onCreate() {
        super.onCreate();
        instance = this;
        startForeground(NOTIFICATION_ID, buildNotification("Dashboard projection idle"));
        log("service created");
    }

    @Override
    //handles service start intents here so boot, watchdog, and UI paths share one runtime entry point.
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = safe(intent == null ? "" : intent.getAction());
        String packageName = safe(intent == null ? "" : intent.getStringExtra(EXTRA_PACKAGE));
        int dashboardMode = intent == null || !intent.hasExtra(EXTRA_MODE)
                ? NavAppDisplayController.get(this).persistedDashboardMode()
                : HudPrefs.normalizeDashboardScreenMode(intent.getIntExtra(
                        EXTRA_MODE, HudPrefs.DASHBOARD_MODE_FULL));
        String reason = safe(intent == null ? "" : intent.getStringExtra(EXTRA_REASON));
        if (action.isEmpty()) {
            restorePersistedProjection("sticky-restart-empty-action");
            return START_STICKY;
        }
        if (ACTION_RETURN.equals(action)) {
            returnPackageToMain(packageName, reason,
                    intent.getLongExtra(EXTRA_SHUTDOWN_TOKEN, 0L),
                    intent.getIntExtra(EXTRA_RETURN_GENERATION, 0),
                    intent.getLongExtra(EXTRA_RETURN_OWNER_TOKEN, 0L));
            return START_NOT_STICKY;
        }
        if (ACTION_PROJECT.equals(action)) {
            if (HudPrefs.isUserShutdownActive(this)) {
                if (stopColdIdleServiceAfterShutdown()) return START_NOT_STICKY;
                releaseIdleProjectionForShutdownOnMain(
                        UserRuntimeSession.PROCESS.shutdownToken(), "late-project-during-shutdown");
                return START_NOT_STICKY;
            }
            requestProjection(packageName, dashboardMode, reason);
            return START_STICKY;
        }
        log("unknown action=" + action + " reason=" + reason);
        return START_NOT_STICKY;
    }

    //restores app-owned dashboard projection after Android restarts this sticky service without extras.
    private void restorePersistedProjection(String reason) {
        String packageName = NavAppDisplayController.get(this).persistedDashboardPackage();
        if (packageName.isEmpty()) {
            log("sticky restore skipped empty package reason=" + safe(reason));
            return;
        }
        int dashboardMode = NavAppDisplayController.get(this).persistedDashboardMode();
        log("sticky restore package=" + packageName + " reason=" + safe(reason));
        requestProjection(packageName, dashboardMode, "restore:" + safe(reason));
    }

    @Override
    //cleans up lifecycle state here so Android teardown does not leave stale runtime markers behind.
    public void onDestroy() {
        releaseProjection("destroy");
        if (instance == this) {
            instance = null;
        }
        log("service destroyed");
        super.onDestroy();
    }

    @Override
    //keeps this step explicit so callers can rely on one documented behavior boundary.
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    //uses SurfaceView so projection buffers stay outside the Compose render path.
    public void surfaceCreated(SurfaceHolder holder) {
        synchronized (lock) {
            if (overlaySurfaceView == null || overlaySurfaceView.getHolder() != holder) return;
        }
        acceptProjectionSurface(holder == null ? null : holder.getSurface());
    }

    @Override
    //keeps cluster projection fixed-size so this test build matches the car display contract.
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        log("projection_surface_changed mode=surface_view width=" + width + " height=" + height);
    }

    @Override
    //releases SurfaceView projections when Android destroys the owner surface.
    public void surfaceDestroyed(SurfaceHolder holder) {
        synchronized (lock) {
            if (overlaySurfaceView == null || overlaySurfaceView.getHolder() != holder) return;
        }
        releaseProjection("surfaceview-destroyed");
    }

    //accepts the SurfaceView owner surface without duplicating display creation.
    private void acceptProjectionSurface(Surface surface) {
        if (surface == null || !surface.isValid()) {
            log("projection_surface_invalid mode=surface_view");
            return;
        }
        String packageName;
        synchronized (lock) {
            projectionSurface = surface;
            packageName = pendingPackage;
        }
        log("projection_surface_ready mode=surface_view");
        createVirtualDisplayIfReady(packageName, "surface-ready");
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private void requestProjection(String packageName, int dashboardMode, String reason) {
        if (packageName.isEmpty()) {
            log("projection ignored empty package reason=" + reason);
            return;
        }
        int normalizedMode = HudPrefs.normalizeDashboardScreenMode(dashboardMode);
        boolean invalidActiveGeometry = false;
        int invalidGeneration = 0;
        long invalidOwnerToken = 0L;
        int invalidMode = normalizedMode;
        String invalidPackage = "";
        boolean rebuildInvalidResources = false;
        boolean reusableResources = false;
        boolean reusingIdleResources = false;
        boolean preserveVisibleOwner = false;
        int previousMode = normalizedMode;
        synchronized (lock) {
            if (projectionRecoveryInProgress) {
                log("projection deferred recovery-in-progress package=" + packageName
                        + " reason=" + safe(reason));
                return;
            }
            if (projectionRequested && !projectionGeometryValid) {
                invalidActiveGeometry = true;
                invalidGeneration = projectionGeneration;
                invalidOwnerToken = projectionOwnerToken;
                invalidMode = projectionMode;
                invalidPackage = projectedPackage;
            } else {
                reusableResources = hasReusableResourcesLocked();
                boolean allocationPending = overlayRoot != null
                        && overlayRoot.isAttachedToWindow()
                        && overlaySurfaceView != null
                        && overlayWindowManager != null
                        && virtualDisplay == null;
                rebuildInvalidResources = hasAllocatedResourcesLocked()
                        && !reusableResources
                        && !allocationPending;
                reusingIdleResources = reusableResources && !projectionRequested;
                preserveVisibleOwner = reusableResources
                        && projectionRequested
                        && projectionContentVisible
                        && packageName.equals(projectedPackage);
                previousMode = projectionMode;
            }
        }
        if (invalidActiveGeometry) {
            log("projection recovering invalid geometry package=" + invalidPackage
                    + " requestedPackage=" + packageName
                    + " reason=" + safe(reason));
            recoverProjectionAfterResizeFailure(
                    invalidPackage,
                    invalidGeneration,
                    invalidOwnerToken,
                    invalidMode,
                    "projection-request:" + safe(reason));
            return;
        }
        if (rebuildInvalidResources) {
            releaseProjection("rebuild-invalid " + safe(reason));
            reusableResources = false;
            reusingIdleResources = false;
        }
        final int requestGeneration;
        final long requestOwnerToken;
        synchronized (lock) {
            projectionGeneration++;
            long nextToken = NEXT_PROJECTION_TOKEN.incrementAndGet();
            projectionOwnerToken = nextToken <= 0L ? 1L : nextToken;
            projectionRequested = true;
            pendingPackage = packageName;
            projectedPackage = packageName;
            projectionMode = normalizedMode;
            projectionContentVisible = preserveVisibleOwner;
            projectionPlacementReady = preserveVisibleOwner;
            surfaceGeneration++;
            requestGeneration = projectionGeneration;
            requestOwnerToken = projectionOwnerToken;
        }
        updateNotification("Projecting " + packageName);
        log("projection requested package=" + packageName
                + " mode=surface_view"
                + " dashboardMode=" + normalizedMode
                + " reason=" + reason);
        ensureOverlay();
        if (!preserveVisibleOwner) {
            setProjectionCoverVisible(true, "projection-requested");
        }
        VirtualDisplay existing;
        synchronized (lock) {
            existing = virtualDisplay;
        }
        if (existing != null && existing.getDisplay() != null) {
            DashboardProjectionPolicy.Geometry requestedGeometry =
                    DashboardProjectionPolicy.geometryForProfile(
                            DashboardProjectionPolicy.nativeProfileForMode(
                                    normalizedMode,
                                    HudPrefs.dashboardProjectionProfile(this, normalizedMode)));
            boolean resizeSucceeded = resizeActiveProjection(
                    normalizedMode,
                    HudPrefs.dashboardProjectionProfile(this, normalizedMode),
                    "projection-mode-update:" + reason);
            boolean requestedGeometrySucceeded;
            synchronized (lock) {
                requestedGeometrySucceeded = virtualDisplay == existing
                        && projectionGeneration == requestGeneration
                        && projectionOwnerToken == requestOwnerToken
                        && packageName.equals(projectedPackage)
                        && projectionMode == normalizedMode
                        && ProjectionLifecyclePolicy.requestedGeometrySucceeded(
                                resizeSucceeded,
                                projectionGeometryValid,
                                projectionWidth,
                                projectionHeight,
                                projectionBufferWidth,
                                projectionBufferHeight,
                                projectionLeft,
                                projectionTop,
                                requestedGeometry.width,
                                requestedGeometry.height,
                                requestedGeometry.bufferWidth,
                                requestedGeometry.bufferHeight,
                                requestedGeometry.left,
                                requestedGeometry.top);
                if (requestedGeometrySucceeded) {
                    projectionPlacementReady = true;
                }
            }
            if (!requestedGeometrySucceeded) {
                handleProjectionRequestResizeFailure(
                        packageName,
                        requestGeneration,
                        requestOwnerToken,
                        preserveVisibleOwner,
                        previousMode,
                        normalizedMode,
                        reason);
                return;
            }
            log("projection_resource_reused state="
                    + (reusingIdleResources ? "idle" : "active")
                    + " id=" + existing.getDisplay().getDisplayId()
                    + " package=" + packageName);
            movePackageToDisplay(
                    packageName,
                    existing.getDisplay().getDisplayId(),
                    "project-existing " + reason);
            return;
        }
        createVirtualDisplayIfReady(packageName, reason);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private void returnPackageToMain(String packageName, String reason, long shutdownToken,
            int returnGeneration, long returnOwnerToken) {
        String targetPackage;
        synchronized (lock) {
            targetPackage = packageName.isEmpty() ? projectedPackage : packageName;
        }
        if (targetPackage.isEmpty()) {
            log("return-main failed package=missing reason=" + reason);
            return;
        }
        Thread worker = new Thread(() -> {
            NavAppDisplayController controller = NavAppDisplayController.get(this);
            long intentGeneration = controller.projectionGenerationForPackage(targetPackage);
            BooleanSupplier requestCurrent = () -> isReturnMoveCurrent(
                    targetPackage, returnGeneration, returnOwnerToken, shutdownToken);
            NavAppDisplayState returned = NavAppDisplayController.get(this)
                    .moveTaskToDisplayBlocking(
                            targetPackage,
                            MAIN_DISPLAY_ID,
                            "cluster-projection return-main " + reason,
                            requestCurrent);
            if (returned.taskId < 0 || returned.displayId != MAIN_DISPLAY_ID) {
                log("return-main failed package=" + targetPackage
                        + " task=" + returned.taskId
                        + " display=" + returned.displayId
                        + " reason=" + reason);
                return;
            }
            mainHandler.post(() -> {
                if (!isReturnOwnerCurrent(targetPackage, returnGeneration, returnOwnerToken)
                        || !shouldRetainAfterReturn(targetPackage, returnGeneration)) {
                    log("return-main failed stale package=" + targetPackage
                            + " reason=" + reason);
                    return;
                }
                controller.clearReturnedProjectionIntent(targetPackage, intentGeneration, reason);
                retainProjectionIdle("return-main " + reason);
            });
        }, "BydHudClusterProjectionReturn");
        worker.start();
    }

    private boolean isReturnMoveCurrent(
            String packageName, int expectedGeneration, long expectedOwnerToken,
            long shutdownToken) {
        if (!NavAppDisplayController.get(this).isShutdownReturnCurrent(shutdownToken)) return false;
        return isReturnOwnerCurrent(packageName, expectedGeneration, expectedOwnerToken);
    }

    private boolean isReturnOwnerCurrent(
            String packageName, int expectedGeneration, long expectedOwnerToken) {
        synchronized (lock) {
            return projectionGeneration == expectedGeneration
                    && projectionOwnerToken == expectedOwnerToken
                    && (projectedPackage.isEmpty() || projectedPackage.equals(packageName));
        }
    }

    //starts or schedules work here so lifecycle recovery follows one controlled path.
    private void ensureOverlay() {
        synchronized (lock) {
            if (overlayRoot != null) {
                return;
            }
        }
        if (!Settings.canDrawOverlays(this)) {
            log("overlay permission missing; trying addView anyway");
        }
        Display targetDisplay = chooseClusterDisplay();
        if (targetDisplay == null) {
            log("projection failed: no display available");
            return;
        }
        Context displayContext = createDisplayContext(targetDisplay);
        WindowManager manager =
                (WindowManager) displayContext.getSystemService(Context.WINDOW_SERVICE);
        if (manager == null) {
            log("projection failed: no WindowManager for display=" + targetDisplay.getDisplayId());
            return;
        }
        SurfaceView surfaceView = new SurfaceView(displayContext);
        FrameLayout root = new FrameLayout(displayContext);
        View cover = new View(displayContext);
        cover.setBackgroundColor(Color.BLACK);
        cover.setClickable(false);
        cover.setFocusable(false);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(cover, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        int dashboardMode;
        synchronized (lock) {
            dashboardMode = projectionMode;
        }
        DashboardProjectionPolicy.Geometry geometry = DashboardProjectionPolicy
                .geometryForProfile(DashboardProjectionPolicy.nativeProfileForMode(
                        dashboardMode,
                        HudPrefs.dashboardProjectionProfile(this, dashboardMode)));
        surfaceView.getHolder().setFixedSize(geometry.bufferWidth, geometry.bufferHeight);
        surfaceView.getHolder().addCallback(this);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                geometry.width,
                geometry.height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = geometry.left;
        params.y = geometry.top;
        synchronized (lock) {
            overlayWindowManager = manager;
            overlayRoot = root;
            overlaySurfaceView = surfaceView;
            projectionCoverView = cover;
            projectionCoverVisible = true;
            projectionContentVisible = false;
            projectionWidth = geometry.width;
            projectionHeight = geometry.height;
            projectionBufferWidth = geometry.bufferWidth;
            projectionBufferHeight = geometry.bufferHeight;
            projectionLeft = geometry.left;
            projectionTop = geometry.top;
            projectionGeometryValid = true;
        }
        try {
            manager.addView(root, params);
        } catch (RuntimeException e) {
            synchronized (lock) {
                if (overlayRoot == root) {
                    overlayWindowManager = null;
                    overlayRoot = null;
                    overlaySurfaceView = null;
                    projectionCoverView = null;
                    projectionSurface = null;
                    projectionCoverVisible = true;
                    projectionContentVisible = false;
                }
            }
            log("overlay add failed display=" + targetDisplay.getDisplayId()
                    + " mode=surface_view"
                    + " " + e.getClass().getSimpleName() + " " + safe(e.getMessage()));
            return;
        }
        log("overlay added mode=surface_view display=" + targetDisplay.getDisplayId()
                + " name=" + targetDisplay.getName());
        log("projection_cover shown reason=overlay-allocated");
    }

    //builds this artifact here so callers do not duplicate protocol or UI construction details.
    @SuppressLint("WrongConstant")
    private void createVirtualDisplayIfReady(String packageName, String reason) {
        Surface surface;
        int bufferWidth;
        int bufferHeight;
        int windowWidth;
        int windowHeight;
        int left;
        int top;
        int expectedGeneration;
        long expectedOwnerToken;
        String expectedPackage;
        synchronized (lock) {
            if (!projectionRequested || !projectionGeometryValid
                    || virtualDisplay != null || projectionSurface == null
                    || !projectionSurface.isValid()) {
                return;
            }
            surface = projectionSurface;
            bufferWidth = projectionBufferWidth;
            bufferHeight = projectionBufferHeight;
            windowWidth = projectionWidth;
            windowHeight = projectionHeight;
            left = projectionLeft;
            top = projectionTop;
            expectedGeneration = projectionGeneration;
            expectedOwnerToken = projectionOwnerToken;
            expectedPackage = projectedPackage;
        }
        DisplayManager displayManager =
                (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (displayManager == null) {
            log("projection failed: no DisplayManager");
            return;
        }
        VirtualDisplay created = displayManager.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                bufferWidth,
                bufferHeight,
                VIRTUAL_DENSITY,
                surface,
                VIRTUAL_DISPLAY_FLAGS);
        if (created == null || created.getDisplay() == null || !created.getDisplay().isValid()) {
            if (created != null) created.release();
            log("projection failed: createVirtualDisplay unavailable");
            return;
        }
        int displayId = created.getDisplay().getDisplayId();
        boolean accepted;
        synchronized (lock) {
            accepted = projectionRequested
                    && projectionGeometryValid
                    && virtualDisplay == null
                    && projectionSurface == surface
                    && projectionGeneration == expectedGeneration
                    && projectionOwnerToken == expectedOwnerToken
                    && expectedPackage.equals(projectedPackage);
            if (accepted) {
                virtualDisplay = created;
                projectionPlacementReady = true;
            }
        }
        if (!accepted) {
            created.release();
            log("projection_resource_allocation_discarded state=stale package="
                    + safe(expectedPackage));
            return;
        }
        log("projection_resource_allocated mode=surface_view"
                + " id=" + displayId + " package=" + packageName
                + " window=" + windowWidth + "x" + windowHeight
                + " buffer=" + bufferWidth + "x" + bufferHeight
                + " left=" + left + " top=" + top);
        if (!expectedPackage.isEmpty()) {
            movePackageToDisplay(expectedPackage, displayId, "project " + reason);
        }
    }

    private String applyDashboardProfileForWidgetBlocking(
            String packageName,
            int dashboardMode,
            DashboardProjectionPolicy.Profile profile,
            String reason,
            long expectedProjectionGeneration,
            BooleanSupplier stillCurrent) {
        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicBoolean gate = new AtomicBoolean(true);
        final AtomicReference<String> result = new AtomicReference<>(
                "profile resize timeout");
        Runnable resize = () -> {
            if (!gate.get()) {
                return;
            }
            BooleanSupplier requestCurrent = () -> gate.get()
                    && isCurrentWidgetResize(
                            packageName, expectedProjectionGeneration, stillCurrent);
            if (!gate.get()
                    || !isCurrentWidgetRequest(
                            packageName, expectedProjectionGeneration, stillCurrent)) {
                result.set("stale widget operation");
                gate.set(false);
                completed.countDown();
                return;
            }
            try {
                result.set(resizeActiveProjectionForWidget(
                        packageName,
                        expectedProjectionGeneration,
                        dashboardMode,
                        profile,
                        requestCurrent));
            } finally {
                gate.set(false);
                completed.countDown();
            }
        };
        try {
            mainHandler.post(resize);
        } catch (RuntimeException e) {
            return "profile resize failed: " + safe(e.getMessage());
        }
        try {
            if (!completed.await(5000L, TimeUnit.MILLISECONDS)) {
                gate.set(false);
                mainHandler.removeCallbacks(resize);
                return result.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            gate.set(false);
            mainHandler.removeCallbacks(resize);
            return "profile resize interrupted";
        }
        return result.get();
    }

    // Adjustable cluster-window geometry was inspired by BYDMate's projection controls.
    private boolean resizeActiveProjection(
            int dashboardMode,
            DashboardProjectionPolicy.Profile profile,
            String reason) {
        int normalizedMode = HudPrefs.normalizeDashboardScreenMode(dashboardMode);
        DashboardProjectionPolicy.Geometry geometry = DashboardProjectionPolicy.geometryForProfile(
                DashboardProjectionPolicy.nativeProfileForMode(normalizedMode, profile));
        VirtualDisplay display;
        FrameLayout root;
        SurfaceView view;
        WindowManager manager;
        int oldWidth;
        int oldHeight;
        int oldBufferWidth;
        int oldBufferHeight;
        int oldLeft;
        int oldTop;
        boolean oldPlacementReady;
        int expectedProjectionGeneration;
        long expectedOwnerToken;
        String packageName;
        synchronized (lock) {
            if (!projectionRequested
                    || !projectionGeometryValid
                    || virtualDisplay == null
                    || virtualDisplay.getDisplay() == null
                    || !virtualDisplay.getDisplay().isValid()
                    || overlayRoot == null
                    || overlaySurfaceView == null
                    || overlayWindowManager == null) {
                log("profile_resize_deferred projection=inactive mode="
                        + normalizedMode + " reason=" + safe(reason));
                return false;
            }
            if (projectionMode != normalizedMode) {
                log("profile_resize_skipped mode_mismatch active=" + projectionMode
                        + " requested=" + normalizedMode + " reason=" + safe(reason));
                return false;
            }
            if (projectionWidth == geometry.width
                    && projectionHeight == geometry.height
                    && projectionBufferWidth == geometry.bufferWidth
                    && projectionBufferHeight == geometry.bufferHeight
                    && projectionLeft == geometry.left
                    && projectionTop == geometry.top) {
                log("profile_resize_skipped unchanged mode=" + normalizedMode);
                return true;
            }
            display = virtualDisplay;
            root = overlayRoot;
            view = overlaySurfaceView;
            manager = overlayWindowManager;
            oldWidth = projectionWidth;
            oldHeight = projectionHeight;
            oldBufferWidth = projectionBufferWidth;
            oldBufferHeight = projectionBufferHeight;
            oldLeft = projectionLeft;
            oldTop = projectionTop;
            oldPlacementReady = projectionPlacementReady;
            expectedProjectionGeneration = projectionGeneration;
            expectedOwnerToken = projectionOwnerToken;
            packageName = projectedPackage;
        }
        if (!(root.getLayoutParams() instanceof WindowManager.LayoutParams)) {
            log("profile_resize_failed layout_params=missing reason=" + safe(reason));
            return false;
        }
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) root.getLayoutParams();
        synchronized (lock) {
            if (virtualDisplay != display
                    || overlayRoot != root
                    || overlaySurfaceView != view
                    || projectionGeneration != expectedProjectionGeneration
                    || projectionOwnerToken != expectedOwnerToken
                    || !packageName.equals(projectedPackage)) {
                log("profile_resize_skipped projection_changed reason=" + safe(reason));
                return false;
            }
            projectionGeometryValid = false;
            projectionPlacementReady = false;
            surfaceGeneration++;
        }
        try {
            view.getHolder().setFixedSize(geometry.bufferWidth, geometry.bufferHeight);
            display.resize(geometry.bufferWidth, geometry.bufferHeight, geometry.density);
            params.width = geometry.width;
            params.height = geometry.height;
            params.x = geometry.left;
            params.y = geometry.top;
            manager.updateViewLayout(root, params);
            synchronized (lock) {
                if (virtualDisplay != display
                        || overlayRoot != root
                        || overlaySurfaceView != view
                        || projectionGeneration != expectedProjectionGeneration
                        || projectionOwnerToken != expectedOwnerToken
                        || !packageName.equals(projectedPackage)) {
                    throw new IllegalStateException("projection changed during resize");
                }
                projectionWidth = geometry.width;
                projectionHeight = geometry.height;
                projectionBufferWidth = geometry.bufferWidth;
                projectionBufferHeight = geometry.bufferHeight;
                projectionLeft = geometry.left;
                projectionTop = geometry.top;
                projectionGeometryValid = true;
                projectionPlacementReady = oldPlacementReady;
                surfaceGeneration++;
            }
            log("profile_resize_applied package=" + projectedPackage
                    + " mode=" + normalizedMode
                    + " window=" + geometry.width + "x" + geometry.height
                    + " buffer=" + geometry.bufferWidth + "x" + geometry.bufferHeight
                    + " left=" + geometry.left + " top=" + geometry.top);
            return true;
        } catch (RuntimeException e) {
            try {
                view.getHolder().setFixedSize(oldBufferWidth, oldBufferHeight);
                display.resize(oldBufferWidth, oldBufferHeight, VIRTUAL_DENSITY);
                params.width = oldWidth;
                params.height = oldHeight;
                params.x = oldLeft;
                params.y = oldTop;
                manager.updateViewLayout(root, params);
                synchronized (lock) {
                    if (virtualDisplay == display
                            && overlayRoot == root
                            && overlaySurfaceView == view
                            && projectionGeneration == expectedProjectionGeneration
                            && projectionOwnerToken == expectedOwnerToken
                            && packageName.equals(projectedPackage)) {
                        projectionWidth = oldWidth;
                        projectionHeight = oldHeight;
                        projectionBufferWidth = oldBufferWidth;
                        projectionBufferHeight = oldBufferHeight;
                        projectionLeft = oldLeft;
                        projectionTop = oldTop;
                        projectionGeometryValid = true;
                        projectionPlacementReady = oldPlacementReady;
                        surfaceGeneration++;
                    }
                }
            } catch (RuntimeException rollbackError) {
                synchronized (lock) {
                    if (virtualDisplay == display
                            && overlayRoot == root
                            && overlaySurfaceView == view
                            && projectionGeneration == expectedProjectionGeneration
                            && projectionOwnerToken == expectedOwnerToken
                            && packageName.equals(projectedPackage)) {
                        projectionGeometryValid = false;
                        projectionPlacementReady = false;
                        surfaceGeneration++;
                    }
                }
                log("profile_resize_rollback_failed error="
                        + rollbackError.getClass().getSimpleName());
                recoverProjectionAfterResizeFailure(
                        packageName,
                        expectedProjectionGeneration,
                        expectedOwnerToken,
                        normalizedMode,
                        safe(reason));
            }
            log("profile_resize_failed error=" + e.getClass().getSimpleName()
                    + " reason=" + safe(reason));
            return false;
        }
    }

    private void handleProjectionRequestResizeFailure(
            String packageName,
            int requestGeneration,
            long requestOwnerToken,
            boolean preserveVisibleOwner,
            int previousMode,
            int requestedMode,
            String reason) {
        ProjectionLifecyclePolicy.FailedRequestTransition transition;
        synchronized (lock) {
            if (projectionGeneration != requestGeneration
                    || projectionOwnerToken != requestOwnerToken
                    || !packageName.equals(projectedPackage)) {
                log("projection_resize_request_failed stale package=" + packageName
                        + " reason=" + safe(reason));
                return;
            }
            transition = ProjectionLifecyclePolicy.failedRequestTransition(
                    projectionGeometryValid, preserveVisibleOwner);
            if (transition == ProjectionLifecyclePolicy.FailedRequestTransition.KEEP_VISIBLE_OWNER) {
                projectionMode = previousMode;
                projectionPlacementReady = true;
            } else if (transition
                    == ProjectionLifecyclePolicy.FailedRequestTransition.RETAIN_IDLE) {
                projectionGeneration++;
                projectionOwnerToken = 0L;
                projectionRequested = false;
                projectedPackage = "";
                pendingPackage = "";
                projectionContentVisible = false;
                projectionPlacementReady = false;
                surfaceGeneration++;
            } else {
                projectionPlacementReady = false;
            }
        }
        if (transition == ProjectionLifecyclePolicy.FailedRequestTransition.RETAIN_IDLE) {
            setProjectionCoverVisible(true, "projection-resize-failed");
            updateNotification("Dashboard projection idle");
            log("projection_idle retained=true reason=projection-resize-failed "
                    + safe(reason));
            return;
        }
        if (transition
                == ProjectionLifecyclePolicy.FailedRequestTransition.RECOVER_INVALID_GEOMETRY) {
            recoverProjectionAfterResizeFailure(
                    packageName,
                    requestGeneration,
                    requestOwnerToken,
                    requestedMode,
                    "projection-resize-failed:" + safe(reason));
            return;
        }
        log("projection_resize_request_failed preserved_visible_owner=true package="
                + packageName + " reason=" + safe(reason));
    }

    private boolean isCurrentWidgetRequest(
            String packageName,
            long expectedProjectionGeneration,
            BooleanSupplier stillCurrent) {
        if (stillCurrent == null || !stillCurrent.getAsBoolean()) {
            return false;
        }
        synchronized (lock) {
            return expectedProjectionGeneration > 0L
                    && projectionRequested
                    && projectionGeometryValid
                    && projectionOwnerToken == expectedProjectionGeneration
                    && safe(packageName).equals(safe(projectedPackage))
                    && virtualDisplay != null
                    && virtualDisplay.getDisplay() != null
                    && projectionSurface != null
                    && projectionSurface.isValid();
        }
    }

    //Checks ownership after geometry is marked transiently invalid during an in-place resize.
    private boolean isCurrentWidgetResize(
            String packageName,
            long expectedProjectionGeneration,
            BooleanSupplier stillCurrent) {
        if (stillCurrent == null || !stillCurrent.getAsBoolean()) {
            return false;
        }
        synchronized (lock) {
            return isExpectedWidgetProjectionLocked(packageName, expectedProjectionGeneration)
                    && projectionSurface != null
                    && projectionSurface.isValid();
        }
    }

    //Resizes only the expected live owner; only rollback failure starts fenced recovery.
    private String resizeActiveProjectionForWidget(
            String expectedPackage,
            long expectedProjectionGeneration,
            int dashboardMode,
            DashboardProjectionPolicy.Profile profile,
            BooleanSupplier stillCurrent) {
        String packageName = safe(expectedPackage);
        int normalizedMode = HudPrefs.normalizeDashboardScreenMode(dashboardMode);
        DashboardProjectionPolicy.Geometry geometry = DashboardProjectionPolicy.geometryForProfile(
                DashboardProjectionPolicy.nativeProfileForMode(normalizedMode, profile));
        VirtualDisplay display;
        FrameLayout root;
        SurfaceView view;
        WindowManager manager;
        int oldWidth;
        int oldHeight;
        int oldBufferWidth;
        int oldBufferHeight;
        int oldLeft;
        int oldTop;
        boolean oldPlacementReady;
        int expectedServiceGeneration;
        synchronized (lock) {
            if (!isCurrentWidgetRequestLocked(packageName, expectedProjectionGeneration)) {
                return "stale projection owner";
            }
            if (projectionWidth == geometry.width
                    && projectionHeight == geometry.height
                    && projectionBufferWidth == geometry.bufferWidth
                    && projectionBufferHeight == geometry.bufferHeight
                    && projectionLeft == geometry.left
                    && projectionTop == geometry.top) {
                if (stillCurrent == null || !stillCurrent.getAsBoolean()) {
                    return "stale widget operation";
                }
                projectionMode = normalizedMode;
                log("profile_resize_skipped unchanged mode=" + normalizedMode
                        + " identity_updated=true");
                return "";
            }
            display = virtualDisplay;
            root = overlayRoot;
            view = overlaySurfaceView;
            manager = overlayWindowManager;
            oldWidth = projectionWidth;
            oldHeight = projectionHeight;
            oldBufferWidth = projectionBufferWidth;
            oldBufferHeight = projectionBufferHeight;
            oldLeft = projectionLeft;
            oldTop = projectionTop;
            oldPlacementReady = projectionPlacementReady;
            expectedServiceGeneration = projectionGeneration;
            projectionGeometryValid = false;
            projectionPlacementReady = false;
            surfaceGeneration++;
        }
        if (!(root.getLayoutParams() instanceof WindowManager.LayoutParams)) {
            synchronized (lock) {
                if (virtualDisplay == display
                        && overlayRoot == root
                        && overlaySurfaceView == view
                        && isExpectedWidgetProjectionLocked(
                                packageName, expectedProjectionGeneration)
                        && packageName.equals(safe(projectedPackage))) {
                    projectionGeometryValid = true;
                    projectionPlacementReady = oldPlacementReady;
                    surfaceGeneration++;
                }
            }
            return "layout params missing";
        }
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) root.getLayoutParams();
        if (!isCurrentWidgetResize(packageName, expectedProjectionGeneration, stillCurrent)) {
            synchronized (lock) {
                if (virtualDisplay == display
                        && overlayRoot == root
                        && overlaySurfaceView == view
                        && isExpectedWidgetProjectionLocked(
                                packageName, expectedProjectionGeneration)
                        && packageName.equals(safe(projectedPackage))) {
                    projectionGeometryValid = true;
                    projectionPlacementReady = oldPlacementReady;
                    surfaceGeneration++;
                }
            }
            return "stale widget operation";
        }
        try {
            view.getHolder().setFixedSize(geometry.bufferWidth, geometry.bufferHeight);
            display.resize(geometry.bufferWidth, geometry.bufferHeight, geometry.density);
            params.width = geometry.width;
            params.height = geometry.height;
            params.x = geometry.left;
            params.y = geometry.top;
            manager.updateViewLayout(root, params);
            synchronized (lock) {
                boolean expectedOwner = isExpectedWidgetProjectionLocked(
                        packageName, expectedProjectionGeneration);
                boolean requestCurrent = stillCurrent != null
                        && stillCurrent.getAsBoolean();
                if (!widgetResizeCommitAllowedForTest(expectedOwner, requestCurrent)
                        || virtualDisplay != display
                        || overlayRoot != root
                        || overlaySurfaceView != view) {
                    throw new IllegalStateException("widget projection changed during resize");
                }
                projectionWidth = geometry.width;
                projectionHeight = geometry.height;
                projectionBufferWidth = geometry.bufferWidth;
                projectionBufferHeight = geometry.bufferHeight;
                projectionLeft = geometry.left;
                projectionTop = geometry.top;
                projectionMode = normalizedMode;
                projectionGeometryValid = true;
                projectionPlacementReady = oldPlacementReady;
                surfaceGeneration++;
            }
            log("profile_resize_applied package=" + packageName
                    + " mode=" + normalizedMode
                    + " widget=true window=" + geometry.width + "x" + geometry.height
                    + " buffer=" + geometry.bufferWidth + "x" + geometry.bufferHeight
                    + " left=" + geometry.left + " top=" + geometry.top);
            return "";
        } catch (RuntimeException error) {
            try {
                view.getHolder().setFixedSize(oldBufferWidth, oldBufferHeight);
                display.resize(oldBufferWidth, oldBufferHeight, VIRTUAL_DENSITY);
                params.width = oldWidth;
                params.height = oldHeight;
                params.x = oldLeft;
                params.y = oldTop;
                manager.updateViewLayout(root, params);
                synchronized (lock) {
                    if (virtualDisplay == display
                            && overlayRoot == root
                            && overlaySurfaceView == view
                            && isExpectedWidgetProjectionLocked(
                                    packageName, expectedProjectionGeneration)
                            && packageName.equals(safe(projectedPackage))) {
                        projectionWidth = oldWidth;
                        projectionHeight = oldHeight;
                        projectionBufferWidth = oldBufferWidth;
                        projectionBufferHeight = oldBufferHeight;
                        projectionLeft = oldLeft;
                        projectionTop = oldTop;
                        projectionGeometryValid = true;
                        projectionPlacementReady = oldPlacementReady;
                        surfaceGeneration++;
                    }
                }
            } catch (RuntimeException rollbackError) {
                synchronized (lock) {
                    if (virtualDisplay == display
                            && overlayRoot == root
                            && overlaySurfaceView == view
                            && isExpectedWidgetProjectionLocked(
                                    packageName, expectedProjectionGeneration)
                            && packageName.equals(safe(projectedPackage))) {
                        projectionGeometryValid = false;
                        projectionPlacementReady = false;
                        surfaceGeneration++;
                    }
                }
                log("profile_resize_rollback_failed widget=true error="
                        + rollbackError.getClass().getSimpleName());
                recoverProjectionAfterResizeFailure(
                        packageName,
                        expectedServiceGeneration,
                        expectedProjectionGeneration,
                        normalizedMode,
                        "widget-profile-resize");
                return "profile resize rollback failed";
            }
            if (stillCurrent == null || !stillCurrent.getAsBoolean()) {
                return "stale widget operation";
            }
            log("profile_resize_failed widget=true error="
                    + error.getClass().getSimpleName());
            return "profile resize failed";
        }
    }

    private boolean isCurrentWidgetRequestLocked(
            String packageName, long expectedProjectionGeneration) {
        return isExpectedWidgetProjectionLocked(packageName, expectedProjectionGeneration)
                && projectionGeometryValid;
    }

    private boolean isExpectedWidgetProjectionLocked(
            String packageName, long expectedProjectionGeneration) {
        return expectedProjectionGeneration > 0L
                && projectionRequested
                && projectionOwnerToken == expectedProjectionGeneration
                && safe(packageName).equals(safe(projectedPackage))
                && virtualDisplay != null
                && virtualDisplay.getDisplay() != null
                && virtualDisplay.getDisplay().isValid()
                && overlayRoot != null
                && overlaySurfaceView != null
                && overlayWindowManager != null;
    }

    private void recoverProjectionAfterResizeFailure(
            String packageName,
            int expectedProjectionGeneration,
            long expectedOwnerToken,
            int dashboardMode,
            String reason) {
        synchronized (lock) {
            if (projectionRecoveryInProgress
                    || !ProjectionLifecyclePolicy.matchesInvalidRecoveryOwner(
                            projectionRequested,
                            projectionGeometryValid,
                            projectedPackage,
                            projectionGeneration,
                            projectionOwnerToken,
                            packageName,
                            expectedProjectionGeneration,
                            expectedOwnerToken)) {
                log("profile_resize_recovery_skipped stale-or-claimed package=" + packageName);
                return;
            }
            projectionRecoveryInProgress = true;
        }
        setProjectionCoverVisible(true, "profile-resize-recovery");
        BooleanSupplier recoveryCurrent = () -> isResizeRecoveryCurrent(
                packageName, expectedProjectionGeneration, expectedOwnerToken);
        Thread worker = new Thread(() -> {
            NavAppDisplayController controller = NavAppDisplayController.get(this);
            long intentGeneration = controller.projectionGenerationForPackage(packageName);
            NavAppDisplayState returned = controller.moveTaskToDisplayBlocking(
                            packageName,
                            MAIN_DISPLAY_ID,
                            "cluster-projection profile-resize-recovery " + reason,
                            recoveryCurrent);
            mainHandler.post(() -> {
                boolean current;
                synchronized (lock) {
                    current = ProjectionLifecyclePolicy.matchesInvalidRecoveryOwner(
                            projectionRequested,
                            projectionGeometryValid,
                            projectedPackage,
                            projectionGeneration,
                            projectionOwnerToken,
                            packageName,
                            expectedProjectionGeneration,
                            expectedOwnerToken);
                    projectionRecoveryInProgress = false;
                    if (!current) {
                        log("profile_resize_recovery_skipped stale package=" + packageName);
                        return;
                    }
                }
                if (returned.taskId < 0 || returned.displayId != MAIN_DISPLAY_ID) {
                    log("profile_resize_recovery_failed package=" + packageName
                            + " task=" + returned.taskId
                            + " display=" + returned.displayId);
                    return;
                }
                if (HudPrefs.isUserShutdownActive(this)) {
                    boolean shutdownReleasePending;
                    synchronized (lock) {
                        shutdownReleasePending = pendingShutdownReleaseToken != 0L;
                    }
                    String shutdownReason = "profile-resize-recovery-shutdown:" + safe(reason);
                    controller.clearReturnedProjectionIntent(
                            packageName, intentGeneration, shutdownReason);
                    retainProjectionIdle(shutdownReason);
                    if (!shutdownReleasePending && !stopColdIdleServiceAfterShutdown()) {
                        releaseIdleProjectionForShutdownOnMain(
                                UserRuntimeSession.PROCESS.shutdownToken(), shutdownReason);
                    }
                    return;
                }
                releaseProjection("profile-resize-recovery");
                requestProjection(packageName, dashboardMode, "profile-resize-recovery:" + reason);
            });
        }, "BydHudClusterProjectionResizeRecovery");
        worker.start();
    }

    private boolean isResizeRecoveryCurrent(
            String packageName, int expectedProjectionGeneration, long expectedOwnerToken) {
        synchronized (lock) {
            return projectionRecoveryInProgress
                    && ProjectionLifecyclePolicy.matchesInvalidRecoveryOwner(
                            projectionRequested,
                            projectionGeometryValid,
                            projectedPackage,
                            projectionGeneration,
                            projectionOwnerToken,
                            packageName,
                            expectedProjectionGeneration,
                            expectedOwnerToken);
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private void movePackageToDisplay(String packageName, int displayId, String reason) {
        final int moveGeneration;
        final long moveOwnerToken;
        synchronized (lock) {
            moveGeneration = projectionGeneration;
            moveOwnerToken = projectionOwnerToken;
        }
        Thread worker = new Thread(
                () -> {
                    String staleReason = staleMoveReason(
                            packageName, displayId, moveGeneration, moveOwnerToken);
                    if (!staleReason.isEmpty()) {
                        log("move skipped stale " + staleReason + " package=" + safe(packageName)
                                + " display=" + displayId + " reason=" + reason);
                        return;
                    }
                    NavAppDisplayState moved = NavAppDisplayController.get(this)
                            .moveTaskToDisplayBlocking(
                            packageName,
                            displayId,
                            "cluster-projection " + reason,
                            () -> staleMoveReason(
                                    packageName,
                                    displayId,
                                    moveGeneration,
                                    moveOwnerToken).isEmpty());
                    if (moved.taskId >= 0
                            && moved.displayId == displayId
                            && moved.visible) {
                        mainHandler.post(() -> confirmProjectionVisibleOnMain(
                                packageName,
                                displayId,
                                moveOwnerToken,
                                "move-result-visible"));
                    }
                },
                "BydHudClusterProjectionMove");
        worker.start();
    }

    //guard dashboard moves so old workers cannot move an app after projection state changes.
    private String staleMoveReason(
            String packageName, int displayId, int moveGeneration, long moveOwnerToken) {
        synchronized (lock) {
            if (!projectionRequested || !projectionGeometryValid || !projectionPlacementReady) {
                return "projection=inactive-or-invalid";
            }
            if (moveGeneration != projectionGeneration) {
                return "generation=" + moveGeneration + " current=" + projectionGeneration;
            }
            if (!safe(packageName).equals(projectedPackage)) {
                return "projectedPackage=" + projectedPackage;
            }
            if (moveOwnerToken != projectionOwnerToken) {
                return "ownerToken=" + moveOwnerToken + " current=" + projectionOwnerToken;
            }
            if (virtualDisplay == null || virtualDisplay.getDisplay() == null) {
                return "display=missing";
            }
            if (!virtualDisplay.getDisplay().isValid()) {
                return "display=invalid";
            }
            int currentDisplayId = virtualDisplay.getDisplay().getDisplayId();
            if (displayId != currentDisplayId) {
                return "display=" + displayId + " current=" + currentDisplayId;
            }
            return "";
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private Display chooseClusterDisplay() {
        DisplayManager displayManager =
                (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (displayManager == null) {
            return null;
        }
        Display[] displays = displayManager.getDisplays();
        Display fallbackNonDefault = null;
        Display fallbackDisplayTwo = null;
        Display defaultDisplay = null;
        for (Display display : displays) {
            if (display == null) {
                continue;
            }
            String name = display.getName();
            int id = display.getDisplayId();
            if (id == MAIN_DISPLAY_ID) {
                defaultDisplay = display;
            }
            if (id == 2) {
                fallbackDisplayTwo = display;
            }
            if (id != MAIN_DISPLAY_ID && fallbackNonDefault == null
                    && !isVirtualProjectionDisplayName(name)) {
                fallbackNonDefault = display;
            }
            if (isClusterDisplayName(name)) {
                return display;
            }
        }
        if (fallbackDisplayTwo != null) {
            return fallbackDisplayTwo;
        }
        if (fallbackNonDefault != null) {
            return fallbackNonDefault;
        }
        return defaultDisplay;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean isClusterDisplayNameForTest(String name) {
        return isClusterDisplayName(name);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isClusterDisplayName(String name) {
        String value = NavTextNormalizer.lower(name);
        if (isVirtualProjectionDisplayName(value)) {
            return false;
        }
        return value.contains("fission")
                || value.contains("xdjascreenprojection")
                || value.contains("cluster")
                || value.contains("dashboard");
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isOwnProjectionDisplayName(String name) {
        return NavTextNormalizer.lower(name).contains(VIRTUAL_DISPLAY_NAME);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isVirtualProjectionDisplayName(String name) {
        String value = NavTextNormalizer.lower(name);
        return value.contains(VIRTUAL_DISPLAY_NAME)
                || value.contains("remote_dashboard");
    }

    private boolean hasAllocatedResourcesLocked() {
        return overlayRoot != null
                || overlaySurfaceView != null
                || projectionCoverView != null
                || projectionSurface != null
                || virtualDisplay != null
                || overlayWindowManager != null;
    }

    private boolean hasReusableResourcesLocked() {
        return ProjectionLifecyclePolicy.canReuseResources(
                overlayRoot != null
                        && overlayRoot.isAttachedToWindow()
                        && overlayRoot.getLayoutParams() instanceof WindowManager.LayoutParams,
                projectionSurface != null && projectionSurface.isValid(),
                virtualDisplay != null
                        && virtualDisplay.getDisplay() != null
                        && virtualDisplay.getDisplay().isValid(),
                projectionGeometryValid);
    }

    //A confirmed normal Return clears ownership while retaining the valid allocation.
    private void retainProjectionIdle(String reason) {
        setProjectionCoverVisible(true, reason);
        boolean reusable;
        synchronized (lock) {
            projectionGeneration++;
            pendingPackage = "";
            projectionRequested = false;
            projectedPackage = "";
            projectionOwnerToken = 0L;
            projectionContentVisible = false;
            projectionPlacementReady = false;
            surfaceGeneration++;
            reusable = hasReusableResourcesLocked();
        }
        updateNotification("Dashboard projection idle");
        if (!reusable) {
            releaseProjection("idle-resource-invalid " + safe(reason));
            drainPendingShutdownRelease(reason);
            return;
        }
        log("projection_idle retained=true reason=" + safe(reason));
        drainPendingShutdownRelease(reason);
    }

    private void drainPendingShutdownRelease(String reason) {
        if (pendingShutdownReleaseToken != 0L) {
            releaseIdleProjectionForShutdownOnMain(pendingShutdownReleaseToken, reason);
        }
    }

    private void releaseIdleProjectionForShutdownOnMain(
            long shutdownToken,
            String reason) {
        boolean shutdownCurrent = shutdownToken > 0L
                && NavAppDisplayController.get(this).isShutdownReturnCurrent(shutdownToken);
        if (!shutdownCurrent) {
            if (pendingShutdownReleaseToken == shutdownToken) pendingShutdownReleaseToken = 0L;
            log("shutdown_release_skipped state=runtime-resumed reason=" + safe(reason));
            return;
        }
        synchronized (lock) {
            pendingShutdownReleaseToken = shutdownToken;
            if (!ProjectionLifecyclePolicy.canReleaseIdleForShutdown(
                    shutdownCurrent,
                    projectionRequested,
                    projectedPackage,
                    pendingPackage,
                    projectionOwnerToken)) {
                log("shutdown_release_deferred state=active reason=" + safe(reason));
                return;
            }
        }
        pendingShutdownReleaseToken = 0L;
        releaseProjection("app-shutdown " + safe(reason));
        stopForegroundCompat();
        stopSelf();
    }

    private boolean stopColdIdleServiceAfterShutdown() {
        if (!HudPrefs.isUserShutdownActive(this)
                || UserRuntimeSession.PROCESS.shutdownToken() != 0L) return false;
        synchronized (lock) {
            if (hasAllocatedResourcesLocked()
                    || !ProjectionLifecyclePolicy.canReleaseIdleForShutdown(
                            true, projectionRequested, projectedPackage, pendingPackage,
                            projectionOwnerToken)) return false;
        }
        log("shutdown_stop_cold_idle_service");
        stopForegroundCompat();
        stopSelf();
        return true;
    }

    //stops or releases work here so stale capture and HUD output cannot keep running silently.
    private void releaseProjection(String reason) {
        VirtualDisplay display;
        View overlayView;
        WindowManager manager;
        boolean hadState;
        synchronized (lock) {
            hadState = hasAllocatedResourcesLocked()
                    || projectionRequested
                    || !pendingPackage.isEmpty()
                    || !projectedPackage.isEmpty();
            if (!hadState) return;
            display = virtualDisplay;
            overlayView = overlayRoot;
            manager = overlayWindowManager;
            virtualDisplay = null;
            projectionSurface = null;
            projectionCoverView = null;
            overlaySurfaceView = null;
            overlayRoot = null;
            overlayWindowManager = null;
            pendingPackage = "";
            projectionRequested = false;
            projectedPackage = "";
            projectionOwnerToken = 0L;
            projectionGeneration++;
            projectionMode = HudPrefs.DASHBOARD_MODE_FULL;
            projectionWidth = VIRTUAL_WIDTH;
            projectionHeight = VIRTUAL_HEIGHT;
            projectionBufferWidth = VIRTUAL_WIDTH;
            projectionBufferHeight = VIRTUAL_HEIGHT;
            projectionLeft = 0;
            projectionTop = 0;
            projectionGeometryValid = true;
            projectionPlacementReady = false;
            projectionCoverVisible = true;
            projectionContentVisible = false;
            surfaceGeneration++;
        }
        if (display != null) {
            display.release();
        }
        if (manager != null && overlayView != null) {
            try {
                manager.removeView(overlayView);
            } catch (RuntimeException e) {
                log("overlay remove failed " + e.getClass().getSimpleName()
                        + " " + safe(e.getMessage()));
            }
        }
        log("projection_full_release mode=surface_view reason=" + reason);
    }

    //copies the current surface metadata without transferring ownership to the caller.
    private ProjectedSurface currentProjectedSurface(String packageName) {
        synchronized (lock) {
            if (!projectionRequested
                    || !projectionPlacementReady
                    || !projectionGeometryValid
                    || virtualDisplay == null
                    || virtualDisplay.getDisplay() == null
                    || !virtualDisplay.getDisplay().isValid()
                    || projectionSurface == null
                    || !projectionSurface.isValid()
                    || !projectionGeometryValid
                    || !safe(projectedPackage).equals(safe(packageName))) {
                return null;
            }
            return new ProjectedSurface(
                    projectionSurface,
                    projectionBufferWidth,
                    projectionBufferHeight,
                    projectedPackage,
                    surfaceGeneration);
        }
    }

    //guards PixelCopy from consuming a surface after return-to-main or reprojection.
    private boolean isCurrentProjectedSurface(ProjectedSurface surface) {
        synchronized (lock) {
            return surface != null
                    && projectionRequested
                    && projectionPlacementReady
                    && virtualDisplay != null
                    && virtualDisplay.getDisplay() != null
                    && virtualDisplay.getDisplay().isValid()
                    && projectionSurface != null
                    && projectionSurface == surface.surface
                    && projectionSurface.isValid()
                    && projectionGeometryValid
                    && surfaceGeneration == surface.generation
                    && safe(projectedPackage).equals(surface.packageName);
        }
    }

    //guards watchdog repair so it only recreates projection when the current owned projection is missing.
    private boolean hasCurrentProjection(String packageName) {
        synchronized (lock) {
            return projectionRequested
                    && projectionPlacementReady
                    && virtualDisplay != null
                    && virtualDisplay.getDisplay() != null
                    && virtualDisplay.getDisplay().isValid()
                    && projectionSurface != null
                    && projectionSurface.isValid()
                    && projectionGeometryValid
                    && safe(projectedPackage).equals(safe(packageName));
        }
    }

    private void confirmProjectionVisibleOnMain(
            String packageName,
            int expectedDisplayId,
            long expectedOwnerToken,
            String reason) {
        int currentDisplayId;
        synchronized (lock) {
            currentDisplayId = virtualDisplay == null || virtualDisplay.getDisplay() == null
                    ? NavAppDisplayState.DISPLAY_UNKNOWN
                    : virtualDisplay.getDisplay().getDisplayId();
            if (!ProjectionLifecyclePolicy.canReveal(
                    projectionRequested,
                    projectionPlacementReady,
                    hasReusableResourcesLocked(),
                    projectedPackage,
                    currentDisplayId,
                    projectionOwnerToken,
                    packageName,
                    expectedDisplayId,
                    expectedOwnerToken)) {
                log("projection_cover_reveal_skipped package=" + safe(packageName)
                        + " display=" + expectedDisplayId
                        + " ownerToken=" + expectedOwnerToken
                        + " reason=" + safe(reason));
                return;
            }
            projectionContentVisible = true;
        }
        setProjectionCoverVisible(false, reason);
    }

    private void setProjectionCoverVisible(boolean visible, String reason) {
        View cover;
        synchronized (lock) {
            cover = projectionCoverView;
            if (cover == null || projectionCoverVisible == visible) return;
            projectionCoverVisible = visible;
        }
        cover.setVisibility(visible ? View.VISIBLE : View.GONE);
        log("projection_cover " + (visible ? "shown" : "hidden")
                + " reason=" + safe(reason));
    }

    private long currentProjectionToken(String packageName) {
        synchronized (lock) {
            if (!projectionRequested
                    || projectionOwnerToken <= 0L
                    || !projectionPlacementReady
                    || !projectionGeometryValid
                    || virtualDisplay == null
                    || virtualDisplay.getDisplay() == null
                    || !virtualDisplay.getDisplay().isValid()
                    || projectionSurface == null
                    || !projectionSurface.isValid()
                    || !safe(projectedPackage).equals(safe(packageName))) {
                return 0L;
            }
            return projectionOwnerToken;
        }
    }

    private boolean hasProjectionOwnerUnsafe() {
        synchronized (lock) {
            return projectionRequested
                    && !safe(projectedPackage).isEmpty();
        }
    }

    //reads the active virtual display id without letting callers mutate projection state.
    private int currentProjectedDisplayId(String packageName) {
        synchronized (lock) {
            if (!projectionRequested
                    || !projectionPlacementReady
                    || !projectionGeometryValid
                    || virtualDisplay == null
                    || virtualDisplay.getDisplay() == null
                    || !virtualDisplay.getDisplay().isValid()
                    || !safe(projectedPackage).equals(safe(packageName))) {
                return NavAppDisplayState.DISPLAY_UNKNOWN;
            }
            return virtualDisplay.getDisplay().getDisplayId();
        }
    }

    //bumps generation so future PixelCopy borrows a fresh snapshot without issuing display move commands.
    private boolean recoverProjectedSurface(String packageName, String reason) {
        Surface surface;
        synchronized (lock) {
            if (!projectionRequested
                    || !projectionPlacementReady
                    || virtualDisplay == null
                    || virtualDisplay.getDisplay() == null
                    || !virtualDisplay.getDisplay().isValid()
                    || overlaySurfaceView == null
                    || !projectionGeometryValid
                    || !safe(projectedPackage).equals(safe(packageName))) {
                log("surface_recover_skipped reason=projection-not-current package="
                        + safe(packageName) + " requestReason=" + safe(reason));
                return false;
            }
            surface = overlaySurfaceView.getHolder() == null
                    ? null
                    : overlaySurfaceView.getHolder().getSurface();
            if (surface == null || !surface.isValid()) {
                log("surface_recover_skipped reason=surface-invalid package="
                        + safe(packageName) + " requestReason=" + safe(reason));
                return false;
            }
            virtualDisplay.setSurface(surface);
            projectionSurface = surface;
            projectionGeometryValid = true;
            surfaceGeneration++;
        }
        log("surface_recover ok package=" + safe(packageName)
                + " reason=" + safe(reason));
        return true;
    }

    //models a borrowed projection surface so PixelCopy callers cannot release it accidentally.
    static final class ProjectedSurface {
        final Surface surface;
        final int width;
        final int height;
        final String packageName;
        final int generation;

        //keeps immutable metadata beside the borrowed surface reference for stale-result checks.
        ProjectedSurface(Surface surface, int width, int height, String packageName, int generation) {
            this.surface = surface;
            this.width = width;
            this.height = height;
            this.packageName = safe(packageName);
            this.generation = generation;
        }
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private boolean shouldRetainAfterReturn(String packageName, int generation) {
        synchronized (lock) {
            return ProjectionLifecyclePolicy.canCompleteReturn(
                    projectionRequested,
                    projectedPackage,
                    projectionGeneration,
                    packageName,
                    generation);
        }
    }

    //builds this artifact here so callers do not duplicate protocol or UI construction details.
    private Notification buildNotification(String text) {
        createNotificationChannel();
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launchIntent == null) {
            launchIntent = new Intent();
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentTitle("BYD HUD dashboard")
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
                "BYD HUD dashboard projection",
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

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private void log(String line) {
        String safeLine = safe(line);
        Log.i(TAG, safeLine);
        AppEventLogger.event(this, "cluster_projection " + safeLine);
        NavCaptureStore.rawEvent(this, "cluster_projection", projectedPackage, safeLine);
    }

    //normalizes values here so malformed app text cannot leak into HUD payloads.
    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
