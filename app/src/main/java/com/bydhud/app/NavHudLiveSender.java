package com.bydhud.app;

//converts parser snapshots into HUD commands so live route evidence reaches the cluster consistently.

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

//defines the NavHudLiveSender module boundary so related behavior stays readable inside one unit.
final class NavHudLiveSender {
    private static final String TAG = "BydHudNavLive";
    private static final String WAZE_PACKAGE = "com.waze";
    private static final long SEND_INTERVAL_MS = 1000L;
    private static final long START_BIND_RETRY_MS = 200L;
    private static final long NOTIFICATION_REMOVED_STOP_DELAY_MS = 2000L;
    private static final long ACCESSIBILITY_NO_ROUTE_STOP_DELAY_MS = 10000L;
    private static final long ARRIVAL_ROUTE_END_STOP_DELAY_MS = 3000L;
    private static final long ROUTE_HEALTH_INTERVAL_MS = 1000L;
    private static final long ACTIVE_ROUTE_STALE_CLEAR_MS = 15000L;
    private static final long WAZE_VISUAL_FRESH_MS = 2500L;
    private static final long WAZE_ROUTE_NODE_FRESH_MS = 3000L;
    private static final long WAZE_ROUTE_FIELD_TTL_MS = WAZE_ROUTE_NODE_FRESH_MS;
    private static final long DASHBOARD_WATCHDOG_INTERVAL_MS = 5000L;
    private static final int START_BIND_RETRY_LIMIT = 30;
    private static final int CLEAR_FRAME_COUNT = 5;

    private static NavHudLiveSender instance;

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static synchronized NavHudLiveSender get(Context context) {
        if (instance == null) {
            instance = new NavHudLiveSender(context.getApplicationContext());
        }
        return instance;
    }

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final HandlerThread sendThread = new HandlerThread("BydHudSomeIpSend");
    private final Handler sendHandler;
    private final SomeIpHudClient hudClient;
    private String activePackage = "";
    private boolean active;
    private boolean hudStarted;
    private boolean sendLoopScheduled;
    private boolean routeHealthScheduled;
    private boolean runtimeReinitInProgress;
    private boolean runtimeReinitPreviousHudStarted;
    private int bindAttempts;
    private int sendCount;
    private volatile int transportGeneration;
    private HudState latestState;
    private HudState latestRouteState;
    private HudState latestVisualState;
    private int latestVisualSourceDisplayId;
    private NavSnapshot.Maneuver latestRouteManeuver = NavSnapshot.Maneuver.UNKNOWN;
    private String latestReason = "";
    private String activeNotificationKey = "";
    private String pendingRemovalKey = "";
    private String pendingRemovalPackage = "";
    private String pendingRemovalActiveKey = "";
    private String pendingNoRoutePackage = "";
    private String pendingArrivalPackage = "";
    private String pendingReinitStartPackage = "";
    private String pendingReinitStartReason = "";
    private long pendingNoRouteScheduledAtMs;
    private long lastAccessibilityResultMs;
    private long lastVisualResultMs;
    private long lastWazeRouteNodeResultMs;
    private long latestRouteStateMs;
    private long lastDashboardWatchdogMs;
    private boolean lastWazeRouteNodeScanHadRoute;
    private boolean firstNavAfterPackageReplaceAwaitingSomeIp;
    private long firstNavAfterPackageReplaceConnectStartMs;

    private final Runnable bindRetryRunnable = new Runnable() {
        @Override
        //keeps this HUD step isolated so cluster payload behavior stays predictable.
        public void run() {
            if (!active) {
                return;
            }
            if (hudClient.isBound()) {
                bindAttempts = 0;
                log("connected activePackage=" + activePackage
                        + " hasPayload=" + (latestState != null));
                sendLatestIfReady("bind-ready");
                return;
            }
            if (bindAttempts >= START_BIND_RETRY_LIMIT) {
                log("bind timeout activePackage=" + activePackage);
                stopOnMain("bind-timeout", false);
                return;
            }
            bindAttempts++;
            hudClient.bind();
            scheduleBindRetry();
        }
    };

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
            NavRouteStateStore routeStore = NavRouteStateStore.get(context);
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
                forceClearNavigator(activePackage, "accessibility-route-ended", now);
                return;
            }
            if (WAZE_PACKAGE.equals(activePackage)) {
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
            if (!active || packageName.isEmpty() || !packageName.equals(activePackage)) {
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
        this.hudClient = new SomeIpHudClient(context, line -> log("someip " + line));
        sendThread.start();
        sendHandler = new Handler(sendThread.getLooper());
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
        handler.post(() -> updateOnMain(normalized, safeKey, result));
    }

    //updates shared state here so freshness and lifecycle checks use the same evidence.
    void updateFromGMapsAccessibility(String packageName, String payload) {
        updateFromNavigationAccessibility(packageName, payload);
    }

    //updates shared state here so freshness and lifecycle checks use the same evidence.
    void updateFromNavigationAccessibility(String packageName, String payload) {
        final String normalized = normalizePackage(packageName);
        final String safePayload = normalizeString(payload);
        handler.post(() -> updateAccessibilityOnMain(normalized, safePayload));
    }

    //updates shared state here so freshness and lifecycle checks use the same evidence.
    void updateWazeAccessibilityGeometry(String packageName, String payload) {
        final String normalized = normalizePackage(packageName);
        final String safePayload = normalizeString(payload);
        handler.post(() -> updateWazeAccessibilityGeometryOnMain(normalized, safePayload));
    }

    //updates shared state here so freshness and lifecycle checks use the same evidence.
    void updateFromWazeVisualCue(String packageName, NavParserResult result) {
        if (result == null) {
            return;
        }
        final String normalized = normalizePackage(packageName);
        handler.post(() -> updateVisualCueOnMain(normalized, result));
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    void onWazeVisualRouteEvidence(String reason) {
        final String safeReason = normalizeString(reason);
        handler.post(() -> {
            long now = SystemClock.elapsedRealtime();
            NavRouteStateStore.get(context).updateFromVisualRouteEvidence(
                    WAZE_PACKAGE,
                    "waze_crop_visual",
                    safeReason,
                    now);
            WazeRouteTracker.get(context).onVisualRouteEvidence(safeReason, now);
            if (active && WAZE_PACKAGE.equals(activePackage)) {
                ensureWazeCropRunning(safeReason);
            }
            log("waze visual route evidence reason=" + safeReason);
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
    void onWazeCropUnavailable(String reason) {
        final String safeReason = normalizeString(reason);
        handler.post(() -> {
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
    void onWazeUnknownLaneRow(String reason) {
        final String safeReason = normalizeString(reason);
        handler.post(() -> {
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
        if ("ui-start".equals(reason) || !packageName.equals(activePackage)) {
            resetLatestPayload();
        }
        cancelPendingRouteEndStops();
        active = true;
        activePackage = packageName;
        bindAttempts = 0;
        lastDashboardWatchdogMs = 0L;
        transportGeneration++;
        log("start package=" + packageName + " reason=" + reason);
        if (WAZE_PACKAGE.equals(packageName)) {
            WazeMediaProjectionController.ensureReadyOrPrompt(
                    context, "start-" + safeReason(reason));
        }
        ensureWazeCropRunning("start-" + reason);
        requestActiveInputState(packageName, reason);
        if (!hudClient.isBound()) {
            hudClient.bind();
            scheduleBindRetry();
        } else {
            sendLatestIfReady("start");
        }
        scheduleSendLoop();
        scheduleRouteHealthLoop();
    }

    //updates shared state here so freshness and lifecycle checks use the same evidence.
    private void updateOnMain(String packageName, String notificationKey,
            NavParserResult result) {
        if (packageName.isEmpty()) {
            return;
        }
        if (!NavCapturePrefs.isCaptureEnabled(context, packageName)) {
            return;
        }
        boolean hudEnabled = NavCapturePrefs.isHudEnabled(context, packageName);
        NavCaptureStore.snapshot(context, result.snapshot);
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
    private void updateAccessibilityOnMain(String packageName, String payload) {
        if (packageName.isEmpty() || payload.isEmpty()) {
            return;
        }
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
    private void updateVisualCueOnMain(String packageName, NavParserResult result) {
        if (packageName.isEmpty()) {
            return;
        }
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
        if (!hudClient.isBound()) {
            hudClient.bind();
            scheduleBindRetry();
            return;
        }
        boolean startBeforeSend = !hudStarted;
        int sendGeneration = transportGeneration;
        if (startBeforeSend) {
            hudStarted = true;
            sendCount = 0;
            log("hud started package=" + activePackage);
        }
        boolean clampSmallDistance = HudPrefs.isSmallDistanceClampEnabled(context);
        HudState displayState = HudDisplayPolicy.apply(latestState, clampSmallDistance);
        HudOutputPreferences.apply(context, displayState);
        byte[] payload = HudRoadPayload.build(displayState);
        sendCount++;
        String detail = " sendCount=" + sendCount
                + " payload=" + payload.length
                + " latest=" + latestReason
                + " rawDist=" + latestState.distanceToIntersection
                + " displayDist=" + displayState.distanceToIntersection
                + " clampSmallDist=" + clampSmallDistance
                + " " + displayState.summary();
        postHudSend(payload, reason, detail, startBeforeSend, true, sendGeneration);
    }

    //stops or releases work here so stale capture and HUD output cannot keep running silently.
    private void stopOnMain(String reason, boolean clearHud) {
        String packageName = activePackage;
        handler.removeCallbacks(bindRetryRunnable);
        handler.removeCallbacks(sendLoop);
        handler.removeCallbacks(routeHealthLoop);
        handler.removeCallbacks(notificationRemovedStop);
        handler.removeCallbacks(accessibilityNoRouteStop);
        handler.removeCallbacks(arrivalRouteEndStop);
        sendHandler.removeCallbacksAndMessages(null);
        boolean stopReinitHud = false;
        if (runtimeReinitInProgress) {
            stopReinitHud = runtimeReinitPreviousHudStarted;
            runtimeReinitInProgress = false;
            runtimeReinitPreviousHudStarted = false;
            pendingReinitStartPackage = "";
            pendingReinitStartReason = "";
            log("package reinit cancelled reason=" + reason);
        }
        transportGeneration++;
        int stopGeneration = transportGeneration;
        sendLoopScheduled = false;
        routeHealthScheduled = false;
        active = false;
        resetLatestPayload();
        boolean stopHud = hudStarted || stopReinitHud;
        if (hudClient.hasBinding()) {
            postHudClearStopAndUnbind(clearHud && stopHud, stopHud, reason, stopGeneration);
        }
        hudStarted = false;
        sendCount = 0;
        if (WAZE_PACKAGE.equals(packageName) && shouldStopWazeCrop(reason)) {
            WazeCropCapture.get(context).stop(reason);
        }
        log("stopped reason=" + reason);
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
        if (!WAZE_PACKAGE.equals(activePackage)) {
            return;
        }
        if (!NavCapturePrefs.isHudEnabled(context, WAZE_PACKAGE)) {
            return;
        }
        WazeMediaProjectionController.ensureReadyOrPrompt(
                context, "crop-" + safeReason(reason));
        WazeCropCapture.get(context).start("runtime-" + safeReason(reason));
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
        lastAccessibilityResultMs = 0L;
        lastVisualResultMs = 0L;
        lastWazeRouteNodeResultMs = 0L;
        latestRouteStateMs = 0L;
        lastWazeRouteNodeScanHadRoute = false;
    }

    //resets stale post-update state before the first new navigation session binds SOME/IP again.
    private void resetRuntimeAfterPackageReplace(String packageName, String reason) {
        handler.removeCallbacks(bindRetryRunnable);
        handler.removeCallbacks(sendLoop);
        handler.removeCallbacks(routeHealthLoop);
        handler.removeCallbacks(notificationRemovedStop);
        handler.removeCallbacks(accessibilityNoRouteStop);
        handler.removeCallbacks(arrivalRouteEndStop);
        sendHandler.removeCallbacksAndMessages(null);
        transportGeneration++;
        int resetGeneration = transportGeneration;
        runtimeReinitInProgress = true;
        pendingReinitStartPackage = packageName;
        pendingReinitStartReason = reason;
        boolean stopPreviousHud = hudStarted;
        runtimeReinitPreviousHudStarted = stopPreviousHud;
        sendLoopScheduled = false;
        routeHealthScheduled = false;
        active = false;
        activePackage = "";
        WazeCropCapture.get(context).stop("package-replaced-reinit");
        WazeMediaProjectionController.resetForRuntimeReinit(
                context, "nav-start:" + packageName + ":" + safeReason(reason));
        hudStarted = false;
        bindAttempts = 0;
        sendCount = 0;
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
                LocalAdbBridge.AuthorizationPromptMode.AUTO_ONCE);
        if (hudClient.hasBinding()) {
            postPackageReinitTransportReset(stopPreviousHud, "package-replaced-reinit",
                    resetGeneration);
        } else {
            finishPackageReinitAndRestart(resetGeneration);
        }
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
        pendingArrivalPackage = packageName;
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
    private void scheduleBindRetry() {
        handler.removeCallbacks(bindRetryRunnable);
        handler.postDelayed(bindRetryRunnable, START_BIND_RETRY_MS);
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

    //guard blocking binder sends so route parsing and UI callbacks stay on the main handler.
    private void postHudSend(byte[] payload, String reason, String detail,
            boolean startBeforeSend, boolean stopOnError, int sendGeneration) {
        sendHandler.post(() -> {
            if (!isTransportGenerationCurrent(sendGeneration)) {
                handler.post(() -> log("live-send skipped stale generation=" + sendGeneration
                        + " current=" + transportGeneration + " reason=" + reason));
                return;
            }
            try {
                if (startBeforeSend) {
                    hudClient.start();
                }
                int ret = hudClient.send(payload);
                handler.post(() -> {
                    if (sendGeneration != transportGeneration) {
                        log("live-send result ignored stale generation=" + sendGeneration
                                + " current=" + transportGeneration + " reason=" + reason);
                        return;
                    }
                    log("live-send ret=" + ret + " reason=" + reason + detail);
                });
            } catch (RemoteException e) {
                handler.post(() -> {
                    log("send error reason=" + reason + ": " + e.getMessage());
                    Log.e(TAG, "postHudSend failed", e);
                    if (stopOnError && sendGeneration == transportGeneration) {
                        stopOnMain("send-error", false);
                    }
                });
            }
        });
    }

    //guard shutdown transport work so clear frames keep order without blocking the main handler.
    private void postHudClearStopAndUnbind(boolean clearHud, boolean stopHud, String reason,
            int stopGeneration) {
        sendHandler.post(() -> {
            if (!isTransportGenerationCurrent(stopGeneration)) {
                handler.post(() -> log("teardown skipped stale generation=" + stopGeneration
                        + " current=" + transportGeneration + " reason=" + reason));
                return;
            }
            if (clearHud) {
                HudState clearState = new HudState().copyForClear();
                for (int i = 1; i <= CLEAR_FRAME_COUNT; i++) {
                    if (!isTransportGenerationCurrent(stopGeneration)) {
                        handler.post(() -> log("clear stopped stale generation=" + stopGeneration
                                + " current=" + transportGeneration + " reason=" + reason));
                        return;
                    }
                    try {
                        int ret = hudClient.send(HudRoadPayload.build(clearState));
                        int frame = i;
                        handler.post(() -> log("clear frame=" + frame + "/"
                                + CLEAR_FRAME_COUNT + " ret=" + ret
                                + " reason=" + reason));
                    } catch (RemoteException e) {
                        handler.post(() -> log("clear error: " + e.getMessage()));
                        break;
                    }
                }
            }
            if (!isTransportGenerationCurrent(stopGeneration)) {
                return;
            }
            if (stopHud) {
                try {
                    hudClient.stop();
                } catch (RemoteException e) {
                    handler.post(() -> log("stop error: " + e.getMessage()));
                }
            }
            handler.post(() -> {
                if (!active && stopGeneration == transportGeneration) {
                    hudClient.unbind();
                    log("someip unbound generation=" + stopGeneration + " reason=" + reason);
                } else {
                    log("someip unbind skipped generation=" + stopGeneration
                            + " current=" + transportGeneration
                            + " active=" + active
                            + " reason=" + reason);
                }
            });
        });
    }

    //resets update-stale SOME/IP state before a deferred first post-update start can bind again.
    private void postPackageReinitTransportReset(boolean stopHud, String reason,
            int resetGeneration) {
        sendHandler.post(() -> {
            if (!isTransportGenerationCurrent(resetGeneration)) {
                handler.post(() -> {
                    log("package reinit transport reset skipped stale generation="
                            + resetGeneration + " current=" + transportGeneration
                            + " reason=" + reason);
                    if (runtimeReinitInProgress) {
                        runtimeReinitInProgress = false;
                        runtimeReinitPreviousHudStarted = false;
                        pendingReinitStartPackage = "";
                        pendingReinitStartReason = "";
                    }
                });
                return;
            }
            if (stopHud) {
                try {
                    hudClient.stop();
                } catch (RemoteException e) {
                    handler.post(() -> log("package reinit stop error: " + e.getMessage()));
                }
            }
            handler.post(() -> {
                if (runtimeReinitInProgress && resetGeneration == transportGeneration) {
                    hudClient.unbind();
                    log("package reinit someip unbound generation=" + resetGeneration
                            + " reason=" + reason);
                    finishPackageReinitAndRestart(resetGeneration);
                } else {
                    log("package reinit unbind skipped generation=" + resetGeneration
                            + " current=" + transportGeneration
                            + " active=" + active
                            + " reinit=" + runtimeReinitInProgress
                            + " reason=" + reason);
                }
            });
        });
    }

    //restarts only after the package-replace transport reset has released stale bindings.
    private void finishPackageReinitAndRestart(int resetGeneration) {
        if (!runtimeReinitInProgress || resetGeneration != transportGeneration) {
            return;
        }
        String restartPackage = pendingReinitStartPackage;
        String restartReason = pendingReinitStartReason;
        runtimeReinitInProgress = false;
        runtimeReinitPreviousHudStarted = false;
        pendingReinitStartPackage = "";
        pendingReinitStartReason = "";
        log("package reinit complete generation=" + resetGeneration
                + " restartPackage=" + restartPackage
                + " reason=" + safeReason(restartReason));
        startOnMain(restartPackage, restartReason);
    }

    //guard transport worker tasks so old stop/send callbacks cannot affect a newer route session.
    private boolean isTransportGenerationCurrent(int generation) {
        return generation == transportGeneration;
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
