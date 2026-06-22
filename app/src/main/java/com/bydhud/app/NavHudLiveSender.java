package com.bydhud.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

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
    private static final int START_BIND_RETRY_LIMIT = 30;
    private static final int CLEAR_FRAME_COUNT = 5;

    private static NavHudLiveSender instance;

    static synchronized NavHudLiveSender get(Context context) {
        if (instance == null) {
            instance = new NavHudLiveSender(context.getApplicationContext());
        }
        return instance;
    }

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SomeIpHudClient hudClient;
    private String activePackage = "";
    private boolean active;
    private boolean hudStarted;
    private boolean sendLoopScheduled;
    private boolean routeHealthScheduled;
    private int bindAttempts;
    private int sendCount;
    private HudState latestState;
    private HudState latestRouteState;
    private HudState latestVisualState;
    private NavSnapshot.Maneuver latestRouteManeuver = NavSnapshot.Maneuver.UNKNOWN;
    private String latestReason = "";
    private String activeNotificationKey = "";
    private String pendingRemovalKey = "";
    private String pendingRemovalPackage = "";
    private String pendingRemovalActiveKey = "";
    private String pendingNoRoutePackage = "";
    private String pendingArrivalPackage = "";
    private long pendingNoRouteScheduledAtMs;
    private long lastAccessibilityResultMs;
    private long lastVisualResultMs;
    private long lastWazeRouteNodeResultMs;
    private boolean lastWazeRouteNodeScanHadRoute;

    private final Runnable bindRetryRunnable = new Runnable() {
        @Override
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
        public void run() {
            sendLoopScheduled = false;
            if (!active) {
                return;
            }
            sendLatestIfReady("loop");
            scheduleSendLoop();
        }
    };

    private final Runnable notificationRemovedStop = new Runnable() {
        @Override
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

    private NavHudLiveSender(Context context) {
        this.context = context;
        this.hudClient = new SomeIpHudClient(context, line -> log("someip " + line));
    }

    void start(String packageName, String reason) {
        final String normalized = normalizePackage(packageName);
        handler.post(() -> startOnMain(normalized, reason));
    }

    boolean isRunning() {
        return active;
    }

    void stop(String packageName, String reason, boolean clearHud) {
        final String normalized = normalizePackage(packageName);
        handler.post(() -> {
            if (!normalized.isEmpty() && !normalized.equals(activePackage)) {
                return;
            }
            stopOnMain(reason, clearHud);
        });
    }

    void updateFromGMapsNotification(String packageName, String notificationKey,
            GMapsNotificationParser.Result result) {
        updateFromNavigationNotification(packageName, notificationKey, result);
    }

    void updateFromNavigationNotification(String packageName, String notificationKey,
            NavParserResult result) {
        if (result == null) {
            return;
        }
        final String normalized = normalizePackage(packageName);
        final String safeKey = normalizeString(notificationKey);
        handler.post(() -> updateOnMain(normalized, safeKey, result));
    }

    void updateFromGMapsAccessibility(String packageName, String payload) {
        updateFromNavigationAccessibility(packageName, payload);
    }

    void updateFromNavigationAccessibility(String packageName, String payload) {
        final String normalized = normalizePackage(packageName);
        final String safePayload = normalizeString(payload);
        handler.post(() -> updateAccessibilityOnMain(normalized, safePayload));
    }

    void updateWazeAccessibilityGeometry(String packageName, String payload) {
        final String normalized = normalizePackage(packageName);
        final String safePayload = normalizeString(payload);
        handler.post(() -> updateWazeAccessibilityGeometryOnMain(normalized, safePayload));
    }

    void updateFromWazeVisualCue(String packageName, NavParserResult result) {
        if (result == null) {
            return;
        }
        final String normalized = normalizePackage(packageName);
        handler.post(() -> updateVisualCueOnMain(normalized, result));
    }

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
            forceClearNavigator(WAZE_PACKAGE, "waze-route-nodes-missing", now);
        });
    }

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

    void stopForRemovedGMapsNotification(String packageName, String notificationKey,
            String reason, boolean clearHud) {
        stopForRemovedNavigationNotification(packageName, notificationKey, reason, clearHud);
    }

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

    private void startOnMain(String packageName, String reason) {
        if (packageName.isEmpty()) {
            return;
        }
        if ("ui-start".equals(reason) || !packageName.equals(activePackage)) {
            resetLatestPayload();
        }
        cancelPendingRouteEndStops();
        active = true;
        activePackage = packageName;
        bindAttempts = 0;
        log("start package=" + packageName + " reason=" + reason);
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
        lastVisualResultMs = now;
        latestVisualState = result.state.copy();
        sanitizeWazeVisualLanes(packageName, latestVisualState);
        if ("com.waze".equals(packageName) && latestRouteState != null) {
            latestState = WazeVisualStatePolicy.mergeRouteFieldsKeepingVisual(
                    latestVisualState, latestRouteState, latestRouteManeuver);
            latestReason = result.reason + " mergedWithRoute";
        } else {
            latestState = latestVisualState.copy();
            latestReason = result.reason;
        }
        log("visual parsed package=" + packageName
                + " dist=" + latestState.distanceToIntersection
                + " road=\"" + latestState.roadName + "\""
                + " lanes=" + latestState.numOfLanes
                + " maneuver=" + result.snapshot.maneuver);
        sendLatestIfReady("visual");
        scheduleSendLoop();
    }

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

    private void sendLatestIfReady(String reason) {
        if (!active || latestState == null) {
            return;
        }
        if (!hudClient.isBound()) {
            hudClient.bind();
            scheduleBindRetry();
            return;
        }
        try {
            if (!hudStarted) {
                hudClient.start();
                hudStarted = true;
                sendCount = 0;
                log("hud started package=" + activePackage);
            }
            boolean clampSmallDistance = HudPrefs.isSmallDistanceClampEnabled(context);
            HudState displayState = HudDisplayPolicy.apply(latestState, clampSmallDistance);
            HudOutputPreferences.apply(context, displayState);
            byte[] payload = HudRoadPayload.build(displayState);
            int ret = hudClient.send(payload);
            sendCount++;
            log("live-send ret=" + ret
                    + " reason=" + reason
                    + " sendCount=" + sendCount
                    + " payload=" + payload.length
                    + " latest=" + latestReason
                    + " rawDist=" + latestState.distanceToIntersection
                    + " displayDist=" + displayState.distanceToIntersection
                    + " clampSmallDist=" + clampSmallDistance
                    + " " + displayState.summary());
        } catch (RemoteException e) {
            log("send error: " + e.getMessage());
            Log.e(TAG, "sendLatestIfReady failed", e);
            stopOnMain("send-error", false);
        }
    }

    private void stopOnMain(String reason, boolean clearHud) {
        String packageName = activePackage;
        handler.removeCallbacks(bindRetryRunnable);
        handler.removeCallbacks(sendLoop);
        handler.removeCallbacks(routeHealthLoop);
        handler.removeCallbacks(notificationRemovedStop);
        handler.removeCallbacks(accessibilityNoRouteStop);
        handler.removeCallbacks(arrivalRouteEndStop);
        sendLoopScheduled = false;
        routeHealthScheduled = false;
        active = false;
        resetLatestPayload();
        if (hudClient.isBound()) {
            if (clearHud && hudStarted) {
                HudState clearState = new HudState().copyForClear();
                for (int i = 1; i <= CLEAR_FRAME_COUNT; i++) {
                    try {
                        int ret = hudClient.send(HudRoadPayload.build(clearState));
                        log("clear frame=" + i + "/" + CLEAR_FRAME_COUNT + " ret=" + ret);
                    } catch (RemoteException e) {
                        log("clear error: " + e.getMessage());
                        break;
                    }
                }
            }
            if (hudStarted) {
                try {
                    hudClient.stop();
                } catch (RemoteException e) {
                    log("stop error: " + e.getMessage());
                }
            }
            hudClient.unbind();
        }
        hudStarted = false;
        sendCount = 0;
        if (WAZE_PACKAGE.equals(packageName) && shouldStopWazeCrop(reason)) {
            WazeCropCapture.get(context).stop(reason);
        }
        log("stopped reason=" + reason);
    }

    private void forceClearNavigator(String packageName, String reason, long now) {
        NavRouteStateStore.get(context).markRouteEnded(packageName, reason, now);
        if (WAZE_PACKAGE.equals(packageName)) {
            WazeRouteTracker.get(context).onRouteEnded(reason, now);
            WazeCropCapture.get(context).stop(reason);
        }
        log("route_clear package=" + packageName + " reason=" + reason);
        stopOnMain(reason, true);
    }

    private void handleWazeNoRouteOrVisualUnavailable(
            String clearReason, String visualReason, long now) {
        latestVisualState = null;
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

    private void ensureWazeCropRunning(String reason) {
        if (!WAZE_PACKAGE.equals(activePackage)) {
            return;
        }
        if (!NavCapturePrefs.isHudEnabled(context, WAZE_PACKAGE)) {
            return;
        }
        WazeCropCapture.get(context).start("runtime-" + safeReason(reason));
    }

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

    private void resetLatestPayload() {
        latestState = null;
        latestRouteState = null;
        latestVisualState = null;
        latestRouteManeuver = NavSnapshot.Maneuver.UNKNOWN;
        latestReason = "";
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
        lastWazeRouteNodeScanHadRoute = false;
    }

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

    private void scheduleArrivalRouteEndStop(String packageName, String reason) {
        pendingArrivalPackage = packageName;
        handler.removeCallbacks(arrivalRouteEndStop);
        handler.postDelayed(arrivalRouteEndStop, ARRIVAL_ROUTE_END_STOP_DELAY_MS);
        log("arrival route-end pending stop package=" + packageName
                + " reason=" + reason
                + " delayMs=" + ARRIVAL_ROUTE_END_STOP_DELAY_MS);
    }

    private void cancelArrivalRouteEndStop() {
        handler.removeCallbacks(arrivalRouteEndStop);
        pendingArrivalPackage = "";
    }

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

    private void cancelAccessibilityNoRouteStop() {
        handler.removeCallbacks(accessibilityNoRouteStop);
        pendingNoRoutePackage = "";
        pendingNoRouteScheduledAtMs = 0L;
    }

    private boolean shouldBlankGMapsNotificationTextOnlyStraightManeuver(String packageName,
            NavParserResult result) {
        return NavTextNormalizer.sourceApp(packageName) == NavSnapshot.SourceApp.GOOGLE_MAPS
                && result != null
                && result.snapshot != null
                && result.snapshot.maneuver == NavSnapshot.Maneuver.STRAIGHT
                && result.maneuverEvidence != null
                && result.maneuverEvidence.source == NavManeuverEvidence.Source.TEXT;
    }

    private HudState blankGMapsNotificationTextOnlyStraightManeuver(HudState notificationState) {
        HudState blankState = notificationState == null ? new HudState() : notificationState.copy();
        blankState.hideNativeWithBlankId();
        blankState.hideTurnBitmapWithBlankSource();
        return blankState;
    }

    private void scheduleBindRetry() {
        handler.removeCallbacks(bindRetryRunnable);
        handler.postDelayed(bindRetryRunnable, START_BIND_RETRY_MS);
    }

    private void scheduleSendLoop() {
        if (sendLoopScheduled) {
            return;
        }
        sendLoopScheduled = true;
        handler.postDelayed(sendLoop, SEND_INTERVAL_MS);
    }

    private void scheduleRouteHealthLoop() {
        if (routeHealthScheduled) {
            return;
        }
        routeHealthScheduled = true;
        handler.postDelayed(routeHealthLoop, ROUTE_HEALTH_INTERVAL_MS);
    }

    private void log(String line) {
        Log.i(TAG, line);
        AppEventLogger.event(context, "nav_live " + line);
    }

    private static String normalizePackage(String packageName) {
        return packageName == null ? "" : packageName.trim();
    }

    private static String normalizeString(String value) {
        return value == null ? "" : value.trim();
    }

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
