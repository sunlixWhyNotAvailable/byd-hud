package com.bydhud.app;

//keeps the legacy projection service entry point available for systems that bind to it directly.

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
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
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;

//anchors the ClusterProjectionService android entry point so lifecycle recovery stays separate from business logic.
public final class ClusterProjectionService extends Service
        implements TextureView.SurfaceTextureListener, SurfaceHolder.Callback {
    static final String VIRTUAL_DISPLAY_NAME = "bydhud_remote_dashboard";

    private static final String TAG = "BydHudClusterProjection";
    private static final String CHANNEL_ID = "byd_hud_cluster_projection";
    private static final int NOTIFICATION_ID = 4304;
    private static final int VIRTUAL_WIDTH = 1920;
    private static final int VIRTUAL_HEIGHT = 720;
    private static final int VIRTUAL_DENSITY = 320;
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
    private TextureView overlayTexture;
    private SurfaceView overlaySurfaceView;
    private Surface projectionSurface;
    private boolean projectionSurfaceOwned;
    private VirtualDisplay virtualDisplay;
    private DashboardProjectionMode activeProjectionMode = DashboardProjectionMode.TEXTURE_VIEW;
    private String projectedPackage = "";
    private String pendingPackage = "";
    private boolean projectionRequested;
    private int projectionGeneration;

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

    //refreshes borrowed surface metadata after PixelCopy stalls without moving the app between displays.
    static boolean softReattachProjection(Context context, String packageName, String reason) {
        ClusterProjectionService service = instance;
        if (service == null) {
            AppEventLogger.event(context,
                    "cluster_projection soft_reattach_skipped reason=service-missing package="
                            + safe(packageName));
            return false;
        }
        return service.softReattachProjectedSurface(packageName, reason);
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
    //keeps this step explicit so callers can rely on one documented behavior boundary.
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
        acceptProjectionSurface(new Surface(surfaceTexture), "texture_view");
    }

    @Override
    //keeps this step explicit so callers can rely on one documented behavior boundary.
    public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
        //keeps cluster projection fixed-size so this test build matches the car display contract.
    }

    @Override
    //keeps this step explicit so callers can rely on one documented behavior boundary.
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
        releaseProjection("surface-destroyed");
        return true;
    }

    @Override
    //keeps this step explicit so callers can rely on one documented behavior boundary.
    public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
        //keeps frame parsing outside the overlay; dashboard Waze capture reads this surface through PixelCopy.
    }

    @Override
    //keeps SurfaceView mode on the same VirtualDisplay path as TextureView for apples-to-apples testing.
    public void surfaceCreated(SurfaceHolder holder) {
        acceptProjectionSurface(holder == null ? null : holder.getSurface(), "surface_view");
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

    //accepts owner surfaces from either TextureView or SurfaceView without duplicating display creation.
    private void acceptProjectionSurface(Surface surface, String mode) {
        if (surface == null || !surface.isValid()) {
            log("projection_surface_invalid mode=" + safe(mode));
            return;
        }
        String packageName;
        synchronized (lock) {
            projectionSurface = surface;
            projectionSurfaceOwned = "texture_view".equals(mode);
            packageName = pendingPackage;
        }
        log("projection_surface_ready mode=" + safe(mode));
        createVirtualDisplayIfReady(packageName, "surface-ready");
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private void requestProjection(String packageName, String reason) {
        if (packageName.isEmpty()) {
            log("projection ignored empty package reason=" + reason);
            return;
        }
        synchronized (lock) {
            projectionGeneration++;
            projectionRequested = true;
            pendingPackage = packageName;
            projectedPackage = packageName;
        }
        updateNotification("Projecting " + packageName);
        log("projection requested package=" + packageName
                + " mode=" + HudPrefs.dashboardProjectionMode(this).id
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
            projectionRequested = false;
            pendingPackage = "";
        }
        if (!targetPackage.isEmpty()) {
            Thread worker = new Thread(() -> {
                NavAppDisplayController.get(this).moveTaskToDisplayBlocking(
                        targetPackage,
                        MAIN_DISPLAY_ID,
                        "cluster-projection return-main " + reason);
                mainHandler.post(() -> {
                    if (!shouldReleaseAfterReturn(targetPackage, returnGeneration)) {
                        log("return-main stale ignored package=" + targetPackage
                                + " reason=" + reason);
                        return;
                    }
                    releaseProjection("return-main " + reason);
                    stopForegroundCompat();
                    stopSelf(startId);
                });
            }, "BydHudClusterProjectionReturn");
            worker.start();
            return;
        }
        releaseProjection("return-main " + reason);
        stopForegroundCompat();
        stopSelf(startId);
    }

    //starts or schedules work here so lifecycle recovery follows one controlled path.
    private void ensureOverlay() {
        DashboardProjectionMode mode = HudPrefs.dashboardProjectionMode(this);
        synchronized (lock) {
            if (overlayTexture != null || overlaySurfaceView != null) {
                return;
            }
            activeProjectionMode = mode;
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
        View overlayView;
        TextureView textureView = null;
        SurfaceView surfaceView = null;
        if (mode == DashboardProjectionMode.SURFACE_VIEW) {
            surfaceView = new SurfaceView(displayContext);
            surfaceView.getHolder().addCallback(this);
            overlayView = surfaceView;
        } else {
            textureView = new TextureView(displayContext);
            textureView.setOpaque(true);
            textureView.setSurfaceTextureListener(this);
            overlayView = textureView;
        }
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                VIRTUAL_WIDTH,
                VIRTUAL_HEIGHT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        try {
            manager.addView(overlayView, params);
        } catch (RuntimeException e) {
            log("overlay add failed display=" + targetDisplay.getDisplayId()
                    + " mode=" + mode.id
                    + " " + e.getClass().getSimpleName() + " " + safe(e.getMessage()));
            return;
        }
        synchronized (lock) {
            overlayWindowManager = manager;
            overlayTexture = textureView;
            overlaySurfaceView = surfaceView;
        }
        log("overlay added mode=" + mode.id + " display=" + targetDisplay.getDisplayId()
                + " name=" + targetDisplay.getName());
    }

    //builds this artifact here so callers do not duplicate protocol or UI construction details.
    private void createVirtualDisplayIfReady(String packageName, String reason) {
        Surface surface;
        DashboardProjectionMode mode;
        synchronized (lock) {
            if (!projectionRequested || virtualDisplay != null || projectionSurface == null
                    || !projectionSurface.isValid()) {
                return;
            }
            surface = projectionSurface;
            mode = activeProjectionMode;
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
                VIRTUAL_HEIGHT,
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
        log("projection_virtual_display_created mode=" + mode.id
                + " id=" + displayId + " package=" + packageName);
        if (!safe(packageName).isEmpty()) {
            movePackageToDisplay(packageName, displayId, "project " + reason);
        }
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
        Surface surface;
        boolean surfaceOwned;
        View overlayView;
        WindowManager manager;
        DashboardProjectionMode mode;
        synchronized (lock) {
            display = virtualDisplay;
            surface = projectionSurface;
            surfaceOwned = projectionSurfaceOwned;
            overlayView = overlayTexture != null ? overlayTexture : overlaySurfaceView;
            manager = overlayWindowManager;
            mode = activeProjectionMode;
            virtualDisplay = null;
            projectionSurface = null;
            projectionSurfaceOwned = false;
            overlayTexture = null;
            overlaySurfaceView = null;
            overlayWindowManager = null;
            pendingPackage = "";
            projectionRequested = false;
            projectedPackage = "";
            activeProjectionMode = DashboardProjectionMode.TEXTURE_VIEW;
        }
        if (display != null) {
            display.release();
        }
        if (surface != null && surfaceOwned) {
            surface.release();
        }
        if (manager != null && overlayView != null) {
            try {
                manager.removeView(overlayView);
            } catch (RuntimeException e) {
                log("overlay remove failed " + e.getClass().getSimpleName()
                        + " " + safe(e.getMessage()));
            }
        }
        log("projection_release mode=" + mode.id + " reason=" + reason);
    }

    //copies the current surface metadata without transferring ownership to the caller.
    private ProjectedSurface currentProjectedSurface(String packageName) {
        synchronized (lock) {
            if (!projectionRequested
                    || virtualDisplay == null
                    || projectionSurface == null
                    || !projectionSurface.isValid()
                    || !safe(projectedPackage).equals(safe(packageName))) {
                return null;
            }
            return new ProjectedSurface(
                    projectionSurface,
                    VIRTUAL_WIDTH,
                    VIRTUAL_HEIGHT,
                    projectedPackage,
                    projectionGeneration);
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
                    && projectionGeneration == surface.generation
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
                    && safe(projectedPackage).equals(safe(packageName));
        }
    }

    //bumps generation so future PixelCopy borrows a fresh snapshot without issuing display move commands.
    private boolean softReattachProjectedSurface(String packageName, String reason) {
        Surface oldSurface;
        Surface newSurface;
        synchronized (lock) {
            if (!projectionRequested
                    || virtualDisplay == null
                    || virtualDisplay.getDisplay() == null
                    || projectionSurface == null
                    || !projectionSurface.isValid()
                    || activeProjectionMode != DashboardProjectionMode.TEXTURE_VIEW
                    || overlayTexture == null
                    || overlayTexture.getSurfaceTexture() == null
                    || !safe(projectedPackage).equals(safe(packageName))) {
                log("soft_reattach_skipped reason=projection-not-current package="
                        + safe(packageName) + " requestReason=" + safe(reason));
                return false;
            }
            oldSurface = projectionSurface;
            newSurface = new Surface(overlayTexture.getSurfaceTexture());
            virtualDisplay.setSurface(newSurface);
            projectionSurface = newSurface;
            projectionSurfaceOwned = true;
            projectionGeneration++;
        }
        if (oldSurface != null) {
            oldSurface.release();
        }
        log("soft_reattach surface_reset package=" + safe(packageName)
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
