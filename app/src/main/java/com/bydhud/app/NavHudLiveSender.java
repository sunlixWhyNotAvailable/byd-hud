package com.bydhud.app;

//converts parser snapshots into HUD commands so live route evidence reaches the cluster consistently.

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

//defines the NavHudLiveSender module boundary so related behavior stays readable inside one unit.
final class NavHudLiveSender {
    private static final String TAG = "BydHudNavLive";
    private static final String WAZE_PACKAGE = "com.waze";
    private static final long SEND_INTERVAL_MS = 1000L;
    private static final long NOTIFICATION_REMOVED_STOP_DELAY_MS = 2000L;
    private static final long ACCESSIBILITY_NO_ROUTE_STOP_DELAY_MS = 10000L;
    private static final long ARRIVAL_ROUTE_END_STOP_DELAY_MS = 3000L;
    private static final long ROUTE_HEALTH_INTERVAL_MS = 1000L;
    private static final long ACTIVE_ROUTE_STALE_CLEAR_MS = 15000L;
    private static final long GMAPS_NOTIFICATION_RECONCILE_MS = 30_000L;
    private static final long WAZE_VISUAL_FRESH_MS = 2500L;
    private static final long WAZE_ROUTE_NODE_FRESH_MS = 3000L;
    private static final long WAZE_ROUTE_FIELD_TTL_MS = WAZE_ROUTE_NODE_FRESH_MS;
    private static final long DASHBOARD_WATCHDOG_INTERVAL_MS = 5000L;
    private static final long WAZE_DIRECT_TIMEOUT_MS = 5000L;
    private static final long WAZE_SURFACE_READY_TIMEOUT_MS = 5000L;
    private static final long GMAPS_DIRECT_TIMEOUT_MS = 5000L;

    private static NavHudLiveSender instance;

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static synchronized NavHudLiveSender get(Context context) {
        if (instance == null) {
            instance = new NavHudLiveSender(context.getApplicationContext());
        }
        return instance;
    }

    static void onWazeRouteLifecycleEvent(Context context, boolean routeActive,
            boolean terminal, long eventElapsedMs, boolean changed, String reason) {
        NavHudLiveSender current;
        synchronized (NavHudLiveSender.class) {
            current = instance;
        }
        if (current != null) {
            current.handler.post(() -> current.onWazeRouteLifecycleEventOnMain(
                    routeActive, terminal, eventElapsedMs, changed, reason));
        }
    }

    static boolean shouldStartWazeDirectHost(boolean bridgeSupported, boolean routeActive) {
        return !bridgeSupported || routeActive;
    }

    void onWazeAlertsPreferenceChanged(boolean enabled) {
        if (!enabled) {
            DirectTbtFrame clusterFrame = latestWazeClusterFrame;
            if (clusterFrame != null) {
                latestWazeClusterFrame = clusterFrame.withAlertOverlay(
                        DirectTbtFrame.AlertOverlay.inactive());
            }
            DirectTbtFrame surfaceFrame = latestWazeSurfaceFrame;
            if (surfaceFrame != null) {
                latestWazeSurfaceFrame = surfaceFrame.withAlertOverlay(
                        DirectTbtFrame.AlertOverlay.inactive());
            }
        }
        wazeDirectChannel.onWazeAlertsPreferenceChanged(enabled);
        wazeSurfaceDirectChannel.onWazeAlertsPreferenceChanged(enabled);
    }

    boolean isWazeDirectChannelActiveForProbe() {
        return wazeDirectChannel.isActive();
    }

    void onWazeScreenCapturePreferenceChanged(boolean enabled) {
        handler.post(() -> {
            log("waze screen capture enabled=" + enabled);
            if (!enabled) {
                cancelWazeDirectColdTimeout();
                cancelWazeFallbackReadiness();
                boolean legacyOwned = wazeFallbackActive;
                wazeFallbackActive = false;
                WazeCropCapture.get(context).stop("screen-capture-disabled");
                WazeMediaProjectionController.stop(context, "screen-capture-disabled");
                if (legacyOwned) {
                    resetLatestPayload();
                    hudOutput.selectNavigationSource(
                            HudOutputCoordinator.Source.NONE,
                            "screen-capture-disabled");
                }
                return;
            }
            if (!active || !WAZE_PACKAGE.equals(activePackage) || wazeDirectFrameReceived) {
                return;
            }
            activateWazeLegacyFallback("screen-capture-enabled");
            scheduleRouteHealthLoop();
        });
    }

    private final Context context;
    private final HandlerThread stateThread;
    private final Handler handler;
    private final HudOutputCoordinator hudOutput;
    private final WazeDirectChannel wazeDirectChannel;
    private final WazeDirectChannel wazeSurfaceDirectChannel;
    private final GMapsDirectChannel gmapsDirectChannel;
    private final Object wazeDirectFrameLock = new Object();
    private DirectTbtFrame pendingWazeDirectFrame;
    private String pendingWazeDirectFrameReason = "";
    private String pendingWazeDirectFrameOwner = "";
    private int pendingWazeDirectFrameSessionGeneration;
    private int pendingWazeDirectFrameGeneration;
    private boolean pendingWazeDirectFrameFromSurface;
    private int wazeDirectFrameGeneration;
    private int coalescedWazeDirectFrames;
    private boolean wazeDirectFrameDispatchScheduled;
    private final Runnable wazeDirectFrameDispatch = this::dispatchLatestWazeDirectFrame;
    private String activePackage = "";
    private volatile boolean active;
    private boolean sendLoopScheduled;
    private boolean routeHealthScheduled;
    private boolean runtimeReinitInProgress;
    private HudState latestState;
    private HudState latestRouteState;
    private HudState latestVisualState;
    private int latestVisualSourceDisplayId;
    private NavSnapshot.Maneuver latestRouteManeuver = NavSnapshot.Maneuver.UNKNOWN;
    private String latestReason = "";
    private String activeNotificationKey = "";
    private String ongoingGMapsNotificationPackage = "";
    private String ongoingGMapsNotificationKey = "";
    private long lastGMapsNotificationReconcileMs;
    private String pendingRemovalKey = "";
    private String pendingRemovalPackage = "";
    private String pendingRemovalActiveKey = "";
    private String pendingNoRoutePackage = "";
    private String pendingArrivalPackage = "";
    private String pendingReinitStartPackage = "";
    private String pendingReinitStartReason = "";
    private long pendingNoRouteScheduledAtMs;
    private long pendingArrivalScheduledAtMs;
    private long lastAccessibilityResultMs;
    private long lastVisualResultMs;
    private long lastWazeRouteNodeResultMs;
    private long latestRouteStateMs;
    private long lastDashboardWatchdogMs;
    private boolean lastWazeRouteNodeScanHadRoute;
    private boolean firstNavAfterPackageReplaceAwaitingSomeIp;
    private long firstNavAfterPackageReplaceConnectStartMs;
    private boolean wazeDirectHandshakeAvailable;
    private boolean wazeDirectNavigating;
    private boolean wazeDirectFrameReceived;
    private boolean wazeDirectRouteEnded;
    private boolean wazeDirectProbeScheduled;
    private int wazeDirectProbeSessionGeneration;
    private volatile int wazeRouteGeneration;
    private boolean wazeFallbackActive;
    private boolean wazeFallbackReadinessCheckInFlight;
    private int wazeFallbackReadinessGeneration;
    private long lastWazeFallbackReadinessCheckMs;
    private boolean wazeSurfaceLaunchPending;
    private volatile boolean wazeSurfaceActive;
    private boolean wazeSurfaceVisible;
    private boolean wazeSurfaceEnabledForRoute;
    private boolean wazeSurfaceDismissedForRoute;
    private int wazeSurfaceTaskId = -1;
    private long wazeSurfaceInstanceId;
    private volatile DirectTbtFrame latestWazeClusterFrame;
    private volatile String latestWazeClusterFrameReason = "";
    private volatile int latestWazeClusterFrameSessionGeneration;
    private volatile DirectTbtFrame latestWazeSurfaceFrame;
    private volatile String latestWazeSurfaceFrameReason = "";
    private volatile int latestWazeSurfaceFrameSessionGeneration;
    private volatile int wazeSurfaceRouteGeneration = -1;
    private volatile DirectTbtFrame latestGMapsDirectFrame;
    private volatile String latestGMapsDirectFrameReason = "";
    private volatile long latestGMapsDirectFrameSessionGeneration;
    private volatile GMapsDirectChannel.BitmapSelection latestGMapsBitmapSelection;
    private String speedOverlayOwner = "";
    private int speedOverlayDisplayValue;
    private int speedOverlayKph;
    private String speedOverlayUnit = "";
    private boolean speedOverlayVisible;
    private int speedOverlayPlacement = DirectTbtPayload.SPEED_PLACEMENT_NONE;
    private boolean speedOverlayOccupied;
    private long speedOverlayHideAtMs;
    private boolean gmapsDirectFrameReceived;
    private boolean gmapsDirectFallbackActive;
    private boolean gmapsDirectTimedOut;
    private boolean gmapsLegacyUnavailableLogged;
    private boolean gmapsDirectRouteEnded;
    private boolean gmapsDirectTimeoutScheduled;
    private long gmapsDirectTimeoutSessionGeneration;
    private long lastGMapsDirectRegistrationProbeMs;
    private boolean gmapsDirectRegistrationSuppressed;
    private volatile DirectSessionLog wazeDirectSession;
    private volatile DirectSessionLog gmapsDirectSession;
    private Boolean cachedWazeBridgeSupported;
    private long cachedWazeVersionCode = Long.MIN_VALUE;
    private long cachedWazeLastUpdateTime = Long.MIN_VALUE;

    private final Runnable wazeDirectProbeTimeout = () -> {
        wazeDirectProbeScheduled = false;
        if (!active || !WAZE_PACKAGE.equals(activePackage)
                || !isCurrentWazeProbeToken()
                || !isWazeScreenCaptureEnabled()
                || wazeDirectFrameReceived
                || wazeFallbackActive
                || wazeDirectRouteEnded
                || !hasActiveWazeRoute()) {
            return;
        }
        activateWazeLegacyFallback("direct-frame-timeout");
    };

    private final Runnable gmapsDirectTimeout = this::onGMapsDirectTimeout;
    private final Runnable speedOverlayTimeout = this::onSpeedOverlayTimeout;
    private final Runnable wazeSurfaceReadyTimeout = () -> {
        if (!wazeSurfaceLaunchPending || wazeSurfaceActive) return;
        fallbackFromWazeSurface("surface-ready-timeout");
    };

    private void onGMapsDirectTimeout() {
        gmapsDirectTimeoutScheduled = false;
        if (!active || !GMapsDirectChannel.PACKAGE_NAME.equals(activePackage)
                || gmapsDirectRouteEnded
                || !isCurrentGMapsTimeoutToken()) {
            return;
        }
        hudOutput.clearDirectFrameForLoss(
                GMapsDirectChannel.OWNER_PACKAGE,
                gmapsDirectTimeoutSessionGeneration,
                "gmaps-direct-timeout",
                SystemClock.elapsedRealtime());
        gmapsDirectFrameReceived = false;
        gmapsDirectTimedOut = true;
        activateGMapsLegacyFallbackIfReady("direct-frame-timeout");
        ensureGMapsRegisteredWhenTransportReady("frame-timeout");
    }

    private final Runnable sendLoop = new Runnable() {
        @Override
        //keeps this HUD step isolated so cluster payload behavior stays predictable.
        public void run() {
            sendLoopScheduled = false;
            if (!active) {
                return;
            }
            maybeRepairDashboardProjection(SystemClock.elapsedRealtime(), "send-loop");
            sendLatestIfReady("loop");
            scheduleSendLoop();
        }
    };

    private final Runnable notificationRemovedStop = new Runnable() {
        @Override
        //keeps this HUD step isolated so cluster payload behavior stays predictable.
        public void run() {
            String key = pendingRemovalKey;
            String packageName = pendingRemovalPackage;
            String activeKey = pendingRemovalActiveKey;
            pendingRemovalKey = "";
            pendingRemovalPackage = "";
            pendingRemovalActiveKey = "";
            if (!NavRouteEndPolicy.shouldStopForRemovedNotification(
                    active, packageName, activePackage, key, activeKey)) {
                return;
            }
            if (WAZE_PACKAGE.equals(activePackage) && !wazeFallbackActive) {
                log("notification removed ignored: direct Waze owns route lifecycle");
                return;
            }
            if (GMapsDirectChannel.PACKAGE_NAME.equals(activePackage)
                    && !gmapsDirectFallbackActive) {
                log("notification removed ignored: direct GMaps owns route lifecycle");
                return;
            }
            if (hasOngoingGMapsNavigationNotification()) {
                log("notification removed stop ignored: replacement GMaps notification active"
                        + " package=" + activePackage + " key=" + ongoingGMapsNotificationKey);
                scheduleRouteHealthLoop();
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (NavTextNormalizer.sourceApp(activePackage) == NavSnapshot.SourceApp.GOOGLE_MAPS
                    && (lastAccessibilityResultMs <= 0L
                    || now - lastAccessibilityResultMs > ACCESSIBILITY_NO_ROUTE_STOP_DELAY_MS)) {
                forceClearNavigator(activePackage, "notification-removed", now);
                return;
            }
            if (NavRouteStateStore.get(context).isRouteActive(activePackage, now)) {
                log("notification removed stop ignored: route evidence still active package="
                        + activePackage + " key=" + key);
                scheduleRouteHealthLoop();
                return;
            }
            stopOnMain("notification-removed", true);
        }
    };

    private final Runnable routeHealthLoop = new Runnable() {
        @Override
        //keeps this HUD step isolated so cluster payload behavior stays predictable.
        public void run() {
            routeHealthScheduled = false;
            if (!active || activePackage.isEmpty()) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (GMapsDirectChannel.PACKAGE_NAME.equals(activePackage)) {
                if (gmapsDirectRouteEnded) {
                    if (now - lastGMapsDirectRegistrationProbeMs
                            >= GMAPS_DIRECT_TIMEOUT_MS) {
                        ensureGMapsRegisteredWhenTransportReady("route-ended-health");
                    }
                    scheduleRouteHealthLoop();
                    return;
                }
                if (gmapsDirectFallbackActive) {
                    activateGMapsLegacyFallbackIfReady("fallback-health");
                }
                if (!gmapsDirectTimedOut) {
                    if (!gmapsDirectTimeoutScheduled) scheduleGMapsDirectTimeout();
                    scheduleRouteHealthLoop();
                    return;
                }
                activateGMapsLegacyFallbackIfReady("fallback-health");
                if (now - lastGMapsDirectRegistrationProbeMs >= GMAPS_DIRECT_TIMEOUT_MS) {
                    ensureGMapsRegisteredWhenTransportReady("fallback-health");
                }
                if (!gmapsDirectFallbackActive) {
                    scheduleRouteHealthLoop();
                    return;
                }
            }
            if (WAZE_PACKAGE.equals(activePackage) && !wazeFallbackActive) {
                scheduleWazeDirectColdTimeout("route-health");
                scheduleRouteHealthLoop();
                return;
            }
            if (WAZE_PACKAGE.equals(activePackage)) {
                ensureWazeCropRunning("route-health");
            }
            NavRouteStateStore routeStore = NavRouteStateStore.get(context);
            if (hasOngoingGMapsNavigationNotification()) {
                if (now - lastGMapsNotificationReconcileMs >= GMAPS_NOTIFICATION_RECONCILE_MS) {
                    lastGMapsNotificationReconcileMs = now;
                    NavNotificationListenerService.requestActiveNotificationScan(
                            context, "route-health");
                }
                log("route stale ignored: ongoing GMaps notification package="
                        + activePackage + " key=" + ongoingGMapsNotificationKey);
                scheduleRouteHealthLoop();
                return;
            }
            boolean routeActive = routeStore.isRouteActive(activePackage, now);
            long age = routeStore.evidenceAgeMs(activePackage, now);
            if (!routeActive
                    && latestState != null
                    && age != Long.MAX_VALUE
                    && age > ACTIVE_ROUTE_STALE_CLEAR_MS) {
                log("route stale clear package=" + activePackage
                        + " ageMs=" + age
                        + " reason=" + routeStore.reason(activePackage));
                stopOnMain("route-stale", true);
                return;
            }
            scheduleRouteHealthLoop();
        }
    };

    private final Runnable accessibilityNoRouteStop = new Runnable() {
        @Override
        //keeps this HUD step isolated so cluster payload behavior stays predictable.
        public void run() {
            String packageName = pendingNoRoutePackage;
            pendingNoRoutePackage = "";
            pendingNoRouteScheduledAtMs = 0L;
            if (!active || packageName.isEmpty() || !packageName.equals(activePackage)) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (NavTextNormalizer.sourceApp(activePackage) == NavSnapshot.SourceApp.GOOGLE_MAPS) {
                if (GMapsDirectChannel.PACKAGE_NAME.equals(activePackage)
                        && !gmapsDirectFallbackActive) {
                    log("accessibility no-route ignored: direct GMaps owns route lifecycle");
                    return;
                }
                if (hasOngoingGMapsNavigationNotification()) {
                    log("accessibility no-route ignored: ongoing GMaps notification package="
                            + activePackage + " key=" + ongoingGMapsNotificationKey);
                    scheduleRouteHealthLoop();
                    return;
                }
                forceClearNavigator(activePackage, "accessibility-route-ended", now);
                return;
            }
            if (WAZE_PACKAGE.equals(activePackage)) {
                if (!wazeFallbackActive) {
                    log("accessibility no-route ignored: direct Waze owns route lifecycle");
                    return;
                }
                handleWazeNoRouteOrVisualUnavailable(
                        "accessibility-route-ended",
                        "accessibility-no-route",
                        now);
                return;
            }
            if (NavRouteStateStore.get(context).isRouteActive(activePackage, now)) {
                log("accessibility no-route stop ignored: route evidence still active package="
                        + activePackage
                        + " reason=" + NavRouteStateStore.get(context).reason(activePackage));
                scheduleRouteHealthLoop();
                return;
            }
            stopOnMain("accessibility-route-ended", true);
        }
    };

    private final Runnable arrivalRouteEndStop = new Runnable() {
        @Override
        //keeps this HUD step isolated so cluster payload behavior stays predictable.
        public void run() {
            String packageName = pendingArrivalPackage;
            pendingArrivalPackage = "";
            pendingArrivalScheduledAtMs = 0L;
            if (!active || packageName.isEmpty() || !packageName.equals(activePackage)) {
                return;
            }
            if (WAZE_PACKAGE.equals(packageName) && !wazeFallbackActive) {
                log("arrival ignored: direct Waze owns route lifecycle");
                return;
            }
            if (GMapsDirectChannel.PACKAGE_NAME.equals(packageName)
                    && !gmapsDirectFallbackActive) {
                log("arrival ignored: direct GMaps owns route lifecycle");
                return;
            }
            long now = SystemClock.elapsedRealtime();
            NavRouteStateStore.get(context).clearRoute(packageName, "arrival-route-ended", now);
            if ("com.waze".equals(packageName)) {
                WazeRouteTracker.get(context).onRouteEnded("arrival-route-ended", now);
            }
            stopOnMain("arrival-route-ended", true);
        }
    };

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private NavHudLiveSender(Context context) {
        this.context = context;
        this.stateThread = new HandlerThread("BydHudNavState");
        this.stateThread.start();
        this.handler = new Handler(stateThread.getLooper());
        this.hudOutput = HudOutputCoordinator.get(context);
        this.wazeDirectChannel = new WazeDirectChannel(context,
                new WazeDirectChannel.Listener() {
                    @Override
                    public void onHandshakeAvailable(String ownerPackage,
                            int sessionGeneration, String reason) {
                        handler.post(() -> onWazeDirectHandshakeAvailable(
                                ownerPackage, sessionGeneration, reason));
                    }

                    @Override
                    public void onHandshakeUnavailable(String ownerPackage,
                            int sessionGeneration, String reason) {
                        invalidatePendingWazeDirectFrames();
                        handler.post(() -> onWazeDirectHandshakeUnavailable(
                                ownerPackage, sessionGeneration, reason));
                    }

                    @Override
                    public void onNavigationStarted(String ownerPackage,
                            int sessionGeneration, String reason) {
                        invalidatePendingWazeDirectFrames();
                        handler.post(() -> {
                            if (!isCurrentWazeDirectCallback(ownerPackage, sessionGeneration)) {
                                return;
                            }
                            ensureWazeDirectSession(
                                    "navigation-started:" + safeReason(reason));
                            eventWazeDirectSession("navigation_started", reason);
                            onWazeDirectNavigationStarted(
                                    ownerPackage, sessionGeneration, reason);
                        });
                    }

                    @Override
                    public void onFrame(String ownerPackage, int sessionGeneration,
                            DirectTbtFrame frame, String reason) {
                        DirectTbtFrame previousFrame = latestWazeClusterFrame;
                        latestWazeClusterFrameReason = safeReason(reason);
                        latestWazeClusterFrameSessionGeneration = sessionGeneration;
                        latestWazeClusterFrame = frame;
                        if (!wazeSurfaceActive) {
                            enqueueLatestWazeDirectFrame(
                                    ownerPackage, sessionGeneration, frame, reason, false);
                        } else if (wazeAlertStateChanged(previousFrame, frame)
                                && latestWazeSurfaceFrame != null) {
                            enqueueLatestWazeDirectFrame(
                                    ownerPackage, latestWazeSurfaceFrameSessionGeneration,
                                    withRetainedWazeClusterAlert(latestWazeSurfaceFrame),
                                    "surface-alert-sync:" + safeReason(reason), true);
                        }
                    }

                    @Override
                    public void onAlertCleared(String ownerPackage, int sessionGeneration,
                            DirectTbtFrame frame, String reason) {
                        latestWazeClusterFrameReason = safeReason(reason);
                        latestWazeClusterFrameSessionGeneration = sessionGeneration;
                        latestWazeClusterFrame = frame;
                        invalidatePendingWazeDirectFrames();
                        handler.post(() -> {
                            if (!isCurrentWazeDirectCallback(ownerPackage, sessionGeneration)) {
                                return;
                            }
                            if (wazeSurfaceActive) return;
                            DirectTbtFrame outputFrame = applySpeedLimitOverlay(
                                    ownerPackage, frame, SystemClock.elapsedRealtime());
                            hudOutput.clearDirectAlertAndRepublish(
                                    ownerPackage, sessionGeneration, outputFrame, reason,
                                    SystemClock.elapsedRealtime());
                        });
                    }

                    @Override
                    public void onNavigationEnded(String ownerPackage,
                            int sessionGeneration, String reason) {
                        invalidatePendingWazeDirectFrames();
                        long detectedAtMs = SystemClock.elapsedRealtime();
                        String safeEndReason = safeReason(reason);
                        if (normalizeString(reason).startsWith("stopped:")) {
                            return;
                        }
                        Log.i(TAG, "waze direct navigation ended detected reason="
                                + safeEndReason + " elapsedMs=" + detectedAtMs);
                        if (!isCurrentWazeDirectCallback(ownerPackage, sessionGeneration)) {
                            return;
                        }
                        WazeCaptureDebugWriter.get().appEvent(context,
                                "nav_live waze_direct navigation_ended_detected reason="
                                        + safeEndReason + " elapsedMs=" + detectedAtMs);
                        hudOutput.endDirectOutput(
                                ownerPackage, sessionGeneration,
                                "waze-direct-ended:" + safeEndReason, detectedAtMs);
                        handler.post(() -> {
                            if (!isCurrentWazeDirectCallback(ownerPackage, sessionGeneration)) {
                                return;
                            }
                            endWazeDirectSession("navigation-ended:" + safeEndReason);
                            onWazeDirectNavigationEnded(
                                    ownerPackage, sessionGeneration, reason, detectedAtMs);
                        });
                    }

                    @Override
                    public void onLiveness(String ownerPackage, int sessionGeneration,
                            String reason) {
                        handler.post(() -> {
                            if (!isCurrentWazeDirectCallback(ownerPackage, sessionGeneration)) {
                                return;
                            }
                            hudOutput.renewDirectLease(
                                    ownerPackage, sessionGeneration, reason);
                        });
                    }

                    @Override
                    public void onSurfaceReady(String ownerPackage, int sessionGeneration) {
                        handler.post(() -> onWazeSurfaceReady(
                                ownerPackage, sessionGeneration));
                    }

                    @Override
                    public void onSurfaceUnavailable(String ownerPackage,
                            int sessionGeneration, String reason) {
                        handler.post(() -> onWazeSurfaceUnavailable(
                                ownerPackage, sessionGeneration, reason));
                    }

                    @Override
                    public void onLog(String message) {
                        String line = "waze_direct " + normalizeString(message);
                        eventWazeDirectSession("channel", message);
                        Log.i(TAG, line);
                        WazeCaptureDebugWriter.get().appEvent(
                                context, "nav_live " + line);
                    }
                });
        this.wazeSurfaceDirectChannel = new WazeDirectChannel(
                context, createWazeSurfaceListener());
        this.gmapsDirectChannel = new GMapsDirectChannel(context,
                new GMapsDirectChannel.Listener() {
                    @Override
                    public void onHandshakeAvailable(String ownerPackage,
                            long sessionGeneration, String reason) {
                        handler.post(() -> onGMapsDirectHandshakeAvailable(
                                ownerPackage, sessionGeneration, reason));
                    }

                    @Override
                    public void onHandshakeUnavailable(String ownerPackage,
                            long sessionGeneration, String reason) {
                        handler.post(() -> onGMapsDirectHandshakeUnavailable(
                                ownerPackage, sessionGeneration, reason));
                    }

                    @Override
                    public void onNavigationStarted(String ownerPackage,
                            long sessionGeneration, String reason) {
                        handler.post(() -> {
                            if (!isCurrentGMapsDirectCallback(ownerPackage, sessionGeneration)) {
                                return;
                            }
                            ensureGMapsDirectSession(
                                    "navigation-started:" + safeReason(reason));
                            eventGMapsDirectSession("navigation_started", reason);
                            onGMapsDirectNavigationStarted(
                                    ownerPackage, sessionGeneration, reason);
                        });
                    }

                    @Override
                    public void onFrame(String ownerPackage, long sessionGeneration,
                            DirectTbtFrame frame, String reason,
                            GMapsDirectChannel.BitmapSelection bitmapSelection) {
                        handler.post(() -> onGMapsDirectFrame(
                                ownerPackage, sessionGeneration, frame, reason, bitmapSelection));
                    }

                    @Override
                    public void onSpeedLimit(String ownerPackage, long sessionGeneration,
                            int displayValue, int kph, String unit, long eventElapsedMs) {
                        handler.post(() -> {
                            if (!isCurrentGMapsDirectCallback(
                                    ownerPackage, sessionGeneration)) return;
                            onDirectSpeedLimitEvent(
                                    ownerPackage, displayValue, kph, unit, eventElapsedMs);
                        });
                    }

                    @Override
                    public void onNavigationEnded(String ownerPackage,
                            long sessionGeneration, String reason) {
                        long detectedAtMs = SystemClock.elapsedRealtime();
                        handler.post(() -> {
                            if (!isCurrentGMapsDirectCallback(ownerPackage, sessionGeneration)) {
                                return;
                            }
                            hudOutput.endDirectOutput(
                                    ownerPackage, sessionGeneration,
                                    "gmaps-direct-ended:" + safeReason(reason), detectedAtMs);
                            endGMapsDirectSession(
                                    "navigation-ended:" + safeReason(reason));
                            onGMapsDirectNavigationEnded(reason, detectedAtMs);
                        });
                    }

                    @Override
                    public void onLiveness(String ownerPackage, long sessionGeneration,
                            String reason) {
                        handler.post(() -> {
                            if (!isCurrentGMapsDirectCallback(ownerPackage, sessionGeneration)) {
                                return;
                            }
                            hudOutput.renewDirectLease(
                                    ownerPackage, sessionGeneration, reason);
                        });
                    }

                    @Override
                    public void onLog(String message) {
                        logGMapsDirectChannelEvent(message);
                    }
                });
    }

    private WazeDirectChannel.Listener createWazeSurfaceListener() {
        return new WazeDirectChannel.Listener() {
            @Override
            public void onHandshakeAvailable(String ownerPackage,
                    int sessionGeneration, String reason) {
                handler.post(() -> {
                    if (!isCurrentWazeSurfaceCallback(ownerPackage, sessionGeneration)) return;
                    log("waze surface channel ready reason=" + safeReason(reason));
                });
            }

            @Override
            public void onHandshakeUnavailable(String ownerPackage,
                    int sessionGeneration, String reason) {
                handler.post(() -> {
                    if (!isCurrentWazeSurfaceCallback(ownerPackage, sessionGeneration)) return;
                    if (wazeSurfaceLaunchPending || wazeSurfaceActive) {
                        fallbackFromWazeSurface("surface-channel-unavailable:"
                                + safeReason(reason));
                    }
                });
            }

            @Override
            public void onNavigationStarted(String ownerPackage,
                    int sessionGeneration, String reason) {
                if (!isCurrentWazeSurfaceCallback(ownerPackage, sessionGeneration)) return;
                eventWazeDirectSession("surface_navigation_started", reason);
            }

            @Override
            public void onFrame(String ownerPackage, int sessionGeneration,
                    DirectTbtFrame frame, String reason) {
                latestWazeSurfaceFrameReason = safeReason(reason);
                latestWazeSurfaceFrameSessionGeneration = sessionGeneration;
                latestWazeSurfaceFrame = frame;
                if (wazeSurfaceActive) {
                    enqueueLatestWazeDirectFrame(
                            ownerPackage, sessionGeneration,
                            withRetainedWazeClusterAlert(frame),
                            "surface:" + safeReason(reason), true);
                }
            }

            @Override
            public void onAlertCleared(String ownerPackage, int sessionGeneration,
                    DirectTbtFrame frame, String reason) {
                latestWazeSurfaceFrameReason = safeReason(reason);
                latestWazeSurfaceFrameSessionGeneration = sessionGeneration;
                latestWazeSurfaceFrame = frame;
                handler.post(() -> {
                    if (!wazeSurfaceActive
                            || !isCurrentWazeSurfaceCallback(
                            ownerPackage, sessionGeneration)) return;
                    DirectTbtFrame outputFrame = withRetainedWazeClusterAlert(frame);
                    if (outputFrame.getAlertOverlay().isActive()) {
                        enqueueLatestWazeDirectFrame(
                                ownerPackage, sessionGeneration, outputFrame,
                                "surface-alert-retained:" + safeReason(reason), true);
                    } else {
                        outputFrame = applySpeedLimitOverlay(
                                ownerPackage, outputFrame, SystemClock.elapsedRealtime());
                        hudOutput.clearDirectAlertAndRepublish(
                                ownerPackage, wazeDirectChannel.sessionGeneration(), outputFrame,
                                "surface:" + safeReason(reason),
                                SystemClock.elapsedRealtime());
                    }
                });
            }

            @Override
            public void onNavigationEnded(String ownerPackage,
                    int sessionGeneration, String reason) {
                if (!isCurrentWazeSurfaceCallback(ownerPackage, sessionGeneration)) return;
                eventWazeDirectSession("surface_navigation_ended", reason);
            }

            @Override
            public void onLiveness(String ownerPackage, int sessionGeneration, String reason) {
                handler.post(() -> {
                    if (!wazeSurfaceActive
                            || !isCurrentWazeSurfaceCallback(
                            ownerPackage, sessionGeneration)) return;
                    hudOutput.renewDirectLease(
                            ownerPackage, wazeDirectChannel.sessionGeneration(),
                            "surface:" + safeReason(reason));
                });
            }

            @Override
            public void onSurfaceReady(String ownerPackage, int sessionGeneration) {
                handler.post(() -> onWazeSurfaceReady(
                        ownerPackage, sessionGeneration));
            }

            @Override
            public void onSurfaceUnavailable(String ownerPackage,
                    int sessionGeneration, String reason) {
                handler.post(() -> onWazeSurfaceUnavailable(
                        ownerPackage, sessionGeneration, reason));
            }

            @Override
            public void onLog(String message) {
                String line = "waze_surface_direct " + normalizeString(message);
                eventWazeDirectSession("surface_channel", message);
                Log.i(TAG, line);
                WazeCaptureDebugWriter.get().appEvent(context, "nav_live " + line);
            }
        };
    }

    //coalesces complete direct snapshots so a busy state worker never replays stale guidance.
    private void enqueueLatestWazeDirectFrame(String ownerPackage,
            int sessionGeneration, DirectTbtFrame frame, String reason,
            boolean fromSurface) {
        if (frame == null) {
            return;
        }
        synchronized (wazeDirectFrameLock) {
            if (pendingWazeDirectFrame != null) {
                coalescedWazeDirectFrames++;
            }
            pendingWazeDirectFrame = frame;
            pendingWazeDirectFrameReason = safeReason(reason);
            pendingWazeDirectFrameOwner = normalizeString(ownerPackage);
            pendingWazeDirectFrameSessionGeneration = sessionGeneration;
            pendingWazeDirectFrameGeneration = wazeDirectFrameGeneration;
            pendingWazeDirectFrameFromSurface = fromSurface;
            if (!wazeDirectFrameDispatchScheduled) {
                wazeDirectFrameDispatchScheduled = true;
                handler.post(wazeDirectFrameDispatch);
            }
        }
    }

    private void dispatchLatestWazeDirectFrame() {
        synchronized (wazeDirectFrameLock) {
            if (!wazeDirectFrameDispatchScheduled) {
                return;
            }
            wazeDirectFrameDispatchScheduled = false;
            DirectTbtFrame frame = pendingWazeDirectFrame;
            String reason = pendingWazeDirectFrameReason;
            String ownerPackage = pendingWazeDirectFrameOwner;
            int sessionGeneration = pendingWazeDirectFrameSessionGeneration;
            int generation = pendingWazeDirectFrameGeneration;
            boolean fromSurface = pendingWazeDirectFrameFromSurface;
            int coalesced = coalescedWazeDirectFrames;
            pendingWazeDirectFrame = null;
            pendingWazeDirectFrameReason = "";
            pendingWazeDirectFrameOwner = "";
            coalescedWazeDirectFrames = 0;
            boolean current = fromSurface
                    ? isCurrentWazeSurfaceCallback(ownerPackage, sessionGeneration)
                    : isCurrentWazeDirectCallback(ownerPackage, sessionGeneration);
            if (frame == null || generation != wazeDirectFrameGeneration || !current
                    || fromSurface != wazeSurfaceActive) {
                return;
            }
            if (coalesced > 0) {
                WazeCaptureDebugWriter.get().appEvent(context,
                        "nav_live waze_direct frames_coalesced=" + coalesced);
            }
            onWazeDirectFrame(ownerPackage, wazeDirectChannel.sessionGeneration(),
                    frame, reason);
        }
    }

    //drops queued snapshots before lifecycle callbacks can switch or end the direct session.
    private void invalidatePendingWazeDirectFrames() {
        synchronized (wazeDirectFrameLock) {
            wazeDirectFrameGeneration++;
            pendingWazeDirectFrame = null;
            pendingWazeDirectFrameReason = "";
            pendingWazeDirectFrameOwner = "";
            pendingWazeDirectFrameFromSurface = false;
            coalescedWazeDirectFrames = 0;
            wazeDirectFrameDispatchScheduled = false;
            handler.removeCallbacks(wazeDirectFrameDispatch);
        }
    }

    private void startWazeDirectProbe(String reason) {
        startWazeDirectHost(reason, true, true, false);
    }

    private void startWazeDirectForRoute(String reason) {
        startWazeDirectForRoute(reason, false);
    }

    private void startWazeDirectForRoute(String reason, boolean recoveringRoute) {
        startWazeDirectHost(reason, false, false, recoveringRoute);
    }

    private void startWazeDirectHost(String reason, boolean clearLegacyRoute,
            boolean allowLegacyTimeout, boolean recoveringRoute) {
        boolean retainSurfaceRoute = recoveringRoute
                && wazeSurfaceEnabledForRoute && !wazeSurfaceDismissedForRoute
                && (wazeSurfaceLaunchPending || wazeSurfaceActive
                || WazeSurfaceActivity.isActive() || wazeSurfaceDirectChannel.isActive());
        DirectTbtFrame retainedSurfaceFrame = latestWazeSurfaceFrame;
        String retainedSurfaceReason = latestWazeSurfaceFrameReason;
        int retainedSurfaceSessionGeneration = latestWazeSurfaceFrameSessionGeneration;
        endWazeDirectSession("restart:" + safeReason(reason));
        resetWazeDirectSessionState();
        if (recoveringRoute) wazeDirectNavigating = true;
        if (retainSurfaceRoute) {
            wazeSurfaceRouteGeneration = wazeRouteGeneration;
            latestWazeSurfaceFrameReason = retainedSurfaceReason;
            latestWazeSurfaceFrameSessionGeneration = retainedSurfaceSessionGeneration;
            latestWazeSurfaceFrame = retainedSurfaceFrame;
        }
        wazeDirectSession = DirectSessionLog.start(
                context, NavigationLogStorage.WAZE_DIRECT_DIR, reason);
        if (clearLegacyRoute) {
            long now = SystemClock.elapsedRealtime();
            String resetReason = "waze-direct-probe:" + safeReason(reason);
            NavRouteStateStore.get(context).clearRoute(WAZE_PACKAGE, resetReason, now);
            WazeRouteTracker.get(context).onRouteEnded(resetReason, now);
        }
        hudOutput.selectNavigationSource(
                HudOutputCoordinator.Source.NONE,
                "waze-direct-probe:" + safeReason(reason));
        cancelWazeDirectColdTimeout();
        wazeDirectChannel.start(reason, WazeDirectChannel.Mode.CLUSTER);
        if (allowLegacyTimeout) {
            scheduleWazeDirectColdTimeout(reason);
            log("waze source=waiting_direct routeAwareTimeoutMs=" + WAZE_DIRECT_TIMEOUT_MS
                    + " reason=" + safeReason(reason));
        } else {
            log("waze source=direct_route_active reason=" + safeReason(reason));
        }
    }

    private void waitForWazeRouteLifecycle(String reason) {
        endWazeDirectSession("wait-route:" + safeReason(reason));
        resetWazeDirectSessionState();
        wazeDirectChannel.stop("wait-route:" + safeReason(reason));
        wazeSurfaceDirectChannel.stop("wait-route:" + safeReason(reason));
        WazeCropCapture.get(context).stop("wait-route:" + safeReason(reason));
        hudOutput.selectNavigationSource(
                HudOutputCoordinator.Source.NONE,
                "waze-wait-route:" + safeReason(reason));
        log("waze source=waiting_route_lifecycle reason=" + safeReason(reason));
    }

    private void onWazeDirectHandshakeAvailable(String ownerPackage,
            int sessionGeneration, String reason) {
        if (!isCurrentWazeDirectCallback(ownerPackage, sessionGeneration)) {
            return;
        }
        wazeDirectHandshakeAvailable = true;
        hudOutput.renewDirectLease(ownerPackage, sessionGeneration, reason);
        log("waze direct handshake available reason=" + safeReason(reason));
    }

    private void onWazeDirectHandshakeUnavailable(String ownerPackage,
            int sessionGeneration, String reason) {
        if (!isCurrentWazeDirectCallback(ownerPackage, sessionGeneration)) {
            return;
        }
        wazeDirectHandshakeAvailable = false;
        if (wazeSurfaceActive && wazeSurfaceDirectChannel.isActive()) {
            log("waze cluster unavailable while surface channel remains active reason="
                    + safeReason(reason));
            return;
        }
        String normalizedReason = normalizeString(reason);
        if (wazeDirectRouteEnded
                || normalizedReason.startsWith("suspended:")
                || normalizedReason.startsWith("hard-stopped:")
                || normalizedReason.startsWith("mode-switch:")) {
            log("waze direct expected stop reason=" + safeReason(reason));
            return;
        }
        hudOutput.clearDirectFrameForLoss(
                ownerPackage, sessionGeneration, "waze-direct-unavailable:" + safeReason(reason),
                SystemClock.elapsedRealtime());
        if (isWazeBridgeSupportedCached()) {
            log("waze direct unavailable; active-route recovery retained reason="
                    + safeReason(reason));
            return;
        }
        if (wazeDirectFrameReceived
                && WazeRouteTracker.get(context).isRouteActive(
                SystemClock.elapsedRealtime())) {
            activateWazeLegacyFallback("direct-unavailable:" + safeReason(reason));
        } else {
            wazeDirectFrameReceived = false;
            scheduleWazeDirectColdTimeout("direct-unavailable:" + safeReason(reason));
        }
    }

    private void onWazeDirectNavigationStarted(String ownerPackage,
            int sessionGeneration, String reason) {
        if (!isCurrentWazeDirectCallback(ownerPackage, sessionGeneration)) {
            return;
        }
        boolean newRoute = !wazeDirectNavigating;
        if (newRoute) {
            clearDirectSpeedLimit(WAZE_PACKAGE);
            wazeDirectFrameReceived = false;
            wazeSurfaceEnabledForRoute = HudPrefs.isWazeCustomSurfaceEnabled(context);
            wazeSurfaceDismissedForRoute = false;
        }
        wazeDirectNavigating = true;
        wazeDirectRouteEnded = false;
        long now = SystemClock.elapsedRealtime();
        NavRouteStateStore.get(context).updateFromVisualRouteEvidence(
                WAZE_PACKAGE, "waze_direct", "navigation_started", now);
        WazeRouteTracker.get(context).onDirectRouteEvidence(
                "direct-navigation-started", now);
        hudOutput.renewDirectLease(ownerPackage, sessionGeneration, reason);
        if (!isWazeBridgeSupportedCached()) {
            scheduleWazeDirectColdTimeout("direct-navigation-started");
        }
        log("waze direct navigation started reason=" + safeReason(reason));
        if (wazeSurfaceActive && latestWazeSurfaceFrame != null) {
            enqueueLatestWazeDirectFrame(
                    WAZE_PACKAGE, latestWazeSurfaceFrameSessionGeneration,
                    withRetainedWazeClusterAlert(latestWazeSurfaceFrame),
                    "surface-recovery:" + latestWazeSurfaceFrameReason, true);
        }
        maybeLaunchWazeSurface("navigation-started:" + safeReason(reason));
    }

    void onWazeSurfaceActivityCreated(int taskId, long instanceId) {
        handler.post(() -> {
            wazeSurfaceTaskId = taskId;
            wazeSurfaceInstanceId = instanceId;
            if (!shouldUseWazeSurface()) {
                log("waze surface activity rejected route/config no longer eligible");
                WazeSurfaceActivity.finishActive("not-eligible");
                return;
            }
            wazeSurfaceRouteGeneration = wazeRouteGeneration;
            wazeSurfaceDirectChannel.start(
                    "surface-activity-created", WazeDirectChannel.Mode.MAIN_SURFACE);
            log("waze surface activity created task=" + taskId
                    + " clusterSession=" + wazeDirectChannel.sessionGeneration());
        });
    }

    void onWazeSurfaceActivityVisibilityChanged(long instanceId, boolean visible) {
        handler.post(() -> {
            if (instanceId != wazeSurfaceInstanceId) return;
            wazeSurfaceVisible = visible;
            log("waze surface activity visible=" + visible + " task=" + wazeSurfaceTaskId);
            if (visible && shouldUseWazeSurface()
                    && wazeSurfaceDirectChannel.isActive()) {
                onWazeSurfaceReady(
                        WAZE_PACKAGE, wazeSurfaceDirectChannel.sessionGeneration());
            } else if (!visible && shouldUseWazeSurface()) {
                suspendWazeSurface("activity-backgrounded");
            }
        });
    }

    void onWazeSurfaceActivityDestroyed(
            int taskId, long instanceId, boolean changingConfigurations) {
        handler.post(() -> {
            if (taskId != wazeSurfaceTaskId || instanceId != wazeSurfaceInstanceId) return;
            if (!changingConfigurations) {
                wazeSurfaceTaskId = -1;
                wazeSurfaceInstanceId = 0L;
            }
            if (changingConfigurations || wazeSurfaceDismissedForRoute
                    || !wazeDirectNavigating) return;
            suspendWazeSurface("surface-activity-destroyed");
        });
    }

    void onWazeSurfaceBackPressed(long instanceId) {
        handler.post(() -> {
            if (instanceId != wazeSurfaceInstanceId) return;
            wazeSurfaceDismissedForRoute = true;
            fallbackFromWazeSurface("surface-back");
        });
    }

    private void maybeLaunchWazeSurface(String reason) {
        requestWazeSurfaceActivity(reason, false);
    }

    private void onWazeAppForegroundEventOnMain(
            long eventElapsedMs, long bridgeGeneration) {
        log("waze app foreground elapsedMs=" + eventElapsedMs
                + " bridgeGeneration=" + bridgeGeneration
                + " surfaceVisible=" + WazeSurfaceActivity.isVisible());
        if (!shouldUseWazeSurface() || WazeSurfaceActivity.isVisible()) return;
        requestWazeSurfaceActivity("waze-app-foreground", true);
    }

    private void requestWazeSurfaceActivity(String reason, boolean bringToFront) {
        if (!shouldUseWazeSurface() || wazeSurfaceLaunchPending
                || wazeSurfaceActive && WazeSurfaceActivity.isVisible()
                || !bringToFront && WazeSurfaceActivity.isActive()) return;
        NavAppDisplayState state = NavAppDisplayController.get(context).lastState(WAZE_PACKAGE);
        int displayId = state == null || state.displayId < 0 ? 0 : state.displayId;
        wazeSurfaceLaunchPending = true;
        handler.removeCallbacks(wazeSurfaceReadyTimeout);
        handler.postDelayed(wazeSurfaceReadyTimeout, WAZE_SURFACE_READY_TIMEOUT_MS);
        if (!WazeSurfaceActivity.launch(context, displayId)) {
            fallbackFromWazeSurface("surface-launch-failed");
            return;
        }
        log("waze surface launch requested display=" + displayId
                + " bringToFront=" + bringToFront
                + " timeoutMs=" + WAZE_SURFACE_READY_TIMEOUT_MS
                + " reason=" + safeReason(reason));
    }

    private boolean shouldUseWazeSurface() {
        return active
                && WAZE_PACKAGE.equals(activePackage)
                && wazeDirectNavigating
                && NavCapturePrefs.isHudEnabled(context, WAZE_PACKAGE)
                && wazeSurfaceEnabledForRoute
                && !wazeSurfaceDismissedForRoute;
    }

    private void onWazeSurfaceReady(String ownerPackage, int sessionGeneration) {
        if (!isCurrentWazeSurfaceCallback(ownerPackage, sessionGeneration)
                || !shouldUseWazeSurface()) return;
        handler.removeCallbacks(wazeSurfaceReadyTimeout);
        wazeSurfaceLaunchPending = false;
        wazeSurfaceActive = true;
        wazeSurfaceVisible = WazeSurfaceActivity.isVisible();
        invalidatePendingWazeDirectFrames();
        int hudSessionGeneration = wazeDirectChannel.sessionGeneration();
        hudOutput.clearDirectFrameForLoss(
                WAZE_PACKAGE, hudSessionGeneration, "waze-surface-ready",
                SystemClock.elapsedRealtime());
        if (latestWazeSurfaceFrame != null
                && latestWazeSurfaceFrameSessionGeneration == sessionGeneration) {
            enqueueLatestWazeDirectFrame(
                    WAZE_PACKAGE, sessionGeneration,
                    withRetainedWazeClusterAlert(latestWazeSurfaceFrame),
                    "surface:" + latestWazeSurfaceFrameReason, true);
        }
        log("waze surface ready task=" + wazeSurfaceTaskId
                + " surfaceSession=" + sessionGeneration
                + " clusterSession=" + hudSessionGeneration);
    }

    private void onWazeSurfaceUnavailable(String ownerPackage, int sessionGeneration,
            String reason) {
        if (!isCurrentWazeSurfaceCallback(ownerPackage, sessionGeneration)) return;
        if (!wazeSurfaceVisible && WazeSurfaceActivity.isActive()) {
            suspendWazeSurface("surface-backgrounded:" + safeReason(reason));
            return;
        }
        if (wazeSurfaceLaunchPending || wazeSurfaceActive) {
            fallbackFromWazeSurface("surface-unavailable:" + safeReason(reason));
        } else {
            log("waze surface temporarily unavailable reason=" + safeReason(reason));
        }
    }

    static void onWazeSpeedLimitEvent(
            Context context, int displayValue, String unit, long eventElapsedMs) {
        NavHudLiveSender current;
        synchronized (NavHudLiveSender.class) {
            current = instance;
        }
        if (current != null) {
            current.handler.post(() -> current.onDirectSpeedLimitEvent(
                    WAZE_PACKAGE, displayValue, -1, unit, eventElapsedMs));
        } else {
            DirectSpeedLimitStore.update(
                    WAZE_PACKAGE, displayValue, -1, unit, eventElapsedMs);
        }
    }

    static void onWazeAppForegroundEvent(
            Context context, long eventElapsedMs, long bridgeGeneration) {
        NavHudLiveSender current;
        synchronized (NavHudLiveSender.class) {
            current = instance;
        }
        if (current != null) {
            current.handler.post(() -> current.onWazeAppForegroundEventOnMain(
                    eventElapsedMs, bridgeGeneration));
        }
    }

    private void suspendWazeSurface(String reason) {
        handler.removeCallbacks(wazeSurfaceReadyTimeout);
        boolean wasSurfaceActive = wazeSurfaceActive;
        wazeSurfaceLaunchPending = false;
        wazeSurfaceActive = false;
        wazeSurfaceVisible = false;
        publishClusterAfterSurfaceLoss(reason, wasSurfaceActive);
        log("waze surface suspended reason=" + safeReason(reason));
    }

    private void fallbackFromWazeSurface(String reason) {
        handler.removeCallbacks(wazeSurfaceReadyTimeout);
        boolean wasSurfaceActive = wazeSurfaceActive;
        boolean wasSurface = wazeSurfaceLaunchPending || wazeSurfaceActive
                || WazeSurfaceActivity.isActive();
        wazeSurfaceLaunchPending = false;
        wazeSurfaceActive = false;
        wazeSurfaceVisible = false;
        wazeSurfaceTaskId = -1;
        wazeSurfaceInstanceId = 0L;
        wazeSurfaceDismissedForRoute = true;
        wazeSurfaceRouteGeneration = -1;
        WazeSurfaceActivity.finishActive(reason);
        //Stopping the main host during an active route invokes Waze onAppStop and ends navigation.
        if (!wasSurface || !active || !WAZE_PACKAGE.equals(activePackage)
                || !wazeDirectNavigating) return;
        if (!wasSurfaceActive) {
            log("waze surface attempt ended before HUD source switch reason="
                    + safeReason(reason));
            return;
        }
        publishClusterAfterSurfaceLoss(reason, true);
    }

    private void publishClusterAfterSurfaceLoss(String reason, boolean wasSurfaceActive) {
        if (!wasSurfaceActive || !active || !WAZE_PACKAGE.equals(activePackage)
                || !wazeDirectNavigating) return;
        invalidatePendingWazeDirectFrames();
        int clusterGeneration = wazeDirectChannel.sessionGeneration();
        hudOutput.clearDirectFrameForLoss(
                WAZE_PACKAGE, clusterGeneration,
                "waze-surface-fallback:" + safeReason(reason),
                SystemClock.elapsedRealtime());
        if (latestWazeClusterFrame != null
                && latestWazeClusterFrameSessionGeneration == clusterGeneration) {
            enqueueLatestWazeDirectFrame(
                    WAZE_PACKAGE, clusterGeneration, latestWazeClusterFrame,
                    "surface-fallback:" + latestWazeClusterFrameReason, false);
        }
        log("waze surface fallback retained cluster session=" + clusterGeneration
                + " reason=" + safeReason(reason));
    }

    private DirectTbtFrame withRetainedWazeClusterAlert(DirectTbtFrame surfaceFrame) {
        if (surfaceFrame == null) {
            return surfaceFrame;
        }
        if (!HudPrefs.isWazeAlertsEnabled(context)) {
            return surfaceFrame.withAlertOverlay(DirectTbtFrame.AlertOverlay.inactive());
        }
        if (surfaceFrame.getAlertOverlay().isActive()) return surfaceFrame;
        DirectTbtFrame clusterFrame = latestWazeClusterFrame;
        if (clusterFrame == null
                || latestWazeClusterFrameSessionGeneration
                != wazeDirectChannel.sessionGeneration()
                || !clusterFrame.getAlertOverlay().isActive()) {
            return surfaceFrame;
        }
        return surfaceFrame.withAlertOverlay(clusterFrame.getAlertOverlay());
    }

    private static boolean wazeAlertStateChanged(
            DirectTbtFrame previousFrame, DirectTbtFrame nextFrame) {
        DirectTbtFrame.AlertOverlay previous = previousFrame == null
                ? DirectTbtFrame.AlertOverlay.inactive()
                : previousFrame.getAlertOverlay();
        DirectTbtFrame.AlertOverlay next = nextFrame == null
                ? DirectTbtFrame.AlertOverlay.inactive()
                : nextFrame.getAlertOverlay();
        if (previous.isActive() != next.isActive()) return true;
        if (!previous.isActive()) return false;
        return previous.getId() != next.getId()
                || previous.getDistanceMeters() != next.getDistanceMeters()
                || previous.useRouteNative() != next.useRouteNative()
                || !previous.getDisplayText().equals(next.getDisplayText())
                || !Arrays.equals(previous.getManeuverPng(), next.getManeuverPng());
    }

    private void closeWazeSurface(String reason) {
        handler.removeCallbacks(wazeSurfaceReadyTimeout);
        wazeSurfaceLaunchPending = false;
        wazeSurfaceActive = false;
        wazeSurfaceVisible = false;
        wazeSurfaceEnabledForRoute = false;
        wazeSurfaceDismissedForRoute = false;
        wazeSurfaceTaskId = -1;
        wazeSurfaceInstanceId = 0L;
        wazeSurfaceRouteGeneration = -1;
        WazeSurfaceActivity.finishActive(reason);
    }

    private void onWazeDirectFrame(String ownerPackage, int sessionGeneration,
            DirectTbtFrame frame, String reason) {
        if (!isCurrentWazeDirectCallback(ownerPackage, sessionGeneration)
                || !wazeDirectNavigating || frame == null) {
            log("waze direct frame ignored active=" + active
                    + " navigating=" + wazeDirectNavigating
                    + " reason=" + safeReason(reason));
            return;
        }
        long now = SystemClock.elapsedRealtime();
        wazeDirectHandshakeAvailable = true;
        wazeDirectFrameReceived = true;
        wazeDirectRouteEnded = false;
        cancelWazeDirectColdTimeout();
        cancelWazeFallbackReadiness();
        NavRouteStateStore.get(context).updateFromVisualRouteEvidence(
                WAZE_PACKAGE, "waze_direct", safeReason(reason), now);
        WazeRouteTracker.get(context).onDirectRouteEvidence(
                "direct:" + safeReason(reason), now);
        DirectTbtFrame outputFrame = applySpeedLimitOverlay(
                ownerPackage, frame, now);
        long receivedWallClockMs = System.currentTimeMillis();
        logWazeDirectFrame(outputFrame, reason, now, receivedWallClockMs,
                DirectTbtPayload.Options.from(context));
        hudOutput.publishDirect(
                outputFrame, reason, now, ownerPackage, sessionGeneration);
        hudOutput.selectNavigationSource(
                HudOutputCoordinator.Source.DIRECT,
                "waze-direct-frame:" + safeReason(reason),
                ownerPackage, sessionGeneration);
        if (wazeFallbackActive) {
            WazeCropCapture.get(context).stop("direct-recovered");
            resetLatestPayload();
        }
        wazeFallbackActive = false;
        String sourceLine = "waze source=direct reason=" + safeReason(reason);
        Log.i(TAG, sourceLine);
        WazeCaptureDebugWriter.get().appEvent(context, "nav_live " + sourceLine);
    }

    private void logWazeDirectFrame(DirectTbtFrame frame, String reason,
                                    long receivedAtMs,
                                    long receivedWallClockMs,
                                    DirectTbtPayload.Options options) {
        byte[] maneuver = frame.getManeuverPng();
        byte[] lanes = frame.getLanePng();
        DirectTbtFrame.AlertOverlay alert = frame.getAlertOverlay();
        DirectTbtPayload.Prepared prepared = DirectTbtPayload.prepare(frame, options);
        DirectSessionLog session = wazeDirectSession;
        String maneuverArtifact = "";
        String laneArtifact = "";
        String alertArtifact = "";
        if (session != null && HudPrefs.isDetailedDebugArtifactsEnabled(context)) {
            maneuverArtifact = session.saveArtifact("maneuver", maneuver);
            laneArtifact = session.saveArtifact("lane", lanes);
            if (alert.isActive()) {
                alertArtifact = session.saveArtifact("alert", alert.getManeuverPng());
            }
        }
        String raw = "reason=" + safeReason(reason)
                + " receivedAtElapsedMs=" + receivedAtMs
                + " receivedAtWallMs=" + receivedWallClockMs
                + " rawType=" + frame.getRawManeuverType()
                + " amap=" + frame.getAmapManeuver()
                + " byd=" + frame.getBydManeuver()
                + " distanceM=" + frame.getDistanceMeters()
                + " road=\"" + normalizeString(frame.getRoadText()) + "\""
                + " cue=\"" + normalizeString(frame.getCueText()) + "\""
                + " maneuverBytes=" + maneuver.length
                + " maneuverArtifact=\"" + maneuverArtifact + "\""
                + " laneCount=" + frame.getLanes().size()
                + " laneDirections=\"" + laneDirections(frame) + "\""
                + " laneBytes=" + lanes.length
                + " laneArtifact=\"" + laneArtifact + "\""
                + " speedLimit=" + frame.getSpeedLimit().getDisplayValue()
                + " speedLimitKph=" + frame.getSpeedLimit().getKph()
                + " speedUnit=\"" + frame.getSpeedLimit().getUnit() + "\""
                + " alert=" + alert.isActive()
                + " alertId=" + (alert.isActive() ? alert.getId() : -1)
                + " alertArtifact=\"" + alertArtifact + "\""
                + " hudManeuver=" + prepared.maneuverMode()
                + " hudManeuverBytes=" + prepared.maneuverPngBytes()
                + " hudNative=" + prepared.nativeManeuver()
                + " hudDistanceM=" + prepared.distanceMeters()
                + " hudText=\"" + normalizeString(prepared.displayText()) + "\""
                + " hudLaneCount=" + prepared.laneCount()
                + " hudLaneBytes=" + prepared.lanePngBytes();
        if (session != null) session.raw(raw);
        WazeCaptureDebugWriter.get().rawEvent(
                context, "waze_direct", WAZE_PACKAGE, raw);
    }

    private void startGMapsDirectProbe(String reason) {
        endGMapsDirectSession("restart:" + safeReason(reason));
        resetGMapsDirectSessionState();
        gmapsDirectSession = DirectSessionLog.start(
                context, NavigationLogStorage.GMAPS_DIRECT_DIR, reason);
        hudOutput.selectNavigationSource(
                HudOutputCoordinator.Source.NONE,
                "gmaps-direct-probe:" + safeReason(reason));
        gmapsDirectChannel.start(reason);
        scheduleGMapsDirectTimeout();
        log("gmaps source=waiting_direct timeoutMs=" + GMAPS_DIRECT_TIMEOUT_MS
                + " reason=" + safeReason(reason));
    }

    private void onGMapsDirectHandshakeAvailable(String ownerPackage,
            long sessionGeneration, String reason) {
        if (!isCurrentGMapsDirectCallback(ownerPackage, sessionGeneration)) return;
        gmapsDirectTimeoutSessionGeneration = sessionGeneration;
        hudOutput.renewDirectLease(ownerPackage, sessionGeneration, reason);
        log("gmaps direct handshake available reason=" + safeReason(reason));
    }

    private void onGMapsDirectHandshakeUnavailable(String ownerPackage,
            long sessionGeneration, String reason) {
        if (!isCurrentGMapsDirectCallback(ownerPackage, sessionGeneration)
                || gmapsDirectRouteEnded) return;
        gmapsDirectTimeoutSessionGeneration = sessionGeneration;
        hudOutput.clearDirectFrameForLoss(
                ownerPackage, sessionGeneration, "gmaps-direct-unavailable:" + safeReason(reason),
                SystemClock.elapsedRealtime());
        gmapsDirectFrameReceived = false;
        if (gmapsDirectTimedOut) {
            activateGMapsLegacyFallbackIfReady("handshake-unavailable");
        } else if (!gmapsDirectTimeoutScheduled) {
            scheduleGMapsDirectTimeout();
        }
        log("gmaps direct handshake unavailable reason=" + safeReason(reason));
    }

    private void onGMapsDirectNavigationStarted(String ownerPackage,
            long sessionGeneration, String reason) {
        if (!isCurrentGMapsDirectCallback(ownerPackage, sessionGeneration)) return;
        clearDirectSpeedLimit(ownerPackage);
        gmapsDirectTimeoutSessionGeneration = sessionGeneration;
        boolean keepFallbackUntilFrame = shouldKeepGMapsFallbackOnDirectStart(
                gmapsDirectFallbackActive);
        gmapsDirectRouteEnded = false;
        gmapsDirectFrameReceived = false;
        gmapsDirectTimedOut = false;
        gmapsLegacyUnavailableLogged = false;
        gmapsDirectRegistrationSuppressed = false;
        lastGMapsDirectRegistrationProbeMs = 0L;
        if (!keepFallbackUntilFrame) {
            hudOutput.selectNavigationSource(
                    HudOutputCoordinator.Source.NONE,
                    "gmaps-direct-start:" + safeReason(reason));
        }
        scheduleGMapsDirectTimeout();
        log("gmaps direct navigation started reason=" + safeReason(reason));
    }

    private void onGMapsDirectFrame(String ownerPackage, long sessionGeneration,
            DirectTbtFrame frame, String reason,
            GMapsDirectChannel.BitmapSelection bitmapSelection) {
        if (!isCurrentGMapsDirectCallback(ownerPackage, sessionGeneration)
                || gmapsDirectRouteEnded || frame == null) {
            return;
        }
        gmapsDirectTimeoutSessionGeneration = sessionGeneration;
        long now = SystemClock.elapsedRealtime();
        gmapsDirectFrameReceived = true;
        gmapsDirectFallbackActive = false;
        gmapsDirectTimedOut = false;
        gmapsLegacyUnavailableLogged = false;
        gmapsDirectRegistrationSuppressed = false;
        lastGMapsDirectRegistrationProbeMs = 0L;
        latestGMapsDirectFrame = frame;
        latestGMapsDirectFrameReason = safeReason(reason);
        latestGMapsDirectFrameSessionGeneration = sessionGeneration;
        latestGMapsBitmapSelection = bitmapSelection;
        cancelGMapsDirectTimeout();
        NavRouteStateStore.get(context).updateFromVisualRouteEvidence(
                GMapsDirectChannel.PACKAGE_NAME, "gmaps_direct", safeReason(reason), now);
        DirectTbtFrame outputFrame = applySpeedLimitOverlay(
                ownerPackage, frame, now);
        logGMapsDirectFrame(outputFrame, reason, now);
        hudOutput.publishDirect(
                outputFrame, reason, now, bitmapSelection, this::logGMapsDirectChannelEvent,
                ownerPackage, sessionGeneration);
        hudOutput.selectNavigationSource(
                HudOutputCoordinator.Source.DIRECT,
                "gmaps-direct-frame:" + safeReason(reason),
                ownerPackage, sessionGeneration);
        scheduleGMapsDirectTimeout();
        log("gmaps source=direct reason=" + safeReason(reason));
    }

    private void logGMapsDirectFrame(DirectTbtFrame frame, String reason, long receivedAtMs) {
        DirectTbtPayload.Prepared prepared = DirectTbtPayload.prepare(
                frame, DirectTbtPayload.Options.from(context));
        String raw = "reason=" + safeReason(reason)
                + " receivedAtElapsedMs=" + receivedAtMs
                + " rawType=" + frame.getRawManeuverType()
                + " amap=" + frame.getAmapManeuver()
                + " byd=" + frame.getBydManeuver()
                + " distanceM=" + frame.getDistanceMeters()
                + " road=\"" + normalizeString(frame.getRoadText()) + "\""
                + " cue=\"" + normalizeString(frame.getCueText()) + "\""
                + " maneuverBytes=" + frame.getManeuverPng().length
                + " laneCount=" + frame.getLanes().size()
                + " laneDirections=\"" + laneDirections(frame) + "\""
                + " laneBytes=" + frame.getLanePng().length
                + " speedLimit=" + frame.getSpeedLimit().getDisplayValue()
                + " speedLimitKph=" + frame.getSpeedLimit().getKph()
                + " speedUnit=\"" + frame.getSpeedLimit().getUnit() + "\""
                + " hudManeuver=" + prepared.maneuverMode()
                + " hudManeuverBytes=" + prepared.maneuverPngBytes()
                + " hudNative=" + prepared.nativeManeuver()
                + " hudDistanceM=" + prepared.distanceMeters()
                + " hudText=\"" + normalizeString(prepared.displayText()) + "\""
                + " hudLaneCount=" + prepared.laneCount()
                + " hudLaneBytes=" + prepared.lanePngBytes();
        DirectSessionLog session = gmapsDirectSession;
        if (session != null) session.raw(raw);
        WazeCaptureDebugWriter.get().rawEvent(
                context, "gmaps_direct", GMapsDirectChannel.PACKAGE_NAME, raw);
    }

    private void onDirectSpeedLimitEvent(String ownerPackage, int displayValue,
            int kph, String unit, long eventElapsedMs) {
        boolean changed = DirectSpeedLimitStore.update(
                ownerPackage, displayValue, kph, unit, eventElapsedMs);
        DirectTbtFrame.SpeedLimit speed = DirectSpeedLimitStore.snapshot(ownerPackage);
        String line = "speed_limit owner=" + normalizeString(ownerPackage)
                + " value=" + speed.getDisplayValue()
                + " kph=" + speed.getKph()
                + " unit=" + speed.getUnit()
                + " changed=" + changed
                + " sourceElapsedMs=" + eventElapsedMs
                + " latencyMs=" + Math.max(
                0L, SystemClock.elapsedRealtime() - eventElapsedMs);
        if (WAZE_PACKAGE.equals(ownerPackage)) eventWazeDirectSession("speed_limit", line);
        else eventGMapsDirectSession("speed_limit", line);
        Log.i(TAG, line);
        if (changed) republishLatestDirectFrame(ownerPackage, "speed-limit-event");
    }

    private DirectTbtFrame applySpeedLimitOverlay(
            String ownerPackage, DirectTbtFrame frame, long now) {
        if (frame == null) return null;
        String owner = normalizeString(ownerPackage);
        DirectTbtFrame.SpeedLimit speed = DirectSpeedLimitStore.snapshot(owner);
        DirectTbtPayload.Options options = DirectTbtPayload.Options.from(context);
        if (!owner.equals(speedOverlayOwner)) {
            resetSpeedOverlayState();
            speedOverlayOwner = owner;
        }
        boolean changed = speedOverlayDisplayValue != speed.getDisplayValue()
                || speedOverlayKph != speed.getKph()
                || !speedOverlayUnit.equals(speed.getUnit());
        speedOverlayDisplayValue = speed.getDisplayValue();
        speedOverlayKph = speed.getKph();
        speedOverlayUnit = speed.getUnit();
        if (!speed.isActive() || options.speedLimitMode == HudPrefs.SPEED_LIMIT_OFF) {
            cancelSpeedOverlayTimeout();
            speedOverlayVisible = false;
            speedOverlayPlacement = DirectTbtPayload.SPEED_PLACEMENT_NONE;
            speedOverlayOccupied = false;
            return frame.withSpeedLimit(DirectTbtFrame.SpeedLimit.inactive());
        }

        DirectTbtFrame candidate = frame.withSpeedLimit(speed);
        int placement = DirectTbtPayload.speedPlacement(candidate, options);
        boolean occupied = DirectTbtPayload.speedOverlaysOccupiedField(candidate, options);
        boolean transition = placement != speedOverlayPlacement
                || occupied != speedOverlayOccupied;
        speedOverlayPlacement = placement;
        speedOverlayOccupied = occupied;
        if (placement == DirectTbtPayload.SPEED_PLACEMENT_NONE) {
            cancelSpeedOverlayTimeout();
            speedOverlayVisible = false;
        } else if (!occupied) {
            cancelSpeedOverlayTimeout();
            speedOverlayVisible = true;
        } else if (changed || transition) {
            speedOverlayVisible = true;
            speedOverlayHideAtMs = now + options.speedLimitOverlaySeconds * 1000L;
            scheduleSpeedOverlayTimeout(now);
        } else if (speedOverlayHideAtMs > 0L && now >= speedOverlayHideAtMs) {
            cancelSpeedOverlayTimeout();
            speedOverlayVisible = false;
        }
        return frame.withSpeedLimit(speedOverlayVisible
                ? speed : DirectTbtFrame.SpeedLimit.inactive());
    }

    private void scheduleSpeedOverlayTimeout(long now) {
        handler.removeCallbacks(speedOverlayTimeout);
        handler.postDelayed(
                speedOverlayTimeout, Math.max(1L, speedOverlayHideAtMs - now));
    }

    private void cancelSpeedOverlayTimeout() {
        handler.removeCallbacks(speedOverlayTimeout);
        speedOverlayHideAtMs = 0L;
    }

    private void onSpeedOverlayTimeout() {
        long now = SystemClock.elapsedRealtime();
        if (speedOverlayHideAtMs <= 0L) return;
        if (now < speedOverlayHideAtMs) {
            scheduleSpeedOverlayTimeout(now);
            return;
        }
        speedOverlayHideAtMs = 0L;
        speedOverlayVisible = false;
        republishLatestDirectFrame(speedOverlayOwner, "speed-limit-timeout");
    }

    private void republishLatestDirectFrame(String ownerPackage, String reason) {
        if (WAZE_PACKAGE.equals(ownerPackage)) {
            DirectTbtFrame frame = wazeSurfaceActive
                    ? latestWazeSurfaceFrame : latestWazeClusterFrame;
            int generation = wazeSurfaceActive
                    ? latestWazeSurfaceFrameSessionGeneration
                    : latestWazeClusterFrameSessionGeneration;
            if (frame != null) {
                enqueueLatestWazeDirectFrame(
                        ownerPackage, generation,
                        wazeSurfaceActive ? withRetainedWazeClusterAlert(frame) : frame,
                        reason, wazeSurfaceActive);
            }
            return;
        }
        if (GMapsDirectChannel.PACKAGE_NAME.equals(ownerPackage)
                && latestGMapsDirectFrame != null) {
            onGMapsDirectFrame(
                    ownerPackage, latestGMapsDirectFrameSessionGeneration,
                    latestGMapsDirectFrame, reason, latestGMapsBitmapSelection);
        }
    }

    private void clearDirectSpeedLimit(String ownerPackage) {
        DirectSpeedLimitStore.clear(ownerPackage);
        if (normalizeString(ownerPackage).equals(speedOverlayOwner)) {
            resetSpeedOverlayState();
        }
    }

    private void resetSpeedOverlayState() {
        cancelSpeedOverlayTimeout();
        speedOverlayOwner = "";
        speedOverlayDisplayValue = 0;
        speedOverlayKph = 0;
        speedOverlayUnit = "";
        speedOverlayVisible = false;
        speedOverlayPlacement = DirectTbtPayload.SPEED_PLACEMENT_NONE;
        speedOverlayOccupied = false;
    }

    private void logGMapsDirectChannelEvent(String message) {
        String normalized = normalizeString(message);
        String line = "gmaps_direct " + normalized;
        eventGMapsDirectSession("channel", normalized);
        Log.i(TAG, line);
        AppEventLogger.event(context, "nav_live " + line);
    }

    private void onGMapsDirectNavigationEnded(String reason, long detectedAtMs) {
        if (!active || !GMapsDirectChannel.PACKAGE_NAME.equals(activePackage)) return;
        clearDirectSpeedLimit(GMapsDirectChannel.PACKAGE_NAME);
        boolean fallbackWasActive = gmapsDirectFallbackActive;
        cancelGMapsDirectTimeout();
        gmapsDirectFrameReceived = false;
        gmapsDirectFallbackActive = false;
        gmapsDirectRouteEnded = true;
        lastGMapsDirectRegistrationProbeMs = 0L;
        resetLatestPayload();
        NavRouteStateStore.get(context).markRouteEnded(
                GMapsDirectChannel.PACKAGE_NAME,
                "gmaps-direct-ended:" + safeReason(reason),
                detectedAtMs);
        if (fallbackWasActive) {
            hudOutput.selectNavigationSource(
                    HudOutputCoordinator.Source.NONE,
                    "gmaps-direct-ended-from-fallback:" + safeReason(reason));
        }
        log("gmaps direct navigation ended main_handoff_ms="
                + Math.max(0L, SystemClock.elapsedRealtime() - detectedAtMs)
                + " reason=" + safeReason(reason));
    }

    private void scheduleGMapsDirectTimeout() {
        handler.removeCallbacks(gmapsDirectTimeout);
        if (!active || !GMapsDirectChannel.PACKAGE_NAME.equals(activePackage)
                || gmapsDirectRouteEnded || gmapsDirectTimedOut) {
            gmapsDirectTimeoutScheduled = false;
            return;
        }
        gmapsDirectTimeoutScheduled = true;
        gmapsDirectTimeoutSessionGeneration = gmapsDirectChannel.isRunning()
                ? gmapsDirectChannel.sessionGeneration()
                : gmapsDirectChannel.sessionGeneration() + 1L;
        handler.postDelayed(gmapsDirectTimeout, GMAPS_DIRECT_TIMEOUT_MS);
    }

    private void cancelGMapsDirectTimeout() {
        handler.removeCallbacks(gmapsDirectTimeout);
        gmapsDirectTimeoutScheduled = false;
        gmapsDirectTimeoutSessionGeneration = 0L;
    }

    private void resetGMapsDirectSessionState() {
        cancelGMapsDirectTimeout();
        gmapsDirectFrameReceived = false;
        gmapsDirectFallbackActive = false;
        gmapsDirectTimedOut = false;
        gmapsLegacyUnavailableLogged = false;
        gmapsDirectRouteEnded = false;
        gmapsDirectRegistrationSuppressed = false;
        lastGMapsDirectRegistrationProbeMs = 0L;
        latestGMapsDirectFrame = null;
        latestGMapsDirectFrameReason = "";
        latestGMapsDirectFrameSessionGeneration = 0L;
        latestGMapsBitmapSelection = null;
    }

    private void activateGMapsLegacyFallbackIfReady(String reason) {
        boolean captureReady = NavRuntimePermissionStatus.check(context).readyForCapture();
        if (shouldDeactivateGMapsLegacyFallback(gmapsDirectFallbackActive, captureReady)) {
            gmapsDirectFallbackActive = false;
            resetLatestPayload();
            hudOutput.selectNavigationSource(
                    HudOutputCoordinator.Source.NONE,
                    "gmaps-fallback-unavailable:" + safeReason(reason));
            log("gmaps fallback stopped capture services unavailable reason="
                    + safeReason(reason));
        }
        if (!shouldActivateGMapsLegacyFallback(
                active,
                activePackage,
                gmapsDirectRouteEnded,
                gmapsDirectFallbackActive,
                captureReady)) {
            if (!gmapsDirectFallbackActive
                    && !captureReady
                    && !gmapsLegacyUnavailableLogged) {
                gmapsLegacyUnavailableLogged = true;
                log("gmaps fallback waiting capture services reason=" + safeReason(reason));
            }
            return;
        }
        gmapsLegacyUnavailableLogged = false;
        gmapsDirectFallbackActive = true;
        hudOutput.selectNavigationSource(
                HudOutputCoordinator.Source.LEGACY,
                "gmaps-fallback:" + safeReason(reason));
        hudOutput.ensureBound("gmaps-fallback:" + safeReason(reason));
        requestActiveInputState(GMapsDirectChannel.PACKAGE_NAME, reason);
        sendLatestIfReady("gmaps-fallback");
        log("gmaps source=legacy reason=" + safeReason(reason));
    }

    static boolean shouldActivateGMapsLegacyFallback(
            boolean active,
            String activePackage,
            boolean routeEnded,
            boolean fallbackActive,
            boolean captureReady) {
        return active
                && GMapsDirectChannel.PACKAGE_NAME.equals(normalizePackage(activePackage))
                && !routeEnded
                && !fallbackActive
                && captureReady;
    }

    static boolean shouldDeactivateGMapsLegacyFallback(
            boolean fallbackActive, boolean captureReady) {
        return fallbackActive && !captureReady;
    }

    static boolean shouldKeepGMapsFallbackOnDirectStart(boolean fallbackActive) {
        return fallbackActive;
    }

    private void eventGMapsDirectSession(String event, String detail) {
        DirectSessionLog session = gmapsDirectSession;
        if (session != null) session.event(event, normalizeString(detail));
    }

    private void ensureGMapsDirectSession(String reason) {
        if (gmapsDirectSession == null) {
            gmapsDirectSession = DirectSessionLog.start(
                    context, NavigationLogStorage.GMAPS_DIRECT_DIR, reason);
        }
    }

    private void endGMapsDirectSession(String reason) {
        DirectSessionLog session = gmapsDirectSession;
        gmapsDirectSession = null;
        if (session != null) session.end(safeReason(reason));
    }

    private boolean ensureGMapsRegisteredWhenTransportReady(String reason) {
        if (!hudOutput.isBound()) {
            if (!gmapsDirectRegistrationSuppressed) {
                gmapsDirectRegistrationSuppressed = true;
                log("gmaps direct registration suppressed transport=offline reason="
                        + safeReason(reason));
            }
            return false;
        }
        if (gmapsDirectRegistrationSuppressed) {
            log("gmaps direct registration resumed transport=online reason="
                    + safeReason(reason));
            gmapsDirectRegistrationSuppressed = false;
        }
        lastGMapsDirectRegistrationProbeMs = SystemClock.elapsedRealtime();
        gmapsDirectChannel.ensureRegistered(reason);
        return true;
    }

    private static String laneDirections(DirectTbtFrame frame) {
        if (frame == null || frame.getLanes().isEmpty()) return "";
        StringBuilder value = new StringBuilder();
        int count = Math.min(8, frame.getLanes().size());
        for (int index = 0; index < count; index++) {
            DirectTbtFrame.Lane lane = frame.getLanes().get(index);
            if (index > 0) value.append('|');
            String raw = normalizeString(lane.getRawDirections());
            if (raw.length() > 32) raw = raw.substring(0, 32);
            value.append(lane.getDirection())
                    .append(lane.isRecommended() ? '*' : '-')
                    .append(':').append(raw);
        }
        if (frame.getLanes().size() > count) value.append("|...");
        return value.toString();
    }

    private void onWazeDirectNavigationEnded(String ownerPackage, int sessionGeneration,
            String reason, long detectedAtMs) {
        if (!isCurrentWazeDirectCallback(ownerPackage, sessionGeneration)) {
            return;
        }
        boolean bridgeSupported = isWazeBridgeSupportedCached();
        WazeRouteLifecycleStore.RecordResult terminal = bridgeSupported
                ? WazeRouteLifecycleStore.recordLocalTerminal(context, detectedAtMs)
                : null;
        if (terminal != null && !terminal.accepted && terminal.snapshot.active) {
            log("waze route lifecycle stale terminal ignored reason=" + terminal.reason
                    + " detectedAtMs=" + detectedAtMs
                    + " storedAtMs=" + terminal.snapshot.eventElapsedMs);
            return;
        }
        if (bridgeSupported && wazeDirectRouteEnded && !wazeDirectChannel.isActive()) {
            log("waze direct terminal ignored; already applied reason=" + safeReason(reason));
            return;
        }
        log("waze direct navigation ended main_handoff_ms="
                + Math.max(0L, SystemClock.elapsedRealtime() - detectedAtMs)
                + " reason=" + safeReason(reason));
        clearDirectSpeedLimit(WAZE_PACKAGE);
        closeWazeSurface("navigation-ended:" + safeReason(reason));
        wazeSurfaceDirectChannel.stop("route-terminal:" + safeReason(reason));
        cancelWazeDirectColdTimeout();
        cancelWazeFallbackReadiness();
        wazeDirectNavigating = false;
        wazeDirectFrameReceived = false;
        wazeDirectRouteEnded = true;
        wazeRouteGeneration++;
        wazeFallbackActive = false;
        WazeCropCapture.get(context).stop("direct-navigation-ended");
        resetLatestPayload();
        long now = SystemClock.elapsedRealtime();
        NavRouteStateStore.get(context).markRouteEnded(
                WAZE_PACKAGE, "direct-navigation-ended", now);
        WazeRouteTracker.get(context).onRouteEnded("direct-navigation-ended", now);
        if (bridgeSupported) {
            wazeDirectChannel.stop("route-terminal:" + safeReason(reason));
            hudOutput.selectNavigationSource(
                    HudOutputCoordinator.Source.NONE,
                    "waze-route-terminal:" + safeReason(reason));
            if (!terminal.accepted && !terminal.snapshot.active) {
                log("waze route lifecycle terminal already recorded reason="
                        + terminal.reason);
            } else {
                log("waze route lifecycle terminal persisted=" + terminal.accepted
                        + " stateChanged=" + terminal.changed
                        + " reason=" + terminal.reason);
            }
        }
        log("waze source=waiting_direct routeEnded=true reason=" + safeReason(reason));
    }

    private void onWazeRouteLifecycleEventOnMain(boolean routeActive, boolean terminal,
            long eventElapsedMs, boolean changed, String reason) {
        if (!isWazeBridgeSupportedCached()) return;
        log("waze route lifecycle routeActive=" + routeActive
                + " terminal=" + terminal
                + " elapsedMs=" + eventElapsedMs
                + " reason=" + safeReason(reason));
        cancelWazeFallbackReadiness();
        wazeFallbackActive = false;
        WazeCropCapture.get(context).stop("route-lifecycle-bridge");
        if (terminal) {
            closeWazeSurface("route-lifecycle-end:" + safeReason(reason));
            wazeSurfaceDirectChannel.stop(
                    "route-lifecycle-end:" + safeReason(reason));
            if (!changed && !wazeDirectChannel.isActive()) {
                log("waze route lifecycle terminal ignored; state already inactive reason="
                        + safeReason(reason));
                return;
            }
            if (active && WAZE_PACKAGE.equals(activePackage)) {
                invalidatePendingWazeDirectFrames();
                int sessionGeneration = wazeDirectChannel.sessionGeneration();
                if (!isCurrentWazeDirectCallback(WAZE_PACKAGE, sessionGeneration)) {
                    return;
                }
                hudOutput.endDirectOutput(
                        WAZE_PACKAGE, sessionGeneration,
                        "waze-route-lifecycle-end", eventElapsedMs);
                endWazeDirectSession("route-lifecycle-end");
                onWazeDirectNavigationEnded(
                        WAZE_PACKAGE, sessionGeneration,
                        "route-lifecycle-end", eventElapsedMs);
            }
            return;
        }
        if (!routeActive) {
            log("waze route lifecycle nonterminal inactive reason=" + safeReason(reason));
            return;
        }
        if (HudPrefs.isUserShutdownActive(context)
                || !HudPrefs.isBootEnabled(context)
                || !NavCapturePrefs.isHudEnabled(context, WAZE_PACKAGE)) {
            return;
        }
        if (!active || !WAZE_PACKAGE.equals(activePackage)) {
            startOnMain(WAZE_PACKAGE, "route-lifecycle-start");
            return;
        }
        if (changed || !wazeDirectChannel.isActive()) {
            startWazeDirectForRoute("route-lifecycle-start", !changed);
        } else {
            wazeDirectChannel.start(
                    "route-lifecycle-ensure", WazeDirectChannel.Mode.CLUSTER);
        }
        if ((wazeSurfaceLaunchPending || wazeSurfaceActive)
                && !wazeSurfaceDirectChannel.isActive()) {
            wazeSurfaceDirectChannel.start(
                    "route-lifecycle-surface-ensure", WazeDirectChannel.Mode.MAIN_SURFACE);
        }
    }

    private void activateWazeLegacyFallback(String reason) {
        if (!active || !WAZE_PACKAGE.equals(activePackage)) {
            return;
        }
        if (isWazeBridgeSupportedCached()) {
            log("waze fallback suppressed for route-lifecycle bridge reason="
                    + safeReason(reason));
            return;
        }
        if (!HudPrefs.isWazeScreenCaptureEnabled(context)) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (wazeDirectRouteEnded || !WazeRouteTracker.get(context).isRouteActive(now)) {
            log("waze fallback waiting routeActive=false routeEnded="
                    + wazeDirectRouteEnded + " reason=" + safeReason(reason));
            return;
        }
        if (wazeFallbackActive) {
            ensureWazeCropRunning(reason);
            return;
        }
        cancelWazeDirectColdTimeout();
        wazeFallbackActive = true;
        hudOutput.selectNavigationSource(
                HudOutputCoordinator.Source.LEGACY,
                "waze-fallback:" + safeReason(reason));
        ensureWazeCropRunning(reason);
        requestActiveInputState(WAZE_PACKAGE, reason);
        sendLatestIfReady("waze-fallback");
        scheduleSendLoop();
        log("waze source=legacy reason=" + safeReason(reason));
    }

    private void resetWazeDirectSessionState() {
        invalidatePendingWazeDirectFrames();
        cancelWazeDirectColdTimeout();
        cancelWazeFallbackReadiness();
        wazeDirectHandshakeAvailable = false;
        wazeDirectNavigating = false;
        wazeDirectFrameReceived = false;
        wazeDirectRouteEnded = false;
        wazeRouteGeneration++;
        wazeFallbackActive = false;
        latestWazeClusterFrame = null;
        latestWazeClusterFrameReason = "";
        latestWazeClusterFrameSessionGeneration = 0;
        latestWazeSurfaceFrame = null;
        latestWazeSurfaceFrameReason = "";
        latestWazeSurfaceFrameSessionGeneration = 0;
    }

    private void eventWazeDirectSession(String event, String detail) {
        DirectSessionLog session = wazeDirectSession;
        if (session != null) session.event(event, normalizeString(detail));
    }

    private void ensureWazeDirectSession(String reason) {
        if (wazeDirectSession == null) {
            wazeDirectSession = DirectSessionLog.start(
                    context, NavigationLogStorage.WAZE_DIRECT_DIR, reason);
        }
    }

    private void endWazeDirectSession(String reason) {
        DirectSessionLog session = wazeDirectSession;
        wazeDirectSession = null;
        if (session != null) session.end(safeReason(reason));
    }

    private void scheduleWazeDirectColdTimeout(String reason) {
        if (!active
                || !WAZE_PACKAGE.equals(activePackage)
                || !HudPrefs.isWazeScreenCaptureEnabled(context)
                || wazeDirectFrameReceived
                || wazeFallbackActive
                || wazeDirectRouteEnded
                || wazeDirectProbeScheduled
                || !WazeRouteTracker.get(context).isRouteActive(
                SystemClock.elapsedRealtime())) {
            return;
        }
        wazeDirectProbeScheduled = true;
        wazeDirectProbeSessionGeneration = wazeDirectChannel.isActive()
                ? wazeDirectChannel.sessionGeneration()
                : wazeDirectChannel.sessionGeneration() + 1;
        handler.postDelayed(wazeDirectProbeTimeout, WAZE_DIRECT_TIMEOUT_MS);
        log("waze direct cold probe armed timeoutMs=" + WAZE_DIRECT_TIMEOUT_MS
                + " reason=" + safeReason(reason));
    }

    private boolean hasActiveWazeRoute() {
        return WazeRouteTracker.get(context).isRouteActive(SystemClock.elapsedRealtime());
    }

    private boolean isWazeScreenCaptureEnabled() {
        return HudPrefs.isWazeScreenCaptureEnabled(context);
    }

    private boolean isCurrentWazeCropCallback(int cropGeneration) {
        return wazeFallbackActive
                && WazeCropCapture.get(context).isCurrentGeneration(cropGeneration);
    }

    private void cancelWazeDirectColdTimeout() {
        handler.removeCallbacks(wazeDirectProbeTimeout);
        wazeDirectProbeScheduled = false;
        wazeDirectProbeSessionGeneration = 0;
    }

    private void onWazeLegacyRouteEvidence(String reason) {
        if (isWazeBridgeSupportedCached()) {
            cancelWazeFallbackReadiness();
            wazeFallbackActive = false;
            WazeCropCapture.get(context).stop("route-lifecycle-bridge");
            return;
        }
        if (!active || !WAZE_PACKAGE.equals(activePackage)
                || !WazeRouteTracker.get(context).isRouteActive(
                SystemClock.elapsedRealtime())) {
            return;
        }
        if (wazeDirectRouteEnded) {
            wazeDirectRouteEnded = false;
            log("waze route-end barrier cleared by legacy evidence reason="
                    + safeReason(reason));
        }
        if (wazeFallbackActive) {
            ensureWazeCropRunning(reason);
        } else {
            scheduleWazeDirectColdTimeout(reason);
        }
    }

    private boolean rejectStaleWazeEvidence(String packageName, int routeGeneration,
                                            String channel, boolean mayStartNewRoute) {
        if (!WAZE_PACKAGE.equals(packageName)) return false;
        if (routeGeneration != wazeRouteGeneration
                || (wazeDirectRouteEnded && !mayStartNewRoute)) {
            log("stale waze evidence ignored channel=" + channel
                    + " expectedGeneration=" + routeGeneration
                    + " currentGeneration=" + wazeRouteGeneration
                    + " routeEnded=" + wazeDirectRouteEnded);
            return true;
        }
        return false;
    }

    //starts or schedules work here so lifecycle recovery follows one controlled path.
    void start(String packageName, String reason) {
        final String normalized = normalizePackage(packageName);
        handler.post(() -> startOnMain(normalized, reason));
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    boolean isRunning() {
        return active;
    }

    //stops or releases work here so stale capture and HUD output cannot keep running silently.
    void stop(String packageName, String reason, boolean clearHud) {
        final String normalized = normalizePackage(packageName);
        handler.post(() -> {
            if (!normalized.isEmpty() && !normalized.equals(activePackage)) {
                return;
            }
            stopOnMain(reason, clearHud);
        });
    }

    //updates shared state here so freshness and lifecycle checks use the same evidence.
    void updateFromGMapsNotification(String packageName, String notificationKey,
            GMapsNotificationParser.Result result) {
        updateFromNavigationNotification(packageName, notificationKey, result);
    }

    //updates shared state here so freshness and lifecycle checks use the same evidence.
    void updateFromNavigationNotification(String packageName, String notificationKey,
            NavParserResult result) {
        if (result == null) {
            return;
        }
        final String normalized = normalizePackage(packageName);
        final String safeKey = normalizeString(notificationKey);
        final int routeGeneration = wazeRouteGeneration;
        handler.post(() -> updateOnMain(normalized, safeKey, result, routeGeneration));
    }

    //updates shared state here so freshness and lifecycle checks use the same evidence.
    void updateFromGMapsAccessibility(String packageName, String payload) {
        updateFromNavigationAccessibility(packageName, payload);
    }

    //updates shared state here so freshness and lifecycle checks use the same evidence.
    void updateFromNavigationAccessibility(String packageName, String payload) {
        final String normalized = normalizePackage(packageName);
        final String safePayload = normalizeString(payload);
        final int routeGeneration = wazeRouteGeneration;
        handler.post(() -> updateAccessibilityOnMain(
                normalized, safePayload, routeGeneration));
    }

    //updates shared state here so freshness and lifecycle checks use the same evidence.
    void updateWazeAccessibilityGeometry(String packageName, String payload) {
        final String normalized = normalizePackage(packageName);
        final String safePayload = normalizeString(payload);
        handler.post(() -> updateWazeAccessibilityGeometryOnMain(normalized, safePayload));
    }

    //updates shared state here so freshness and lifecycle checks use the same evidence.
    void updateFromWazeVisualCue(
            String packageName, NavParserResult result, int cropGeneration) {
        if (result == null) {
            return;
        }
        final String normalized = normalizePackage(packageName);
        final int routeGeneration = wazeRouteGeneration;
        handler.post(() -> {
            if (!isCurrentWazeCropCallback(cropGeneration)) return;
            updateVisualCueOnMain(normalized, result, routeGeneration);
        });
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    void onWazeVisualRouteEvidence(String reason, int cropGeneration) {
        final String safeReason = normalizeString(reason);
        final int routeGeneration = wazeRouteGeneration;
        handler.post(() -> {
            if (!isCurrentWazeCropCallback(cropGeneration)) return;
            if (routeGeneration != wazeRouteGeneration || wazeDirectRouteEnded) {
                log("stale waze visual route evidence ignored reason=" + safeReason);
                return;
            }
            long now = SystemClock.elapsedRealtime();
            NavRouteStateStore.get(context).updateFromVisualRouteEvidence(
                    WAZE_PACKAGE,
                    "waze_crop_visual",
                    safeReason,
                    now);
            WazeRouteTracker.get(context).onVisualRouteEvidence(safeReason, now);
            if (active && WAZE_PACKAGE.equals(activePackage)) {
                onWazeLegacyRouteEvidence(safeReason);
            }
            log("waze visual route evidence reason=" + safeReason);
        });
    }

    //tracks GMaps notification liveness even when its current payload cannot be parsed.
    void updateNavigationNotificationPresence(
            String packageName, String notificationKey, boolean ongoing, String category) {
        final String normalizedPackage = normalizePackage(packageName);
        final String normalizedKey = normalizeString(notificationKey);
        final boolean activeNavigation = ongoing
                && "navigation".equals(NavTextNormalizer.lower(category))
                && NavTextNormalizer.sourceApp(normalizedPackage)
                == NavSnapshot.SourceApp.GOOGLE_MAPS;
        handler.post(() -> {
            if (!activeNavigation) {
                if (normalizedPackage.equals(ongoingGMapsNotificationPackage)
                        && (normalizedKey.isEmpty()
                        || normalizedKey.equals(ongoingGMapsNotificationKey))) {
                    ongoingGMapsNotificationPackage = "";
                    ongoingGMapsNotificationKey = "";
                    lastGMapsNotificationReconcileMs = 0L;
                }
                return;
            }
            ongoingGMapsNotificationPackage = normalizedPackage;
            ongoingGMapsNotificationKey = normalizedKey;
            lastGMapsNotificationReconcileMs = SystemClock.elapsedRealtime();
            log("GMaps navigation notification active package=" + normalizedPackage
                    + " key=" + normalizedKey);
            scheduleRouteHealthLoop();
        });
    }

    //reconciles posted callbacks against NotificationListenerService's authoritative active set.
    void reconcileNavigationNotificationPresence(Set<String> activeTokens, String reason) {
        final Set<String> snapshot = activeTokens == null
                ? new HashSet<>()
                : new HashSet<>(activeTokens);
        final String safeReason = normalizeString(reason);
        handler.post(() -> {
            if (ongoingGMapsNotificationKey.isEmpty()) {
                return;
            }
            String token = notificationPresenceToken(
                    ongoingGMapsNotificationPackage, ongoingGMapsNotificationKey);
            if (snapshot.contains(token)) {
                return;
            }
            log("GMaps navigation notification reconciled missing package="
                    + ongoingGMapsNotificationPackage
                    + " key=" + ongoingGMapsNotificationKey
                    + " reason=" + safeReason);
            ongoingGMapsNotificationPackage = "";
            ongoingGMapsNotificationKey = "";
            lastGMapsNotificationReconcileMs = 0L;
            scheduleRouteHealthLoop();
        });
    }

    void clearNavigationNotificationPresence(String reason) {
        reconcileNavigationNotificationPresence(new HashSet<>(), reason);
    }

    //re-arms Waze crop after Android recreates MediaProjection so recovery does not wait for a user tap.
    void onWazeMediaProjectionReady(String reason) {
        final String safeReason = normalizeString(reason);
        handler.post(() -> {
            if (!active || !WAZE_PACKAGE.equals(activePackage)) {
                return;
            }
            ensureWazeCropRunning("mediaprojection-ready-" + safeReason);
            scheduleSendLoop();
            log("waze media projection ready crop rearm reason=" + safeReason);
        });
    }

    //starts or schedules work here so lifecycle recovery follows one controlled path.
    void onDashboardProjectionConfirmed(String packageName, NavAppDisplayState state) {
        final String normalized = normalizePackage(packageName);
        final boolean onDashboardDisplay = state != null && state.isOnDashboardDisplay();
        handler.post(() -> {
            if (!DashboardProjectionPolicy.shouldRestartWazeCropAfterDashboardProjection(
                    normalized,
                    activePackage,
                    onDashboardDisplay)) {
                return;
            }
            ensureWazeCropRunning("dashboard-projection-confirmed");
            sendLatestIfReady("dashboard-projection-confirmed");
            scheduleSendLoop();
            log("dashboard projection confirmed package=" + normalized
                    + " display=" + (state == null ? -1 : state.displayId)
                    + " crop restart requested");
        });
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    void onWazeCropUnavailable(String reason, int cropGeneration) {
        final String safeReason = normalizeString(reason);
        handler.post(() -> {
            if (!isCurrentWazeCropCallback(cropGeneration)) return;
            if (!active || !WAZE_PACKAGE.equals(activePackage)) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (!WazeVisualStatePolicy.shouldClearVisualWhenCropUnavailable(
                    true, safeReason, 0L)) {
                return;
            }
            handleWazeNoRouteOrVisualUnavailable(
                    "waze-crop-unavailable",
                    safeReason,
                    now);
        });
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    void onWazeRouteNodesMissing(String reason) {
        final String safeReason = normalizeString(reason);
        handler.post(() -> {
            long now = SystemClock.elapsedRealtime();
            lastWazeRouteNodeResultMs = now;
            lastWazeRouteNodeScanHadRoute = false;
            log("waze route-node missing reason=" + safeReason);
            if (!active || !WAZE_PACKAGE.equals(activePackage)) {
                return;
            }
            if (!wazeFallbackActive) {
                log("waze route-node missing ignored: direct Waze owns route lifecycle"
                        + " reason=" + safeReason);
                return;
            }
            if (shouldKeepWazeVisualOnly(now)) {
                NavRouteStateStore.get(context).updateFromVisualRouteEvidence(
                        WAZE_PACKAGE,
                        "waze_visual_only",
                        "route-nodes-missing:" + safeReason,
                        now);
                WazeRouteTracker.get(context).onVisualRouteEvidence(
                        "route-nodes-missing:" + safeReason, now);
                log("waze route-node missing ignored: fresh visual state ageMs="
                        + (now - lastVisualResultMs) + " reason=" + safeReason);
                ensureWazeCropRunning("route-nodes-missing-visual-only");
                sendLatestIfReady("waze-visual-only");
                scheduleSendLoop();
                scheduleRouteHealthLoop();
                return;
            }
            forceClearNavigator(WAZE_PACKAGE, "waze-route-nodes-missing", now);
        });
    }

    //renders this UI section here so screen structure stays traceable during preview and car testing.
    void onWazeUnknownLaneRow(String reason, int cropGeneration) {
        final String safeReason = normalizeString(reason);
        handler.post(() -> {
            if (!isCurrentWazeCropCallback(cropGeneration)) return;
            if (!active || !WAZE_PACKAGE.equals(activePackage)) {
                return;
            }
            boolean changed = false;
            if (latestVisualState != null) {
                latestVisualState =
                        WazeVisualStatePolicy.clearLanesForCurrentUnknownRow(latestVisualState);
                changed = true;
            }
            if (latestRouteState != null) {
                latestRouteState =
                        WazeVisualStatePolicy.clearLanesForCurrentUnknownRow(latestRouteState);
            }
            if (latestState != null) {
                latestState = WazeVisualStatePolicy.clearLanesForCurrentUnknownRow(latestState);
                changed = true;
            }
            if (!changed) {
                return;
            }
            latestReason = "waze unknown lane row " + safeReason;
            log("waze unknown lane row cleared lanes reason=" + safeReason);
            sendLatestIfReady("waze-unknown-lane-row");
            scheduleSendLoop();
        });
    }

    //stops or releases work here so stale capture and HUD output cannot keep running silently.
    void stopForRemovedGMapsNotification(String packageName, String notificationKey,
            String reason, boolean clearHud) {
        stopForRemovedNavigationNotification(packageName, notificationKey, reason, clearHud);
    }

    //stops or releases work here so stale capture and HUD output cannot keep running silently.
    void stopForRemovedNavigationNotification(String packageName, String notificationKey,
            String reason, boolean clearHud) {
        final String normalized = normalizePackage(packageName);
        final String safeKey = normalizeString(notificationKey);
        handler.post(() -> {
            if (normalized.equals(ongoingGMapsNotificationPackage)
                    && (safeKey.isEmpty() || safeKey.equals(ongoingGMapsNotificationKey))) {
                ongoingGMapsNotificationPackage = "";
                ongoingGMapsNotificationKey = "";
                lastGMapsNotificationReconcileMs = 0L;
                log("GMaps navigation notification removed package=" + normalized
                        + " key=" + safeKey);
            }
            if (!active || normalized.isEmpty() || !normalized.equals(activePackage)) {
                return;
            }
            if (safeKey.isEmpty() || !safeKey.equals(activeNotificationKey)) {
                if (!activeNotificationKey.isEmpty()) {
                    return;
                }
            }
            pendingRemovalKey = safeKey;
            pendingRemovalPackage = normalized;
            pendingRemovalActiveKey = activeNotificationKey;
            handler.removeCallbacks(notificationRemovedStop);
            handler.postDelayed(notificationRemovedStop, NOTIFICATION_REMOVED_STOP_DELAY_MS);
            log("notification removed pending stop reason=" + reason
                    + " delayMs=" + NOTIFICATION_REMOVED_STOP_DELAY_MS);
        });
    }

    //starts or schedules work here so lifecycle recovery follows one controlled path.
    private void startOnMain(String packageName, String reason) {
        if (packageName.isEmpty()) {
            return;
        }
        if (runtimeReinitInProgress) {
            pendingReinitStartPackage = packageName;
            pendingReinitStartReason = reason;
            log("start deferred during package reinit package=" + packageName
                    + " reason=" + safeReason(reason));
            return;
        }
        if (HudRuntimeUpgradeGuard.consumePendingReinit(
                context, "nav-start:" + packageName + ":" + safeReason(reason))) {
            resetRuntimeAfterPackageReplace(packageName, reason);
            return;
        }
        if (active && packageName.equals(activePackage)) {
            if (WAZE_PACKAGE.equals(packageName) && !wazeDirectChannel.isActive()) {
                boolean bridgeSupported = isWazeBridgeSupportedCached();
                if (shouldStartWazeDirectHost(
                        bridgeSupported, WazeRouteLifecycleStore.isRouteActive(context))) {
                    if (bridgeSupported) {
                        startWazeDirectForRoute(
                                "active-restart:" + safeReason(reason), true);
                    } else {
                        startWazeDirectProbe("active-restart:" + safeReason(reason));
                    }
                }
            }
            log("start ignored; already active package=" + packageName
                    + " reason=" + safeReason(reason));
            requestActiveInputState(packageName, reason);
            return;
        }
        String previousPackage = activePackage;
        if ("ui-start".equals(reason) || !packageName.equals(previousPackage)) {
            resetLatestPayload();
            HudDeliveryStatus.reset();
        }
        if (WAZE_PACKAGE.equals(previousPackage) && !WAZE_PACKAGE.equals(packageName)) {
            closeWazeSurface("source-switch:" + packageName);
            endWazeDirectSession("source-switch:" + packageName);
            resetWazeDirectSessionState();
            wazeDirectChannel.stop("source-switch:" + packageName);
            wazeSurfaceDirectChannel.stop("source-switch:" + packageName);
            WazeCropCapture.get(context).stop("source-switch:" + packageName);
        }
        if (GMapsDirectChannel.PACKAGE_NAME.equals(previousPackage)
                && !GMapsDirectChannel.PACKAGE_NAME.equals(packageName)) {
            endGMapsDirectSession("source-switch:" + packageName);
            resetGMapsDirectSessionState();
            gmapsDirectChannel.stop("source-switch:" + packageName);
        }
        cancelPendingRouteEndStops();
        active = true;
        activePackage = packageName;
        lastDashboardWatchdogMs = 0L;
        log("start package=" + packageName + " reason=" + reason);
        if (WAZE_PACKAGE.equals(packageName)) {
            boolean bridgeSupported = isWazeBridgeSupportedCached();
            if (shouldStartWazeDirectHost(
                    bridgeSupported, WazeRouteLifecycleStore.isRouteActive(context))) {
                if (bridgeSupported) startWazeDirectForRoute(reason);
                else startWazeDirectProbe(reason);
            } else {
                waitForWazeRouteLifecycle(reason);
            }
            requestActiveInputState(packageName, reason);
        } else if (GMapsDirectChannel.PACKAGE_NAME.equals(packageName)) {
            startGMapsDirectProbe(reason);
            requestActiveInputState(packageName, reason);
        } else {
            hudOutput.selectNavigationSource(
                    HudOutputCoordinator.Source.LEGACY,
                    "nav-start:" + packageName);
            hudOutput.ensureBound("nav-start:" + packageName);
            requestActiveInputState(packageName, reason);
            sendLatestIfReady("start");
        }
        scheduleSendLoop();
        scheduleRouteHealthLoop();
    }

    //updates shared state here so freshness and lifecycle checks use the same evidence.
    private void updateOnMain(String packageName, String notificationKey,
            NavParserResult result, int routeGeneration) {
        if (packageName.isEmpty()) {
            return;
        }
        if (rejectStaleWazeEvidence(
                packageName, routeGeneration, "notification", true)) return;
        if (!NavCapturePrefs.isCaptureEnabled(context, packageName)) {
            return;
        }
        boolean hudEnabled = NavCapturePrefs.isHudEnabled(context, packageName);
        NavCaptureStore.snapshot(context, result.snapshot);
        if (shouldStartWazeBeforeFreshRouteEvidence(
                hudEnabled, packageName, active, activePackage)) {
            startOnMain(packageName, "notification");
        }
        long evidenceNow = SystemClock.elapsedRealtime();
        NavRouteStateStore.get(context).updateFromSnapshot(
                result.snapshot, "notification", evidenceNow);
        WazeRouteTracker.get(context).updateFromSnapshot(
                result.snapshot, "notification", evidenceNow);
        if (!hudEnabled) {
            log("snapshot only package=" + packageName + " reason=" + result.reason);
            return;
        }
        if (!active || !packageName.equals(activePackage)) {
            startOnMain(packageName, "notification");
        }
        if (WAZE_PACKAGE.equals(packageName)) {
            onWazeLegacyRouteEvidence("notification");
            ensureWazeCropRunning("notification");
        }
        handler.removeCallbacks(notificationRemovedStop);
        pendingRemovalKey = "";
        pendingRemovalPackage = "";
        pendingRemovalActiveKey = "";
        cancelAccessibilityNoRouteStop();
        updateArrivalRouteEndForResult(packageName, result);
        activeNotificationKey = notificationKey;
        if (!NavSourcePriorityPolicy.shouldUseNotificationFallback(
                SystemClock.elapsedRealtime(),
                lastAccessibilityResultMs)) {
            log("notification fallback suppressed package=" + packageName
                    + " reason=" + result.reason);
            scheduleSendLoop();
            return;
        }
        if (!NavLiveSendPolicy.shouldSendLiveNavigation(result)) {
            log("payload suppressed package=" + packageName + " reason=" + result.reason);
            scheduleSendLoop();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        boolean blankGMapsTextOnlyStraightManeuver =
                shouldBlankGMapsNotificationTextOnlyStraightManeuver(packageName, result);
        if (blankGMapsTextOnlyStraightManeuver) {
            latestRouteState = blankGMapsNotificationTextOnlyStraightManeuver(result.state);
            latestRouteManeuver = NavSnapshot.Maneuver.UNKNOWN;
        } else {
            latestRouteState = result.state.copy();
            latestRouteManeuver = result.snapshot.maneuver;
        }
        latestRouteStateMs = now;
        if (WazeVisualStatePolicy.shouldPreserveWazeVisual(packageName, latestVisualState, result,
                now - lastVisualResultMs)) {
            latestState = WazeVisualStatePolicy.mergeRouteFieldsKeepingVisual(
                    latestVisualState, latestRouteState, latestRouteManeuver);
            latestReason = result.reason + " mergedWithVisual";
        } else {
            latestState = latestRouteState.copy();
            latestReason = blankGMapsTextOnlyStraightManeuver
                    ? result.reason + " blankTextOnlyStraightManeuver"
                    : result.reason;
        }
        log("payload parsed package=" + packageName
                + " dist=" + latestState.distanceToIntersection
                + " road=\"" + latestState.roadName + "\""
                + " carToDest=" + latestState.carToDestination
                + " timeToDest=" + latestState.timeToDestination);
        sendLatestIfReady("notification");
        scheduleSendLoop();
    }

    //updates shared state here so freshness and lifecycle checks use the same evidence.
    private void updateAccessibilityOnMain(String packageName, String payload,
                                           int routeGeneration) {
        if (packageName.isEmpty() || payload.isEmpty()) {
            return;
        }
        if (rejectStaleWazeEvidence(
                packageName, routeGeneration, "accessibility", true)) return;
        if (!NavCapturePrefs.isCaptureEnabled(context, packageName)) {
            return;
        }
        updateWazeAccessibilityGeometryOnMain(packageName, payload);
        HudState baseline = active && packageName.equals(activePackage) ? latestState : null;
        NavParserResult result =
                NavParserDispatcher.parseAccessibility(packageName, payload, baseline);
        if (result == null) {
            if (NavRouteEndPolicy.shouldScheduleNoRouteAccessibilityStop(
                    active, packageName, activePackage, payload)) {
                scheduleAccessibilityNoRouteStop(packageName, "accessibility-unparsed");
            }
            return;
        }
        if (WAZE_PACKAGE.equals(packageName) && payload.contains("waze_nodes=true")) {
            lastWazeRouteNodeResultMs = SystemClock.elapsedRealtime();
            lastWazeRouteNodeScanHadRoute = true;
            log("waze route-node state ok reason=" + result.reason);
        }
        boolean hudEnabled = NavCapturePrefs.isHudEnabled(context, packageName);
        NavCaptureStore.snapshot(context, result.snapshot);
        if (shouldStartWazeBeforeFreshRouteEvidence(
                hudEnabled, packageName, active, activePackage)) {
            startOnMain(packageName, "accessibility");
        }
        long evidenceNow = SystemClock.elapsedRealtime();
        NavRouteStateStore.get(context).updateFromSnapshot(
                result.snapshot, "accessibility", evidenceNow);
        WazeRouteTracker.get(context).updateFromSnapshot(
                result.snapshot, "accessibility", evidenceNow);
        if (!hudEnabled) {
            log("accessibility snapshot only package=" + packageName
                    + " reason=" + result.reason);
            return;
        }
        if (!active || !packageName.equals(activePackage)) {
            startOnMain(packageName, "accessibility");
        }
        if (WAZE_PACKAGE.equals(packageName)) {
            onWazeLegacyRouteEvidence("accessibility");
            ensureWazeCropRunning("accessibility");
        }
        handler.removeCallbacks(notificationRemovedStop);
        pendingRemovalKey = "";
        pendingRemovalPackage = "";
        pendingRemovalActiveKey = "";
        updateArrivalRouteEndForResult(packageName, result);
        if (!NavLiveSendPolicy.shouldSendLiveNavigation(result)) {
            log("accessibility suppressed package=" + packageName + " reason=" + result.reason);
            if (NavRouteEndPolicy.shouldScheduleNoRouteAccessibilityStop(
                    active, packageName, activePackage, payload)) {
                scheduleAccessibilityNoRouteStop(packageName, "accessibility-suppressed");
            }
            scheduleSendLoop();
            return;
        }
        cancelAccessibilityNoRouteStop();
        long now = SystemClock.elapsedRealtime();
        lastAccessibilityResultMs = now;
        latestRouteState = result.state.copy();
        latestRouteManeuver = result.snapshot.maneuver;
        latestRouteStateMs = now;
        if (WazeVisualStatePolicy.shouldPreserveWazeVisual(packageName, latestVisualState, result,
                now - lastVisualResultMs)) {
            latestState = WazeVisualStatePolicy.mergeRouteFieldsKeepingVisual(
                    latestVisualState, latestRouteState, latestRouteManeuver);
            latestReason = result.reason + " mergedWithVisual";
        } else {
            latestState = latestRouteState.copy();
            latestReason = result.reason;
        }
        log("accessibility parsed package=" + packageName
                + " dist=" + latestState.distanceToIntersection
                + " road=\"" + latestState.roadName + "\""
                + " carToDest=" + latestState.carToDestination
                + " timeToDest=" + latestState.timeToDestination);
        sendLatestIfReady("accessibility");
        scheduleSendLoop();
    }

    //updates shared state here so freshness and lifecycle checks use the same evidence.
    private void updateVisualCueOnMain(String packageName, NavParserResult result,
                                       int routeGeneration) {
        if (packageName.isEmpty()) {
            return;
        }
        if (rejectStaleWazeEvidence(packageName, routeGeneration, "visual", false)) return;
        if (!NavCapturePrefs.isCaptureEnabled(context, packageName)) {
            return;
        }
        boolean hudEnabled = NavCapturePrefs.isHudEnabled(context, packageName);
        NavCaptureStore.snapshot(context, result.snapshot);
        NavRouteStateStore.get(context).updateFromSnapshot(
                result.snapshot, "visual", SystemClock.elapsedRealtime());
        WazeRouteTracker.get(context).updateFromSnapshot(
                result.snapshot, "visual", SystemClock.elapsedRealtime());
        if (!hudEnabled) {
            log("visual snapshot only package=" + packageName + " reason=" + result.reason);
            return;
        }
        if (!active || !packageName.equals(activePackage)) {
            startOnMain(packageName, "visual");
        }
        if (WAZE_PACKAGE.equals(packageName)) {
            onWazeLegacyRouteEvidence("visual");
            ensureWazeCropRunning("visual");
        }
        handler.removeCallbacks(notificationRemovedStop);
        pendingRemovalKey = "";
        pendingRemovalPackage = "";
        pendingRemovalActiveKey = "";
        cancelAccessibilityNoRouteStop();
        updateArrivalRouteEndForResult(packageName, result);
        if (!NavLiveSendPolicy.shouldSendLiveNavigation(result)) {
            log("visual suppressed package=" + packageName + " reason=" + result.reason);
            scheduleSendLoop();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        boolean virtualWazeVisual = WAZE_PACKAGE.equals(packageName) && result.sourceDisplayId > 0;
        lastVisualResultMs = now;
        latestVisualSourceDisplayId = result.sourceDisplayId;
        latestVisualState = result.state.copy();
        sanitizeWazeVisualLanes(packageName, latestVisualState);
        if (virtualWazeVisual) {
            latestVisualState =
                    WazeVisualStatePolicy.staleRouteFieldsClearedForVisual(latestVisualState);
            latestState = latestVisualState.copy();
            latestReason = result.reason + " virtualDisplayRouteFieldsCleared";
            log("virtualDisplayRouteFieldsCleared sourceDisplay=" + result.sourceDisplayId);
        } else if (WAZE_PACKAGE.equals(packageName) && freshWazeRouteState(now)) {
            latestState = WazeVisualStatePolicy.mergeRouteFieldsKeepingVisual(
                    latestVisualState, latestRouteState, latestRouteManeuver);
            latestReason = result.reason + " mergedWithRoute";
        } else if (WAZE_PACKAGE.equals(packageName) && latestRouteState != null) {
            if (!virtualWazeVisual && shouldKeepExpiredWazeRouteFields(now)) {
                latestVisualState = WazeVisualStatePolicy.routeFieldsKeptForVisual(
                        latestVisualState, latestRouteState, latestRouteManeuver);
                latestState = latestVisualState.copy();
                latestReason = result.reason + " routeFieldsKeptByRouteEvidence";
                log("waze route fields kept by route evidence reason=" + result.reason);
            } else {
                latestVisualState =
                        WazeVisualStatePolicy.staleRouteFieldsClearedForVisual(latestVisualState);
                latestState = latestVisualState.copy();
                latestReason = result.reason + " routeFieldsExpired";
            }
        } else {
            latestState = latestVisualState.copy();
            latestReason = result.reason;
        }
        log("visual parsed package=" + packageName
                + " dist=" + latestState.distanceToIntersection
                + " road=\"" + latestState.roadName + "\""
                + " lanes=" + latestState.numOfLanes
                + " sourceDisplay=" + result.sourceDisplayId
                + " maneuver=" + result.snapshot.maneuver);
        sendLatestIfReady("visual");
        scheduleSendLoop();
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    private void sanitizeWazeVisualLanes(String packageName, HudState state) {
        if (!"com.waze".equals(packageName) || state == null) {
            return;
        }
        String lanes = state.laneString == null ? "" : state.laneString.trim();
        if (!WazeVisualCueParser.isKnownMultiLaneSignature(lanes)) {
            state.laneString = "";
            state.numOfLanes = 0;
            state.includeLaneBitmap = false;
            return;
        }
        int laneCount = WazeLaneParser.laneCountFromSignature(lanes);
        state.laneString = lanes;
        state.numOfLanes = laneCount;
        state.includeLaneBitmap = laneCount > 1;
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    private void requestActiveInputState(String packageName, String reason) {
        NavRuntimePermissionStatus permissionStatus = NavRuntimePermissionStatus.check(context);
        if (!permissionStatus.notificationListenerConnected) {
            log("active input notification unavailable package=" + packageName
                    + " reason=" + reason + " status=" + permissionStatus.summary());
        } else {
            NavNotificationListenerService.requestActiveNotificationScan(
                    context,
                    "start-hud-" + packageName + "-" + reason);
        }
        if (!permissionStatus.accessibilityServiceConnected
                || permissionStatus.accessibilityServiceCrashed) {
            log("active input accessibility unavailable package=" + packageName
                    + " reason=" + reason + " status=" + permissionStatus.summary());
        } else {
            NavAccessibilityService.requestActiveWindowCapture(
                    context,
                    packageName,
                    "start-hud-" + reason);
        }
    }

    //sends encoded data here so transport side effects stay behind a single boundary.
    private void sendLatestIfReady(String reason) {
        if (!active || latestState == null) {
            return;
        }
        clearExpiredWazeRouteFieldsForSend(SystemClock.elapsedRealtime());
        hudOutput.publishLegacy(latestState, reason + ":" + latestReason);
    }

    //stops or releases work here so stale capture and HUD output cannot keep running silently.
    private void stopOnMain(String reason, boolean clearHud) {
        String packageName = activePackage;
        clearRouteStoreForStop(packageName, reason);
        handler.removeCallbacks(sendLoop);
        handler.removeCallbacks(routeHealthLoop);
        handler.removeCallbacks(notificationRemovedStop);
        handler.removeCallbacks(accessibilityNoRouteStop);
        handler.removeCallbacks(arrivalRouteEndStop);
        if (runtimeReinitInProgress) {
            runtimeReinitInProgress = false;
            pendingReinitStartPackage = "";
            pendingReinitStartReason = "";
            log("package reinit cancelled reason=" + reason);
        }
        sendLoopScheduled = false;
        routeHealthScheduled = false;
        active = false;
        if (WAZE_PACKAGE.equals(packageName)) {
            closeWazeSurface("stop:" + safeReason(reason));
            endWazeDirectSession(reason);
            resetWazeDirectSessionState();
            wazeDirectChannel.stop(reason);
            wazeSurfaceDirectChannel.stop(reason);
        }
        if (GMapsDirectChannel.PACKAGE_NAME.equals(packageName)) {
            endGMapsDirectSession(reason);
            resetGMapsDirectSessionState();
            gmapsDirectChannel.stop(reason);
        }
        resetLatestPayload();
        hudOutput.selectNavigationSource(
                HudOutputCoordinator.Source.NONE,
                reason + (clearHud ? ":clear" : ""));
        if (WAZE_PACKAGE.equals(packageName) && shouldStopWazeCrop(reason)) {
            WazeCropCapture.get(context).stop(reason);
        }
        log("stopped reason=" + reason);
    }

    //clears stale Waze route memory only after the real route-stale stop decision fires.
    private void clearRouteStoreForStop(String packageName, String reason) {
        String normalizedReason = normalizeString(reason);
        if (!"route-stale".equals(NavTextNormalizer.lower(normalizedReason))) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        NavRouteStateStore.get(context).clearRoute(packageName, normalizedReason, now);
        if (WAZE_PACKAGE.equals(packageName)) {
            WazeRouteTracker.get(context).onRouteEnded(normalizedReason, now);
        }
        log("route_store_clear package=" + packageName + " reason=" + normalizedReason);
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    private void forceClearNavigator(String packageName, String reason, long now) {
        NavRouteStateStore.get(context).markRouteEnded(packageName, reason, now);
        if (WAZE_PACKAGE.equals(packageName)) {
            WazeRouteTracker.get(context).onRouteEnded(reason, now);
            WazeCropCapture.get(context).stop(reason);
        }
        log("route_clear package=" + packageName + " reason=" + reason);
        stopOnMain(reason, true);
    }

    //handles this branch here so source-specific edge cases stay out of the main flow.
    private void handleWazeNoRouteOrVisualUnavailable(
            String clearReason, String visualReason, long now) {
        latestVisualState = null;
        latestVisualSourceDisplayId = 0;
        lastVisualResultMs = 0L;
        if (hasCurrentWazeRouteNodeState(now)) {
            HudState routeSource = latestRouteState != null ? latestRouteState : latestState;
            HudState routeOnly = WazeVisualStatePolicy.routeOnlyWithoutVisual(routeSource);
            if (routeOnly != null) {
                latestState = routeOnly;
                latestReason = "waze route-only " + visualReason;
                log("waze visual cleared route-only reason=" + visualReason
                        + " route=" + NavRouteStateStore.get(context).reason(WAZE_PACKAGE));
                sendLatestIfReady("waze-route-only");
                scheduleSendLoop();
                return;
            }
        }
        forceClearNavigator(WAZE_PACKAGE, clearReason + ":" + visualReason, now);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private boolean hasCurrentWazeRouteNodeState(long now) {
        if (!lastWazeRouteNodeScanHadRoute
                || lastWazeRouteNodeResultMs <= 0L
                || now - lastWazeRouteNodeResultMs > WAZE_ROUTE_NODE_FRESH_MS
                || latestRouteState == null) {
            return false;
        }
        NavRouteStateStore routeStore = NavRouteStateStore.get(context);
        NavSnapshot latestSnapshot = routeStore.latestSnapshot(WAZE_PACKAGE);
        return latestSnapshot == null
                || (latestSnapshot.maneuver != NavSnapshot.Maneuver.ARRIVE
                && latestSnapshot.maneuver != NavSnapshot.Maneuver.HIDE);
    }

    //guards visual payloads from carrying old Waze route text after accessibility disappears.
    private boolean freshWazeRouteState(long now) {
        return latestRouteState != null
                && latestRouteStateMs > 0L
                && now - latestRouteStateMs <= WAZE_ROUTE_FIELD_TTL_MS;
    }

    //guards virtual-display visual payloads so stale frames cannot override fresh route text.
    private boolean freshWazeVisualState(long now) {
        return latestVisualState != null
                && lastVisualResultMs > 0L
                && now - lastVisualResultMs <= WAZE_VISUAL_FRESH_MS;
    }

    //keeps route text while bounded route evidence proves Waze is still actively navigating.
    boolean shouldKeepExpiredWazeRouteFields(long now) {
        return WAZE_PACKAGE.equals(activePackage)
                && latestRouteState != null
                && NavRouteStateStore.get(context).hasFreshWazeRouteEvidence(now);
    }

    //keeps dashboard Waze alive when route text disappears but PixelCopy still provides fresh visual HUD data.
    private boolean shouldKeepWazeVisualOnly(long now) {
        if (!WAZE_PACKAGE.equals(activePackage)) {
            return false;
        }
        if (latestVisualState != null
                && lastVisualResultMs > 0L
                && now - lastVisualResultMs <= WAZE_VISUAL_FRESH_MS) {
            return true;
        }
        return WAZE_PACKAGE.equals(NavAppDisplayController.get(context).persistedDashboardPackage());
    }

    //repairs lost app-owned dashboard projection without adding another service or a tight polling loop.
    private void maybeRepairDashboardProjection(long now, String reason) {
        if (!active || !WAZE_PACKAGE.equals(activePackage)) {
            return;
        }
        if (now - lastDashboardWatchdogMs < DASHBOARD_WATCHDOG_INTERVAL_MS) {
            return;
        }
        lastDashboardWatchdogMs = now;
        String packageName = NavAppDisplayController.get(context).persistedDashboardPackage();
        if (!WAZE_PACKAGE.equals(packageName)) {
            return;
        }
        if (ClusterProjectionService.isProjectedPackageCurrent(packageName)) {
            return;
        }
        log("dashboard watchdog restore package=" + packageName
                + " reason=" + safeReason(reason));
        ClusterProjectionService.startProjection(
                context,
                packageName,
                "watchdog:" + safeReason(reason));
    }

    //clears route text before send so looped HUD frames cannot keep stale distance or street.
    private void clearExpiredWazeRouteFieldsForSend(long now) {
        if (!WAZE_PACKAGE.equals(activePackage)
                || latestState == null
                || (latestVisualSourceDisplayId <= 0 && freshWazeRouteState(now))) {
            return;
        }
        if (latestVisualSourceDisplayId > 0 && latestVisualState != null) {
            if (freshWazeVisualState(now)) {
                latestVisualState =
                        WazeVisualStatePolicy.staleRouteFieldsClearedForVisual(latestVisualState);
                latestState = latestVisualState.copy();
                if (!latestReason.contains("virtualDisplayRouteFieldsCleared")) {
                    latestReason = latestReason + " virtualDisplayRouteFieldsCleared";
                    log("virtualDisplayRouteFieldsCleared sourceDisplay=" + latestVisualSourceDisplayId
                            + " reason=send-boundary");
                }
                return;
            }
            log("virtualDisplayVisualExpired sourceDisplay=" + latestVisualSourceDisplayId
                    + " ageMs=" + (lastVisualResultMs > 0L ? (now - lastVisualResultMs) : -1L));
            latestVisualSourceDisplayId = 0;
            if (freshWazeRouteState(now)) {
                latestState = latestRouteState.copy();
                if (!latestReason.contains("visualExpiredRouteRestored")) {
                    latestReason = latestReason + " visualExpiredRouteRestored";
                }
                return;
            }
        }
        if (latestRouteState == null) {
            return;
        }
        if (shouldKeepExpiredWazeRouteFields(now)) {
            HudState keptState = WazeVisualStatePolicy.routeFieldsKeptForVisual(
                    latestVisualState, latestRouteState, latestRouteManeuver);
            if (latestVisualState != null) {
                latestVisualState = keptState;
            }
            latestState = keptState.copy();
            if (!latestReason.contains("routeFieldsKeptByRouteEvidence")) {
                latestReason = latestReason + " routeFieldsKeptByRouteEvidence";
                log("waze route fields kept by route evidence reason=send-boundary");
            }
            return;
        }
        if (latestVisualState != null) {
            latestVisualState =
                    WazeVisualStatePolicy.staleRouteFieldsClearedForVisual(latestVisualState);
            latestState = latestVisualState.copy();
        } else {
            latestState = WazeVisualStatePolicy.staleRouteFieldsClearedForVisual(latestState);
        }
        if (!latestReason.contains("routeFieldsExpired")) {
            latestReason = latestReason + " routeFieldsExpired";
        }
    }

    //updates shared state here so freshness and lifecycle checks use the same evidence.
    private void updateWazeAccessibilityGeometryOnMain(String packageName, String payload) {
        if (!WAZE_PACKAGE.equals(packageName)) {
            return;
        }
        WazeAccessibilityGeometry geometry = WazeAccessibilityParser.geometry(payload);
        if (geometry.hasAnyBounds()) {
            WazeCropCapture.get(context).updateAccessibilityGeometry(geometry);
            log("waze accessibility geometry " + geometry.summary());
        }
    }

    //starts or schedules work here so lifecycle recovery follows one controlled path.
    private void ensureWazeCropRunning(String reason) {
        if (!active || !WAZE_PACKAGE.equals(activePackage)) {
            return;
        }
        if (isWazeBridgeSupportedCached()) {
            cancelWazeFallbackReadiness();
            wazeFallbackActive = false;
            WazeCropCapture.get(context).stop("route-lifecycle-bridge");
            return;
        }
        if (!HudPrefs.isWazeScreenCaptureEnabled(context)) {
            return;
        }
        if (!wazeFallbackActive) {
            return;
        }
        if (!NavCapturePrefs.isHudEnabled(context, WAZE_PACKAGE)) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (wazeDirectRouteEnded || !WazeRouteTracker.get(context).isRouteActive(now)) {
            log("waze crop waiting routeActive=false routeEnded="
                    + wazeDirectRouteEnded + " reason=" + safeReason(reason));
            return;
        }
        if (WazeCropCapture.get(context).isRunning()) return;
        if (wazeFallbackReadinessCheckInFlight
                || now - lastWazeFallbackReadinessCheckMs < ROUTE_HEALTH_INTERVAL_MS) {
            return;
        }
        wazeFallbackReadinessCheckInFlight = true;
        lastWazeFallbackReadinessCheckMs = now;
        int expectedGeneration = wazeFallbackReadinessGeneration;
        String safeReason = safeReason(reason);
        Thread worker = new Thread(() -> {
            NavAppDisplayController controller = NavAppDisplayController.get(context);
            NavAppDisplayState state = controller.checkDisplay(
                    WAZE_PACKAGE, "waze-fallback-readiness");
            String activeDashboard = controller.activeDashboardPackage();
            boolean projected = ClusterProjectionService.isProjectedPackageCurrent(WAZE_PACKAGE);
            boolean usable = WazeCropCapture.isUsableWazeCropState(
                    state, activeDashboard, projected);
            handler.post(() -> finishWazeFallbackReadiness(
                    expectedGeneration, safeReason, state, usable));
        }, "BydHudWazeFallbackReadiness");
        worker.start();
    }

    private void finishWazeFallbackReadiness(int expectedGeneration, String reason,
                                             NavAppDisplayState state, boolean usable) {
        if (expectedGeneration != wazeFallbackReadinessGeneration) return;
        wazeFallbackReadinessCheckInFlight = false;
        if (isWazeBridgeSupportedCached()) {
            wazeFallbackActive = false;
            WazeCropCapture.get(context).stop("route-lifecycle-bridge");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (!active
                || !WAZE_PACKAGE.equals(activePackage)
                || !wazeFallbackActive
                || wazeDirectRouteEnded
                || !HudPrefs.isWazeScreenCaptureEnabled(context)
                || !NavCapturePrefs.isHudEnabled(context, WAZE_PACKAGE)
                || !WazeRouteTracker.get(context).isRouteActive(now)) {
            return;
        }
        if (!usable) {
            log("waze crop waiting task/display task=" + (state == null ? -1 : state.taskId)
                    + " display=" + (state == null ? -1 : state.displayId)
                    + " visible=" + (state != null && state.visible)
                    + " reason=" + reason);
            return;
        }
        WazeMediaProjectionController.ensureReadyOrPrompt(context, "crop-" + reason);
        WazeCropCapture.get(context).start("runtime-" + reason);
    }

    private void cancelWazeFallbackReadiness() {
        wazeFallbackReadinessGeneration++;
        wazeFallbackReadinessCheckInFlight = false;
        lastWazeFallbackReadinessCheckMs = 0L;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private boolean shouldStopWazeCrop(String reason) {
        if (!NavCapturePrefs.isHudEnabled(context, WAZE_PACKAGE)) {
            log("waze-clear decision=stop-crop reason=" + normalizeString(reason)
                    + " hudEnabled=false");
            return true;
        }
        String safe = NavTextNormalizer.lower(reason);
        if ("route-stale".equals(safe)
                || "accessibility-route-ended".equals(safe)
                || "arrival-route-ended".equals(safe)) {
            log("waze-clear decision=stop-crop reason=" + safe + " routeEnded=true");
            return true;
        }
        log("waze-clear decision=stop-crop reason=" + safe + " default=true");
        return true;
    }

    //clears state here so stale navigation output is removed before new evidence arrives.
    private void resetLatestPayload() {
        latestState = null;
        latestRouteState = null;
        latestVisualState = null;
        latestRouteManeuver = NavSnapshot.Maneuver.UNKNOWN;
        latestReason = "";
        latestVisualSourceDisplayId = 0;
        activeNotificationKey = "";
        pendingRemovalKey = "";
        pendingRemovalPackage = "";
        pendingRemovalActiveKey = "";
        pendingNoRoutePackage = "";
        pendingArrivalPackage = "";
        pendingNoRouteScheduledAtMs = 0L;
        pendingArrivalScheduledAtMs = 0L;
        lastAccessibilityResultMs = 0L;
        lastVisualResultMs = 0L;
        lastWazeRouteNodeResultMs = 0L;
        latestRouteStateMs = 0L;
        lastWazeRouteNodeScanHadRoute = false;
    }

    private boolean hasOngoingGMapsNavigationNotification() {
        return NavTextNormalizer.sourceApp(activePackage) == NavSnapshot.SourceApp.GOOGLE_MAPS
                && activePackage.equals(ongoingGMapsNotificationPackage)
                && !ongoingGMapsNotificationKey.isEmpty();
    }

    static String notificationPresenceToken(String packageName, String notificationKey) {
        return normalizePackage(packageName) + "\n" + normalizeString(notificationKey);
    }

    static boolean shouldStartWazeBeforeFreshRouteEvidence(
            boolean hudEnabled, String packageName, boolean active, String activePackage) {
        return hudEnabled
                && WAZE_PACKAGE.equals(normalizePackage(packageName))
                && (!active || !WAZE_PACKAGE.equals(normalizePackage(activePackage)));
    }

    //resets stale post-update state before the first new navigation session binds SOME/IP again.
    private void resetRuntimeAfterPackageReplace(String packageName, String reason) {
        handler.removeCallbacks(sendLoop);
        handler.removeCallbacks(routeHealthLoop);
        handler.removeCallbacks(notificationRemovedStop);
        handler.removeCallbacks(accessibilityNoRouteStop);
        handler.removeCallbacks(arrivalRouteEndStop);
        runtimeReinitInProgress = true;
        pendingReinitStartPackage = packageName;
        pendingReinitStartReason = reason;
        sendLoopScheduled = false;
        routeHealthScheduled = false;
        active = false;
        activePackage = "";
        closeWazeSurface("package-replaced-reinit");
        endWazeDirectSession("package-replaced-reinit");
        resetWazeDirectSessionState();
        wazeDirectChannel.hardStop("package-replaced-reinit");
        wazeSurfaceDirectChannel.hardStop("package-replaced-reinit");
        endGMapsDirectSession("package-replaced-reinit");
        resetGMapsDirectSessionState();
        gmapsDirectChannel.stop("package-replaced-reinit");
        WazeCropCapture.get(context).stop("package-replaced-reinit");
        WazeMediaProjectionController.resetForRuntimeReinit(
                context, "nav-start:" + packageName + ":" + safeReason(reason));
        resetLatestPayload();
        firstNavAfterPackageReplaceAwaitingSomeIp = true;
        firstNavAfterPackageReplaceConnectStartMs = SystemClock.elapsedRealtime();
        log("firstNavStartAfterPackageReplace package=" + packageName
                + " reason=" + safeReason(reason)
                + " reset=runtime,capture,someip");
        NavRuntimePermissionRepair.checkAndRepairAsync(
                context,
                "first-nav-after-package-replace",
                true,
                LocalAdbBridge.AuthorizationPromptMode.NEVER);
        hudOutput.selectNavigationSource(
                HudOutputCoordinator.Source.NONE,
                "package-replaced-reinit");
        hudOutput.resetTransport("package-replaced-reinit");
        finishPackageReinitAndRestart();
    }

    //updates shared state here so freshness and lifecycle checks use the same evidence.
    private void updateArrivalRouteEndForResult(String packageName, NavParserResult result) {
        long now = SystemClock.elapsedRealtime();
        boolean hasFreshVisualCue = WAZE_PACKAGE.equals(packageName)
                && lastVisualResultMs > 0L
                && now - lastVisualResultMs <= WAZE_VISUAL_FRESH_MS;
        boolean hasFreshRouteText = WAZE_PACKAGE.equals(packageName)
                && hasCurrentWazeRouteNodeState(now);
        if (NavRouteEndPolicy.shouldScheduleArrivalStop(
                active,
                packageName,
                activePackage,
                result,
                hasFreshVisualCue,
                hasFreshRouteText,
                false,
                ARRIVAL_ROUTE_END_STOP_DELAY_MS)) {
            scheduleArrivalRouteEndStop(packageName, result.reason);
            return;
        }
        if (active
                && packageName.equals(activePackage)
                && NavRouteEndPolicy.hasActiveRouteSnapshot(result)) {
            cancelArrivalRouteEndStop();
        }
    }

    //starts or schedules work here so lifecycle recovery follows one controlled path.
    private void scheduleArrivalRouteEndStop(String packageName, String reason) {
        long now = SystemClock.elapsedRealtime();
        if (packageName.equals(pendingArrivalPackage) && pendingArrivalScheduledAtMs > 0L) {
            long remainingMs = Math.max(0L,
                    ARRIVAL_ROUTE_END_STOP_DELAY_MS - (now - pendingArrivalScheduledAtMs));
            log("arrival route-end pending already scheduled package=" + packageName
                    + " reason=" + reason
                    + " remainingMs=" + remainingMs);
            return;
        }
        pendingArrivalPackage = packageName;
        pendingArrivalScheduledAtMs = now;
        handler.removeCallbacks(arrivalRouteEndStop);
        handler.postDelayed(arrivalRouteEndStop, ARRIVAL_ROUTE_END_STOP_DELAY_MS);
        log("arrival route-end pending stop package=" + packageName
                + " reason=" + reason
                + " delayMs=" + ARRIVAL_ROUTE_END_STOP_DELAY_MS);
    }

    //stops or releases work here so stale capture and HUD output cannot keep running silently.
    private void cancelArrivalRouteEndStop() {
        handler.removeCallbacks(arrivalRouteEndStop);
        pendingArrivalPackage = "";
        pendingArrivalScheduledAtMs = 0L;
    }

    //starts or schedules work here so lifecycle recovery follows one controlled path.
    private void scheduleAccessibilityNoRouteStop(String packageName, String reason) {
        long now = SystemClock.elapsedRealtime();
        if (packageName.equals(pendingNoRoutePackage) && pendingNoRouteScheduledAtMs > 0L) {
            long remainingMs = Math.max(0L,
                    ACCESSIBILITY_NO_ROUTE_STOP_DELAY_MS - (now - pendingNoRouteScheduledAtMs));
            log("accessibility no-route pending already scheduled package=" + packageName
                    + " reason=" + reason
                    + " remainingMs=" + remainingMs);
            return;
        }
        pendingNoRoutePackage = packageName;
        pendingNoRouteScheduledAtMs = now;
        handler.removeCallbacks(accessibilityNoRouteStop);
        handler.postDelayed(accessibilityNoRouteStop, ACCESSIBILITY_NO_ROUTE_STOP_DELAY_MS);
        log("accessibility no-route pending stop package=" + packageName
                + " reason=" + reason
                + " delayMs=" + ACCESSIBILITY_NO_ROUTE_STOP_DELAY_MS);
    }

    //stops or releases work here so stale capture and HUD output cannot keep running silently.
    private void cancelPendingRouteEndStops() {
        handler.removeCallbacks(notificationRemovedStop);
        handler.removeCallbacks(accessibilityNoRouteStop);
        handler.removeCallbacks(arrivalRouteEndStop);
        pendingRemovalKey = "";
        pendingRemovalPackage = "";
        pendingRemovalActiveKey = "";
        pendingNoRoutePackage = "";
        pendingArrivalPackage = "";
        pendingNoRouteScheduledAtMs = 0L;
        pendingArrivalScheduledAtMs = 0L;
    }

    //stops or releases work here so stale capture and HUD output cannot keep running silently.
    private void cancelAccessibilityNoRouteStop() {
        handler.removeCallbacks(accessibilityNoRouteStop);
        pendingNoRoutePackage = "";
        pendingNoRouteScheduledAtMs = 0L;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private boolean shouldBlankGMapsNotificationTextOnlyStraightManeuver(String packageName,
            NavParserResult result) {
        return NavTextNormalizer.sourceApp(packageName) == NavSnapshot.SourceApp.GOOGLE_MAPS
                && result != null
                && result.snapshot != null
                && result.snapshot.maneuver == NavSnapshot.Maneuver.STRAIGHT
                && result.maneuverEvidence != null
                && result.maneuverEvidence.source == NavManeuverEvidence.Source.TEXT;
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    private HudState blankGMapsNotificationTextOnlyStraightManeuver(HudState notificationState) {
        HudState blankState = notificationState == null ? new HudState() : notificationState.copy();
        blankState.hideNativeWithBlankId();
        blankState.hideTurnBitmapWithBlankSource();
        return blankState;
    }

    //starts or schedules work here so lifecycle recovery follows one controlled path.
    private void scheduleSendLoop() {
        if (sendLoopScheduled) {
            return;
        }
        sendLoopScheduled = true;
        handler.postDelayed(sendLoop, SEND_INTERVAL_MS);
    }

    //starts or schedules work here so lifecycle recovery follows one controlled path.
    private void scheduleRouteHealthLoop() {
        if (routeHealthScheduled) {
            return;
        }
        routeHealthScheduled = true;
        handler.postDelayed(routeHealthLoop, ROUTE_HEALTH_INTERVAL_MS);
    }

    //restarts only after the package-replace transport reset has released stale bindings.
    private void finishPackageReinitAndRestart() {
        if (!runtimeReinitInProgress) {
            return;
        }
        String restartPackage = pendingReinitStartPackage;
        String restartReason = pendingReinitStartReason;
        runtimeReinitInProgress = false;
        pendingReinitStartPackage = "";
        pendingReinitStartReason = "";
        log("package reinit complete restartPackage=" + restartPackage
                + " reason=" + safeReason(restartReason));
        startOnMain(restartPackage, restartReason);
    }

    private boolean isCurrentWazeDirectCallback(String ownerPackage,
            int sessionGeneration) {
        return active
                && WAZE_PACKAGE.equals(activePackage)
                && WAZE_PACKAGE.equals(ownerPackage)
                && wazeDirectChannel.sessionGeneration() == sessionGeneration;
    }

    private boolean isCurrentWazeSurfaceCallback(String ownerPackage,
            int sessionGeneration) {
        return active
                && WAZE_PACKAGE.equals(activePackage)
                && WAZE_PACKAGE.equals(ownerPackage)
                && wazeSurfaceRouteGeneration == wazeRouteGeneration
                && wazeSurfaceDirectChannel.sessionGeneration() == sessionGeneration;
    }

    private boolean isCurrentWazeProbeToken() {
        return wazeDirectChannel.sessionGeneration() == wazeDirectProbeSessionGeneration;
    }

    private boolean isCurrentGMapsTimeoutToken() {
        return gmapsDirectChannel.sessionGeneration() == gmapsDirectTimeoutSessionGeneration;
    }

    private boolean isCurrentGMapsDirectCallback(String ownerPackage,
            long sessionGeneration) {
        return active
                && GMapsDirectChannel.PACKAGE_NAME.equals(activePackage)
                && GMapsDirectChannel.OWNER_PACKAGE.equals(ownerPackage)
                && gmapsDirectChannel.sessionGeneration() == sessionGeneration;
    }

    private boolean isWazeBridgeSupportedCached() {
        long versionCode = Long.MIN_VALUE;
        long lastUpdateTime = Long.MIN_VALUE;
        try {
            PackageManager packageManager = context.getPackageManager();
            PackageInfo packageInfo = packageManager.getPackageInfo(WAZE_PACKAGE, 0);
            versionCode = packageInfo.getLongVersionCode();
            lastUpdateTime = packageInfo.lastUpdateTime;
        } catch (Throwable error) {
            log("waze capability package metadata unavailable error="
                    + error.getClass().getSimpleName());
        }
        if (cachedWazeBridgeSupported != null
                && cachedWazeVersionCode == versionCode
                && cachedWazeLastUpdateTime == lastUpdateTime) {
            return cachedWazeBridgeSupported;
        }
        boolean supported = WazeRouteLifecycleStore.isBridgeSupported(context);
        cachedWazeVersionCode = versionCode;
        cachedWazeLastUpdateTime = lastUpdateTime;
        cachedWazeBridgeSupported = supported;
        log("waze capability refreshed versionCode=" + versionCode
                + " lastUpdateTime=" + lastUpdateTime + " supported=" + supported);
        return supported;
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    private void log(String line) {
        String output = line;
        if (firstNavAfterPackageReplaceAwaitingSomeIp
                && (line.startsWith("someip connected:")
                || line.startsWith("bind timeout"))) {
            long ageMs = firstNavAfterPackageReplaceConnectStartMs <= 0L
                    ? -1L
                    : SystemClock.elapsedRealtime() - firstNavAfterPackageReplaceConnectStartMs;
            output = output + " firstNavStartAfterPackageReplace someipConnectMs="
                    + Math.max(0L, ageMs);
            firstNavAfterPackageReplaceAwaitingSomeIp = false;
            firstNavAfterPackageReplaceConnectStartMs = 0L;
        }
        Log.i(TAG, output);
        AppEventLogger.event(context, "nav_live " + output);
    }

    //normalizes values here so malformed app text cannot leak into HUD payloads.
    private static String normalizePackage(String packageName) {
        return packageName == null ? "" : packageName.trim();
    }

    //normalizes values here so malformed app text cannot leak into HUD payloads.
    private static String normalizeString(String value) {
        return value == null ? "" : value.trim();
    }

    //normalizes values here so malformed app text cannot leak into HUD payloads.
    private static String safeReason(String reason) {
        String normalized = normalizeString(reason).toLowerCase();
        if (normalized.isEmpty()) {
            return "unknown";
        }
        StringBuilder builder = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '-') {
                builder.append(ch);
            } else {
                builder.append('-');
            }
        }
        return builder.toString();
    }

}
