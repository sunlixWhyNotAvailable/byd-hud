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
    private static final String EXTRA_REASON = "reason";
    private static ClusterProjectionService instance;

    private final Object lock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WindowManager overlayWindowManager;
    private SurfaceView overlaySurfaceView;
    private Surface projectionSurface;
    private VirtualDisplay virtualDisplay;
    private String projectedPackage = "";
    private String pendingPackage = "";
    private boolean projectionRequested;
    private int projectionGeneration;
    private int surfaceGeneration;
    private int projectionHeight = VIRTUAL_HEIGHT;
    private int projectionTop;
    private boolean projectionGeometryValid = true;

    //starts or schedules work here so lifecycle recovery follows one controlled path.
    static void startProjection(Context context, String packageName, String reason) {
        Intent intent = new Intent(context, ClusterProjectionService.class);
        intent.setAction(ACTION_PROJECT);
        intent.putExtra(EXTRA_PACKAGE, safe(packageName));
        intent.putExtra(EXTRA_REASON, safe(reason));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static void returnToMain(Context context, String packageName, String reason) {
        Intent intent = new Intent(context, ClusterProjectionService.class);
        intent.setAction(ACTION_RETURN);
        intent.putExtra(EXTRA_PACKAGE, safe(packageName));
        intent.putExtra(EXTRA_REASON, safe(reason));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    static void applyDashboardHeight(Context context, int percent, String reason) {
        ClusterProjectionService service = instance;
        if (service == null) {
            AppEventLogger.event(context,
                    "cluster_projection height_deferred service=missing percent="
                            + DashboardProjectionPolicy.clampHeightPercent(percent));
            return;
        }
        service.mainHandler.post(() -> service.resizeActiveProjection(percent, reason));
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
        String reason = safe(intent == null ? "" : intent.getStringExtra(EXTRA_REASON));
        if (action.isEmpty()) {
            restorePersistedProjection("sticky-restart-empty-action");
            return START_STICKY;
        }
        if (ACTION_RETURN.equals(action)) {
            returnPackageToMain(packageName, reason, startId);
            return START_NOT_STICKY;
        }
        if (ACTION_PROJECT.equals(action)) {
            requestProjection(packageName, reason);
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
        log("sticky restore package=" + packageName + " reason=" + safe(reason));
        requestProjection(packageName, "restore:" + safe(reason));
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
    private void requestProjection(String packageName, String reason) {
        if (packageName.isEmpty()) {
            log("projection ignored empty package reason=" + reason);
            return;
        }
        synchronized (lock) {
            if (projectionRequested && !projectionGeometryValid) {
                log("projection deferred invalid geometry package=" + packageName
                        + " reason=" + reason);
                return;
            }
            projectionGeneration++;
            projectionRequested = true;
            pendingPackage = packageName;
            projectedPackage = packageName;
        }
        updateNotification("Projecting " + packageName);
        log("projection requested package=" + packageName
                + " mode=surface_view"
                + " reason=" + reason);
        ensureOverlay();
        VirtualDisplay existing;
        synchronized (lock) {
            existing = virtualDisplay;
        }
        if (existing != null && existing.getDisplay() != null) {
            movePackageToDisplay(
                    packageName,
                    existing.getDisplay().getDisplayId(),
                    "project-existing " + reason);
            return;
        }
        createVirtualDisplayIfReady(packageName, reason);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private void returnPackageToMain(String packageName, String reason, int startId) {
        String targetPackage;
        int returnGeneration;
        synchronized (lock) {
            targetPackage = packageName.isEmpty() ? projectedPackage : packageName;
            returnGeneration = projectionGeneration;
        }
        if (targetPackage.isEmpty()) {
            log("return-main failed package=missing reason=" + reason);
            return;
        }
        Thread worker = new Thread(() -> {
            NavAppDisplayState returned = NavAppDisplayController.get(this)
                    .moveTaskToDisplayBlocking(
                            targetPackage,
                            MAIN_DISPLAY_ID,
                            "cluster-projection return-main " + reason);
            if (returned.taskId < 0 || returned.displayId != MAIN_DISPLAY_ID) {
                log("return-main failed package=" + targetPackage
                        + " task=" + returned.taskId
                        + " display=" + returned.displayId
                        + " reason=" + reason);
                return;
            }
            mainHandler.post(() -> {
                if (!shouldReleaseAfterReturn(targetPackage, returnGeneration)) {
                    log("return-main failed stale package=" + targetPackage
                            + " reason=" + reason);
                    return;
                }
                releaseProjection("return-main " + reason);
                stopForegroundCompat();
                stopSelf(startId);
            });
        }, "BydHudClusterProjectionReturn");
        worker.start();
    }

    //starts or schedules work here so lifecycle recovery follows one controlled path.
    private void ensureOverlay() {
        synchronized (lock) {
            if (overlaySurfaceView != null) {
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
        DashboardProjectionPolicy.Geometry geometry = DashboardProjectionPolicy
                .geometryForHeightPercent(HudPrefs.dashboardHeightPercent(this));
        surfaceView.getHolder().setFixedSize(geometry.width, geometry.height);
        surfaceView.getHolder().addCallback(this);
        View overlayView = surfaceView;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                geometry.width,
                geometry.height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.y = geometry.top;
        try {
            manager.addView(overlayView, params);
        } catch (RuntimeException e) {
            log("overlay add failed display=" + targetDisplay.getDisplayId()
                    + " mode=surface_view"
                    + " " + e.getClass().getSimpleName() + " " + safe(e.getMessage()));
            return;
        }
        synchronized (lock) {
            overlayWindowManager = manager;
            overlaySurfaceView = surfaceView;
            projectionHeight = geometry.height;
            projectionTop = geometry.top;
            projectionGeometryValid = true;
        }
        log("overlay added mode=surface_view display=" + targetDisplay.getDisplayId()
                + " name=" + targetDisplay.getName());
    }

    //builds this artifact here so callers do not duplicate protocol or UI construction details.
    @SuppressLint("WrongConstant")
    private void createVirtualDisplayIfReady(String packageName, String reason) {
        Surface surface;
        int height;
        synchronized (lock) {
            if (!projectionRequested || virtualDisplay != null || projectionSurface == null
                    || !projectionSurface.isValid()) {
                return;
            }
            surface = projectionSurface;
            height = projectionHeight;
        }
        DisplayManager displayManager =
                (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (displayManager == null) {
            log("projection failed: no DisplayManager");
            return;
        }
        VirtualDisplay created = displayManager.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                VIRTUAL_WIDTH,
                height,
                VIRTUAL_DENSITY,
                surface,
                VIRTUAL_DISPLAY_FLAGS);
        if (created == null || created.getDisplay() == null) {
            log("projection failed: createVirtualDisplay returned null");
            return;
        }
        int displayId = created.getDisplay().getDisplayId();
        synchronized (lock) {
            virtualDisplay = created;
        }
        log("projection_virtual_display_created mode=surface_view"
                + " id=" + displayId + " package=" + packageName
                + " height=" + height + " top=" + projectionTop);
        if (!safe(packageName).isEmpty()) {
            movePackageToDisplay(packageName, displayId, "project " + reason);
        }
    }

    // Adjustable cluster-window height was inspired by BYDMate's projection controls.
    private void resizeActiveProjection(int percent, String reason) {
        DashboardProjectionPolicy.Geometry geometry = DashboardProjectionPolicy
                .geometryForHeightPercent(percent);
        VirtualDisplay display;
        SurfaceView view;
        WindowManager manager;
        int oldHeight;
        int oldTop;
        int expectedProjectionGeneration;
        String packageName;
        synchronized (lock) {
            if (!projectionRequested
                    || virtualDisplay == null
                    || virtualDisplay.getDisplay() == null
                    || overlaySurfaceView == null
                    || overlayWindowManager == null) {
                log("height_resize_deferred projection=inactive percent="
                        + geometry.heightPercent + " reason=" + safe(reason));
                return;
            }
            if (projectionHeight == geometry.height && projectionTop == geometry.top) {
                log("height_resize_skipped unchanged percent=" + geometry.heightPercent);
                return;
            }
            display = virtualDisplay;
            view = overlaySurfaceView;
            manager = overlayWindowManager;
            oldHeight = projectionHeight;
            oldTop = projectionTop;
            expectedProjectionGeneration = projectionGeneration;
            packageName = projectedPackage;
        }
        if (!(view.getLayoutParams() instanceof WindowManager.LayoutParams)) {
            log("height_resize_failed layout_params=missing reason=" + safe(reason));
            return;
        }
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) view.getLayoutParams();
        try {
            view.getHolder().setFixedSize(geometry.width, geometry.height);
            display.resize(geometry.width, geometry.height, geometry.density);
            params.width = geometry.width;
            params.height = geometry.height;
            params.y = geometry.top;
            manager.updateViewLayout(view, params);
            synchronized (lock) {
                if (virtualDisplay != display || overlaySurfaceView != view) {
                    throw new IllegalStateException("projection changed during resize");
                }
                projectionHeight = geometry.height;
                projectionTop = geometry.top;
                projectionGeometryValid = true;
                surfaceGeneration++;
            }
            log("height_resize_applied package=" + projectedPackage
                    + " percent=" + geometry.heightPercent
                    + " height=" + geometry.height
                    + " top=" + geometry.top);
        } catch (RuntimeException e) {
            try {
                view.getHolder().setFixedSize(VIRTUAL_WIDTH, oldHeight);
                display.resize(VIRTUAL_WIDTH, oldHeight, VIRTUAL_DENSITY);
                params.width = VIRTUAL_WIDTH;
                params.height = oldHeight;
                params.y = oldTop;
                manager.updateViewLayout(view, params);
                synchronized (lock) {
                    if (virtualDisplay == display && overlaySurfaceView == view) {
                        projectionHeight = oldHeight;
                        projectionTop = oldTop;
                        projectionGeometryValid = true;
                        surfaceGeneration++;
                    }
                }
            } catch (RuntimeException rollbackError) {
                synchronized (lock) {
                    if (virtualDisplay == display && overlaySurfaceView == view) {
                        projectionGeometryValid = false;
                        surfaceGeneration++;
                    }
                }
                log("height_resize_rollback_failed error="
                        + rollbackError.getClass().getSimpleName());
                recoverProjectionAfterResizeFailure(
                        packageName, expectedProjectionGeneration, safe(reason));
            }
            log("height_resize_failed error=" + e.getClass().getSimpleName()
                    + " reason=" + safe(reason));
        }
    }

    private void recoverProjectionAfterResizeFailure(
            String packageName,
            int expectedProjectionGeneration,
            String reason) {
        Thread worker = new Thread(() -> {
            NavAppDisplayState returned = NavAppDisplayController.get(this)
                    .moveTaskToDisplayBlocking(
                            packageName,
                            MAIN_DISPLAY_ID,
                            "cluster-projection height-resize-recovery " + reason);
            mainHandler.post(() -> {
                synchronized (lock) {
                    if (projectionGeneration != expectedProjectionGeneration
                            || !projectionRequested
                            || projectionGeometryValid
                            || !packageName.equals(projectedPackage)) {
                        log("height_resize_recovery_skipped stale package=" + packageName);
                        return;
                    }
                }
                if (returned.taskId < 0 || returned.displayId != MAIN_DISPLAY_ID) {
                    log("height_resize_recovery_failed package=" + packageName
                            + " task=" + returned.taskId
                            + " display=" + returned.displayId);
                    return;
                }
                releaseProjection("height-resize-recovery");
                requestProjection(packageName, "height-resize-recovery:" + reason);
            });
        }, "BydHudClusterProjectionResizeRecovery");
        worker.start();
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private void movePackageToDisplay(String packageName, int displayId, String reason) {
        final int moveGeneration;
        synchronized (lock) {
            moveGeneration = projectionGeneration;
        }
        Thread worker = new Thread(
                () -> {
                    String staleReason = staleMoveReason(packageName, displayId, moveGeneration);
                    if (!staleReason.isEmpty()) {
                        log("move skipped stale " + staleReason + " package=" + safe(packageName)
                                + " display=" + displayId + " reason=" + reason);
                        return;
                    }
                    NavAppDisplayController.get(this).moveTaskToDisplayBlocking(
                            packageName,
                            displayId,
                            "cluster-projection " + reason);
                },
                "BydHudClusterProjectionMove");
        worker.start();
    }

    //guard dashboard moves so old workers cannot move an app after projection state changes.
    private String staleMoveReason(String packageName, int displayId, int moveGeneration) {
        synchronized (lock) {
            if (moveGeneration != projectionGeneration) {
                return "generation=" + moveGeneration + " current=" + projectionGeneration;
            }
            if (!safe(packageName).equals(projectedPackage)) {
                return "projectedPackage=" + projectedPackage;
            }
            if (virtualDisplay == null || virtualDisplay.getDisplay() == null) {
                return "display=missing";
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

    //stops or releases work here so stale capture and HUD output cannot keep running silently.
    private void releaseProjection(String reason) {
        VirtualDisplay display;
        View overlayView;
        WindowManager manager;
        synchronized (lock) {
            display = virtualDisplay;
            overlayView = overlaySurfaceView;
            manager = overlayWindowManager;
            virtualDisplay = null;
            projectionSurface = null;
            overlaySurfaceView = null;
            overlayWindowManager = null;
            pendingPackage = "";
            projectionRequested = false;
            projectedPackage = "";
            projectionHeight = VIRTUAL_HEIGHT;
            projectionTop = 0;
            projectionGeometryValid = true;
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
        log("projection_release mode=surface_view reason=" + reason);
    }

    //copies the current surface metadata without transferring ownership to the caller.
    private ProjectedSurface currentProjectedSurface(String packageName) {
        synchronized (lock) {
            if (!projectionRequested
                    || virtualDisplay == null
                    || projectionSurface == null
                    || !projectionSurface.isValid()
                    || !projectionGeometryValid
                    || !safe(projectedPackage).equals(safe(packageName))) {
                return null;
            }
            return new ProjectedSurface(
                    projectionSurface,
                    VIRTUAL_WIDTH,
                    projectionHeight,
                    projectedPackage,
                    surfaceGeneration);
        }
    }

    //guards PixelCopy from consuming a surface after return-to-main or reprojection.
    private boolean isCurrentProjectedSurface(ProjectedSurface surface) {
        synchronized (lock) {
            return surface != null
                    && projectionRequested
                    && virtualDisplay != null
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
                    && virtualDisplay != null
                    && virtualDisplay.getDisplay() != null
                    && projectionSurface != null
                    && projectionSurface.isValid()
                    && projectionGeometryValid
                    && safe(projectedPackage).equals(safe(packageName));
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
                    || virtualDisplay == null
                    || virtualDisplay.getDisplay() == null
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
                    || virtualDisplay == null
                    || virtualDisplay.getDisplay() == null
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
    private boolean shouldReleaseAfterReturn(String packageName, int generation) {
        synchronized (lock) {
            return projectionGeneration == generation
                    && (projectedPackage.isEmpty() || projectedPackage.equals(packageName));
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
