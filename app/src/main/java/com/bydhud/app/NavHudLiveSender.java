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
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

//defines the NavHudLiveSender module boundary so related behavior stays readable inside one unit.
final class NavHudLiveSender {
    private static final String TAG = "BydHudNavLive";
    private static final String WAZE_PACKAGE = "com.waze";
    private static final String MANUAL_TBT_OWNER = VehicleTbtPublisher.MANUAL_OWNER;
    private static final long SEND_INTERVAL_MS = 1000L;
    private static final long NOTIFICATION_REMOVED_STOP_DELAY_MS = 2000L;
    private static final long ACCESSIBILITY_NO_ROUTE_STOP_DELAY_MS = 10000L;
    private static final long ARRIVAL_ROUTE_END_STOP_DELAY_MS = 3000L;
    private static final long ROUTE_HEALTH_INTERVAL_MS = 1000L;
    private static final long TBT_TEARDOWN_RETRY_MS = 30_000L;
    private static final long ACTIVE_ROUTE_STALE_CLEAR_MS = 15000L;
    private static final long GMAPS_NOTIFICATION_RECONCILE_MS = 30_000L;
    private static final long WAZE_VISUAL_FRESH_MS = 2500L;
    private static final long WAZE_ROUTE_NODE_FRESH_MS = 3000L;
    private static final long WAZE_ROUTE_FIELD_TTL_MS = WAZE_ROUTE_NODE_FRESH_MS;
    private static final long DASHBOARD_WATCHDOG_INTERVAL_MS = 5000L;
    private static final long WAZE_DIRECT_TIMEOUT_MS = 5000L;
    private static final long WAZE_ROUTE_STATE_REQUEST_INTERVAL_MS = 5000L;
    private static final long WAZE_SPEED_LIMIT_EXPIRY_MS = 60_000L;
    static final int WAZE_CAP_SPEED_LIMIT_HEARTBEAT = 1;
    private static final long WAZE_SURFACE_READY_TIMEOUT_MS = 5000L;
    private static final long WAZE_SURFACE_HANDOFF_POLL_MS = 100L;
    private static final long GMAPS_DIRECT_TIMEOUT_MS = 5000L;
    static final int SURFACE_HANDOFF_NOT_REQUIRED = 0;
    static final int SURFACE_HANDOFF_RELAUNCH = 1;
    static final int SURFACE_HANDOFF_MOVE = 2;
    static final int SURFACE_HANDOFF_WAIT = 3;
    static final int SURFACE_HANDOFF_READY = 4;
    static final int SURFACE_HANDOFF_FAILED = 5;

    private static NavHudLiveSender instance;
    private static boolean wazeBridgeMetadataSeen;
    private static long acceptedWazeBridgeGeneration;
    private static int acceptedWazeBridgeCapabilities;

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static synchronized NavHudLiveSender get(Context context) {
        if (instance == null) {
            instance = new NavHudLiveSender(context.getApplicationContext());
        }
        return instance;
    }

    static void onOutputPreferenceChanged(String key) {
        NavHudLiveSender current;
        synchronized (NavHudLiveSender.class) {
            current = instance;
        }
        if (current != null) {
            current.handler.post(() -> current.onOutputPreferenceChangedOnMain(key));
        }
    }

    /** A validated V2 identity is a structural capability signal, not a signer
     * decision; refresh this sender's startup capability cache before dispatch. */
    static void noteWazeV2BridgeObserved(Context context) {
        NavHudLiveSender current;
        synchronized (NavHudLiveSender.class) {
            current = instance;
        }
        if (current != null) {
            current.handler.post(current::markWazeBridgeSupportedFromV2);
        }
    }

    private void markWazeBridgeSupportedFromV2() {
        long versionCode = Long.MIN_VALUE;
        long lastUpdateTime = Long.MIN_VALUE;
        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(WAZE_PACKAGE, 0);
            versionCode = packageInfo.getLongVersionCode();
            lastUpdateTime = packageInfo.lastUpdateTime;
        } catch (Throwable error) {
            log("waze v2 capability metadata unavailable error="
                    + error.getClass().getSimpleName());
        }
        cachedWazeVersionCode = versionCode;
        cachedWazeLastUpdateTime = lastUpdateTime;
        cachedWazeBridgeSupported = true;
        log("waze v2 structural capability observed versionCode=" + versionCode
                + " lastUpdateTime=" + lastUpdateTime);
    }

    static void onWazeRouteLifecycleEvent(long eventElapsedMs,
            WazeRouteLifecycleStore.RecordResult result) {
        WazeRouteLifecycleStore.Snapshot snapshot = result.snapshot;
        boolean routeActive = snapshot.active;
        long bridgeGeneration = snapshot.bridgeGeneration;
        int bridgeCapabilities = snapshot.bridgeCapabilities;
        NavHudLiveSender current;
        boolean bridgeTransition = false;
        synchronized (NavHudLiveSender.class) {
            current = instance;
            if (routeActive) {
                bridgeTransition = shouldClearWazeSpeedForBridgeTransition(
                        wazeBridgeMetadataSeen,
                        acceptedWazeBridgeGeneration, acceptedWazeBridgeCapabilities,
                        bridgeGeneration, bridgeCapabilities);
                wazeBridgeMetadataSeen = true;
                acceptedWazeBridgeGeneration = bridgeGeneration;
                acceptedWazeBridgeCapabilities = bridgeCapabilities;
            }
        }
        if (result.terminal || bridgeTransition) DirectSpeedLimitStore.clear(WAZE_PACKAGE);
        if (current != null) {
            boolean clearSpeed = bridgeTransition;
            current.handler.post(() -> current.onWazeRouteLifecycleEventOnMain(
                    result, eventElapsedMs, clearSpeed));
        }
    }

    static boolean shouldClearWazeSpeedForBridgeTransition(
            boolean metadataSeen, long previousGeneration, int previousCapabilities,
            long bridgeGeneration, int bridgeCapabilities) {
        boolean heartbeatChanged = ((previousCapabilities ^ bridgeCapabilities)
                & WAZE_CAP_SPEED_LIMIT_HEARTBEAT) != 0;
        return metadataSeen
                ? previousGeneration != bridgeGeneration || heartbeatChanged
                : bridgeGeneration != 0L
                || (bridgeCapabilities & WAZE_CAP_SPEED_LIMIT_HEARTBEAT) != 0;
    }

    static boolean shouldStartWazeDirectHost(boolean bridgeSupported, boolean routeActive) {
        return !bridgeSupported || routeActive;
    }

    static boolean shouldRestartWazeDirectForLifecycle(
            boolean changed, boolean channelActive, boolean navigating) {
        return !channelActive || (changed && !navigating);
    }

    static boolean shouldRecoverWazeDirectForLifecycle(boolean changed, boolean navigating) {
        return navigating || !changed;
    }

    static boolean shouldCloseWazeForSupersedingInactiveSnapshotForTest(
            boolean supersedingInactive, boolean wazeNavigating, boolean observerActive,
            boolean publisherRouteActive, String publisherOwner) {
        return supersedingInactive && (wazeNavigating || observerActive
                || publisherRouteActive && WAZE_PACKAGE.equals(normalizePackage(publisherOwner)));
    }

    static boolean shouldAcceptWazeNavigationStartAfterTerminalForTest(
            boolean terminalFence) {
        return !terminalFence;
    }

    static boolean shouldAcceptWazeFrameAfterTerminalForTest(
            boolean terminalFence, boolean frameOtherwiseCurrent) {
        return !terminalFence && frameOtherwiseCurrent;
    }

    static boolean shouldOpenLegacyRearmForTest(
            boolean rearmPending, int sessionFloor, int callbackSessionGeneration) {
        return !rearmPending || callbackSessionGeneration > sessionFloor;
    }

    static boolean shouldOpenLegacyRearmForChannelForTest(
            boolean surfaceChannel,
            boolean directRearmPending, int directSessionFloor,
            boolean surfaceRearmPending, int surfaceSessionFloor,
            int callbackSessionGeneration) {
        return shouldOpenLegacyRearmForTest(
                surfaceChannel ? surfaceRearmPending : directRearmPending,
                surfaceChannel ? surfaceSessionFloor : directSessionFloor,
                callbackSessionGeneration);
    }

    private boolean openLegacyRearmIfFreshSession(
            int callbackSessionGeneration, String source) {
        return openLegacyRearmIfFreshSession(
                callbackSessionGeneration, source, false);
    }

    private boolean openLegacyRearmIfFreshSession(
            int callbackSessionGeneration, String source, boolean surfaceChannel) {
        boolean rearmPending = surfaceChannel
                ? wazeLegacySurfaceRearmPending : wazeLegacyDirectRearmPending;
        int sessionFloor = surfaceChannel
                ? wazeLegacySurfaceSessionFloor : wazeLegacyDirectSessionFloor;
        if (!rearmPending) {
            return !wazeDirectRouteTerminalFence;
        }
        if (!shouldOpenLegacyRearmForChannelForTest(
                surfaceChannel,
                wazeLegacyDirectRearmPending, wazeLegacyDirectSessionFloor,
                wazeLegacySurfaceRearmPending, wazeLegacySurfaceSessionFloor,
                callbackSessionGeneration)) {
            log("legacy callback blocked by pending rearm source=" + safeReason(source)
                    + " callbackSession=" + callbackSessionGeneration
                    + " sessionFloor=" + sessionFloor
                    + " channel=" + (surfaceChannel ? "surface" : "cluster"));
            return false;
        }
        if (surfaceChannel) {
            wazeLegacySurfaceRearmPending = false;
            wazeLegacySurfaceSessionFloor = -1;
        } else {
            wazeLegacyDirectRearmPending = false;
            wazeLegacyDirectSessionFloor = -1;
        }
        wazeDirectRouteTerminalFence = false;
        log("legacy session proof opened source=" + safeReason(source)
                + " callbackSession=" + callbackSessionGeneration
                + " channel=" + (surfaceChannel ? "surface" : "cluster"));
        return true;
    }

    void onWazeAlertsPreferenceChanged(boolean enabled) {
        handler.post(() -> {
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
        });
        wazeDirectChannel.onWazeAlertsPreferenceChanged(enabled);
        wazeSurfaceDirectChannel.onWazeAlertsPreferenceChanged(enabled);
    }

    boolean isWazeDirectChannelActiveForProbe() {
        return wazeDirectChannel.isActive();
    }

    void startManual(HudState state, String reason) {
        HudState copy = state == null ? null : state.copy();
        handler.post(() -> startManualOnWorker(copy, reason));
    }

    void publishManual(HudState state, String reason) {
        HudState copy = state == null ? null : state.copy();
        if (copy == null) return;
        synchronized (manualPublishLock) {
            pendingManualPublishState = copy;
            pendingManualPublishReason = reason;
            if (manualPublishScheduled) return;
            manualPublishScheduled = true;
        }
        handler.post(this::drainManualPublish);
    }

    void stopManual(String reason) {
        stopManual(reason, true);
    }

    void stopManual(String reason, boolean restoreDirect) {
        stopManual(reason, restoreDirect, null);
    }

    void stopManual(String reason, boolean restoreDirect, Runnable completion) {
        handler.post(() -> {
            stopManualOnWorker(reason, restoreDirect);
            if (completion != null) completion.run();
        });
    }

    private void startManualOnWorker(HudState state, String reason) {
        if (state == null) return;
        if (!manualTbtActive) {
            manualTbtActive = true;
            manualTbtGeneration++;
            long generation = manualTbtGeneration;
            ++tbtLifecycleToken;
            tbtPublisher.beginRoute(
                    MANUAL_TBT_OWNER, generation, true, true,
                    "manual-start:" + safeReason(reason));
            log("manual tbt start generation=" + manualTbtGeneration
                    + " reason=" + safeReason(reason));
            logRouteStartOutputPreferences("manual", generation, reason);
        }
        publishManualOnWorker(state, reason);
        hudOutput.setManualEnabled(true, "manual-start:" + safeReason(reason));
        hudOutput.ensureBound("manual-start");
    }

    private void publishManualOnWorker(HudState state, String reason) {
        if (state == null) return;
        if (!manualTbtActive) {
            startManualOnWorker(state, reason);
            return;
        }
        latestManualSourceState = state.copy();
        latestManualTbtState = effectiveManualState(state);
        tbtPublisher.publishManualFrame(
                MANUAL_TBT_OWNER, manualTbtGeneration,
                latestManualTbtState, "manual-frame:" + safeReason(reason));
        hudOutput.publishManual(state, reason);
        log("manual tbt frame generation=" + manualTbtGeneration
                + " native=" + latestManualTbtState.maneuverId
                + " distanceM=" + latestManualTbtState.distanceToIntersection
                + " textMode=" + HudPrefs.transliterationMode(context)
                + " rawRoad=\"" + normalizeString(latestManualSourceState.roadName) + "\""
                + " sentRoad=\"" + normalizeString(latestManualTbtState.roadName) + "\""
                + " reason=" + safeReason(reason));
    }

    private void drainManualPublish() {
        HudState state;
        String reason;
        synchronized (manualPublishLock) {
            state = pendingManualPublishState;
            reason = pendingManualPublishReason;
            pendingManualPublishState = null;
            pendingManualPublishReason = "";
            manualPublishScheduled = false;
        }
        if (state != null && manualTbtActive) {
            publishManualOnWorker(state, reason);
        }
        synchronized (manualPublishLock) {
            if (pendingManualPublishState != null && !manualPublishScheduled) {
                manualPublishScheduled = true;
                handler.post(this::drainManualPublish);
            }
        }
    }

    private void stopManualOnWorker(String reason, boolean restoreDirect) {
        hudOutput.setManualEnabled(false, "manual-stop:" + safeReason(reason));
        synchronized (manualPublishLock) {
            pendingManualPublishState = null;
            pendingManualPublishReason = "";
        }
        if (!manualTbtActive) return;
        long generation = manualTbtGeneration;
        manualTbtActive = false;
        latestManualSourceState = null;
        latestManualTbtState = null;
        ++tbtLifecycleToken;
        tbtPublisher.endManualRoute(
                MANUAL_TBT_OWNER, generation, "manual-stop:" + safeReason(reason));
        if (restoreDirect) {
            selectRemainingTbtRoute(MANUAL_TBT_OWNER, "manual-stop:" + safeReason(reason));
        }
        if (!tbtPublisher.isRouteActive()) tbtPublisher.sendTeardownStatus();
        log("manual tbt stop generation=" + generation
                + " restored=" + tbtPublisher.ownerPackage()
                + " reason=" + safeReason(reason));
    }

    private HudState effectiveManualState(HudState state) {
        HudState effective = HudDisplayPolicy.apply(
                state, HudPrefs.isSmallDistanceClampEnabled(context));
        int tbtManeuver = manualTbtManeuverForTest(effective.maneuverId);
        if (tbtManeuver != effective.maneuverId) {
            effective.maneuverId = tbtManeuver;
            effective.includeNativeArrow = false;
        }
        HudOutputPreferences.apply(context, effective);
        return effective;
    }

    void onWazeScreenCapturePreferenceChanged(boolean enabled) {
        handler.post(() -> {
            log("waze screen capture enabled=" + enabled);
            NavCaptureIngressPolicy.refreshPreferences(context);
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
                updateCaptureIngressPolicy();
                return;
            }
            if (!active || !WAZE_PACKAGE.equals(activePackage) || wazeDirectFrameReceived) {
                updateCaptureIngressPolicy();
                return;
            }
            activateWazeLegacyFallback("screen-capture-enabled");
            updateCaptureIngressPolicy();
            scheduleRouteHealthLoop();
        });
    }

    private void updateCaptureIngressPolicy() {
        if (!wazeFallbackActive && WAZE_PACKAGE.equals(legacyFallbackOwnerPackage)) {
            legacyFallbackOwnerPackage = "";
        }
        if (!gmapsDirectFallbackActive
                && GMapsDirectChannel.PACKAGE_NAME.equals(legacyFallbackOwnerPackage)) {
            legacyFallbackOwnerPackage = "";
        }
        boolean wazeDirect = wazeDirectFrameReceived
                || (wazeDirectHandshakeAvailable && !wazeFallbackActive);
        boolean gmapsDirect = gmapsDirectFrameReceived
                || (gmapsDirectHandshakeAvailable && !gmapsDirectFallbackActive);
        NavCaptureIngressPolicy.updateRuntime(
                active, activePackage, wazeDirect, wazeFallbackActive,
                gmapsDirect, gmapsDirectFallbackActive);
        if (wazeDirect) NavCaptureIngressPolicy.disarmPackage(WAZE_PACKAGE);
        if (gmapsDirect) {
            NavCaptureIngressPolicy.disarmPackage(GMapsDirectChannel.PACKAGE_NAME);
        }
    }

    private final Context context;
    private final HandlerThread stateThread;
    private final Handler handler;
    private final HudOutputCoordinator hudOutput;
    private final VehicleTbtPublisher tbtPublisher;
    private final WazeDirectChannel wazeDirectChannel;
    private final WazeDirectChannel wazeSurfaceDirectChannel;
    private final GMapsDirectChannel gmapsDirectChannel;
    private boolean tbtWazeObserver;
    private boolean tbtGMapsObserver;
    private boolean manualTbtActive;
    private long manualTbtGeneration;
    private HudState latestManualSourceState;
    private HudState latestManualTbtState;
    private final Object manualPublishLock = new Object();
    private HudState pendingManualPublishState;
    private String pendingManualPublishReason = "";
    private boolean manualPublishScheduled;
    private long tbtLifecycleToken;
    private boolean sourceSwitchInProgress;
    private long sourceSwitchToken;
    private String pendingSourceSwitchPackage = "";
    private String pendingSourceSwitchReason = "";
    private boolean stopInProgress;
    private String pendingStopStartPackage = "";
    private String pendingStopStartReason = "";
    private Runnable pendingStopCompletion;
    private boolean pendingForcedDirectTeardown;
    private String pendingHudDemotionObserverPackage = "";
    private long wazeTbtRouteStartedAtMs;
    private long gmapsTbtRouteStartedAtMs;
    private final Object wazeDirectFrameLock = new Object();
    private DirectTbtFrame pendingWazeDirectFrame;
    private String pendingWazeDirectFrameReason = "";
    private String pendingWazeDirectFrameOwner = "";
    private int pendingWazeDirectFrameSessionGeneration;
    private int pendingWazeDirectFramePublisherGeneration;
    private int pendingWazeDirectFrameGeneration;
    private boolean pendingWazeDirectFrameFromSurface;
    private WazeRouteTiming.Frame pendingWazeDirectFrameTiming;
    private int wazeDirectFrameGeneration;
    private int coalescedWazeDirectFrames;
    private boolean wazeDirectFrameDispatchScheduled;
    private final Runnable wazeDirectFrameDispatch = this::dispatchLatestWazeDirectFrame;
    private String activePackage = "";
    //tracks the package currently allowed to publish legacy capture without owning Direct.
    private String legacyFallbackOwnerPackage = "";
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
    private boolean wazeDirectRouteTerminalFence;
    private long wazeDirectTerminalBridgeGeneration;
    private boolean wazeLegacyDirectRearmPending;
    private int wazeLegacyDirectSessionFloor = -1;
    private boolean wazeLegacySurfaceRearmPending;
    private int wazeLegacySurfaceSessionFloor = -1;
    private boolean wazeDirectProbeScheduled;
    private int wazeDirectProbeSessionGeneration;
    private volatile int wazeRouteGeneration;
    private boolean wazeFallbackActive;
    private boolean wazeFallbackReadinessCheckInFlight;
    private int wazeFallbackReadinessGeneration;
    private long lastWazeFallbackReadinessCheckMs;
    private long lastWazeRouteStateRequestMs;
    private boolean wazeSurfaceLaunchPending;
    private volatile boolean wazeSurfaceActive;
    private boolean wazeSurfaceVisible;
    private volatile boolean wazeSurfaceEnabledForRoute;
    private volatile boolean wazeSurfaceDismissedForRoute;
    private int wazeSurfaceTaskId = -1;
    private long wazeSurfaceInstanceId;
    private volatile long wazeSurfaceReadyInstanceId;
    private volatile int wazeSurfaceReadyDisplayId = -1;
    private volatile long wazeSurfaceReadyEpoch;
    private volatile DirectTbtFrame latestWazeClusterFrame;
    private volatile String latestWazeClusterFrameReason = "";
    private volatile int latestWazeClusterFrameSessionGeneration;
    private volatile DirectTbtFrame latestWazeSurfaceFrame;
    private volatile String latestWazeSurfaceFrameReason = "";
    private volatile int latestWazeSurfaceFrameSessionGeneration;
    private volatile int wazeSurfaceRouteGeneration = -1;
    private volatile int wazeSurfaceFailureRouteGeneration = -1;
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
    private long wazeSpeedLimitExpiryAtMs;
    private long wazeSpeedLimitBridgeGeneration;
    private long wazeSpeedLimitEventElapsedMs;
    private boolean gmapsDirectFrameReceived;
    private boolean gmapsDirectHandshakeAvailable;
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
    private HudOutputPreferenceSnapshot lastOutputPreferenceSnapshot;
    private long lastManualOutputPreferenceGeneration = Long.MIN_VALUE;
    private long lastWazeOutputPreferenceGeneration = Long.MIN_VALUE;
    private long lastGMapsOutputPreferenceGeneration = Long.MIN_VALUE;
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
                || wazeDirectRouteTerminalFence
                || !hasActiveWazeRoute()) {
            return;
        }
        activateWazeLegacyFallback("direct-frame-timeout");
    };

    private final Runnable gmapsDirectTimeout = this::onGMapsDirectTimeout;
    private final Runnable speedOverlayTimeout = this::onSpeedOverlayTimeout;
    private final Runnable wazeSpeedLimitExpiry = this::onWazeSpeedLimitExpiry;
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
            if (!isLegacyFallbackIngressOwner(packageName)) {
                log("notification removed ignored: fallback owner changed package="
                        + packageName);
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
                forceClearLegacyFallback(activePackage, "notification-removed", now);
                return;
            }
            if (NavRouteStateStore.get(context).isRouteActive(activePackage, now)) {
                log("notification removed stop ignored: route evidence still active package="
                        + activePackage + " key=" + key);
                scheduleRouteHealthLoop();
                return;
            }
            stopLegacyFallbackOnMain(activePackage, "notification-removed");
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
                stopLegacyFallbackOnMain(activePackage, "route-stale");
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
            if (!isLegacyFallbackIngressOwner(packageName)) {
                log("accessibility no-route ignored: fallback owner changed package="
                        + packageName);
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
                forceClearLegacyFallback(activePackage, "accessibility-route-ended", now);
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
            stopLegacyFallbackOnMain(activePackage, "accessibility-route-ended");
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
            if (!isLegacyFallbackIngressOwner(packageName)) {
                log("arrival ignored: fallback owner changed package=" + packageName);
                return;
            }
            long now = SystemClock.elapsedRealtime();
            NavRouteStateStore.get(context).clearRoute(packageName, "arrival-route-ended", now);
            if ("com.waze".equals(packageName)) {
                WazeRouteTracker.get(context).onRouteEnded("arrival-route-ended", now);
            }
            stopLegacyFallbackOnMain(packageName, "arrival-route-ended");
        }
    };

    private static final class SurfaceHandoffStart {
        final CountDownLatch ready = new CountDownLatch(1);
        boolean required;
        int routeGeneration = -1;
    }

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private NavHudLiveSender(Context context) {
        this.context = context;
        this.stateThread = new HandlerThread("BydHudNavState");
        this.stateThread.start();
        this.handler = new Handler(stateThread.getLooper());
        this.hudOutput = HudOutputCoordinator.get(context);
        this.tbtPublisher = new VehicleTbtPublisher(context, handler);
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
                            if (!openLegacyRearmIfFreshSession(
                                    sessionGeneration, "navigation_started")) {
                                return;
                            }
                            if (!shouldAcceptWazeNavigationStartAfterTerminalForTest(
                                    wazeDirectRouteTerminalFence)) {
                                log("waze direct navigation start ignored by terminal fence");
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
                            DirectTbtFrame frame, String reason,
                            WazeRouteTiming.Frame timing) {
                        handler.post(() -> {
                            if (!isCurrentWazeDirectCallback(
                                    ownerPackage, sessionGeneration)
                                    || !openLegacyRearmIfFreshSession(
                                    sessionGeneration, "frame")
                                    || !shouldAcceptWazeFrameAfterTerminalForTest(
                                    wazeDirectRouteTerminalFence, true)) return;
                            DirectTbtFrame previousFrame = latestWazeClusterFrame;
                            latestWazeClusterFrameReason = safeReason(reason);
                            latestWazeClusterFrameSessionGeneration = sessionGeneration;
                            latestWazeClusterFrame = frame;
                            if (!wazeSurfaceActive) {
                                enqueueLatestWazeDirectFrame(
                                        ownerPackage, sessionGeneration,
                                        frame, reason, false, timing);
                            } else if (wazeAlertStateChanged(previousFrame, frame)
                                    && latestWazeSurfaceFrame != null) {
                                enqueueLatestWazeDirectFrame(
                                        ownerPackage,
                                        latestWazeSurfaceFrameSessionGeneration,
                                        withRetainedWazeClusterAlert(
                                                latestWazeSurfaceFrame),
                                        "surface-alert-sync:" + safeReason(reason), true);
                            }
                        });
                    }

                    @Override
                    public void onAlertCleared(String ownerPackage, int sessionGeneration,
                            DirectTbtFrame frame, String reason) {
                        invalidatePendingWazeDirectFrames();
                        handler.post(() -> {
                            if (!isCurrentWazeDirectCallback(ownerPackage, sessionGeneration)) {
                                return;
                            }
                            if (!openLegacyRearmIfFreshSession(
                                    sessionGeneration, "alert_cleared")) return;
                            if (wazeDirectRouteTerminalFence) return;
                            latestWazeClusterFrameReason = safeReason(reason);
                            latestWazeClusterFrameSessionGeneration = sessionGeneration;
                            latestWazeClusterFrame = frame;
                            if (wazeSurfaceActive) return;
                            DirectTbtFrame outputFrame = applySpeedLimitOverlay(
                                    ownerPackage, frame, SystemClock.elapsedRealtime());
                            outputFrame = effectiveDirectFrame(outputFrame);
                            if (isHudOutputOwner(ownerPackage)) {
                                hudOutput.clearDirectAlertAndRepublish(
                                        ownerPackage, sessionGeneration, outputFrame, reason,
                                        SystemClock.elapsedRealtime());
                            }
                        });
                    }

                    @Override
                    public void onNavigationEnded(String ownerPackage,
                            int routeGeneration, int callbackGeneration, String reason) {
                        invalidatePendingWazeDirectFrames();
                        long detectedAtMs = SystemClock.elapsedRealtime();
                        String safeEndReason = safeReason(reason);
                        if (normalizeString(reason).startsWith("stopped:")) {
                            return;
                        }
                        Log.i(TAG, "waze direct navigation ended detected reason="
                                + safeEndReason + " elapsedMs=" + detectedAtMs);
                        WazeCaptureDebugWriter.get().appEvent(context,
                                "nav_live waze_direct navigation_ended_detected reason="
                                        + safeEndReason + " elapsedMs=" + detectedAtMs);
                        handler.post(() -> {
                            if (!isCurrentWazeDirectCallback(
                                    ownerPackage, callbackGeneration)) {
                                return;
                            }
                            endWazeDirectSession("navigation-ended:" + safeEndReason);
                            onWazeDirectNavigationEnded(
                                    ownerPackage, routeGeneration, callbackGeneration,
                                    reason, detectedAtMs);
                        });
                    }

                    @Override
                    public void onLiveness(String ownerPackage, int sessionGeneration,
                            String reason) {
                        handler.post(() -> {
                            if (!isCurrentWazeDirectCallback(ownerPackage, sessionGeneration)
                                    || !openLegacyRearmIfFreshSession(
                                    sessionGeneration, "liveness")
                                    || wazeDirectRouteTerminalFence) {
                                return;
                            }
                            if (isHudOutputOwner(ownerPackage)) {
                                hudOutput.renewDirectLease(
                                        ownerPackage, sessionGeneration, reason);
                            }
                        });
                    }

                    @Override
                    public void onSurfaceReady(String ownerPackage, int sessionGeneration,
                            long activityInstanceId, int displayId, long surfaceEpoch) {
                        handler.post(() -> onWazeSurfaceReady(
                                ownerPackage, sessionGeneration,
                                activityInstanceId, displayId, surfaceEpoch));
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
                        handler.post(() -> onGMapsDirectNavigationStarted(
                                ownerPackage, sessionGeneration, reason));
                    }

                    @Override
                    public void onFrame(String ownerPackage, long sessionGeneration,
                            DirectTbtFrame frame, String reason,
                            GMapsDirectChannel.BitmapSelection bitmapSelection,
                            GMapsTimingDiagnostics.Frame timing) {
                        handler.post(() -> onGMapsDirectFrame(
                                ownerPackage, sessionGeneration, frame, reason,
                                bitmapSelection, timing));
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
                            long routeGeneration, long callbackGeneration, String reason) {
                        long detectedAtMs = SystemClock.elapsedRealtime();
                        handler.post(() -> {
                            if (!isCurrentGMapsDirectCallback(
                                    ownerPackage, callbackGeneration)) {
                                return;
                            }
                            endGMapsDirectSession(
                                    "navigation-ended:" + safeReason(reason));
                            onGMapsDirectNavigationEnded(
                                    ownerPackage, routeGeneration, callbackGeneration,
                                    reason, detectedAtMs);
                        });
                    }

                    @Override
                    public void onLiveness(String ownerPackage, long sessionGeneration,
                            String reason) {
                        handler.post(() -> {
                            if (!isCurrentGMapsDirectCallback(ownerPackage, sessionGeneration)) {
                                return;
                            }
                            if (isHudOutputOwner(ownerPackage)) {
                                hudOutput.renewDirectLease(
                                        ownerPackage, sessionGeneration, reason);
                            }
                        });
                    }

                    @Override
                    public void onLog(String message) {
                        logGMapsDirectChannelEvent(message);
                    }
                });
        NavCaptureIngressPolicy.refreshPreferencesAsync(context);
        updateCaptureIngressPolicy();
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
                handler.post(() -> {
                    if (!isCurrentWazeSurfaceCallback(
                            ownerPackage, sessionGeneration)
                            || !openLegacyRearmIfFreshSession(
                            sessionGeneration, "surface_navigation_started", true)
                            || wazeDirectRouteTerminalFence) return;
                    eventWazeDirectSession("surface_navigation_started", reason);
                });
            }

            @Override
            public void onFrame(String ownerPackage, int sessionGeneration,
                    DirectTbtFrame frame, String reason,
                    WazeRouteTiming.Frame timing) {
                handler.post(() -> {
                    if (!isCurrentWazeSurfaceCallback(
                            ownerPackage, sessionGeneration)
                            || !openLegacyRearmIfFreshSession(
                            sessionGeneration, "surface_frame", true)
                            || wazeDirectRouteTerminalFence) return;
                    latestWazeSurfaceFrameReason = safeReason(reason);
                    latestWazeSurfaceFrameSessionGeneration = sessionGeneration;
                    latestWazeSurfaceFrame = frame;
                    if (wazeSurfaceActive) {
                        enqueueLatestWazeDirectFrame(
                                ownerPackage, sessionGeneration,
                                withRetainedWazeClusterAlert(frame),
                                "surface:" + safeReason(reason), true);
                    }
                });
            }

            @Override
            public void onAlertCleared(String ownerPackage, int sessionGeneration,
                    DirectTbtFrame frame, String reason) {
                handler.post(() -> {
                    if (!isCurrentWazeSurfaceCallback(
                            ownerPackage, sessionGeneration)
                            || !openLegacyRearmIfFreshSession(
                            sessionGeneration, "surface_alert_cleared", true)
                            || wazeDirectRouteTerminalFence) return;
                    latestWazeSurfaceFrameReason = safeReason(reason);
                    latestWazeSurfaceFrameSessionGeneration = sessionGeneration;
                    latestWazeSurfaceFrame = frame;
                    if (!wazeSurfaceActive) return;
                    DirectTbtFrame outputFrame = withRetainedWazeClusterAlert(frame);
                    if (outputFrame.getAlertOverlay().isActive()) {
                        enqueueLatestWazeDirectFrame(
                                ownerPackage, sessionGeneration, outputFrame,
                                "surface-alert-retained:" + safeReason(reason), true);
                    } else {
                        outputFrame = applySpeedLimitOverlay(
                                ownerPackage, outputFrame, SystemClock.elapsedRealtime());
                        outputFrame = effectiveDirectFrame(outputFrame);
                        hudOutput.clearDirectAlertAndRepublish(
                                ownerPackage, wazeDirectChannel.sessionGeneration(), outputFrame,
                                "surface:" + safeReason(reason),
                                SystemClock.elapsedRealtime());
                    }
                });
            }

            @Override
            public void onNavigationEnded(String ownerPackage,
                    int routeGeneration, int callbackGeneration, String reason) {
                handler.post(() -> {
                    if (!isCurrentWazeSurfaceCallback(
                            ownerPackage, callbackGeneration)) return;
                    eventWazeDirectSession("surface_navigation_ended", reason);
                });
            }

            @Override
            public void onLiveness(String ownerPackage, int sessionGeneration, String reason) {
                handler.post(() -> {
                    if (!wazeSurfaceActive
                            || !isCurrentWazeSurfaceCallback(
                            ownerPackage, sessionGeneration)
                            || !openLegacyRearmIfFreshSession(
                            sessionGeneration, "surface_liveness", true)
                            || wazeDirectRouteTerminalFence) return;
                    int publisherGeneration = wazeDirectChannel.sessionGeneration();
                    if (isCurrentWazeDirectCallback(ownerPackage, publisherGeneration)) {
                        hudOutput.renewDirectLease(
                                ownerPackage, publisherGeneration,
                                "surface:" + safeReason(reason));
                    }
                });
            }

            @Override
            public void onSurfaceReady(String ownerPackage, int sessionGeneration,
                    long activityInstanceId, int displayId, long surfaceEpoch) {
                handler.post(() -> onWazeSurfaceReady(
                        ownerPackage, sessionGeneration,
                        activityInstanceId, displayId, surfaceEpoch));
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
        enqueueLatestWazeDirectFrame(ownerPackage, sessionGeneration, frame, reason,
                fromSurface, null);
    }

    private void enqueueLatestWazeDirectFrame(String ownerPackage,
            int sessionGeneration, DirectTbtFrame frame, String reason,
            boolean fromSurface, WazeRouteTiming.Frame timing) {
        if (frame == null || wazeDirectRouteTerminalFence) {
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
            pendingWazeDirectFramePublisherGeneration = fromSurface
                    ? wazeDirectChannel.sessionGeneration() : sessionGeneration;
            pendingWazeDirectFrameGeneration = wazeDirectFrameGeneration;
            pendingWazeDirectFrameFromSurface = fromSurface;
            if (timing != null || pendingWazeDirectFrameTiming == null) {
                pendingWazeDirectFrameTiming = timing;
            }
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
            int sourceGeneration = pendingWazeDirectFrameSessionGeneration;
            int publisherGeneration = pendingWazeDirectFramePublisherGeneration;
            int generation = pendingWazeDirectFrameGeneration;
            boolean fromSurface = pendingWazeDirectFrameFromSurface;
            WazeRouteTiming.Frame timing = pendingWazeDirectFrameTiming;
            int coalesced = coalescedWazeDirectFrames;
            pendingWazeDirectFrame = null;
            pendingWazeDirectFrameReason = "";
            pendingWazeDirectFrameOwner = "";
            pendingWazeDirectFramePublisherGeneration = 0;
            pendingWazeDirectFrameTiming = null;
            coalescedWazeDirectFrames = 0;
            boolean sourceCurrent = fromSurface
                    ? isCurrentWazeSurfaceCallback(ownerPackage, sourceGeneration)
                    : isCurrentWazeDirectCallback(ownerPackage, sourceGeneration);
            boolean publisherCurrent = isCurrentWazeDirectCallback(
                    ownerPackage, publisherGeneration);
            if (!shouldAcceptWazeFrameForTest(
                    frame != null, generation == wazeDirectFrameGeneration,
                    sourceCurrent, publisherCurrent, fromSurface, wazeSurfaceActive)) {
                return;
            }
            if (!shouldAcceptWazeFrameAfterTerminalForTest(
                    wazeDirectRouteTerminalFence, true)) return;
            if (coalesced > 0) {
                WazeCaptureDebugWriter.get().appEvent(context,
                        "nav_live waze_direct frames_coalesced=" + coalesced);
            }
            onWazeDirectFrame(ownerPackage, publisherGeneration, frame, reason,
                    fromSurface, sourceGeneration, timing);
        }
    }

    //drops queued snapshots before lifecycle callbacks can switch or end the direct session.
    private void invalidatePendingWazeDirectFrames() {
        synchronized (wazeDirectFrameLock) {
            wazeDirectFrameGeneration++;
            pendingWazeDirectFrame = null;
            pendingWazeDirectFrameReason = "";
            pendingWazeDirectFrameOwner = "";
            pendingWazeDirectFramePublisherGeneration = 0;
            pendingWazeDirectFrameFromSurface = false;
            pendingWazeDirectFrameTiming = null;
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
        boolean legacySessionProof = !isWazeBridgeSupportedCached();
        if (legacySessionProof) {
            // Legacy has no bridge generation; the explicit host restart is
            // its session proof for opening the channel's terminal fence.
            wazeLegacyDirectRearmPending = true;
            wazeLegacyDirectSessionFloor = wazeDirectChannel.sessionGeneration();
            wazeLegacySurfaceRearmPending = true;
            wazeLegacySurfaceSessionFloor = wazeSurfaceDirectChannel.sessionGeneration();
            if (wazeDirectChannel.isActive()) {
                wazeDirectChannel.stop("legacy-session-restart");
            }
            if (wazeSurfaceDirectChannel.isActive()) {
                wazeSurfaceDirectChannel.stop("legacy-session-restart");
            }
            wazeDirectChannel.openAcceptedFreshRoute(
                    "legacy-session-start:" + safeReason(reason), 0L);
            wazeSurfaceDirectChannel.openAcceptedFreshRoute(
                    "legacy-session-start:" + safeReason(reason), 0L);
        }
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
        requestWazeRouteStateSnapshot("wait-route:" + safeReason(reason), false);
    }

    private void onWazeDirectHandshakeAvailable(String ownerPackage,
            int sessionGeneration, String reason) {
        if (!isCurrentWazeDirectCallback(ownerPackage, sessionGeneration)) {
            return;
        }
        if (!openLegacyRearmIfFreshSession(sessionGeneration, "handshake")) {
            return;
        }
        wazeDirectHandshakeAvailable = true;
        updateCaptureIngressPolicy();
        if (isHudOutputOwner(ownerPackage)) {
            hudOutput.renewDirectLease(ownerPackage, sessionGeneration, reason);
        }
        log("waze direct handshake available reason=" + safeReason(reason));
    }

    private void onWazeDirectHandshakeUnavailable(String ownerPackage,
            int sessionGeneration, String reason) {
        if (!isCurrentWazeDirectCallback(ownerPackage, sessionGeneration)) {
            return;
        }
        wazeDirectHandshakeAvailable = false;
        updateCaptureIngressPolicy();
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
        if (isHudOutputOwner(ownerPackage)) {
            hudOutput.clearDirectFrameForLoss(
                    ownerPackage, sessionGeneration,
                    "waze-direct-unavailable:" + safeReason(reason),
                    SystemClock.elapsedRealtime());
        }
        if (!isHudOutputOwner(ownerPackage)) {
            log("waze tbt observer unavailable reason=" + safeReason(reason));
            return;
        }
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
            updateCaptureIngressPolicy();
            scheduleWazeDirectColdTimeout("direct-unavailable:" + safeReason(reason));
        }
    }

    private void onWazeDirectNavigationStarted(String ownerPackage,
            int sessionGeneration, String reason) {
        if (!isCurrentWazeDirectCallback(ownerPackage, sessionGeneration)) {
            return;
        }
        if (!shouldAcceptWazeNavigationStartAfterTerminalForTest(
                wazeDirectRouteTerminalFence)) {
            log("waze direct navigation start rejected by terminal fence reason="
                    + safeReason(reason));
            return;
        }
        logRouteStartOutputPreferences("waze", sessionGeneration, reason);
        boolean newRoute = !wazeDirectNavigating;
        if (newRoute) {
            ++tbtLifecycleToken;
            if (wazeTbtRouteStartedAtMs <= 0L) {
                wazeTbtRouteStartedAtMs = SystemClock.elapsedRealtime();
            }
            wazeDirectFrameReceived = false;
            wazeSurfaceEnabledForRoute = HudPrefs.isWazeCustomSurfaceEnabled(context);
            wazeSurfaceDismissedForRoute = false;
            wazeSurfaceFailureRouteGeneration = -1;
        }
        wazeDirectNavigating = true;
        wazeDirectHandshakeAvailable = true;
        wazeDirectRouteEnded = false;
        updateCaptureIngressPolicy();
        long now = SystemClock.elapsedRealtime();
        NavRouteStateStore.get(context).updateFromVisualRouteEvidence(
                WAZE_PACKAGE, "waze_direct", "navigation_started", now);
        WazeRouteTracker.get(context).onDirectRouteEvidence(
                "direct-navigation-started", now);
        boolean hudOwner = isHudOutputOwner(ownerPackage);
        if (!manualTbtActive) {
            tbtPublisher.beginRoute(ownerPackage, sessionGeneration,
                    shouldRequestDashboardForDirectRouteForTest(hudOwner,
                            HudPrefs.isSwitchToTbtOnHudStartEnabled(context)), hudOwner,
                    "waze-navigation-started:" + safeReason(reason));
        } else {
            tbtPublisher.recordDeferredLifecycle(
                    ownerPackage, sessionGeneration, "start",
                    "waze-navigation-started:" + safeReason(reason));
        }
        if (hudOwner) {
            hudOutput.selectNavigationSource(
                    HudOutputCoordinator.Source.DIRECT,
                    "waze-navigation-started:" + safeReason(reason),
                    ownerPackage, sessionGeneration);
            hudOutput.renewDirectLease(ownerPackage, sessionGeneration, reason);
        }
        if (hudOwner && !isWazeBridgeSupportedCached()) {
            scheduleWazeDirectColdTimeout("direct-navigation-started");
        }
        log("waze direct navigation started reason=" + safeReason(reason));
        if (hudOwner && wazeSurfaceActive && latestWazeSurfaceFrame != null) {
            enqueueLatestWazeDirectFrame(
                    WAZE_PACKAGE, latestWazeSurfaceFrameSessionGeneration,
                    withRetainedWazeClusterAlert(latestWazeSurfaceFrame),
                    "surface-recovery:" + latestWazeSurfaceFrameReason, true);
        }
        if (hudOwner) {
            maybeLaunchWazeSurface("navigation-started:" + safeReason(reason));
        }
    }

    void onWazeSurfaceActivityCreated(int taskId, long instanceId) {
        handler.post(() -> {
            wazeSurfaceTaskId = taskId;
            wazeSurfaceInstanceId = instanceId;
            if (wazeSurfaceReadyInstanceId != instanceId) {
                invalidateWazeSurfaceReadiness();
            }
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
                    && wazeSurfaceDirectChannel.isActive()
                    && hasCurrentWazeSurfaceReadyEvidence(
                            WazeSurfaceActivity.activeDisplayId(), wazeRouteGeneration)) {
                activateWazeSurface(wazeSurfaceDirectChannel.sessionGeneration());
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
                invalidateWazeSurfaceReadiness();
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
        requestWazeRouteStateSnapshot("app-foreground", true);
        if (!shouldUseWazeSurface() || WazeSurfaceActivity.isVisible()) return;
        requestWazeSurfaceActivity("waze-app-foreground", true);
    }

    private void requestWazeSurfaceActivity(String reason, boolean bringToFront) {
        requestWazeSurfaceActivity(reason, bringToFront, -1);
    }

    private void requestWazeSurfaceActivity(
            String reason, boolean bringToFront, int requestedDisplayId) {
        if (!shouldUseWazeSurface() || wazeSurfaceLaunchPending
                || wazeSurfaceActive && WazeSurfaceActivity.isVisible()
                || !bringToFront && WazeSurfaceActivity.isActive()) return;
        NavAppDisplayState state = NavAppDisplayController.get(context).lastState(WAZE_PACKAGE);
        int displayId = requestedDisplayId >= 0
                ? requestedDisplayId
                : state == null || state.displayId < 0 ? 0 : state.displayId;
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

    static int wazeSurfaceHandoffAction(
            boolean routeCurrent,
            boolean failed,
            boolean dismissed,
            int taskId,
            int actualDisplay,
            int targetDisplay,
            boolean ready) {
        if (!routeCurrent) return SURFACE_HANDOFF_NOT_REQUIRED;
        if (failed) return SURFACE_HANDOFF_FAILED;
        if (dismissed) return SURFACE_HANDOFF_NOT_REQUIRED;
        if (taskId < 0) return SURFACE_HANDOFF_RELAUNCH;
        if (actualDisplay != targetDisplay) return SURFACE_HANDOFF_MOVE;
        return ready ? SURFACE_HANDOFF_READY : SURFACE_HANDOFF_WAIT;
    }

    static boolean wazeSurfaceReadyForHandoff(
            boolean routeCurrent,
            boolean active,
            boolean visible,
            boolean validSurface,
            long activeInstanceId,
            long readyInstanceId,
            int actualDisplay,
            int readyDisplay,
            int targetDisplay,
            long activeSurfaceEpoch,
            long readySurfaceEpoch) {
        return routeCurrent && active && visible && validSurface
                && activeInstanceId > 0L
                && activeInstanceId == readyInstanceId
                && actualDisplay == targetDisplay
                && readyDisplay == targetDisplay
                && activeSurfaceEpoch > 0L
                && activeSurfaceEpoch == readySurfaceEpoch;
    }

    static boolean wazeSurfaceHandoffNeedsLaunch(int action) {
        return action == SURFACE_HANDOFF_RELAUNCH || action == SURFACE_HANDOFF_WAIT;
    }

    static boolean isTransientWazeSurfaceUnavailable(
            String reason, boolean routeEligible) {
        return routeEligible && "activity-surface-destroyed".equals(reason);
    }

    boolean ensureWazeSurfaceOnDisplayBlocking(
            int targetDisplay, String reason, long timeoutMs) {
        if (targetDisplay < 0 || timeoutMs <= 0L
                || android.os.Looper.myLooper() == handler.getLooper()) {
            log("surface_handoff_invalid target=" + targetDisplay
                    + " timeoutMs=" + timeoutMs + " reason=" + safeReason(reason));
            return false;
        }
        SurfaceHandoffStart start = new SurfaceHandoffStart();
        handler.post(() -> {
            start.routeGeneration = wazeRouteGeneration;
            start.required = shouldUseWazeSurface();
            if (start.required && WazeSurfaceActivity.activeTaskId() < 0) {
                log("surface_missing target=" + targetDisplay
                        + " routeGeneration=" + start.routeGeneration
                        + " reason=" + safeReason(reason));
                requestWazeSurfaceActivity(
                        "display-handoff:" + safeReason(reason), true, targetDisplay);
            }
            start.ready.countDown();
        });

        // ponytail: this bounds polling; each ADB command still uses its transport timeout.
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        try {
            if (!start.ready.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                log("surface_handoff_start_timeout target=" + targetDisplay
                        + " reason=" + safeReason(reason));
                return false;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (!start.required) return true;

        long lastRelaunchRequestMs = 0L;
        while (SystemClock.elapsedRealtime() < deadline) {
            int taskId = WazeSurfaceActivity.activeTaskId();
            int actualDisplay = WazeSurfaceActivity.activeDisplayId();
            boolean ready = wazeSurfaceReadyForHandoff(
                    start.routeGeneration == wazeRouteGeneration,
                    wazeSurfaceActive,
                    WazeSurfaceActivity.isVisible(),
                    WazeSurfaceActivity.hasValidSurface(),
                    WazeSurfaceActivity.activeInstanceId(),
                    wazeSurfaceReadyInstanceId,
                    actualDisplay,
                    wazeSurfaceReadyDisplayId,
                    targetDisplay,
                    WazeSurfaceActivity.activeSurfaceEpoch(),
                    wazeSurfaceReadyEpoch);
            int action = wazeSurfaceHandoffAction(
                    start.routeGeneration == wazeRouteGeneration,
                    wazeSurfaceFailureRouteGeneration == start.routeGeneration,
                    wazeSurfaceDismissedForRoute,
                    taskId,
                    actualDisplay,
                    targetDisplay,
                    ready);
            if (action == SURFACE_HANDOFF_NOT_REQUIRED) return true;
            if (action == SURFACE_HANDOFF_FAILED) {
                log("surface_handoff_failed target=" + targetDisplay
                        + " routeGeneration=" + start.routeGeneration
                        + " reason=" + safeReason(reason));
                return false;
            }
            if (action == SURFACE_HANDOFF_READY) {
                log("surface_ready task=" + taskId
                        + " target=" + targetDisplay
                        + " actual=" + actualDisplay
                        + " routeGeneration=" + start.routeGeneration
                        + " reason=" + safeReason(reason));
                return true;
            }
            if (action == SURFACE_HANDOFF_MOVE) {
                invalidateWazeSurfaceReadiness();
                wazeSurfaceDirectChannel.prepareSurfaceHandoff(reason);
                NavAppDisplayState moved = NavAppDisplayController.get(context)
                        .moveTaskIdToDisplayBlocking(
                                WAZE_PACKAGE, taskId, targetDisplay,
                                "surface-handoff:" + safeReason(reason));
                log("surface_move task=" + taskId
                        + " target=" + targetDisplay
                        + " actual=" + moved.displayId
                        + " routeGeneration=" + start.routeGeneration
                        + " reason=" + safeReason(reason));
            } else if (wazeSurfaceHandoffNeedsLaunch(action)) {
                long now = SystemClock.elapsedRealtime();
                if (now - lastRelaunchRequestMs >= 500L) {
                    lastRelaunchRequestMs = now;
                    log((action == SURFACE_HANDOFF_RELAUNCH
                            ? "surface_relaunch" : "surface_restore")
                            + " target=" + targetDisplay
                            + " routeGeneration=" + start.routeGeneration
                            + " reason=" + safeReason(reason));
                    handler.post(() -> {
                        if (start.routeGeneration == wazeRouteGeneration
                                && shouldUseWazeSurface()) {
                            requestWazeSurfaceActivity(
                                    "display-handoff:" + safeReason(reason),
                                    true, targetDisplay);
                        }
                    });
                }
            }
            try {
                Thread.sleep(WAZE_SURFACE_HANDOFF_POLL_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        log("surface_handoff_timeout target=" + targetDisplay
                + " routeGeneration=" + start.routeGeneration
                + " task=" + WazeSurfaceActivity.activeTaskId()
                + " actual=" + WazeSurfaceActivity.activeDisplayId()
                + " reason=" + safeReason(reason));
        return false;
    }

    private void onWazeSurfaceReady(String ownerPackage, int sessionGeneration,
            long activityInstanceId, int displayId, long surfaceEpoch) {
        if (!isCurrentWazeSurfaceCallback(ownerPackage, sessionGeneration)
                || !openLegacyRearmIfFreshSession(
                sessionGeneration, "surface_ready", true)
                || wazeDirectRouteTerminalFence || !shouldUseWazeSurface()) return;
        if (activityInstanceId != WazeSurfaceActivity.activeInstanceId()
                || displayId != WazeSurfaceActivity.activeDisplayId()
                || surfaceEpoch != WazeSurfaceActivity.activeSurfaceEpoch()
                || !WazeSurfaceActivity.hasValidSurface()) {
            log("waze surface ready ignored stale instance=" + activityInstanceId
                    + " display=" + displayId + " epoch=" + surfaceEpoch);
            return;
        }
        wazeSurfaceReadyInstanceId = activityInstanceId;
        wazeSurfaceReadyDisplayId = displayId;
        wazeSurfaceReadyEpoch = surfaceEpoch;
        wazeSurfaceLaunchPending = false;
        if (hasCurrentWazeSurfaceReadyEvidence(displayId, wazeRouteGeneration)) {
            activateWazeSurface(sessionGeneration);
        } else {
            log("waze surface delivered while activity is hidden task="
                    + wazeSurfaceTaskId + " display=" + displayId);
        }
    }

    private boolean hasCurrentWazeSurfaceReadyEvidence(
            int targetDisplay, int routeGeneration) {
        return wazeSurfaceReadyForHandoff(
                routeGeneration == wazeRouteGeneration
                        && wazeSurfaceRouteGeneration == routeGeneration,
                true,
                WazeSurfaceActivity.isVisible(),
                WazeSurfaceActivity.hasValidSurface(),
                WazeSurfaceActivity.activeInstanceId(),
                wazeSurfaceReadyInstanceId,
                WazeSurfaceActivity.activeDisplayId(),
                wazeSurfaceReadyDisplayId,
                targetDisplay,
                WazeSurfaceActivity.activeSurfaceEpoch(),
                wazeSurfaceReadyEpoch);
    }

    private void activateWazeSurface(int sessionGeneration) {
        if (wazeSurfaceActive
                || !hasCurrentWazeSurfaceReadyEvidence(
                        WazeSurfaceActivity.activeDisplayId(), wazeRouteGeneration)) return;
        handler.removeCallbacks(wazeSurfaceReadyTimeout);
        wazeSurfaceLaunchPending = false;
        wazeSurfaceActive = true;
        wazeSurfaceVisible = true;
        wazeSurfaceFailureRouteGeneration = -1;
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

    private void invalidateWazeSurfaceReadiness() {
        wazeSurfaceActive = false;
        wazeSurfaceReadyInstanceId = 0L;
        wazeSurfaceReadyDisplayId = -1;
        wazeSurfaceReadyEpoch = 0L;
    }

    private void onWazeSurfaceUnavailable(String ownerPackage, int sessionGeneration,
            String reason) {
        if (!isCurrentWazeSurfaceCallback(ownerPackage, sessionGeneration)) return;
        if (isTransientWazeSurfaceUnavailable(reason, shouldUseWazeSurface())) {
            invalidateWazeSurfaceReadiness();
            suspendWazeSurface("surface-rebinding:" + safeReason(reason));
            return;
        }
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
        onWazeSpeedLimitEvent(context, displayValue, unit, eventElapsedMs, 0L, 0);
    }

    static void onWazeSpeedLimitEvent(
            Context context, int displayValue, String unit, long eventElapsedMs,
            long bridgeGeneration, int bridgeCapabilities) {
        NavHudLiveSender current;
        synchronized (NavHudLiveSender.class) {
            current = instance;
        }
        if (current != null) {
            current.handler.post(() -> current.onDirectSpeedLimitEvent(
                    WAZE_PACKAGE, displayValue, -1, unit, eventElapsedMs,
                    bridgeGeneration, bridgeCapabilities));
        } else if (shouldRetainWazeSpeedWithoutSender(bridgeCapabilities)) {
            DirectSpeedLimitStore.update(
                    WAZE_PACKAGE, displayValue, -1, unit, eventElapsedMs);
        } else {
            DirectSpeedLimitStore.clear(WAZE_PACKAGE);
        }
    }

    static boolean shouldRetainWazeSpeedWithoutSender(int bridgeCapabilities) {
        return (bridgeCapabilities & WAZE_CAP_SPEED_LIMIT_HEARTBEAT) == 0;
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
        wazeSurfaceFailureRouteGeneration = wazeRouteGeneration;
        wazeSurfaceLaunchPending = false;
        invalidateWazeSurfaceReadiness();
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
        invalidateWazeSurfaceReadiness();
        wazeSurfaceVisible = false;
        wazeSurfaceEnabledForRoute = false;
        wazeSurfaceDismissedForRoute = false;
        wazeSurfaceTaskId = -1;
        wazeSurfaceInstanceId = 0L;
        wazeSurfaceRouteGeneration = -1;
        wazeSurfaceFailureRouteGeneration = -1;
        WazeSurfaceActivity.finishActive(reason);
    }

    private void onWazeDirectFrame(String ownerPackage, int sessionGeneration,
            DirectTbtFrame frame, String reason, boolean fromSurface, int sourceGeneration,
            WazeRouteTiming.Frame timing) {
        long callbackEntryElapsedMs = SystemClock.elapsedRealtime();
        if (timing != null) timing.markListenerCallback(callbackEntryElapsedMs);
        if (!isCurrentWazeDirectCallback(ownerPackage, sessionGeneration)
                || (fromSurface && !isCurrentWazeSurfaceCallback(ownerPackage, sourceGeneration))
                || !wazeDirectNavigating || frame == null
                || !shouldAcceptWazeFrameAfterTerminalForTest(
                wazeDirectRouteTerminalFence, true)) {
            log("waze direct frame ignored active=" + active
                    + " navigating=" + wazeDirectNavigating
                    + " reason=" + safeReason(reason));
            return;
        }
        long now = SystemClock.elapsedRealtime();
        wazeDirectHandshakeAvailable = true;
        wazeDirectFrameReceived = true;
        wazeDirectRouteEnded = false;
        updateCaptureIngressPolicy();
        cancelWazeDirectColdTimeout();
        cancelWazeFallbackReadiness();
        NavRouteStateStore.get(context).updateFromVisualRouteEvidence(
                WAZE_PACKAGE, "waze_direct", safeReason(reason), now);
        WazeRouteTracker.get(context).onDirectRouteEvidence(
                "direct:" + safeReason(reason), now);
        DirectTbtFrame outputFrame = applySpeedLimitOverlay(
                ownerPackage, frame, now);
        int sourceDistanceMeters = outputFrame.getDistanceMeters();
        outputFrame = effectiveDirectFrame(outputFrame);
        long tbtDispatchElapsedMs = WazeRouteTiming.UNSET;
        long hudDispatchElapsedMs = WazeRouteTiming.UNSET;
        boolean tbtDispatched = false;
        boolean hudDispatched = false;
        boolean firstTbtDispatch = false;
        boolean firstHudDispatch = false;
        boolean firstRouteEvidence = wazeTbtRouteStartedAtMs <= 0L;
        if (firstRouteEvidence) wazeTbtRouteStartedAtMs = now;
        advanceTbtLifecycleForFirstFrame();
        boolean hudOwner = isHudOutputOwner(ownerPackage);
        boolean semanticTbt = shouldDispatchSemanticTbtForDirectReason(reason);
        if (semanticTbt) {
            if (!manualTbtActive && shouldClaimTbtOwnerForFrameForTest(
                    tbtPublisher.isRouteActive(),
                    ownerPackage.equals(tbtPublisher.ownerPackage()),
                    hudOwner, firstRouteEvidence,
                    isHudOutputOwner(tbtPublisher.ownerPackage()))) {
                tbtPublisher.beginRoute(ownerPackage, sessionGeneration,
                        shouldRequestDashboardForDirectRouteForTest(hudOwner,
                                HudPrefs.isSwitchToTbtOnHudStartEnabled(context)), hudOwner,
                        "waze-frame:" + safeReason(reason));
            }
            if (!manualTbtActive) {
                tbtPublisher.updateOwnerHudPriority(
                        ownerPackage, sessionGeneration, hudOwner);
                tbtPublisher.publishFrame(
                        ownerPackage, sessionGeneration, outputFrame,
                        "waze-frame:" + safeReason(reason));
                tbtDispatched = true;
                tbtDispatchElapsedMs = SystemClock.elapsedRealtime();
                if (timing != null) {
                    firstTbtDispatch = timing.markFirstTbtDispatch(
                            tbtDispatchElapsedMs);
                }
            } else {
                tbtPublisher.recordDeferredFrame(
                        ownerPackage, sessionGeneration, outputFrame,
                        "waze-frame:" + safeReason(reason));
            }
        } else {
            log("waze visual-only frame reason=" + safeReason(reason));
        }
        long receivedWallClockMs = System.currentTimeMillis();
        logWazeDirectFrame(frame, outputFrame, sourceDistanceMeters,
                reason, now, receivedWallClockMs,
                DirectTbtPayload.Options.from(context));
        if (!isHudOutputOwner(ownerPackage)) {
            if (timing != null && firstTbtDispatch) {
                log(timing.directLine("first_tbt_dispatch"));
            }
            logWazeDirectTiming(timing, tbtDispatchElapsedMs,
                    hudDispatchElapsedMs, tbtDispatched, hudDispatched);
            log("waze source=tbt-only reason=" + safeReason(reason));
            return;
        }
        hudOutput.publishDirect(
                outputFrame, reason, now, ownerPackage, sessionGeneration);
        hudDispatched = true;
        hudDispatchElapsedMs = SystemClock.elapsedRealtime();
        if (timing != null) {
            firstHudDispatch = timing.markFirstHudDispatch(hudDispatchElapsedMs);
        }
        hudOutput.selectNavigationSource(
                HudOutputCoordinator.Source.DIRECT,
                "waze-direct-frame:" + safeReason(reason),
                ownerPackage, sessionGeneration);
        if (wazeFallbackActive) {
            WazeCropCapture.get(context).stop("direct-recovered");
            resetLatestPayload();
        }
        wazeFallbackActive = false;
        updateCaptureIngressPolicy();
        if (timing != null && (firstTbtDispatch || firstHudDispatch)) {
            log(timing.directLine("first_dispatch"));
        }
        logWazeDirectTiming(timing, tbtDispatchElapsedMs,
                hudDispatchElapsedMs, tbtDispatched, hudDispatched);
        String sourceLine = "waze source=direct reason=" + safeReason(reason);
        Log.i(TAG, sourceLine);
        WazeCaptureDebugWriter.get().appEvent(context, "nav_live " + sourceLine);
    }

    private void logWazeDirectTiming(WazeRouteTiming.Frame timing,
            long tbtDispatchElapsedMs, long hudDispatchElapsedMs,
            boolean tbtDispatched, boolean hudDispatched) {
        if (timing == null || !timing.shouldLog(
                HudPrefs.isDetailedDebugArtifactsEnabled(context),
                tbtDispatchElapsedMs, hudDispatchElapsedMs)) {
            return;
        }
        log(timing.line(tbtDispatchElapsedMs, hudDispatchElapsedMs,
                tbtDispatched, hudDispatched));
    }

    private void logWazeDirectFrame(DirectTbtFrame rawFrame, DirectTbtFrame frame,
                                    int sourceDistanceMeters, String reason,
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
                + " distanceM=" + sourceDistanceMeters
                + " textMode=" + HudPrefs.transliterationMode(context)
                + " rawRoad=\"" + normalizeString(rawFrame.getRoadText()) + "\""
                + " rawCue=\"" + normalizeString(rawFrame.getCueText()) + "\""
                + " sentRoad=\"" + normalizeString(frame.getRoadText()) + "\""
                + " sentCue=\"" + normalizeString(frame.getCueText()) + "\""
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
        if ("hello-producer-replaced".equals(reason)) {
            hudOutput.clearDirectFrameForSupersedingSession(
                    ownerPackage, sessionGeneration,
                    "gmaps-producer-replaced", SystemClock.elapsedRealtime());
        }
        if (!isCurrentGMapsDirectCallback(ownerPackage, sessionGeneration)) return;
        gmapsDirectHandshakeAvailable = true;
        updateCaptureIngressPolicy();
        gmapsDirectTimeoutSessionGeneration = sessionGeneration;
        log("gmaps direct handshake available reason=" + safeReason(reason));
    }

    private void onGMapsDirectHandshakeUnavailable(String ownerPackage,
            long sessionGeneration, String reason) {
        if (!isCurrentGMapsDirectCallback(ownerPackage, sessionGeneration)
                || gmapsDirectRouteEnded) return;
        gmapsDirectHandshakeAvailable = false;
        updateCaptureIngressPolicy();
        gmapsDirectTimeoutSessionGeneration = sessionGeneration;
        if (isHudOutputOwner(ownerPackage)) {
            hudOutput.clearDirectFrameForLoss(
                    ownerPackage, sessionGeneration,
                    "gmaps-direct-unavailable:" + safeReason(reason),
                    SystemClock.elapsedRealtime());
        }
        if (!isHudOutputOwner(ownerPackage)) {
            log("gmaps tbt observer unavailable reason=" + safeReason(reason));
            return;
        }
        gmapsDirectFrameReceived = false;
        updateCaptureIngressPolicy();
        if (gmapsDirectTimedOut) {
            activateGMapsLegacyFallbackIfReady("handshake-unavailable");
        } else if (!gmapsDirectTimeoutScheduled) {
            scheduleGMapsDirectTimeout();
        }
        log("gmaps direct handshake unavailable reason=" + safeReason(reason));
    }

    private void onGMapsDirectNavigationStarted(String ownerPackage,
            long sessionGeneration, String reason) {
        hudOutput.clearDirectFrameForSupersedingSession(
                ownerPackage, sessionGeneration,
                "gmaps-route-superseded:" + safeReason(reason),
                SystemClock.elapsedRealtime());
        if (!isCurrentGMapsDirectCallback(ownerPackage, sessionGeneration)) return;
        ensureGMapsDirectSession("navigation-started:" + safeReason(reason));
        eventGMapsDirectSession("navigation_started", reason);
        logRouteStartOutputPreferences("gmaps", sessionGeneration, reason);
        ++tbtLifecycleToken;
        gmapsTbtRouteStartedAtMs = SystemClock.elapsedRealtime();
        if (shouldClearGMapsSpeedLimitOnDirectStart(reason)) {
            clearDirectSpeedLimit(ownerPackage);
        }
        gmapsDirectTimeoutSessionGeneration = sessionGeneration;
        boolean keepFallbackUntilFrame = shouldKeepGMapsFallbackOnDirectStart(
                gmapsDirectFallbackActive);
        gmapsDirectRouteEnded = false;
        gmapsDirectHandshakeAvailable = true;
        gmapsDirectFrameReceived = false;
        gmapsDirectTimedOut = false;
        gmapsLegacyUnavailableLogged = false;
        gmapsDirectRegistrationSuppressed = false;
        updateCaptureIngressPolicy();
        lastGMapsDirectRegistrationProbeMs = 0L;
        boolean hudOwner = isHudOutputOwner(ownerPackage);
        if (!manualTbtActive) {
            tbtPublisher.beginRoute(ownerPackage, sessionGeneration,
                    shouldRequestDashboardForDirectRouteForTest(hudOwner,
                            HudPrefs.isSwitchToTbtOnHudStartEnabled(context)), hudOwner,
                    "gmaps-navigation-started:" + safeReason(reason));
        } else {
            tbtPublisher.recordDeferredLifecycle(
                    ownerPackage, sessionGeneration, "start",
                    "gmaps-navigation-started:" + safeReason(reason));
        }
        if (hudOwner && !keepFallbackUntilFrame) {
            hudOutput.selectNavigationSource(
                    HudOutputCoordinator.Source.DIRECT,
                    "gmaps-navigation-started:" + safeReason(reason),
                    ownerPackage, sessionGeneration);
        }
        if (hudOwner) scheduleGMapsDirectTimeout();
        log("gmaps direct navigation started reason=" + safeReason(reason));
    }

    private void onGMapsDirectFrame(String ownerPackage, long sessionGeneration,
            DirectTbtFrame frame, String reason,
            GMapsDirectChannel.BitmapSelection bitmapSelection,
            GMapsTimingDiagnostics.Frame timing) {
        if (!isCurrentGMapsDirectCallback(ownerPackage, sessionGeneration)
                || gmapsDirectRouteEnded || frame == null) {
            return;
        }
        long callbackEntryElapsedMs = SystemClock.elapsedRealtime();
        long tbtDispatchElapsedMs = -1L;
        long hudDispatchElapsedMs = -1L;
        boolean tbtDispatched = false;
        boolean hudDispatched = false;
        gmapsDirectTimeoutSessionGeneration = sessionGeneration;
        long now = SystemClock.elapsedRealtime();
        gmapsDirectFrameReceived = true;
        gmapsDirectHandshakeAvailable = true;
        gmapsDirectFallbackActive = false;
        gmapsDirectTimedOut = false;
        gmapsLegacyUnavailableLogged = false;
        gmapsDirectRegistrationSuppressed = false;
        updateCaptureIngressPolicy();
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
        int sourceDistanceMeters = outputFrame.getDistanceMeters();
        outputFrame = effectiveDirectFrame(outputFrame);
        boolean firstRouteEvidence = gmapsTbtRouteStartedAtMs <= 0L;
        if (firstRouteEvidence) gmapsTbtRouteStartedAtMs = now;
        advanceTbtLifecycleForFirstFrame();
        boolean hudOwner = isHudOutputOwner(ownerPackage);
        boolean semanticTbt = shouldDispatchSemanticTbtForDirectReason(reason);
        if (semanticTbt) {
            if (!manualTbtActive && shouldClaimTbtOwnerForFrameForTest(
                    tbtPublisher.isRouteActive(),
                    ownerPackage.equals(tbtPublisher.ownerPackage()),
                    hudOwner, firstRouteEvidence,
                    isHudOutputOwner(tbtPublisher.ownerPackage()))) {
                tbtPublisher.beginRoute(ownerPackage, sessionGeneration,
                        shouldRequestDashboardForDirectRouteForTest(hudOwner,
                                HudPrefs.isSwitchToTbtOnHudStartEnabled(context)), hudOwner,
                        "gmaps-frame:" + safeReason(reason));
            }
            if (!manualTbtActive) {
                tbtPublisher.updateOwnerHudPriority(
                        ownerPackage, sessionGeneration, hudOwner);
                tbtPublisher.publishFrame(
                        ownerPackage, sessionGeneration, outputFrame,
                        "gmaps-frame:" + safeReason(reason));
                tbtDispatched = true;
                tbtDispatchElapsedMs = SystemClock.elapsedRealtime();
            } else {
                tbtPublisher.recordDeferredFrame(
                        ownerPackage, sessionGeneration, outputFrame,
                        "gmaps-frame:" + safeReason(reason));
            }
        } else {
            log("gmaps visual-only frame reason=" + safeReason(reason));
        }
        logGMapsDirectFrame(frame, outputFrame, sourceDistanceMeters, reason, now);
        if (!isHudOutputOwner(ownerPackage)) {
            logGMapsDirectTiming(timing, callbackEntryElapsedMs,
                    tbtDispatchElapsedMs, hudDispatchElapsedMs,
                    tbtDispatched, hudDispatched);
            log("gmaps source=tbt-only reason=" + safeReason(reason));
            return;
        }
        hudOutput.publishDirect(
                outputFrame, reason, now, bitmapSelection, this::logGMapsDirectChannelEvent,
                ownerPackage, sessionGeneration);
        hudDispatched = true;
        hudDispatchElapsedMs = SystemClock.elapsedRealtime();
        hudOutput.selectNavigationSource(
                HudOutputCoordinator.Source.DIRECT,
                "gmaps-direct-frame:" + safeReason(reason),
                ownerPackage, sessionGeneration);
        scheduleGMapsDirectTimeout();
        logGMapsDirectTiming(timing, callbackEntryElapsedMs,
                tbtDispatchElapsedMs, hudDispatchElapsedMs,
                tbtDispatched, hudDispatched);
        log("gmaps source=direct reason=" + safeReason(reason));
    }

    private void logGMapsDirectTiming(GMapsTimingDiagnostics.Frame timing,
            long callbackEntryElapsedMs, long tbtDispatchElapsedMs,
            long hudDispatchElapsedMs, boolean tbtDispatched,
            boolean hudDispatched) {
        if (timing == null || !timing.shouldLog(
                HudPrefs.isDetailedDebugArtifactsEnabled(context),
                callbackEntryElapsedMs, tbtDispatchElapsedMs,
                hudDispatchElapsedMs)) {
            return;
        }
        logGMapsDirectChannelEvent("timing " + timing.dispatchLine(
                callbackEntryElapsedMs, tbtDispatchElapsedMs,
                hudDispatchElapsedMs, tbtDispatched, hudDispatched));
    }

    private void logGMapsDirectFrame(DirectTbtFrame rawFrame, DirectTbtFrame frame,
            int sourceDistanceMeters, String reason, long receivedAtMs) {
        DirectTbtPayload.Prepared prepared = DirectTbtPayload.prepare(
                frame, DirectTbtPayload.Options.from(context));
        String raw = "reason=" + safeReason(reason)
                + " receivedAtElapsedMs=" + receivedAtMs
                + " rawType=" + frame.getRawManeuverType()
                + " amap=" + frame.getAmapManeuver()
                + " byd=" + frame.getBydManeuver()
                + " distanceM=" + sourceDistanceMeters
                + " textMode=" + HudPrefs.transliterationMode(context)
                + " rawRoad=\"" + normalizeString(rawFrame.getRoadText()) + "\""
                + " rawCue=\"" + normalizeString(rawFrame.getCueText()) + "\""
                + " sentRoad=\"" + normalizeString(frame.getRoadText()) + "\""
                + " sentCue=\"" + normalizeString(frame.getCueText()) + "\""
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
        onDirectSpeedLimitEvent(
                ownerPackage, displayValue, kph, unit, eventElapsedMs, 0L, 0);
    }

    private void onDirectSpeedLimitEvent(String ownerPackage, int displayValue,
            int kph, String unit, long eventElapsedMs,
            long bridgeGeneration, int bridgeCapabilities) {
        boolean changed = DirectSpeedLimitStore.update(
                ownerPackage, displayValue, kph, unit, eventElapsedMs);
        if (WAZE_PACKAGE.equals(ownerPackage)) {
            updateWazeSpeedLimitExpiry(
                    displayValue, eventElapsedMs, bridgeGeneration, bridgeCapabilities);
        }
        DirectTbtFrame.SpeedLimit speed = DirectSpeedLimitStore.snapshot(ownerPackage);
        String line = "speed_limit owner=" + normalizeString(ownerPackage)
                + " value=" + speed.getDisplayValue()
                + " kph=" + speed.getKph()
                + " unit=" + speed.getUnit()
                + " changed=" + changed
                + " bridgeGeneration=" + bridgeGeneration
                + " bridgeCapabilities=" + bridgeCapabilities
                + " sourceElapsedMs=" + eventElapsedMs
                + " latencyMs=" + Math.max(
                0L, SystemClock.elapsedRealtime() - eventElapsedMs);
        if (WAZE_PACKAGE.equals(ownerPackage)) eventWazeDirectSession("speed_limit", line);
        else eventGMapsDirectSession("speed_limit", line);
        Log.i(TAG, line);
        if (changed) republishLatestDirectFrame(ownerPackage, "speed-limit-event");
    }

    private void updateWazeSpeedLimitExpiry(int displayValue, long eventElapsedMs,
            long bridgeGeneration, int bridgeCapabilities) {
        if (!shouldArmWazeSpeedLimitExpiry(displayValue, bridgeCapabilities)) {
            cancelWazeSpeedLimitExpiry();
            return;
        }
        handler.removeCallbacks(wazeSpeedLimitExpiry);
        wazeSpeedLimitBridgeGeneration = bridgeGeneration;
        wazeSpeedLimitEventElapsedMs = eventElapsedMs;
        wazeSpeedLimitExpiryAtMs = SystemClock.elapsedRealtime()
                + WAZE_SPEED_LIMIT_EXPIRY_MS;
        handler.postDelayed(wazeSpeedLimitExpiry, WAZE_SPEED_LIMIT_EXPIRY_MS);
    }

    static boolean shouldArmWazeSpeedLimitExpiry(
            int displayValue, int bridgeCapabilities) {
        return displayValue > 0
                && (bridgeCapabilities & WAZE_CAP_SPEED_LIMIT_HEARTBEAT) != 0;
    }

    private void cancelWazeSpeedLimitExpiry() {
        handler.removeCallbacks(wazeSpeedLimitExpiry);
        wazeSpeedLimitExpiryAtMs = 0L;
        wazeSpeedLimitBridgeGeneration = 0L;
        wazeSpeedLimitEventElapsedMs = 0L;
    }

    private void onWazeSpeedLimitExpiry() {
        long now = SystemClock.elapsedRealtime();
        if (wazeSpeedLimitExpiryAtMs <= 0L) return;
        if (now < wazeSpeedLimitExpiryAtMs) {
            handler.postDelayed(wazeSpeedLimitExpiry, wazeSpeedLimitExpiryAtMs - now);
            return;
        }
        long bridgeGeneration = wazeSpeedLimitBridgeGeneration;
        long eventElapsedMs = wazeSpeedLimitEventElapsedMs;
        cancelWazeSpeedLimitExpiry();
        DirectSpeedLimitStore.clear(WAZE_PACKAGE);
        if (WAZE_PACKAGE.equals(speedOverlayOwner)) resetSpeedOverlayState();
        republishLatestDirectFrame(WAZE_PACKAGE, "speed-limit-heartbeat-expired");
        log("waze speed limit heartbeat expired bridgeGeneration=" + bridgeGeneration
                + " eventElapsedMs=" + eventElapsedMs);
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
                    latestGMapsDirectFrame, reason, latestGMapsBitmapSelection, null);
        }
    }

    private DirectTbtFrame effectiveDirectFrame(DirectTbtFrame frame) {
        DirectTbtFrame effective = HudDisplayPolicy.applyActiveFrame(
                frame, HudPrefs.isSmallDistanceClampEnabled(context));
        return HudTextTransliterator.transformFrame(
                effective, HudPrefs.transliterationMode(context));
    }

    private void selectRemainingTbtRoute(String endedPackage, String reason) {
        if (tbtPublisher.isRouteActive()) return;
        String ended = normalizePackage(endedPackage);
        DirectTbtFrame wazeFrame = latestRestorableWazeFrame();
        boolean wazeAvailable = !WAZE_PACKAGE.equals(ended)
                && wazeDirectNavigating
                && !wazeDirectRouteEnded
                && !wazeDirectRouteTerminalFence
                && wazeDirectChannel.isActive();
        boolean gmapsAvailable = !GMapsDirectChannel.PACKAGE_NAME.equals(ended)
                && !gmapsDirectRouteEnded
                && gmapsDirectChannel.isRunning()
                && gmapsDirectChannel.isNavigating();
        long wazeStartedAt = WAZE_PACKAGE.equals(ended) ? Long.MIN_VALUE
                : wazeTbtRouteStartedAtMs;
        long gmapsStartedAt = GMapsDirectChannel.PACKAGE_NAME.equals(ended) ? Long.MIN_VALUE
                : gmapsTbtRouteStartedAtMs;
        String next = selectRemainingTbtOwnerForTest(
                ended, wazeAvailable, gmapsAvailable, activePackage,
                wazeStartedAt, gmapsStartedAt);
        if (WAZE_PACKAGE.equals(next)) {
            int generation = wazeDirectChannel.sessionGeneration();
            boolean hudOwner = isHudOutputOwner(next);
            tbtPublisher.beginRoute(next, generation, false, hudOwner,
                    "restore:" + safeReason(reason));
            if (wazeFrame != null) {
                tbtPublisher.publishFrame(next, generation,
                        effectiveDirectFrame(applySpeedLimitOverlay(
                                next, wazeFrame, SystemClock.elapsedRealtime())),
                        "restore:" + safeReason(reason));
            }
        } else if (GMapsDirectChannel.PACKAGE_NAME.equals(next)) {
            long generation = gmapsDirectChannel.sessionGeneration();
            boolean hudOwner = isHudOutputOwner(next);
            tbtPublisher.beginRoute(next, generation, false, hudOwner,
                    "restore:" + safeReason(reason));
            if (latestGMapsDirectFrame != null
                    && latestGMapsDirectFrameSessionGeneration == generation) {
                tbtPublisher.publishFrame(next, generation,
                        effectiveDirectFrame(applySpeedLimitOverlay(
                                next, latestGMapsDirectFrame,
                                SystemClock.elapsedRealtime())),
                        "restore:" + safeReason(reason));
            }
        }
        if (!next.isEmpty()) {
            ++tbtLifecycleToken;
            log("tbt owner resumed package=" + next + " reason=" + safeReason(reason));
        }
    }

    private DirectTbtFrame latestRestorableWazeFrame() {
        return selectWazeRestoreFrameForTest(
                wazeSurfaceActive,
                latestWazeSurfaceFrame,
                latestWazeSurfaceFrameSessionGeneration
                        == wazeSurfaceDirectChannel.sessionGeneration(),
                latestWazeClusterFrame,
                latestWazeClusterFrameSessionGeneration
                        == wazeDirectChannel.sessionGeneration());
    }

    static DirectTbtFrame selectWazeRestoreFrameForTest(
            boolean surfaceActive, DirectTbtFrame surfaceFrame, boolean surfaceCurrent,
            DirectTbtFrame clusterFrame, boolean clusterCurrent) {
        if (surfaceActive && surfaceCurrent && surfaceFrame != null) return surfaceFrame;
        return clusterCurrent ? clusterFrame : null;
    }

    static String selectRemainingTbtOwnerForTest(
            String endedPackage, boolean wazeAvailable, boolean gmapsAvailable,
            String hudOwnerPackage, long wazeStartedAtMs, long gmapsStartedAtMs) {
        String ended = normalizePackage(endedPackage);
        String hud = normalizePackage(hudOwnerPackage);
        if (WAZE_PACKAGE.equals(hud) && wazeAvailable && !WAZE_PACKAGE.equals(ended)) {
            return WAZE_PACKAGE;
        }
        if (GMapsDirectChannel.PACKAGE_NAME.equals(hud)
                && gmapsAvailable && !GMapsDirectChannel.PACKAGE_NAME.equals(ended)) {
            return GMapsDirectChannel.PACKAGE_NAME;
        }
        if (wazeAvailable && gmapsAvailable) {
            return gmapsStartedAtMs > wazeStartedAtMs
                    ? GMapsDirectChannel.PACKAGE_NAME : WAZE_PACKAGE;
        }
        if (wazeAvailable && !WAZE_PACKAGE.equals(ended)) return WAZE_PACKAGE;
        if (gmapsAvailable && !GMapsDirectChannel.PACKAGE_NAME.equals(ended)) {
            return GMapsDirectChannel.PACKAGE_NAME;
        }
        return "";
    }

    private void clearDirectSpeedLimit(String ownerPackage) {
        DirectSpeedLimitStore.clear(ownerPackage);
        if (WAZE_PACKAGE.equals(ownerPackage)) cancelWazeSpeedLimitExpiry();
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

    private void onGMapsDirectNavigationEnded(String ownerPackage,
            long routeGeneration, long callbackGeneration,
            String reason, long detectedAtMs) {
        if (!acceptsGMapsTeardownForTest(
                isCurrentGMapsDirectCallback(ownerPackage, callbackGeneration),
                routeGeneration, callbackGeneration)) return;
        if (manualTbtActive) {
            tbtPublisher.recordDeferredLifecycle(
                    ownerPackage, callbackGeneration, "end",
                    "gmaps-navigation-ended:" + safeReason(reason));
        }
        boolean hudOwner = isHudOutputOwner(GMapsDirectChannel.OWNER_PACKAGE);
        boolean lifecycleOwnsClear = (sourceSwitchInProgress || stopInProgress)
                && GMapsDirectChannel.PACKAGE_NAME.equals(activePackage);
        long teardownToken = lifecycleOwnsClear
                ? tbtLifecycleToken : ++tbtLifecycleToken;
        Runnable finishTbt = () -> handler.post(() -> {
            tbtPublisher.endRoute(
                    ownerPackage, routeGeneration,
                    "navigation-ended:" + safeReason(reason));
            selectRemainingTbtRoute(ownerPackage, "gmaps-route-ended");
            if (!tbtPublisher.isRouteActive()) {
                confirmTbtTeardown(GMapsDirectChannel.PACKAGE_NAME, teardownToken);
            }
        });
        if (lifecycleOwnsClear) {
            log("gmaps route end deferred to lifecycle clear");
        } else if (hudOwner) {
            hudOutput.endNavigationOutput(
                    ownerPackage, routeGeneration,
                    "gmaps-direct-ended:" + safeReason(reason), detectedAtMs, finishTbt);
        } else {
            finishTbt.run();
        }
        clearDirectSpeedLimit(GMapsDirectChannel.PACKAGE_NAME);
        cancelGMapsDirectTimeout();
        gmapsDirectFrameReceived = false;
        gmapsDirectFallbackActive = false;
        gmapsDirectRouteEnded = true;
        gmapsTbtRouteStartedAtMs = 0L;
        lastGMapsDirectRegistrationProbeMs = 0L;
        if (hudOwner) resetLatestPayload();
        NavRouteStateStore.get(context).markRouteEnded(
                GMapsDirectChannel.PACKAGE_NAME,
                "gmaps-direct-ended:" + safeReason(reason),
                detectedAtMs);
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
        gmapsDirectHandshakeAvailable = false;
        gmapsDirectFrameReceived = false;
        gmapsDirectFallbackActive = false;
        gmapsDirectTimedOut = false;
        gmapsLegacyUnavailableLogged = false;
        gmapsDirectRouteEnded = false;
        gmapsDirectRegistrationSuppressed = false;
        gmapsTbtRouteStartedAtMs = 0L;
        lastGMapsDirectRegistrationProbeMs = 0L;
        latestGMapsDirectFrame = null;
        latestGMapsDirectFrameReason = "";
        latestGMapsDirectFrameSessionGeneration = 0L;
        latestGMapsBitmapSelection = null;
        updateCaptureIngressPolicy();
    }

    private void activateGMapsLegacyFallbackIfReady(String reason) {
        boolean captureReady = NavRuntimePermissionStatus.check(context).readyForCapture();
        if (shouldDeactivateGMapsLegacyFallback(gmapsDirectFallbackActive, captureReady)) {
            gmapsDirectFallbackActive = false;
            updateCaptureIngressPolicy();
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
        legacyFallbackOwnerPackage = GMapsDirectChannel.PACKAGE_NAME;
        updateCaptureIngressPolicy();
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

    static boolean shouldClearGMapsSpeedLimitOnDirectStart(String reason) {
        return "start".equals(reason) || "frame-missed-start".equals(reason);
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
        // GMaps direct IPC is an app-to-app channel.  SOME/IP/HUD binding must
        // not gate producer liveness or re-registration while HUD output is off
        // or temporarily unavailable.
        if (gmapsDirectRegistrationSuppressed) {
            log("gmaps direct registration resumed reason="
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

    private void onWazeDirectNavigationEnded(String ownerPackage, int routeGeneration,
            int callbackGeneration, String reason, long detectedAtMs) {
        if (!isCurrentWazeDirectCallback(ownerPackage, callbackGeneration)
                || !acceptsWazeTeardownForTest(routeGeneration, callbackGeneration)) {
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
        wazeDirectRouteTerminalFence = true;
        if (terminal != null && terminal.snapshot != null
                && terminal.snapshot.bridgeGeneration
                > wazeDirectTerminalBridgeGeneration) {
            wazeDirectTerminalBridgeGeneration = terminal.snapshot.bridgeGeneration;
        }
        wazeDirectChannel.noteRouteTerminalGeneration(
                "direct-navigation-ended:" + safeReason(reason),
                wazeDirectTerminalBridgeGeneration);
        wazeSurfaceDirectChannel.noteRouteTerminalGeneration(
                "direct-navigation-ended:" + safeReason(reason),
                wazeDirectTerminalBridgeGeneration);
        if (manualTbtActive) {
            tbtPublisher.recordDeferredLifecycle(
                    ownerPackage, callbackGeneration, "end",
                    "waze-navigation-ended:" + safeReason(reason));
        }
        log("waze direct navigation ended main_handoff_ms="
                + Math.max(0L, SystemClock.elapsedRealtime() - detectedAtMs)
                + " reason=" + safeReason(reason));
        boolean hudOwner = isHudOutputOwner(ownerPackage);
        boolean lifecycleOwnsClear = (sourceSwitchInProgress || stopInProgress)
                && ownerPackage.equals(activePackage);
        long teardownToken = lifecycleOwnsClear
                ? tbtLifecycleToken : ++tbtLifecycleToken;
        Runnable finishTbt = () -> handler.post(() -> {
            tbtPublisher.endRoute(ownerPackage, routeGeneration,
                    "navigation-ended:" + safeReason(reason));
            selectRemainingTbtRoute(ownerPackage, "waze-route-ended");
            if (!tbtPublisher.isRouteActive()) {
                confirmTbtTeardown(WAZE_PACKAGE, teardownToken);
            }
        });
        if (lifecycleOwnsClear) {
            log("waze route end deferred to lifecycle clear");
        } else if (hudOwner) {
            hudOutput.endNavigationOutput(
                    ownerPackage, routeGeneration,
                    "waze-direct-ended:" + safeReason(reason), detectedAtMs, finishTbt);
        } else {
            finishTbt.run();
        }
        clearDirectSpeedLimit(WAZE_PACKAGE);
        closeWazeSurface("navigation-ended:" + safeReason(reason));
        wazeSurfaceDirectChannel.stop("route-terminal:" + safeReason(reason));
        cancelWazeDirectColdTimeout();
        cancelWazeFallbackReadiness();
        wazeDirectNavigating = false;
        wazeDirectFrameReceived = false;
        wazeDirectRouteEnded = true;
        wazeTbtRouteStartedAtMs = 0L;
        wazeRouteGeneration++;
        wazeFallbackActive = false;
        WazeCropCapture.get(context).stop("direct-navigation-ended");
        if (hudOwner) resetLatestPayload();
        long now = SystemClock.elapsedRealtime();
        NavRouteStateStore.get(context).markRouteEnded(
                WAZE_PACKAGE, "direct-navigation-ended", now);
        WazeRouteTracker.get(context).onRouteEnded("direct-navigation-ended", now);
        if (bridgeSupported) {
            if (!terminal.accepted && !terminal.snapshot.active) {
                log("waze route lifecycle terminal already recorded reason="
                        + terminal.reason);
            } else {
                log("waze route lifecycle terminal persisted=" + terminal.accepted
                        + " stateChanged=" + terminal.changed
                        + " reason=" + terminal.reason);
            }
        }
        tbtWazeObserver = false;
        wazeDirectChannel.stop("route-terminal:" + safeReason(reason));
        log("waze source=waiting_direct routeEnded=true reason=" + safeReason(reason));
    }

    private void closeWazeForSupersedingInactiveLifecycle(
            long detectedAtMs, String reason) {
        invalidatePendingWazeDirectFrames();
        int sessionGeneration = wazeDirectChannel.sessionGeneration();
        if (isCurrentWazeDirectCallback(WAZE_PACKAGE, sessionGeneration)) {
            endWazeDirectSession(reason);
            onWazeDirectNavigationEnded(
                    WAZE_PACKAGE, sessionGeneration, sessionGeneration,
                    reason, detectedAtMs);
            return;
        }
        if (tbtPublisher.isRouteActive()
                && WAZE_PACKAGE.equals(normalizePackage(tbtPublisher.ownerPackage()))) {
            tbtPublisher.endRoute(
                    WAZE_PACKAGE,
                    tbtOwnerGeneration(WAZE_PACKAGE, sessionGeneration),
                    reason);
            selectRemainingTbtRoute(WAZE_PACKAGE, reason);
        }
        wazeDirectRouteTerminalFence = true;
        wazeDirectRouteEnded = true;
        wazeDirectNavigating = false;
        wazeDirectFrameReceived = false;
        wazeRouteGeneration++;
        clearDirectSpeedLimit(WAZE_PACKAGE);
        long now = SystemClock.elapsedRealtime();
        NavRouteStateStore.get(context).markRouteEnded(WAZE_PACKAGE, reason, now);
        WazeRouteTracker.get(context).onRouteEnded(reason, now);
        tbtWazeObserver = false;
        wazeDirectChannel.noteRouteTerminalGeneration(
                reason, wazeDirectTerminalBridgeGeneration);
        wazeSurfaceDirectChannel.noteRouteTerminalGeneration(
                reason, wazeDirectTerminalBridgeGeneration);
        wazeSurfaceDirectChannel.stop(reason);
        log("waze inactive newer-generation snapshot closed owner reason="
                + safeReason(reason));
    }

    private void onWazeRouteLifecycleEventOnMain(
            WazeRouteLifecycleStore.RecordResult result,
            long eventElapsedMs, boolean clearSpeedForBridgeTransition) {
        WazeRouteLifecycleStore.Snapshot snapshot = result.snapshot;
        boolean routeActive = snapshot.active;
        long bridgeGeneration = snapshot.bridgeGeneration;
        int bridgeCapabilities = snapshot.bridgeCapabilities;
        boolean lifecycleChanged = result.changed || result.freshRouteAccepted;
        String reason = result.reason;
        if (clearSpeedForBridgeTransition) {
            clearDirectSpeedLimit(WAZE_PACKAGE);
            republishLatestDirectFrame(WAZE_PACKAGE, "speed-limit-bridge-transition");
        }
        if (!isWazeBridgeSupportedCached()) return;
        log("waze route lifecycle routeActive=" + routeActive
                + " terminal=" + result.terminal
                + " elapsedMs=" + eventElapsedMs
                + " bridgeGeneration=" + bridgeGeneration
                + " bridgeCapabilities=" + bridgeCapabilities
                + " changed=" + result.changed
                + " freshRouteAccepted=" + result.freshRouteAccepted
                + " supersedingInactive=" + result.supersedingInactive
                + " reason=" + safeReason(reason));
        if (result.terminal
                || (bridgeCapabilities & WAZE_CAP_SPEED_LIMIT_HEARTBEAT) == 0) {
            cancelWazeSpeedLimitExpiry();
        }
        cancelWazeFallbackReadiness();
        wazeFallbackActive = false;
        updateCaptureIngressPolicy();
        WazeCropCapture.get(context).stop("route-lifecycle-bridge");
        if (result.terminal) {
            wazeLegacyDirectRearmPending = false;
            wazeLegacyDirectSessionFloor = -1;
            wazeLegacySurfaceRearmPending = false;
            wazeLegacySurfaceSessionFloor = -1;
            wazeDirectRouteTerminalFence = true;
            if (bridgeGeneration > wazeDirectTerminalBridgeGeneration) {
                wazeDirectTerminalBridgeGeneration = bridgeGeneration;
            }
            wazeDirectChannel.noteRouteTerminalGeneration(
                    "route-lifecycle-end:" + safeReason(reason),
                    wazeDirectTerminalBridgeGeneration);
            wazeSurfaceDirectChannel.noteRouteTerminalGeneration(
                    "route-lifecycle-end:" + safeReason(reason),
                    wazeDirectTerminalBridgeGeneration);
            closeWazeSurface("route-lifecycle-end:" + safeReason(reason));
            wazeSurfaceDirectChannel.stop(
                    "route-lifecycle-end:" + safeReason(reason));
            if (!result.changed && !wazeDirectChannel.isActive()) {
                log("waze route lifecycle terminal ignored; state already inactive reason="
                        + safeReason(reason));
                return;
            }
            if ((active && WAZE_PACKAGE.equals(activePackage)) || tbtWazeObserver) {
                invalidatePendingWazeDirectFrames();
                int sessionGeneration = wazeDirectChannel.sessionGeneration();
                if (!isCurrentWazeDirectCallback(WAZE_PACKAGE, sessionGeneration)) {
                    return;
                }
                endWazeDirectSession("route-lifecycle-end");
                onWazeDirectNavigationEnded(
                        WAZE_PACKAGE, sessionGeneration, sessionGeneration,
                        "route-lifecycle-end", eventElapsedMs);
            }
            return;
        }
        if (!routeActive) {
            if (shouldCloseWazeForSupersedingInactiveSnapshotForTest(
                    result.supersedingInactive, wazeDirectNavigating, tbtWazeObserver,
                    tbtPublisher.isRouteActive(), tbtPublisher.ownerPackage())) {
                if (bridgeGeneration > wazeDirectTerminalBridgeGeneration) {
                    wazeDirectTerminalBridgeGeneration = bridgeGeneration;
                }
                closeWazeForSupersedingInactiveLifecycle(
                        eventElapsedMs, "route-lifecycle-inactive-new-generation");
                return;
            }
            log("waze route lifecycle nonterminal inactive reason=" + safeReason(reason));
            return;
        }
        if (result.freshRouteAccepted) {
            wazeLegacyDirectRearmPending = false;
            wazeLegacyDirectSessionFloor = -1;
            wazeLegacySurfaceRearmPending = false;
            wazeLegacySurfaceSessionFloor = -1;
            wazeDirectRouteTerminalFence = false;
            wazeDirectRouteEnded = false;
            if (bridgeGeneration > wazeDirectTerminalBridgeGeneration) {
                wazeDirectTerminalBridgeGeneration = bridgeGeneration;
            }
            wazeDirectChannel.openAcceptedFreshRoute(
                    "route-lifecycle-start:" + safeReason(reason),
                    bridgeGeneration);
            wazeSurfaceDirectChannel.openAcceptedFreshRoute(
                    "route-lifecycle-start:" + safeReason(reason),
                    bridgeGeneration);
        }
        if (wazeDirectRouteTerminalFence) {
            log("waze route lifecycle active ignored by terminal fence reason="
                    + safeReason(reason));
            return;
        }
        if (lifecycleChanged && eventElapsedMs > 0L) {
            wazeTbtRouteStartedAtMs = eventElapsedMs;
        }
        boolean hudEnabled = NavCapturePrefs.isHudEnabled(context, WAZE_PACKAGE);
        boolean tbtEnabled = shouldObserveTbtWithoutHud(context, WAZE_PACKAGE);
        if (HudPrefs.isUserShutdownActive(context)
                || !HudPrefs.isBootEnabled(context)
                || (!hudEnabled && !tbtEnabled)) {
            return;
        }
        if (!active || !WAZE_PACKAGE.equals(activePackage)) {
            if (hudEnabled) {
                startOnMain(WAZE_PACKAGE, "route-lifecycle-start");
            } else {
                refreshTbtObserver(WAZE_PACKAGE);
            }
            return;
        }
        if (hudEnabled && shouldRestartWazeDirectForLifecycle(
                lifecycleChanged, wazeDirectChannel.isActive(), wazeDirectNavigating)) {
            startWazeDirectForRoute(
                    "route-lifecycle-start",
                    shouldRecoverWazeDirectForLifecycle(
                            lifecycleChanged, wazeDirectNavigating));
        } else if (hudEnabled || tbtWazeObserver) {
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
        if (wazeDirectRouteTerminalFence) {
            log("waze fallback blocked by terminal fence reason=" + safeReason(reason));
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
        legacyFallbackOwnerPackage = WAZE_PACKAGE;
        updateCaptureIngressPolicy();
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
        updateCaptureIngressPolicy();
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

    private void logRouteStartOutputPreferences(
            String source, long generation, String reason) {
        if (!claimOutputPreferenceRouteStart(source, generation)) return;
        emitOutputPreferences(
                source, generation, "route-start:" + safeReason(reason),
                HudOutputPreferenceSnapshot.capture(context));
    }

    private boolean claimOutputPreferenceRouteStart(String source, long generation) {
        if ("manual".equals(source)) {
            if (lastManualOutputPreferenceGeneration == generation) return false;
            lastManualOutputPreferenceGeneration = generation;
            return true;
        }
        if ("waze".equals(source)) {
            if (lastWazeOutputPreferenceGeneration == generation) return false;
            lastWazeOutputPreferenceGeneration = generation;
            return true;
        }
        if (lastGMapsOutputPreferenceGeneration == generation) return false;
        lastGMapsOutputPreferenceGeneration = generation;
        return true;
    }

    private void logChangedOutputPreferences(String key) {
        HudOutputPreferenceSnapshot snapshot = HudOutputPreferenceSnapshot.capture(context);
        if (snapshot.equals(lastOutputPreferenceSnapshot)) return;
        emitOutputPreferences(
                "preferences", 0L, "changed:" + safeReason(key), snapshot);
    }

    private void onOutputPreferenceChangedOnMain(String key) {
        logChangedOutputPreferences(key);
        if (!HudPrefs.KEY_TEXT_TRANSLITERATION.equals(key)) return;

        String reason = "text-transliteration-mode-changed";
        String republished = "none";
        if (manualTbtActive && latestManualSourceState != null) {
            publishManualOnWorker(latestManualSourceState.copy(), reason);
            republished = "manual";
        } else {
            String owner = normalizePackage(activePackage);
            if (!isHudOutputOwner(owner) || !hasLatestDirectFrame(owner)) {
                owner = normalizePackage(tbtPublisher.ownerPackage());
            }
            if (hasLatestDirectFrame(owner)) {
                republishLatestDirectFrame(owner, reason);
                republished = owner;
            } else if (active && latestState != null) {
                sendLatestIfReady(reason);
                HudState sent = latestState.copy();
                HudOutputPreferences.apply(context, sent);
                log("legacy text projection textMode="
                        + HudPrefs.transliterationMode(context)
                        + " rawRoad=\"" + normalizeString(latestState.roadName) + "\""
                        + " sentRoad=\"" + normalizeString(sent.roadName) + "\"");
                republished = "legacy";
            }
        }
        log("text transliteration changed mode=" + HudPrefs.transliterationMode(context)
                + " republished=" + republished);
    }

    private boolean hasLatestDirectFrame(String ownerPackage) {
        if (WAZE_PACKAGE.equals(ownerPackage)) {
            return wazeDirectNavigating && !wazeDirectRouteEnded
                    && !wazeDirectRouteTerminalFence && (wazeSurfaceActive
                    ? latestWazeSurfaceFrame != null : latestWazeClusterFrame != null);
        }
        return GMapsDirectChannel.PACKAGE_NAME.equals(ownerPackage)
                && !gmapsDirectRouteEnded
                && gmapsDirectChannel.isNavigating()
                && latestGMapsDirectFrame != null;
    }

    private void emitOutputPreferences(
            String source, long generation, String trigger,
            HudOutputPreferenceSnapshot snapshot) {
        lastOutputPreferenceSnapshot = snapshot;
        String detail = "source=" + source
                + " generation=" + generation
                + " trigger=" + trigger
                + " " + snapshot.compact();
        log("output_preferences " + detail);
        if ("waze".equals(source)) {
            eventWazeDirectSession("output_preferences", detail);
        } else if ("gmaps".equals(source)) {
            eventGMapsDirectSession("output_preferences", detail);
        } else if ("preferences".equals(source)) {
            eventWazeDirectSession("output_preferences", detail);
            eventGMapsDirectSession("output_preferences", detail);
        }
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
                || wazeDirectRouteTerminalFence
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

    static boolean shouldObserveTbtWithoutHud(Context context, String packageName) {
        String normalized = normalizePackage(packageName);
        return shouldObserveTbtWithoutHudForTest(
                HudPrefs.isTbtWithoutHudOutputEnabled(context),
                NavCapturePrefs.isHudEnabled(context, normalized),
                WAZE_PACKAGE.equals(normalized),
                GMapsDirectChannel.PACKAGE_NAME.equals(normalized)
                        && isInstalledPackage(context, normalized));
    }

    static boolean shouldObserveTbtWithoutHudForTest(
            boolean tbtEnabled, boolean hudEnabled, boolean waze, boolean gmapsInstalled) {
        return tbtEnabled && !hudEnabled && (waze || gmapsInstalled);
    }

    static boolean shouldStartTbtObserverForTest(
            boolean waze, boolean wantsObserver, boolean ownsHud,
            boolean routeActive, boolean observerActive, boolean channelActive) {
        if (!wantsObserver || ownsHud || (waze && !routeActive)) return false;
        return !observerActive || !channelActive;
    }

    static boolean shouldPromoteTbtObserverForHudForTest(
            boolean hudEnabled, boolean observerActive) {
        return hudEnabled && observerActive;
    }

    static boolean shouldAcceptWazeFrameForTest(
            boolean framePresent, boolean queuedGenerationCurrent,
            boolean sourceCurrent, boolean publisherCurrent,
            boolean fromSurface, boolean surfaceActive) {
        return framePresent && queuedGenerationCurrent && sourceCurrent
                && publisherCurrent && fromSurface == surfaceActive;
    }

    static boolean acceptsGMapsTeardownForTest(
            boolean callbackIsCurrent, long routeGeneration, long callbackGeneration) {
        return callbackIsCurrent && callbackGeneration == routeGeneration + 1L;
    }

    static boolean acceptsWazeTeardownForTest(
            int routeGeneration, int callbackGeneration) {
        return callbackGeneration == routeGeneration
                || callbackGeneration == routeGeneration + 1;
    }

    static boolean acceptsHudStopCallbackForTest(long observedToken, long currentToken) {
        return observedToken == currentToken;
    }

    static long nextObserverLifecycleTokenForTest(long currentToken) {
        return currentToken + 1L;
    }

    static int manualTbtManeuverForTest(int nativeId) {
        VehicleTbtPublisher.ManualMapping mapping =
                VehicleTbtPublisher.manualMappingForTest(nativeId);
        return mapping.amapSupported ? mapping.instrumentId : HudState.NATIVE_BLANK_ID;
    }

    private boolean isTbtObserver(String packageName) {
        String normalized = normalizePackage(packageName);
        return (WAZE_PACKAGE.equals(normalized) && tbtWazeObserver)
                || (GMapsDirectChannel.PACKAGE_NAME.equals(normalized) && tbtGMapsObserver);
    }

    private boolean isHudOutputOwner(String packageName) {
        String normalized = normalizePackage(packageName);
        return active
                && !sourceSwitchInProgress
                && normalized.equals(activePackage)
                && NavCapturePrefs.isHudEnabled(context, normalized)
                && !isTbtObserver(normalized);
    }

    private void refreshTbtObserversOnMain() {
        if (HudPrefs.isUserShutdownActive(context) || !HudPrefs.isBootEnabled(context)) {
            stopTbtObserver(WAZE_PACKAGE, "runtime-disabled");
            stopTbtObserver(GMapsDirectChannel.PACKAGE_NAME, "runtime-disabled");
            return;
        }
        refreshTbtObserver(WAZE_PACKAGE);
        refreshTbtObserver(GMapsDirectChannel.PACKAGE_NAME);
    }

    private void refreshTbtObserver(String packageName) {
        reconcileTbtOwnershipForHud(packageName);
        boolean wantsObserver = shouldObserveTbtWithoutHud(context, packageName);
        boolean ownsHud = isHudOutputOwner(packageName);
        if (WAZE_PACKAGE.equals(packageName)) {
            boolean routeActive = WazeRouteLifecycleStore.isRouteActive(context);
            if (wantsObserver && !ownsHud && !routeActive) {
                requestWazeRouteStateSnapshot("tbt-observer", false);
            }
            if (shouldStartTbtObserverForTest(
                    true, wantsObserver, ownsHud, routeActive,
                    tbtWazeObserver, wazeDirectChannel.isActive())) {
                if (tbtWazeObserver) {
                    stopTbtObserver(packageName, "tbt-observer-rebind");
                }
                long observerToken = nextObserverLifecycleTokenForTest(tbtLifecycleToken);
                tbtLifecycleToken = observerToken;
                tbtWazeObserver = true;
                wazeDirectChannel.start("tbt-observer-start", WazeDirectChannel.Mode.CLUSTER);
                log("tbt observer started package=" + packageName
                        + " token=" + observerToken);
            } else if ((!wantsObserver || ownsHud || !routeActive) && tbtWazeObserver) {
                String reason = !wantsObserver ? "tbt-observer-disabled"
                        : !routeActive ? "tbt-observer-route-ended"
                        : "tbt-observer-promoted";
                stopTbtObserver(packageName, reason);
            }
            updateTbtOwnerPriority(packageName);
            return;
        }
        if (GMapsDirectChannel.PACKAGE_NAME.equals(packageName)) {
            if (shouldStartTbtObserverForTest(
                    false, wantsObserver, ownsHud, true,
                    tbtGMapsObserver, gmapsDirectChannel.isRunning())) {
                if (tbtGMapsObserver) {
                    stopTbtObserver(packageName, "tbt-observer-rebind");
                }
                long observerToken = nextObserverLifecycleTokenForTest(tbtLifecycleToken);
                tbtLifecycleToken = observerToken;
                tbtGMapsObserver = true;
                gmapsDirectChannel.start("tbt-observer-start");
                log("tbt observer started package=" + packageName
                        + " token=" + observerToken);
            } else if ((!wantsObserver || ownsHud) && tbtGMapsObserver) {
                stopTbtObserver(packageName,
                        !wantsObserver ? "tbt-observer-disabled"
                                : "tbt-observer-promoted");
            }
            updateTbtOwnerPriority(packageName);
        }
    }

    private void reconcileTbtOwnershipForHud(String packageName) {
        if (!NavCapturePrefs.isHudEnabled(context, packageName)) return;
        if (WAZE_PACKAGE.equals(packageName)
                && shouldPromoteTbtObserverForHudForTest(true, tbtWazeObserver)) {
            tbtWazeObserver = false;
            log("tbt observer promoted to HUD package=" + packageName);
        } else if (GMapsDirectChannel.PACKAGE_NAME.equals(packageName)
                && shouldPromoteTbtObserverForHudForTest(true, tbtGMapsObserver)) {
            tbtGMapsObserver = false;
            log("tbt observer promoted to HUD package=" + packageName);
        }
    }

    private void updateTbtOwnerPriority(String packageName) {
        if (!active || !packageName.equals(activePackage)) return;
        if (WAZE_PACKAGE.equals(packageName) && wazeDirectChannel.isActive()) {
            tbtPublisher.updateOwnerHudPriority(
                    packageName, wazeDirectChannel.sessionGeneration(),
                    NavCapturePrefs.isHudEnabled(context, packageName)
                            && !tbtWazeObserver,
                    HudPrefs.isSwitchToTbtOnHudStartEnabled(context));
        } else if (GMapsDirectChannel.PACKAGE_NAME.equals(packageName)
                && gmapsDirectChannel.isRunning()) {
            tbtPublisher.updateOwnerHudPriority(
                    packageName, gmapsDirectChannel.sessionGeneration(),
                    NavCapturePrefs.isHudEnabled(context, packageName)
                            && !tbtGMapsObserver,
                    HudPrefs.isSwitchToTbtOnHudStartEnabled(context));
        }
    }

    private static boolean isInstalledPackage(Context context, String packageName) {
        try {
            context.getPackageManager().getApplicationInfo(packageName, 0);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void stopTbtObserver(String packageName, String reason) {
        boolean rebind = "tbt-observer-rebind".equals(reason);
        boolean explicitTeardown = "runtime-disabled".equals(reason);
        boolean stopped = false;
        if (WAZE_PACKAGE.equals(packageName) && tbtWazeObserver) {
            long generation = tbtOwnerGeneration(
                    packageName, wazeDirectChannel.sessionGeneration());
            tbtPublisher.endRoute(packageName, generation, reason);
            tbtWazeObserver = false;
            wazeDirectChannel.stop(reason);
            stopped = true;
        } else if (GMapsDirectChannel.PACKAGE_NAME.equals(packageName) && tbtGMapsObserver) {
            long generation = tbtOwnerGeneration(
                    packageName, gmapsDirectChannel.sessionGeneration());
            tbtPublisher.endRoute(packageName, generation, reason);
            tbtGMapsObserver = false;
            gmapsDirectChannel.stop(reason);
            stopped = true;
        }
        if (!stopped) return;
        long teardownToken = ++tbtLifecycleToken;
        if (!explicitTeardown) {
            selectRemainingTbtRoute(packageName, "observer-stop:" + reason);
        }
        if (rebind || tbtPublisher.isRouteActive()) return;
        if (explicitTeardown) {
            tbtPublisher.sendTeardownStatus();
        } else if ("tbt-observer-route-ended".equals(reason)) {
            confirmTbtTeardown(packageName, teardownToken);
        }
    }

    void refreshTbtObservers() {
        handler.post(this::refreshTbtObserversOnMain);
    }

    private void requestWazeRouteStateSnapshot(String reason, boolean force) {
        boolean hudEnabled = NavCapturePrefs.isHudEnabled(context, WAZE_PACKAGE);
        boolean tbtEnabled = shouldObserveTbtWithoutHud(context, WAZE_PACKAGE);
        if (!shouldRequestWazeRouteStateForTest(
                HudPrefs.isUserShutdownActive(context), HudPrefs.isBootEnabled(context),
                hudEnabled, tbtEnabled)) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (!force && lastWazeRouteStateRequestMs > 0L
                && now - lastWazeRouteStateRequestMs
                < WAZE_ROUTE_STATE_REQUEST_INTERVAL_MS) {
            return;
        }
        lastWazeRouteStateRequestMs = now;
        WazeRouteLifecycleV2Receiver.requestCurrentState(context, reason);
    }

    static boolean shouldRequestWazeRouteStateForTest(boolean userShutdown,
            boolean bootEnabled, boolean hudEnabled, boolean tbtEnabled) {
        return !userShutdown && bootEnabled && (hudEnabled || tbtEnabled);
    }

    private void confirmTbtTeardown(String packageName, long lifecycleToken) {
        final String normalized = normalizePackage(packageName);
        Thread worker = new Thread(() -> {
            NavAppTaskScanner.TeardownEvidence first =
                    NavAppTaskScanner.confirmTeardown(context, normalized);
            SystemClock.sleep(500L);
            NavAppTaskScanner.TeardownEvidence second =
                    NavAppTaskScanner.confirmTeardown(context, normalized);
            handler.post(() -> {
                log("tbt teardown evidence package=" + normalized
                        + " first=" + first.reason
                        + " second=" + second.reason);
                if (shouldApplyTeardownForTest(
                        lifecycleToken, tbtLifecycleToken,
                        tbtPublisher.isRouteActive(), first.positive(), second.positive())) {
                    tbtPublisher.sendTeardownStatus();
                } else if (shouldRetryTeardownForTest(
                        lifecycleToken, tbtLifecycleToken,
                        tbtPublisher.isRouteActive(),
                        first.adbAvailable, second.adbAvailable,
                        first.positive(), second.positive())) {
                    handler.postDelayed(
                            () -> confirmTbtTeardown(normalized, lifecycleToken),
                            TBT_TEARDOWN_RETRY_MS);
                }
            });
        }, "BydHudTbtTeardown");
        worker.setDaemon(true);
        worker.start();
    }

    static boolean shouldApplyTeardownForTest(
            long observedToken, long currentToken, boolean routeActive,
            boolean firstPositive, boolean secondPositive) {
        return observedToken == currentToken
                && !routeActive && firstPositive && secondPositive;
    }

    static boolean shouldRetryTeardownForTest(
            long observedToken, long currentToken, boolean routeActive,
            boolean firstAdbAvailable, boolean secondAdbAvailable,
            boolean firstPositive, boolean secondPositive) {
        return observedToken == currentToken
                && !routeActive
                && firstAdbAvailable && secondAdbAvailable
                && !(firstPositive && secondPositive);
    }

    private void advanceTbtLifecycleForFirstFrame() {
        if (shouldAdvanceTbtLifecycleForTest(tbtPublisher.isRouteActive())) {
            ++tbtLifecycleToken;
        }
    }

    static boolean shouldAdvanceTbtLifecycleForTest(boolean publisherRouteActive) {
        return !publisherRouteActive;
    }

    static boolean shouldClaimTbtOwnerForFrameForTest(
            boolean routeActive, boolean samePackage, boolean incomingHasHud,
            boolean firstRouteEvidence, boolean currentHasHud) {
        return !routeActive || samePackage || incomingHasHud
                || firstRouteEvidence && !currentHasHud;
    }

    /** Rendered maneuver bitmap updates affect RoadInfo only, never semantic TBT. */
    static boolean shouldDispatchSemanticTbtForDirectReason(String reason) {
        return !"maneuver-bitmap".equals(normalizeDirectReasonForTest(reason));
    }

    private static String normalizeDirectReasonForTest(String reason) {
        if (reason == null) return "";
        return reason.trim().toLowerCase(Locale.ROOT);
    }

    //starts or schedules work here so lifecycle recovery follows one controlled path.
    void start(String packageName, String reason) {
        final String normalized = normalizePackage(packageName);
        handler.post(() -> startOnMain(normalized, reason));
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    boolean isRunning() {
        return active || stopInProgress || runtimeReinitInProgress || manualTbtActive;
    }

    //stops or releases work here so stale capture and HUD output cannot keep running silently.
    void stop(String packageName, String reason, boolean clearHud) {
        stop(packageName, reason, clearHud, null);
    }

    //keeps package-replace teardown fenced until the serialized sender stop has completed.
    void stop(String packageName, String reason, boolean clearHud, Runnable completion) {
        final String normalized = normalizePackage(packageName);
        handler.post(() -> {
            if ("package-replace-hard-reset".equals(
                    NavTextNormalizer.lower(normalizeString(reason)))) {
                pendingForcedDirectTeardown = true;
            }
            if (sourceSwitchInProgress
                    && (normalized.isEmpty()
                    || normalized.equals(activePackage)
                    || normalized.equals(pendingSourceSwitchPackage))) {
                pendingSourceSwitchPackage = "";
                pendingSourceSwitchReason = reason;
                log("source switch converted to stop reason=" + safeReason(reason));
                if (completion != null) pendingStopCompletion = completion;
                return;
            }
            if (stopInProgress) {
                pendingStopStartPackage = "";
                pendingStopStartReason = "";
                log("stop already in progress reason=" + safeReason(reason));
                if (completion != null) pendingStopCompletion = completion;
                return;
            }
            if (runtimeReinitInProgress) {
                if (completion != null) pendingStopCompletion = completion;
                log("stop completion queued during package reinit reason="
                        + safeReason(reason));
                return;
            }
            if (!active && !normalized.isEmpty() && isTbtObserver(normalized)) {
                stopTbtObserver(normalized, reason);
                if (completion != null) completion.run();
                return;
            }
            if (!normalized.isEmpty() && !normalized.equals(activePackage)) {
                if (isTbtObserver(normalized)) {
                    stopTbtObserver(normalized, reason);
                }
                if (completion != null) completion.run();
                return;
            }
            if (completion != null) pendingStopCompletion = completion;
            stopOnMain(reason, clearHud);
            if (!stopInProgress) finishStopCompletion();
        });
    }

    void demoteHudToTbtObserver(String packageName, String reason, boolean clearHud) {
        final String normalized = normalizePackage(packageName);
        handler.post(() -> {
            pendingHudDemotionObserverPackage = normalized;
            if (GMapsDirectChannel.PACKAGE_NAME.equals(normalized)
                    && shouldObserveTbtWithoutHud(context, normalized)) {
                tbtGMapsObserver = true;
            }
            if (stopInProgress) {
                log("HUD demotion queued during stop package=" + normalized);
                return;
            }
            if (!active || !normalized.equals(activePackage)) {
                completeHudDemotionObserverRefresh();
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
            if (routeGeneration != wazeRouteGeneration
                    || wazeDirectRouteEnded || wazeDirectRouteTerminalFence) {
                log("stale waze visual route evidence ignored reason=" + safeReason);
                return;
            }
            long now = SystemClock.elapsedRealtime();
            NavRouteStateStore.get(context).updateFromVisualRouteEvidence(
                    WAZE_PACKAGE,
                    "waze_crop_visual",
                    safeReason,
                    now);
            if (wazeFallbackActive) {
                WazeRouteTracker.get(context).onVisualRouteEvidence(safeReason, now);
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

    //reasserts only the current Direct route after an explicit task return to display 0.
    void onDashboardReturnConfirmed(String packageName, String reason) {
        final String normalized = normalizePackage(packageName);
        handler.post(() -> {
            long directGeneration = directSessionGeneration(normalized);
            boolean directRouteActive = WAZE_PACKAGE.equals(normalized)
                    ? wazeDirectChannel.isActive() && wazeDirectNavigating
                            && !wazeDirectRouteEnded && !wazeDirectRouteTerminalFence
                            && wazeDirectChannel.sessionGeneration() == directGeneration
                    : GMapsDirectChannel.PACKAGE_NAME.equals(normalized)
                            && gmapsDirectChannel.isRunning()
                            && gmapsDirectChannel.isNavigating()
                            && !gmapsDirectRouteEnded
                            && gmapsDirectChannel.sessionGeneration() == directGeneration;
            boolean allowed = shouldReassertTbtAfterDashboardReturnForTest(
                    HudPrefs.isSwitchToTbtOnHudStartEnabled(context),
                    isHudOutputOwner(normalized),
                    sourceSwitchInProgress || stopInProgress || runtimeReinitInProgress,
                    directRouteActive,
                    tbtPublisher.isRouteActive(),
                    normalized, tbtPublisher.ownerPackage(),
                    directGeneration, tbtPublisher.ownerGeneration());
            if (!allowed) {
                log("dashboard return TBT reassert skipped package=" + normalized
                        + " directGeneration=" + directGeneration
                        + " reason=" + safeReason(reason));
                return;
            }
            tbtPublisher.reassertDashboardForCurrentRoute(
                    normalized, directGeneration,
                    "dashboard-return:" + safeReason(reason));
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
            if (!wazeFallbackActive) {
                log("waze crop unavailable ignored: direct Waze owns lifecycle reason="
                        + safeReason);
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
            forceClearLegacyFallback(WAZE_PACKAGE, "waze-route-nodes-missing", now);
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
        if (stopInProgress) {
            pendingStopStartPackage = packageName;
            pendingStopStartReason = reason;
            log("start deferred during HUD clear package=" + packageName
                    + " reason=" + safeReason(reason));
            return;
        }
        if (sourceSwitchInProgress) {
            pendingSourceSwitchPackage = packageName;
            pendingSourceSwitchReason = reason;
            log("source switch target updated package=" + packageName
                    + " reason=" + safeReason(reason));
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
            reconcileTbtOwnershipForHud(packageName);
            updateTbtOwnerPriority(packageName);
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
        if (active && !previousPackage.equals(packageName)) {
            beginSourceSwitch(previousPackage, packageName, reason);
            return;
        }
        if ("ui-start".equals(reason) || !packageName.equals(previousPackage)) {
            resetLatestPayload();
            HudDeliveryStatus.reset();
        }
        cancelPendingRouteEndStops();
        active = true;
        activePackage = packageName;
        NavCaptureIngressPolicy.refreshPreferences(context);
        updateCaptureIngressPolicy();
        reconcileTbtOwnershipForHud(packageName);
        updateTbtOwnerPriority(packageName);
        lastDashboardWatchdogMs = 0L;
        log("start package=" + packageName + " reason=" + reason);
        if (WAZE_PACKAGE.equals(packageName)) {
            if (!resumeExistingDirectRouteForHud(packageName, reason)) {
                boolean bridgeSupported = isWazeBridgeSupportedCached();
                if (shouldStartWazeDirectHost(
                        bridgeSupported, WazeRouteLifecycleStore.isRouteActive(context))) {
                    if (bridgeSupported) startWazeDirectForRoute(reason);
                    else startWazeDirectProbe(reason);
                } else {
                    waitForWazeRouteLifecycle(reason);
                }
            }
            requestActiveInputState(packageName, reason);
        } else if (GMapsDirectChannel.PACKAGE_NAME.equals(packageName)) {
            if (!resumeExistingDirectRouteForHud(packageName, reason)) {
                startGMapsDirectProbe(reason);
            }
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

    private void beginSourceSwitch(String previousPackage, String nextPackage, String reason) {
        String previous = normalizePackage(previousPackage);
        String next = normalizePackage(nextPackage);
        long channelGeneration = directSessionGeneration(previous);
        long tbtGeneration = tbtOwnerGeneration(previous, channelGeneration);
        boolean retainRoute = shouldRetainRouteForTbt(previous);
        sourceSwitchInProgress = true;
        long switchToken = ++sourceSwitchToken;
        pendingSourceSwitchPackage = next;
        pendingSourceSwitchReason = reason;
        if (WAZE_PACKAGE.equals(previous)) {
            tbtWazeObserver = retainRoute;
            cancelWazeDirectColdTimeout();
            cancelWazeFallbackReadiness();
            wazeFallbackActive = false;
            updateCaptureIngressPolicy();
            closeWazeSurface("source-switch:" + next);
            wazeSurfaceDirectChannel.stop("source-switch:" + next);
            WazeCropCapture.get(context).stop("source-switch:" + next);
        } else if (GMapsDirectChannel.PACKAGE_NAME.equals(previous)) {
            tbtGMapsObserver = retainRoute;
            cancelGMapsDirectTimeout();
            gmapsDirectFallbackActive = false;
            updateCaptureIngressPolicy();
        }
        tbtPublisher.updateOwnerHudPriority(previous, tbtGeneration, false);
        log("source switch begin previous=" + previous + " next=" + next
                + " retainTbt=" + retainRoute + " reason=" + safeReason(reason));
        hudOutput.endNavigationOutput(
                previous, channelGeneration, "source-switch:" + next,
                SystemClock.elapsedRealtime(),
                () -> handler.post(() -> completeSourceSwitch(
                        previous, tbtGeneration, switchToken, retainRoute)));
    }

    private void completeSourceSwitch(String previousPackage, long tbtGeneration,
            long switchToken, boolean retainRequested) {
        if (!sourceSwitchInProgress || switchToken != sourceSwitchToken) return;
        String nextPackage = pendingSourceSwitchPackage;
        String nextReason = pendingSourceSwitchReason;
        boolean retainRoute = retainRequested && shouldRetainRouteForTbt(previousPackage);
        boolean forceTeardown = pendingForcedDirectTeardown;
        if (!retainRoute || forceTeardown) {
            ++tbtLifecycleToken;
            tbtPublisher.endRoute(
                    previousPackage, tbtGeneration, "source-switch:" + nextPackage);
            stopDirectNavigator(previousPackage, "source-switch:" + nextPackage);
        } else {
            log("source switch retained TBT route package=" + previousPackage);
        }
        active = false;
        activePackage = "";
        updateCaptureIngressPolicy();
        sourceSwitchInProgress = false;
        pendingSourceSwitchPackage = "";
        pendingSourceSwitchReason = "";
        resetLatestPayload();
        HudDeliveryStatus.reset();
        if (!nextPackage.isEmpty()) {
            startOnMain(nextPackage, nextReason);
        } else {
            log("source switch completed as stop reason=" + safeReason(nextReason));
        }
        finishStopCompletion();
    }

    private boolean shouldRetainRouteForTbt(String packageName) {
        String normalized = normalizePackage(packageName);
        boolean runtimeEnabled = !HudPrefs.isUserShutdownActive(context)
                && HudPrefs.isBootEnabled(context);
        boolean routeActive = WAZE_PACKAGE.equals(normalized)
                ? wazeDirectChannel.isActive() && wazeDirectNavigating
                && !wazeDirectRouteEnded && !wazeDirectRouteTerminalFence
                : GMapsDirectChannel.PACKAGE_NAME.equals(normalized)
                && gmapsDirectChannel.isRunning()
                && gmapsDirectChannel.isNavigating()
                && !gmapsDirectRouteEnded;
        return shouldRetainTbtRouteOnHudSwitchForTest(
                runtimeEnabled, shouldObserveTbtWithoutHud(context, normalized), routeActive);
    }

    static boolean shouldRetainTbtRouteOnHudSwitchForTest(
            boolean runtimeEnabled, boolean tbtWithoutHudEnabled, boolean routeActive) {
        return runtimeEnabled && tbtWithoutHudEnabled && routeActive;
    }

    private boolean resumeExistingDirectRouteForHud(String packageName, String reason) {
        String normalized = normalizePackage(packageName);
        if (WAZE_PACKAGE.equals(normalized)
                && wazeDirectChannel.isActive() && wazeDirectNavigating
                && !wazeDirectRouteEnded && !wazeDirectRouteTerminalFence
                && latestWazeClusterFrame != null
                && latestWazeClusterFrameSessionGeneration
                == wazeDirectChannel.sessionGeneration()) {
            ensureWazeDirectSession("hud-promoted:" + safeReason(reason));
            int generation = wazeDirectChannel.sessionGeneration();
            promoteExistingDirectRouteForHud(normalized, generation, reason, () -> {
                tbtPublisher.beginRoute(normalized, generation,
                        HudPrefs.isSwitchToTbtOnHudStartEnabled(context), true);
                republishLatestDirectFrame(
                        normalized, "hud-promoted:" + safeReason(reason));
                maybeLaunchWazeSurface("hud-promoted:" + safeReason(reason));
                log("waze existing direct route promoted to HUD");
            });
            return true;
        }
        if (GMapsDirectChannel.PACKAGE_NAME.equals(normalized)
                && gmapsDirectChannel.isRunning() && !gmapsDirectRouteEnded
                && latestGMapsDirectFrame != null
                && latestGMapsDirectFrameSessionGeneration
                == gmapsDirectChannel.sessionGeneration()) {
            ensureGMapsDirectSession("hud-promoted:" + safeReason(reason));
            long generation = gmapsDirectChannel.sessionGeneration();
            promoteExistingDirectRouteForHud(normalized, generation, reason, () -> {
                tbtPublisher.beginRoute(normalized, generation,
                        HudPrefs.isSwitchToTbtOnHudStartEnabled(context), true);
                republishLatestDirectFrame(
                        normalized, "hud-promoted:" + safeReason(reason));
                scheduleGMapsDirectTimeout();
                log("gmaps existing direct route promoted to HUD");
            });
            return true;
        }
        return false;
    }

    private void promoteExistingDirectRouteForHud(String ownerPackage,
            long sessionGeneration, String reason, Runnable publishCachedFrame) {
        hudOutput.claimDirectOwnerForPromotion(
                ownerPackage, sessionGeneration,
                "hud-promoted:" + safeReason(reason),
                claimed -> handler.post(() -> {
                    if (!acceptsDirectPromotionForTest(
                            claimed, active,
                            sourceSwitchInProgress || stopInProgress || runtimeReinitInProgress,
                            activePackage,
                            directSessionGeneration(ownerPackage),
                            ownerPackage, sessionGeneration)) {
                        log("direct route promotion rejected or stale package="
                                + ownerPackage + " session=" + sessionGeneration
                                + " claimed=" + claimed);
                        return;
                    }
                    publishCachedFrame.run();
                }));
    }

    static boolean acceptsDirectPromotionForTest(boolean claimed, boolean active,
            boolean transitionInProgress, String activePackage, long activeSessionGeneration,
            String ownerPackage, long ownerSessionGeneration) {
        return claimed && active && !transitionInProgress
                && normalizePackage(ownerPackage).equals(normalizePackage(activePackage))
                && activeSessionGeneration == ownerSessionGeneration;
    }

    static boolean shouldRequestDashboardForDirectRouteForTest(
            boolean hudOwner, boolean switchDashboardEnabled) {
        return hudOwner && switchDashboardEnabled;
    }

    static boolean shouldReassertTbtAfterDashboardReturnForTest(
            boolean preferenceEnabled, boolean hudOwner, boolean transitionInProgress,
            boolean directRouteActive, boolean tbtRouteActive,
            String returnedPackage, String tbtOwner,
            long directGeneration, long tbtGeneration) {
        return preferenceEnabled && hudOwner && !transitionInProgress
                && directRouteActive && tbtRouteActive
                && normalizePackage(returnedPackage).equals(normalizePackage(tbtOwner))
                && directGeneration == tbtGeneration;
    }

    private long directSessionGeneration(String packageName) {
        String normalized = normalizePackage(packageName);
        if (WAZE_PACKAGE.equals(normalized)) return wazeDirectChannel.sessionGeneration();
        if (GMapsDirectChannel.PACKAGE_NAME.equals(normalized)) {
            return gmapsDirectChannel.sessionGeneration();
        }
        return -1L;
    }

    private long tbtOwnerGeneration(String packageName, long fallbackGeneration) {
        return tbtOwnerGenerationForTest(
                packageName, tbtPublisher.ownerPackage(),
                tbtPublisher.ownerGeneration(), fallbackGeneration);
    }

    static long tbtOwnerGenerationForTest(String packageName, String ownerPackage,
            long ownerGeneration, long fallbackGeneration) {
        return normalizePackage(packageName).equals(normalizePackage(ownerPackage))
                ? ownerGeneration : fallbackGeneration;
    }

    private static boolean isDirectNavigator(String packageName) {
        String normalized = normalizePackage(packageName);
        return WAZE_PACKAGE.equals(normalized)
                || GMapsDirectChannel.PACKAGE_NAME.equals(normalized);
    }

    //keeps framework fallback callbacks from becoming a second Direct lifecycle authority.
    private boolean isLegacyFallbackIngressOwner(String packageName) {
        String normalized = normalizePackage(packageName);
        if (!active || !normalized.equals(activePackage)) return false;
        if (WAZE_PACKAGE.equals(normalized)) return wazeFallbackActive;
        if (GMapsDirectChannel.PACKAGE_NAME.equals(normalized)) {
            return gmapsDirectFallbackActive;
        }
        return !isDirectNavigator(normalized)
                && normalized.equals(legacyFallbackOwnerPackage);
    }

    static boolean shouldPublishLegacyFallbackForTest(
            String incomingPackage, String activePackage, String legacyOwner,
            boolean active, boolean wazeDirect, boolean wazeFallback,
            boolean gmapsDirect, boolean gmapsFallback) {
        String incoming = normalizePackage(incomingPackage);
        String activeOwner = normalizePackage(activePackage);
        if (!active || incoming.isEmpty() || !incoming.equals(activeOwner)) return false;
        if (WAZE_PACKAGE.equals(incoming)) return wazeFallback && !wazeDirect;
        if (GMapsDirectChannel.PACKAGE_NAME.equals(incoming)) {
            return gmapsFallback && !gmapsDirect;
        }
        return incoming.equals(normalizePackage(legacyOwner));
    }

    //selects only a legacy owner; it never starts, stops, registers, or rearms Direct.
    private boolean prepareLegacyFallbackIngress(String packageName, String reason) {
        String normalized = normalizePackage(packageName);
        if (normalized.isEmpty()) return false;
        if (isDirectNavigator(normalized)) {
            if (!isLegacyFallbackIngressOwner(normalized)) {
                log("fallback ingress cached only package=" + normalized
                        + " reason=direct-owner-or-unavailable");
                return false;
            }
            return true;
        }
        if (active && !normalized.equals(activePackage)) {
            log("fallback ingress cached only package=" + normalized
                    + " reason=other-owner=" + activePackage);
            return false;
        }
        if (!active) {
            active = true;
            activePackage = normalized;
            resetLatestPayload();
            HudDeliveryStatus.reset();
            updateCaptureIngressPolicy();
            log("legacy fallback owner selected package=" + normalized
                    + " reason=" + safeReason(reason));
        }
        legacyFallbackOwnerPackage = normalized;
        hudOutput.selectNavigationSource(
                HudOutputCoordinator.Source.LEGACY,
                "legacy-fallback:" + normalized + ":" + safeReason(reason));
        hudOutput.ensureBound("legacy-fallback:" + normalized);
        return true;
    }

    //clears only legacy fallback output; a direct owner remains alive and eligible to recover.
    private void stopLegacyFallbackOnMain(String packageName, String reason) {
        String normalized = normalizePackage(packageName);
        if (!isLegacyFallbackIngressOwner(normalized)) return;
        if (WAZE_PACKAGE.equals(normalized)) {
            wazeFallbackActive = false;
            cancelWazeFallbackReadiness();
            WazeCropCapture.get(context).stop("legacy-fallback-stop:" + safeReason(reason));
        } else if (GMapsDirectChannel.PACKAGE_NAME.equals(normalized)) {
            gmapsDirectFallbackActive = false;
        } else {
            active = false;
            activePackage = "";
        }
        legacyFallbackOwnerPackage = "";
        resetLatestPayload();
        hudOutput.selectNavigationSource(
                HudOutputCoordinator.Source.NONE,
                "legacy-fallback-stop:" + safeReason(reason));
        updateCaptureIngressPolicy();
        log("legacy fallback stopped package=" + normalized
                + " reason=" + safeReason(reason));
    }

    private void forceClearLegacyFallback(String packageName, String reason, long now) {
        String normalized = normalizePackage(packageName);
        if (!isLegacyFallbackIngressOwner(normalized)) return;
        NavRouteStateStore.get(context).markRouteEnded(normalized, reason, now);
        if (WAZE_PACKAGE.equals(normalized)) {
            WazeRouteTracker.get(context).onRouteEnded(reason, now);
        }
        stopLegacyFallbackOnMain(normalized, reason);
    }

    private void stopDirectNavigator(String packageName, String reason) {
        if (WAZE_PACKAGE.equals(packageName)) {
            tbtWazeObserver = false;
            closeWazeSurface(reason);
            endWazeDirectSession(reason);
            resetWazeDirectSessionState();
            wazeDirectChannel.stop(reason);
            wazeSurfaceDirectChannel.stop(reason);
            WazeCropCapture.get(context).stop(reason);
        } else if (GMapsDirectChannel.PACKAGE_NAME.equals(packageName)) {
            tbtGMapsObserver = false;
            endGMapsDirectSession(reason);
            resetGMapsDirectSessionState();
            gmapsDirectChannel.stop(reason);
        }
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
        long evidenceNow = SystemClock.elapsedRealtime();
        NavRouteStateStore.get(context).updateFromSnapshot(
                result.snapshot, "notification", evidenceNow);
        if (!WAZE_PACKAGE.equals(packageName) || wazeFallbackActive) {
            WazeRouteTracker.get(context).updateFromSnapshot(
                    result.snapshot, "notification", evidenceNow);
        }
        if (!hudEnabled) {
            log("snapshot only package=" + packageName + " reason=" + result.reason);
            return;
        }
        if (!prepareLegacyFallbackIngress(packageName, "notification")) {
            return;
        }
        if (WAZE_PACKAGE.equals(packageName) && wazeFallbackActive) {
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
            if (isLegacyFallbackIngressOwner(packageName)
                    && NavRouteEndPolicy.shouldScheduleNoRouteAccessibilityStop(
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
        long evidenceNow = SystemClock.elapsedRealtime();
        NavRouteStateStore.get(context).updateFromSnapshot(
                result.snapshot, "accessibility", evidenceNow);
        if (!WAZE_PACKAGE.equals(packageName) || wazeFallbackActive) {
            WazeRouteTracker.get(context).updateFromSnapshot(
                    result.snapshot, "accessibility", evidenceNow);
        }
        if (!hudEnabled) {
            log("accessibility snapshot only package=" + packageName
                    + " reason=" + result.reason);
            return;
        }
        if (!prepareLegacyFallbackIngress(packageName, "accessibility")) {
            return;
        }
        if (WAZE_PACKAGE.equals(packageName) && wazeFallbackActive) {
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
        if (!WAZE_PACKAGE.equals(packageName) || wazeFallbackActive) {
            WazeRouteTracker.get(context).updateFromSnapshot(
                    result.snapshot, "visual", SystemClock.elapsedRealtime());
        }
        if (!hudEnabled) {
            log("visual snapshot only package=" + packageName + " reason=" + result.reason);
            return;
        }
        if (!prepareLegacyFallbackIngress(packageName, "visual")) {
            return;
        }
        if (WAZE_PACKAGE.equals(packageName) && wazeFallbackActive) {
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
        if (runtimeReinitInProgress) {
            pendingReinitStartPackage = "";
            pendingReinitStartReason = reason;
            log("package reinit will complete without restart reason=" + safeReason(reason));
            return;
        }
        if (sourceSwitchInProgress) {
            pendingSourceSwitchPackage = "";
            pendingSourceSwitchReason = reason;
            log("source switch converted to stop reason=" + safeReason(reason));
            return;
        }
        if (stopInProgress) {
            pendingStopStartPackage = "";
            pendingStopStartReason = "";
            log("stop already in progress reason=" + safeReason(reason));
            return;
        }
        String packageName = activePackage;
        int wazeGeneration = wazeDirectChannel.sessionGeneration();
        long gmapsGeneration = gmapsDirectChannel.sessionGeneration();
        long wazeTbtGeneration = tbtOwnerGeneration(WAZE_PACKAGE, wazeGeneration);
        long gmapsTbtGeneration = tbtOwnerGeneration(
                GMapsDirectChannel.PACKAGE_NAME, gmapsGeneration);
        boolean forceTeardown = "package-replace-hard-reset".equals(
                NavTextNormalizer.lower(normalizeString(reason)));
        boolean retainTbtRoute = !forceTeardown
                && isDirectNavigator(packageName)
                && shouldRetainRouteForTbt(packageName);
        if (WAZE_PACKAGE.equals(packageName) && retainTbtRoute) {
            tbtWazeObserver = true;
        } else if (GMapsDirectChannel.PACKAGE_NAME.equals(packageName)
                && retainTbtRoute) {
            tbtGMapsObserver = true;
        }
        clearRouteStoreForStop(packageName, reason);
        handler.removeCallbacks(sendLoop);
        handler.removeCallbacks(routeHealthLoop);
        handler.removeCallbacks(notificationRemovedStop);
        handler.removeCallbacks(accessibilityNoRouteStop);
        handler.removeCallbacks(arrivalRouteEndStop);
        sendLoopScheduled = false;
        routeHealthScheduled = false;
        active = false;
        legacyFallbackOwnerPackage = "";
        updateCaptureIngressPolicy();
        stopInProgress = isDirectNavigator(packageName);
        if (WAZE_PACKAGE.equals(packageName)) {
            long teardownToken = ++tbtLifecycleToken;
            if (retainTbtRoute) {
                tbtPublisher.updateOwnerHudPriority(
                        WAZE_PACKAGE, wazeTbtGeneration, false);
            }
            hudOutput.endNavigationOutput(
                    WAZE_PACKAGE, wazeGeneration,
                    "waze-stop:" + safeReason(reason),
                    SystemClock.elapsedRealtime(),
                    () -> handler.post(() -> {
                        if (!acceptsHudStopCallbackForTest(
                                teardownToken, tbtLifecycleToken)) {
                            log("stale waze HUD clear callback ignored token="
                                    + teardownToken + " current=" + tbtLifecycleToken);
                            completeNavigationStop();
                            return;
                        }
                        if (retainTbtRoute && shouldRetainRouteForTbt(WAZE_PACKAGE)) {
                            log("waze route retained for TBT after HUD stop");
                            completeNavigationStop();
                            return;
                        }
                        tbtPublisher.endRoute(
                                WAZE_PACKAGE, wazeTbtGeneration,
                                "sender-stop:" + reason);
                        stopDirectNavigator(WAZE_PACKAGE, reason);
                        if (!tbtPublisher.isRouteActive()) {
                            confirmTbtTeardown(WAZE_PACKAGE, teardownToken);
                        }
                        completeNavigationStop();
                    }));
        }
        if (GMapsDirectChannel.PACKAGE_NAME.equals(packageName)) {
            long teardownToken = ++tbtLifecycleToken;
            if (retainTbtRoute) {
                tbtPublisher.updateOwnerHudPriority(
                        GMapsDirectChannel.PACKAGE_NAME, gmapsTbtGeneration, false);
            }
            hudOutput.endNavigationOutput(
                    GMapsDirectChannel.PACKAGE_NAME, gmapsGeneration,
                    "gmaps-stop:" + safeReason(reason),
                    SystemClock.elapsedRealtime(),
                    () -> handler.post(() -> {
                        if (!acceptsHudStopCallbackForTest(
                                teardownToken, tbtLifecycleToken)) {
                            log("stale gmaps HUD clear callback ignored token="
                                    + teardownToken + " current=" + tbtLifecycleToken);
                            completeNavigationStop();
                            return;
                        }
                        if (retainTbtRoute
                                && shouldRetainRouteForTbt(
                                GMapsDirectChannel.PACKAGE_NAME)) {
                            log("gmaps route retained for TBT after HUD stop");
                            completeNavigationStop();
                            return;
                        }
                        tbtPublisher.endRoute(
                                GMapsDirectChannel.PACKAGE_NAME, gmapsTbtGeneration,
                                "sender-stop:" + reason);
                        stopDirectNavigator(GMapsDirectChannel.PACKAGE_NAME, reason);
                        if (!tbtPublisher.isRouteActive()) {
                            confirmTbtTeardown(
                                    GMapsDirectChannel.PACKAGE_NAME, teardownToken);
                        }
                        completeNavigationStop();
                    }));
        }
        resetLatestPayload();
        if (!isDirectNavigator(packageName)) {
            hudOutput.selectNavigationSource(
                    HudOutputCoordinator.Source.NONE,
                    reason + (clearHud ? ":clear" : ""));
        }
        if (WAZE_PACKAGE.equals(packageName) && shouldStopWazeCrop(reason)) {
            WazeCropCapture.get(context).stop(reason);
        }
        log("stopped reason=" + reason);
    }

    private void completeNavigationStop() {
        stopInProgress = false;
        completeHudDemotionObserverRefresh();
        String nextPackage = pendingStopStartPackage;
        String nextReason = pendingStopStartReason;
        pendingStopStartPackage = "";
        pendingStopStartReason = "";
        if (!nextPackage.isEmpty()) {
            startOnMain(nextPackage, nextReason);
        }
        finishStopCompletion();
    }

    private void finishStopCompletion() {
        if (pendingForcedDirectTeardown) {
            hardStopDirectNavigatorsForPackageReplace();
            pendingForcedDirectTeardown = false;
        }
        Runnable completion = pendingStopCompletion;
        pendingStopCompletion = null;
        if (completion != null) completion.run();
    }

    private void hardStopDirectNavigatorsForPackageReplace() {
        if (tbtPublisher.isRouteActive()) {
            tbtPublisher.endRoute(
                    tbtPublisher.ownerPackage(), tbtPublisher.ownerGeneration(),
                    "package-replace-hard-reset");
        }
        stopDirectNavigator(WAZE_PACKAGE, "package-replace-hard-reset");
        wazeDirectChannel.hardStop("package-replace-hard-reset");
        wazeSurfaceDirectChannel.hardStop("package-replace-hard-reset");
        stopDirectNavigator(
                GMapsDirectChannel.PACKAGE_NAME, "package-replace-hard-reset");
        active = false;
        activePackage = "";
        legacyFallbackOwnerPackage = "";
        updateCaptureIngressPolicy();
    }

    private void completeHudDemotionObserverRefresh() {
        String packageName = pendingHudDemotionObserverPackage;
        pendingHudDemotionObserverPackage = "";
        if (packageName.isEmpty()) return;
        refreshTbtObserver(packageName);
        log("HUD demotion observer reconciled package=" + packageName);
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
        forceClearLegacyFallback(
                WAZE_PACKAGE, clearReason + ":" + visualReason, now);
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
            updateCaptureIngressPolicy();
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
        if (wazeDirectRouteEnded || wazeDirectRouteTerminalFence
                || !WazeRouteTracker.get(context).isRouteActive(now)) {
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
            updateCaptureIngressPolicy();
            WazeCropCapture.get(context).stop("route-lifecycle-bridge");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (!active
                || !WAZE_PACKAGE.equals(activePackage)
                || !wazeFallbackActive
                || wazeDirectRouteEnded
                || wazeDirectRouteTerminalFence
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
        String hudOwner = packageReinitOutputOwnerForTest(
                active, activePackage, tbtPublisher.isRouteActive(),
                tbtPublisher.ownerPackage());
        long hudGeneration = directSessionGeneration(hudOwner);
        String tbtOwner = tbtPublisher.isRouteActive()
                ? tbtPublisher.ownerPackage() : "";
        long tbtGeneration = tbtPublisher.ownerGeneration();
        long teardownToken = ++tbtLifecycleToken;
        active = false;
        updateCaptureIngressPolicy();
        if (isDirectNavigator(hudOwner)) {
            hudOutput.endNavigationOutput(
                    hudOwner, hudGeneration,
                    "package-replaced-reinit", SystemClock.elapsedRealtime(),
                    () -> handler.post(() -> completeRuntimeResetAfterPackageReplace(
                            packageName, reason, tbtOwner, tbtGeneration, teardownToken)));
            return;
        }
        completeRuntimeResetAfterPackageReplace(
                packageName, reason, tbtOwner, tbtGeneration, teardownToken);
    }

    static String packageReinitOutputOwnerForTest(boolean active, String activePackage,
            boolean tbtRouteActive, String tbtOwner) {
        String selected = normalizePackage(activePackage);
        if (active && isDirectNavigator(selected)) return selected;
        String observed = normalizePackage(tbtOwner);
        return tbtRouteActive && isDirectNavigator(observed) ? observed : "";
    }

    private void completeRuntimeResetAfterPackageReplace(String packageName, String reason,
            String tbtOwner, long tbtGeneration, long teardownToken) {
        if (!runtimeReinitInProgress) return;
        if (acceptsHudStopCallbackForTest(teardownToken, tbtLifecycleToken)) {
            tbtPublisher.endRoute(
                    tbtOwner, tbtGeneration, "package-replaced-reinit");
            if (!tbtPublisher.isRouteActive() && isDirectNavigator(tbtOwner)) {
                confirmTbtTeardown(tbtOwner, teardownToken);
            }
        } else {
            log("stale package-reinit terminal callback ignored token="
                    + teardownToken + " current=" + tbtLifecycleToken);
        }
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
        boolean forcedDirectTeardown = pendingForcedDirectTeardown;
        finishStopCompletion();
        if (forcedDirectTeardown) {
            log("package reinit restart suppressed by forced teardown");
        } else {
            startOnMain(restartPackage, restartReason);
        }
    }

    private boolean isCurrentWazeDirectCallback(String ownerPackage,
            int sessionGeneration) {
        return (isHudOutputOwner(ownerPackage) || tbtWazeObserver)
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
        return (isHudOutputOwner(ownerPackage) || tbtGMapsObserver)
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
        String normalized = normalizeString(reason).toLowerCase(Locale.ROOT);
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
