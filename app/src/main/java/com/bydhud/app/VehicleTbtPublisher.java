package com.bydhud.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Publishes the normalized route frame to the dashboard TBT compatibility planes. */
final class VehicleTbtPublisher {
    static final int STATUS_IDLE = 1;
    static final int STATUS_ACTIVE = 2;
    static final int STATUS_TEARDOWN = 4;
    static final String MANUAL_OWNER = "bydhud.manual";

    private static final String TAG = "BydHudVehicleTbt";
    private static final String AMAP_ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND";
    private static final String AMAP_PACKAGE = "com.byd.amapservice";
    private static final int FLAG_RECEIVER_INCLUDE_BACKGROUND = 0x01000000;
    private static final int FID_NAV_STATUS = 1_138_753_594;
    private static final int FID_SIMPLE_ICON = 1_139_806_224;
    private static final int FID_DUAL_ICON = 1_139_806_256;
    private static final int FID_DISTANCE = 1_139_806_232;
    private static final int FID_ROAD = 1_140_461_576;
    private final Context context;
    private final Handler ownerHandler;
    private final InstrumentProxyManager instrument;
    private final AtomicLong transactionSequence = new AtomicLong();
    private volatile String ownerPackage = "";
    private volatile long ownerGeneration = Long.MIN_VALUE;
    private volatile boolean routeActive;
    private boolean teardownEligible;
    private boolean ownerHasHudPriority;
    private volatile long routeToken;
    private String teardownOwner = "";
    private long teardownGeneration = Long.MIN_VALUE;
    private String teardownReason = "";
    private boolean hasInstrumentStatus;
    private int lastInstrumentStatus;
    private boolean hasInstrumentGuidance;
    private int lastInstrumentIcon;
    private int lastInstrumentDistance = -1;
    private String lastInstrumentRoad = "";
    private int[] lastInstrumentLaneDirections = new int[0];
    private int[] lastInstrumentLaneRecommendations = new int[0];
    private boolean lastGuidanceHasAmapFallback;
    private boolean hasLastInstrumentSemantic;
    private boolean hasLastAmapSemantic;
    private String lastAmapSemanticKey = "";

    VehicleTbtPublisher(Context context, Handler ownerHandler) {
        this.context = context.getApplicationContext();
        this.ownerHandler = ownerHandler;
        this.instrument = InstrumentProxyManager.get(this.context);
        this.instrument.addReadyListener(
                () -> this.ownerHandler.post(this::replayInstrumentState));
        this.instrument.addUnavailableListener(reason -> this.ownerHandler.post(
                () -> handleInstrumentUnavailable(reason)));
    }

    void beginRoute(String packageName, long generation, boolean switchDashboard) {
        beginRoute(packageName, generation, switchDashboard, false, "route-start");
    }

    void beginRoute(String packageName, long generation,
            boolean switchDashboard, boolean hasHudPriority) {
        beginRoute(packageName, generation, switchDashboard, hasHudPriority, "route-start");
    }

    void beginRoute(String packageName, long generation,
            boolean switchDashboard, boolean hasHudPriority, String reason) {
        beginRoute(packageName, generation, switchDashboard,
                hasHudPriority, reason, null);
    }

    void beginRoute(String packageName, long generation,
            boolean switchDashboard, boolean hasHudPriority,
            String reason, Runnable dashboardCompletion) {
        String owner = safe(packageName);
        if (owner.isEmpty()) return;
        if (routeActive && MANUAL_OWNER.equals(ownerPackage) && !MANUAL_OWNER.equals(owner)) {
            log("tbt_route_start ignored manual_owner incoming=" + owner);
            return;
        }
        if (routeActive && owner.equals(ownerPackage)) {
            if (shouldIgnoreOwnerGenerationForTest(
                    routeActive, ownerPackage, ownerGeneration, owner, generation)) {
                log("tbt_route_start ignored stale_generation owner=" + owner
                        + " generation=" + generation + " current=" + ownerGeneration);
                return;
            }
            boolean generationChanged = shouldClearGuidanceForGenerationReplacementForTest(
                    routeActive, ownerPackage, ownerGeneration, owner, generation);
            boolean gainedHudPriority = !ownerHasHudPriority && hasHudPriority;
            if (generationChanged) {
                Trace replaced = trace(
                        ownerPackage, ownerGeneration,
                        "route-generation-replaced", null, null);
                lastGuidanceHasAmapFallback = false;
                sendTerminalGuidanceClear(replaced);
                sendAmapTerminal(replaced);
                ownerGeneration = generation;
                ++routeToken;
                Trace started = trace(owner, generation, reason, null, null);
                sendStatus(STATUS_ACTIVE, started);
                record(started, "lifecycle", "begin", "navigation",
                        null, 0, 0L, "");
                log("tbt_route_generation owner=" + owner + " generation=" + generation);
            }
            ownerHasHudPriority = hasHudPriority;
            if (switchDashboard && gainedHudPriority) {
                dispatchDashboard(owner, generation,
                        trace(owner, generation, reason, null, null), dashboardCompletion);
            } else if (dashboardCompletion != null) {
                dashboardCompletion.run();
            }
            return;
        }
        if (routeActive && ownerHasHudPriority && !hasHudPriority) {
            log("tbt_route_start ignored lower_priority owner=" + owner
                    + " current=" + ownerPackage);
            return;
        }
        if (routeActive) {
            boolean replacingDirectWithManual = MANUAL_OWNER.equals(owner)
                    && !MANUAL_OWNER.equals(ownerPackage);
            endRoute(ownerPackage, ownerGeneration, "source-replaced",
                    !replacingDirectWithManual);
        }
        ownerPackage = owner;
        ownerGeneration = generation;
        routeActive = true;
        resetGuidanceDedup();
        teardownEligible = false;
        ownerHasHudPriority = hasHudPriority;
        ++routeToken;
        teardownOwner = "";
        teardownGeneration = Long.MIN_VALUE;
        teardownReason = "";
        Trace trace = trace(owner, generation, reason, null, null);
        sendStatus(STATUS_ACTIVE, trace);
        if (switchDashboard) {
            dispatchDashboard(owner, generation, trace, dashboardCompletion);
        } else if (dashboardCompletion != null) {
            dashboardCompletion.run();
        }
        record(trace, "lifecycle", "begin", "navigation", null, 0, 0L, "");
        log("tbt_route_start owner=" + owner + " generation=" + generation);
    }

    void publishFrame(String packageName, long generation, DirectTbtFrame frame) {
        publishFrame(packageName, generation, frame, "frame");
    }

    void publishFrame(String packageName, long generation,
            DirectTbtFrame frame, String reason) {
        if (frame == null) return;
        if (!matches(packageName, generation)) {
            if (routeActive) return;
            beginRoute(packageName, generation, false, false);
        }
        if (!routeActive || !matches(packageName, generation)) return;
        Trace trace = trace(packageName, generation, reason, frame, null);
        lastGuidanceHasAmapFallback = true;
        sendInstrumentFrame(frame, trace);
        sendAmapFrame(frame, trace);
    }

    void publishManualFrame(String packageName, long generation,
            HudState state, String reason) {
        if (state == null || !routeActive || !matches(packageName, generation)) return;
        ManualMapping mapping = manualMappingForTest(
                state.turnBitmapId, state.maneuverId);
        Trace trace = trace(packageName, generation, reason, null, state);
        int distance = Math.max(0, state.distanceToIntersection);
        String road = safe(state.roadName);
        lastGuidanceHasAmapFallback = mapping.amapSupported;
        sendInstrumentGuidance(
                mapping.instrumentId, distance, road, trace.lanes, trace, true);
        if (mapping.amapSupported) {
            sendAmapFrame(mapping.amapManeuver, mapping.roundaboutExit,
                    distance, road, DirectTbtFrame.TravelMetrics.unavailable(), trace, true);
        } else {
            record(trace, "amap_broadcast", "skip", "no_exact_mapping",
                    null, 0, 0L, "");
        }
    }

    void recordDeferredLifecycle(String packageName, long generation,
            String operation, String reason) {
        Trace trace = trace(packageName, generation, reason, null, null);
        record(trace, "arbitration", operation, "manual_owner",
                null, 0, 0L, "");
    }

    void recordDeferredFrame(String packageName, long generation,
            DirectTbtFrame frame, String reason) {
        if (frame == null) return;
        Trace trace = trace(packageName, generation, reason, frame, null);
        record(trace, "arbitration", "cache", "manual_owner",
                null, 0, 0L, "");
    }

    void endRoute(String packageName, long generation, String reason) {
        endRoute(packageName, generation, reason, true);
    }

    void endManualRoute(String packageName, long generation, String reason) {
        endRoute(packageName, generation, reason, false);
    }

    private void endRoute(String packageName, long generation,
            String reason, boolean emitIdle) {
        if (!routeActive || !matches(packageName, generation)) return;
        String endedOwner = ownerPackage;
        long endedGeneration = ownerGeneration;
        Trace trace = trace(endedOwner, endedGeneration, reason, null, null);
        ++routeToken;
        lastGuidanceHasAmapFallback = false;
        sendTerminalGuidanceClear(trace);
        sendAmapTerminal(trace);
        if (emitIdle) sendStatus(STATUS_IDLE, trace);
        record(trace, "lifecycle", emitIdle ? "end_idle" : "end",
                "navigation", null, 0, 0L, "");
        log("tbt_route_end owner=" + ownerPackage + " generation=" + ownerGeneration
                + " reason=" + safe(reason));
        routeActive = false;
        ownerPackage = "";
        ownerGeneration = Long.MIN_VALUE;
        ownerHasHudPriority = false;
        teardownEligible = true;
        teardownOwner = endedOwner;
        teardownGeneration = endedGeneration;
        teardownReason = safe(reason);
    }

    void updateOwnerHudPriority(String packageName, long generation, boolean hasHudPriority) {
        updateOwnerHudPriority(packageName, generation, hasHudPriority, false);
    }

    void updateOwnerHudPriority(String packageName, long generation,
            boolean hasHudPriority, boolean switchDashboard) {
        if (matches(packageName, generation)) {
            boolean gainedHudPriority = !ownerHasHudPriority && hasHudPriority;
            ownerHasHudPriority = hasHudPriority;
            if (switchDashboard && gainedHudPriority) {
                dispatchDashboard(ownerPackage, ownerGeneration,
                        trace(ownerPackage, ownerGeneration,
                                "hud-priority-gained", null, null), null);
            }
        }
    }

    static boolean shouldReplaceOwnerForTest(
            boolean currentHasHudPriority, boolean nextHasHudPriority) {
        return !currentHasHudPriority || nextHasHudPriority;
    }

    static boolean shouldClearGuidanceForGenerationReplacementForTest(
            boolean active, String currentOwner, long currentGeneration,
            String incomingOwner, long incomingGeneration) {
        return active
                && safe(currentOwner).equals(safe(incomingOwner))
                && incomingGeneration > currentGeneration;
    }

    static boolean shouldIgnoreOwnerGenerationForTest(
            boolean active, String currentOwner, long currentGeneration,
            String incomingOwner, long incomingGeneration) {
        return active
                && safe(currentOwner).equals(safe(incomingOwner))
                && incomingGeneration < currentGeneration;
    }

    void sendTeardownStatus() {
        if (!teardownEligible || routeActive) return;
        Trace trace = trace(teardownOwner, teardownGeneration,
                teardownReason.isEmpty() ? "teardown" : teardownReason, null, null);
        sendStatus(STATUS_TEARDOWN, trace);
        record(trace, "lifecycle", "teardown", "navigation", null, 0, 0L, "");
        teardownEligible = false;
        log("tbt_status=4");
    }

    boolean isRouteActive() {
        return routeActive;
    }

    String ownerPackage() {
        return ownerPackage;
    }

    long ownerGeneration() {
        return ownerGeneration;
    }

    void reassertDashboardForCurrentRoute(
            String packageName, long generation, String reason) {
        if (!shouldReassertDashboardForTest(
                routeActive, ownerHasHudPriority,
                packageName, generation, ownerPackage, ownerGeneration)) {
            log("tbt_dashboard reassert skipped owner=" + safe(packageName)
                    + " generation=" + generation);
            return;
        }
        dispatchDashboard(
                ownerPackage, ownerGeneration,
                trace(ownerPackage, ownerGeneration, reason, null, null), null);
    }

    static boolean shouldReassertDashboardForTest(
            boolean routeActive, boolean ownerHasHudPriority,
            String packageName, long generation,
            String currentOwner, long currentGeneration) {
        return routeActive && ownerHasHudPriority
                && safe(packageName).equals(safe(currentOwner))
                && generation == currentGeneration;
    }

    static int instrumentManeuverForAmap(int amapManeuver) {
        switch (amapManeuver) {
            case 2: return 1;
            case 3: return 2;
            case 4: return 3;
            case 5: return 5;
            case 6: return 7;
            case 7: return 8;
            case 8: return 9;
            case 9: return 11;
            case 10: return 45;
            case 11: return 13;
            case 12: return 24;
            case 13: return 46;
            case 14: return 47;
            case 15: return 48;
            case 16: return 49;
            case 17: return 14;
            case 18: return 23;
            case 19: return 10;
            case 20: return 12;
            case 21: return 15;
            case 22: return 18;
            case 23: return 20;
            case 24: return 22;
            case 25: return 16;
            case 26: return 17;
            case 27: return 19;
            case 28: return 21;
            default: return 0;
        }
    }

    /** Resolves the Instrument symbol from the final serialized AMap icon and exit. */
    static int instrumentManeuverForAmap(int amapIcon, int roundaboutExit) {
        if (roundaboutExit >= 1 && roundaboutExit <= 10) {
            if (amapIcon == 11) return 24 + roundaboutExit;
            if (amapIcon == 17) return 34 + roundaboutExit;
        }
        return instrumentManeuverForAmap(amapIcon);
    }

    static ManualMapping manualMappingForTest(int nativeId) {
        int instrumentId = Math.max(0, Math.min(99, nativeId));
        if (instrumentId == HudState.NATIVE_BLANK_ID) {
            return new ManualMapping(0, -1, 0, true);
        }
        if (instrumentId >= 25 && instrumentId <= 34) {
            return new ManualMapping(instrumentId, 11, instrumentId - 24, true);
        }
        if (instrumentId >= 35 && instrumentId <= 44) {
            return new ManualMapping(instrumentId, 17, instrumentId - 34, true);
        }
        int amap;
        switch (instrumentId) {
            case 1: amap = 2; break;
            case 2: amap = 3; break;
            case 3: amap = 4; break;
            case 5: amap = 5; break;
            case 7: amap = 6; break;
            case 8: amap = 7; break;
            case 9: amap = 8; break;
            case 10: amap = 19; break;
            case 11: amap = 9; break;
            case 12: amap = 20; break;
            case 13: amap = 11; break;
            case 14: amap = 17; break;
            case 15: amap = 21; break;
            case 16: amap = 25; break;
            case 17: amap = 26; break;
            case 18: amap = 22; break;
            case 19: amap = 27; break;
            case 20: amap = 23; break;
            case 21: amap = 28; break;
            case 22: amap = 24; break;
            case 23: amap = 18; break;
            case 24: amap = 12; break;
            case 45: amap = 10; break;
            case 46: amap = 13; break;
            case 47: amap = 14; break;
            case 48: amap = 15; break;
            case 49: amap = 16; break;
            default: amap = 0; break;
        }
        return new ManualMapping(instrumentId, amap, 0, amap > 0);
    }

    static ManualMapping manualMappingForTest(int sourceId, int nativeId) {
        if (sourceId == 20 && nativeId == 11) {
            return new ManualMapping(12, 20, 0, true);
        }
        return manualMappingForTest(nativeId);
    }

    static boolean shouldDispatchDashboardForTest(
            boolean routeActive, String owner, long generation,
            String currentOwner, long currentGeneration, long token,
            long currentToken) {
        return routeActive
                && token == currentToken
                && safe(owner).equals(safe(currentOwner))
                && generation == currentGeneration;
    }

    static String roadTextForTest(DirectTbtFrame frame) {
        if (frame == null) return "";
        String road = preserveText(frame.getRoadText());
        if (!road.isEmpty()) return road;
        String cue = preserveText(frame.getCueText());
        return cue.isEmpty() ? " " : cue;
    }

    private boolean matches(String packageName, long generation) {
        return safe(packageName).equals(ownerPackage) && generation == ownerGeneration;
    }

    private boolean isCurrentRoute(String packageName, long generation, long token) {
        return shouldDispatchDashboardForTest(
                routeActive, packageName, generation,
                ownerPackage, ownerGeneration, token, routeToken);
    }

    private void dispatchDashboard(
            String owner, long generation, Trace trace, Runnable completion) {
        long currentRouteToken = routeToken;
        record(trace, "dashboard_30011", "request", "type=2",
                ints(2), 0, 0L, "");
        Thread dashboardWorker = new Thread(() -> {
            long startedAt = System.nanoTime();
            try {
                if (!isCurrentRoute(owner, generation, currentRouteToken)) {
                    record(trace, "dashboard_30011", "dispatch", "type=2",
                            ints(2), -1, elapsedMs(startedAt), "stale route");
                    log("tbt_dashboard operation=2 skipped stale_route");
                    return;
                }
                String result = StockMapProtocol30011.dispatch(
                        context, 2,
                        () -> isCurrentRoute(owner, generation, currentRouteToken));
                record(trace, "dashboard_30011", "dispatch", "type=2",
                        ints(2), result.isEmpty() ? 0 : -1, elapsedMs(startedAt), result);
                log("tbt_dashboard operation=2 result=" + safe(result));
            } finally {
                if (completion != null) completion.run();
            }
        }, "BydHudTbtDashboard");
        dashboardWorker.setDaemon(true);
        dashboardWorker.start();
    }

    private void sendStatus(int status, Trace trace) {
        hasInstrumentStatus = true;
        lastInstrumentStatus = status;
        instrument.sendNavigationStatus(status,
                result -> ownerHandler.post(() -> recordInstrumentResult(
                        result, trace, status, 0, -1, "")));
        log("tbt_status=" + status);
    }

    private void sendInstrumentFrame(DirectTbtFrame frame, Trace trace) {
        int icon = instrumentManeuverForAmap(
                frame.getAmapBroadcastManeuver(), frame.getRoundaboutExitNumber());
        int distance = Math.max(0, frame.getDistanceMeters());
        sendInstrumentGuidance(
                icon, distance, roadTextForTest(frame), trace.lanes, trace, true);
    }

    private void sendTerminalGuidanceClear(Trace trace) {
        hasInstrumentGuidance = true;
        lastInstrumentIcon = 0;
        lastInstrumentDistance = -1;
        lastInstrumentRoad = "";
        lastInstrumentLaneDirections = new int[0];
        lastInstrumentLaneRecommendations = new int[0];
        instrument.sendTerminalGuidanceClear(
                result -> ownerHandler.post(() -> recordInstrumentResult(
                        result, trace, 0, 0, -1, "")));
    }

    private void sendInstrumentGuidance(
            int icon, int distance, String road, LanePayload lanes,
            Trace trace, boolean deduplicate) {
        hasInstrumentGuidance = true;
        int nextIcon = Math.max(0, Math.min(49, icon));
        int nextDistance = Math.max(-1, Math.min(2_000_000, distance));
        String normalizedRoad = preserveText(road);
        String nextRoad = normalizedRoad.length() <= 512
                ? normalizedRoad : normalizedRoad.substring(0, 512);
        LanePayload nextLanes = lanes == null ? LanePayload.EMPTY : lanes;
        if (deduplicate && hasLastInstrumentSemantic
                && lastInstrumentIcon == nextIcon
                && lastInstrumentDistance == nextDistance
                && lastInstrumentRoad.equals(nextRoad)
                && Arrays.equals(lastInstrumentLaneDirections, nextLanes.directions)
                && Arrays.equals(lastInstrumentLaneRecommendations,
                        nextLanes.recommendations)) {
            record(trace, "instrument", "dedup", "guidance", guidanceBytes(
                    lastInstrumentIcon, lastInstrumentDistance, lastInstrumentRoad,
                    nextLanes), 0, 0L, "");
            return;
        }
        lastInstrumentIcon = nextIcon;
        lastInstrumentDistance = nextDistance;
        lastInstrumentRoad = nextRoad;
        lastInstrumentLaneDirections = nextLanes.directions.clone();
        lastInstrumentLaneRecommendations = nextLanes.recommendations.clone();
        int sentIcon = lastInstrumentIcon;
        int sentDistance = lastInstrumentDistance;
        String sentRoad = lastInstrumentRoad;
        int[] sentLaneDirections = lastInstrumentLaneDirections.clone();
        int[] sentLaneRecommendations = lastInstrumentLaneRecommendations.clone();
        instrument.sendGuidance(
                sentIcon, sentDistance, sentRoad,
                sentLaneDirections, sentLaneRecommendations,
                result -> ownerHandler.post(() -> recordInstrumentGuidanceResult(
                        result, trace, sentIcon, sentDistance, sentRoad,
                        sentLaneDirections, sentLaneRecommendations)));
        hasLastInstrumentSemantic = true;
    }

    private void sendAmapFrame(DirectTbtFrame frame, Trace trace) {
        sendAmapFrame(frame.getAmapBroadcastManeuver(), frame.getRoundaboutExitNumber(),
                frame.getDistanceMeters(), roadTextForTest(frame), selectMetrics(frame), trace, true);
    }

    private void sendAmapFrame(int amapIcon, int roundaboutExit, int distance,
            String road, DirectTbtFrame.TravelMetrics metrics, Trace trace) {
        sendAmapFrame(amapIcon, roundaboutExit, distance, road, metrics, trace, false);
    }

    private void sendAmapFrame(int amapIcon, int roundaboutExit, int distance,
            String road, DirectTbtFrame.TravelMetrics metrics, Trace trace,
            boolean deduplicate) {
        DirectTbtFrame.TravelMetrics selected = metrics == null
                ? DirectTbtFrame.TravelMetrics.unavailable() : metrics;
        String nextRoad = preserveText(road);
        String semanticKey = amapSemanticKey(amapIcon, roundaboutExit, distance,
                nextRoad, selected);
        if (deduplicate && hasLastAmapSemantic && semanticKey.equals(lastAmapSemanticKey)) {
            record(trace, "amap_broadcast", "dedup", "guidance", new byte[0], 0, 0L, "");
            return;
        }
        Intent intent = new Intent(AMAP_ACTION);
        intent.setPackage(AMAP_PACKAGE);
        addStockAmapFlags(intent);
        intent.putExtra("KEY_TYPE", 10001);
        intent.putExtra("TYPE", 0);
        intent.putExtra("EXTRA_STATE", 0);
        intent.putExtra("EXTRA_IS_FOREGROUND", 0);
        intent.putExtra("IS_BYD_MAP", true);
        intent.putExtra("IS_BYD_BAIDU_MAP", false);
        intent.putExtra("NEW_ICON", amapIcon);
        intent.putExtra("ROUNG_ABOUT_NUM", roundaboutExit);
        intent.putExtra("SEG_REMAIN_DIS", distance);
        intent.putExtra("NEXT_ROAD_NAME", nextRoad);
        intent.putExtra("ROUTE_REMAIN_DIS", toInt(selected.getRemainingDistanceMeters(), -1));
        intent.putExtra("ROUTE_REMAIN_TIME", toInt(selected.getRemainingTimeSeconds(), -1));
        if (sendAmap(intent, "frame", trace, amapBytes(
                10001, amapIcon, roundaboutExit, distance, nextRoad,
                toInt(selected.getRemainingDistanceMeters(), -1),
                toInt(selected.getRemainingTimeSeconds(), -1)))) {
            hasLastAmapSemantic = true;
            lastAmapSemanticKey = semanticKey;
        }
    }

    private void sendAmapTerminal(Trace trace) {
        Intent intent = new Intent(AMAP_ACTION);
        intent.setPackage(AMAP_PACKAGE);
        addStockAmapFlags(intent);
        intent.putExtra("KEY_TYPE", 10019);
        intent.putExtra("EXTRA_STATE", 9);
        intent.putExtra("EXTRA_IS_FOREGROUND", 1);
        intent.putExtra("IS_BYD_MAP", true);
        intent.putExtra("IS_BYD_BAIDU_MAP", false);
        intent.putExtra("NEW_ICON", -1);
        intent.putExtra("SEG_REMAIN_DIS", -1);
        intent.putExtra("NEXT_ROAD_NAME", "");
        intent.putExtra("ROUTE_REMAIN_DIS", -1);
        intent.putExtra("ROUTE_REMAIN_TIME", -1);
        sendAmap(intent, "terminal", trace,
                amapBytes(10019, -1, 0, -1, "", -1, -1));
        resetGuidanceDedup();
    }

    @SuppressLint("WrongConstant")
    private static void addStockAmapFlags(Intent intent) {
        // Hidden platform flag used by the stock LauncherMap broadcast contract.
        intent.addFlags(FLAG_RECEIVER_INCLUDE_BACKGROUND);
    }

    private static DirectTbtFrame.TravelMetrics selectMetrics(DirectTbtFrame frame) {
        DirectTbtFrame.TravelMetrics whole = frame.getTripMetrics().getWholeRoute();
        DirectTbtFrame.TravelMetrics next = frame.getTripMetrics().getNextStop();
        long arrival = whole.getArrivalTimeEpochMs() > 0L
                ? whole.getArrivalTimeEpochMs() : next.getArrivalTimeEpochMs();
        int zoneOffset = whole.getArrivalTimeEpochMs() > 0L
                ? whole.getArrivalZoneOffsetSeconds() : next.getArrivalZoneOffsetSeconds();
        long remainingTime = whole.getRemainingTimeSeconds() >= 0L
                ? whole.getRemainingTimeSeconds() : next.getRemainingTimeSeconds();
        long remainingDistance = whole.getRemainingDistanceMeters() >= 0L
                ? whole.getRemainingDistanceMeters() : next.getRemainingDistanceMeters();
        return new DirectTbtFrame.TravelMetrics(
                arrival, zoneOffset, remainingTime, remainingDistance);
    }

    private static String amapSemanticKey(int icon, int exit, int distance,
            String road, DirectTbtFrame.TravelMetrics metrics) {
        return icon + "|" + exit + "|" + distance + "|" + preserveText(road)
                + "|" + metrics.getRemainingDistanceMeters()
                + "|" + metrics.getRemainingTimeSeconds();
    }

    private void resetGuidanceDedup() {
        hasLastInstrumentSemantic = false;
        hasLastAmapSemantic = false;
        lastAmapSemanticKey = "";
    }

    static DirectTbtFrame.TravelMetrics selectMetricsForTest(DirectTbtFrame frame) {
        return selectMetrics(frame);
    }

    private static int toInt(long value, int fallback) {
        if (value < 0L) return fallback;
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private void replayInstrumentState() {
        if (!hasInstrumentStatus && !hasInstrumentGuidance) return;
        String owner = ownerPackage.isEmpty() ? teardownOwner : ownerPackage;
        long generation = ownerGeneration == Long.MIN_VALUE
                ? teardownGeneration : ownerGeneration;
        String source = sourceForOwner(owner);
        Trace trace = new Trace(source, safe(owner), generation,
                "tbt-" + transactionSequence.incrementAndGet(),
                "proxy-ready-replay", lastInstrumentIcon, 0, 0,
                lastInstrumentDistance, lastInstrumentRoad,
                DirectTbtFrame.TravelMetrics.unavailable(),
                DirectTbtFrame.TravelMetrics.unavailable(),
                new LanePayload(lastInstrumentLaneDirections,
                        lastInstrumentLaneRecommendations));
        if (hasInstrumentStatus) {
            int status = lastInstrumentStatus;
            instrument.sendNavigationStatus(status,
                    result -> ownerHandler.post(() -> recordInstrumentResult(
                            result, trace, status, 0, -1, "")));
        }
        if (hasInstrumentGuidance) {
            int icon = lastInstrumentIcon;
            int distance = lastInstrumentDistance;
            String road = lastInstrumentRoad;
            int[] laneDirections = lastInstrumentLaneDirections.clone();
            int[] laneRecommendations = lastInstrumentLaneRecommendations.clone();
            instrument.sendGuidance(
                    icon, distance, road, laneDirections, laneRecommendations,
                    result -> ownerHandler.post(() -> recordInstrumentGuidanceResult(
                            result, trace, icon, distance, road,
                            laneDirections, laneRecommendations)));
        }
        log("tbt_proxy_replay status="
                + (hasInstrumentStatus ? lastInstrumentStatus : -1)
                + " icon=" + (hasInstrumentGuidance ? lastInstrumentIcon : -1));
    }

    private void handleInstrumentUnavailable(String reason) {
        if (!hasInstrumentStatus && !hasInstrumentGuidance) return;
        String owner = ownerPackage.isEmpty() ? teardownOwner : ownerPackage;
        long generation = ownerGeneration == Long.MIN_VALUE
                ? teardownGeneration : ownerGeneration;
        Trace trace = new Trace(sourceForOwner(owner), safe(owner), generation,
                "tbt-" + transactionSequence.incrementAndGet(),
                "proxy-unavailable:" + safe(reason), lastInstrumentIcon, 0, 0,
                lastInstrumentDistance, lastInstrumentRoad,
                DirectTbtFrame.TravelMetrics.unavailable(),
                DirectTbtFrame.TravelMetrics.unavailable());
        if (shouldPreserveAmapFallbackForTest(
                routeActive, lastGuidanceHasAmapFallback)) {
            record(trace, "instrument_proxy", "unavailable", "preserve_amap_fallback",
                    null, -1, 0L, safe(reason));
            log("tbt_proxy_unavailable preserve_amap_fallback reason=" + safe(reason));
            return;
        }
        sendAmapTerminal(trace);
        record(trace, "instrument_proxy", "unavailable", "fallback_terminal",
                null, -1, 0L, safe(reason));
        log("tbt_proxy_unavailable fallback_terminal reason=" + safe(reason));
    }

    static boolean shouldPreserveAmapFallbackForTest(
            boolean activeRoute, boolean exactAmapFramePublished) {
        return activeRoute && exactAmapFramePublished;
    }

    private void recordInstrumentResult(
            InstrumentProxyManager.Result result, Trace trace,
            int status, int icon, int distance, String road) {
        if (result == null || !result.available || result.operations.isEmpty()) {
            String error = result == null ? "no result"
                    : result.error.isEmpty() ? "proxy unavailable" : result.error;
            record(trace, "instrument_proxy", "invoke",
                    status > 0 ? "navigation_status" : "guidance",
                    status > 0 ? ints(status)
                            : guidanceBytes(icon, distance, road, trace.lanes),
                    -1, 0L, error);
            return;
        }
        for (InstrumentProxyContract.Operation operation : result.operations) {
            String name = operation.name;
            int separator = name.indexOf(':');
            String plane = separator > 0 ? name.substring(0, separator) : "instrument_proxy";
            String target = separator > 0 ? name.substring(separator + 1) : name;
            byte[] arguments = instrumentArguments(
                    plane, target, status, icon, distance, road, trace.lanes);
            String error = operation.error.isEmpty() ? result.error : operation.error;
            record(trace, plane, "instrument_fid".equals(plane) ? "set" : "invoke",
                    target, arguments, operation.result, operation.durationMs, error);
        }
    }

    private void recordInstrumentGuidanceResult(
            InstrumentProxyManager.Result result, Trace trace,
            int icon, int distance, String road,
            int[] laneDirections, int[] laneRecommendations) {
        recordInstrumentResult(result, trace, 0, icon, distance, road);
        if (laneOperationsSucceededForTest(result == null ? null : result.operations)) return;
        if (lastInstrumentIcon == icon
                && lastInstrumentDistance == distance
                && lastInstrumentRoad.equals(road)
                && Arrays.equals(lastInstrumentLaneDirections, laneDirections)
                && Arrays.equals(lastInstrumentLaneRecommendations, laneRecommendations)) {
            hasLastInstrumentSemantic = false;
        }
    }

    static boolean laneOperationsSucceededForTest(
            List<InstrumentProxyContract.Operation> operations) {
        if (operations == null) return true;
        for (InstrumentProxyContract.Operation operation : operations) {
            if (operation == null) continue;
            if (!operation.name.startsWith("setting_fid:")
                    && !operation.name.startsWith("instrument_lane:")) {
                continue;
            }
            if (operation.result != 0 || !operation.error.isEmpty()) return false;
        }
        return true;
    }

    private static byte[] instrumentArguments(
            String plane, String target, int status,
            int icon, int distance, String road, LanePayload lanes) {
        if ("instrument_fid".equals(plane)) {
            int featureId;
            try {
                featureId = Integer.parseInt(target);
            } catch (NumberFormatException ignored) {
                return new byte[0];
            }
            if (featureId == FID_NAV_STATUS) return ints(featureId, status);
            if (featureId == FID_SIMPLE_ICON || featureId == FID_DUAL_ICON) {
                return ints(featureId, icon);
            }
            if (featureId == FID_DISTANCE) return ints(featureId, distance);
            if (featureId == FID_ROAD) {
                return withInt(featureId, preserveText(road).getBytes(StandardCharsets.UTF_16LE));
            }
            return ints(featureId);
        }
        if ("sendAutoNaviStatus".equals(target)) return ints(status);
        if ("sendSimpleGuidanceInfo".equals(target)) return ints(icon, distance);
        if ("sendNextPathName".equals(target)) {
            return preserveText(road).getBytes(StandardCharsets.UTF_8);
        }
        if ("sendLaneGuidanceInfo".equals(target)) {
            return laneBytes(lanes, distance);
        }
        return new byte[0];
    }

    private static byte[] guidanceBytes(int icon, int distance, String road) {
        byte[] text = preserveText(road).getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES * 3 + text.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        return buffer.putInt(icon).putInt(distance).putInt(text.length).put(text).array();
    }

    private static byte[] guidanceBytes(
            int icon, int distance, String road, LanePayload lanes) {
        byte[] guidance = guidanceBytes(icon, distance, road);
        byte[] lane = laneBytes(lanes, distance);
        ByteBuffer buffer = ByteBuffer.allocate(guidance.length + lane.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        return buffer.put(guidance).put(lane).array();
    }

    private static byte[] laneBytes(LanePayload lanes, int distance) {
        LanePayload selected = lanes == null ? LanePayload.EMPTY : lanes;
        int count = selected.directions.length;
        ByteBuffer buffer = ByteBuffer.allocate((count * 2 + 2) * Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(count).putInt(count == 0 ? -1 : Math.max(0, distance));
        for (int value : selected.directions) buffer.putInt(value);
        for (int value : selected.recommendations) buffer.putInt(value);
        return buffer.array();
    }

    private boolean sendAmap(Intent intent, String operation, Trace trace, byte[] arguments) {
        long startedAt = System.nanoTime();
        try {
            context.sendBroadcast(intent);
            record(trace, "amap_broadcast", operation, AMAP_ACTION,
                    arguments, 0, elapsedMs(startedAt), "");
            return true;
        } catch (RuntimeException error) {
            record(trace, "amap_broadcast", operation, AMAP_ACTION,
                    arguments, -1, elapsedMs(startedAt), describe(error));
            Log.w(TAG, "TBT AMap broadcast failed: " + operation, error);
            return false;
        }
    }

    private Trace trace(String owner, long generation, String reason,
            DirectTbtFrame frame, HudState manualState) {
        String source = sourceForOwner(owner);
        ManualMapping manualMapping = manualState == null ? null
                : manualMappingForTest(
                        manualState.turnBitmapId, manualState.maneuverId);
        int nativeId = manualState != null
                ? manualMapping.instrumentId
                : frame == null ? 0 : instrumentManeuverForAmap(
                        frame.getAmapBroadcastManeuver(), frame.getRoundaboutExitNumber());
        int intermediateAmapIcon = manualState != null
                ? manualMapping.amapManeuver
                : frame == null ? 0 : frame.getAmapManeuver();
        int amapIcon = manualState != null
                ? manualMapping.amapManeuver
                : frame == null ? 0 : frame.getAmapBroadcastManeuver();
        int roundaboutExit = manualState != null
                ? manualMapping.roundaboutExit
                : frame == null ? 0 : frame.getRoundaboutExitNumber();
        int distance = manualState != null ? manualState.distanceToIntersection
                : frame == null ? -1 : frame.getDistanceMeters();
        String road = manualState != null ? safe(manualState.roadName)
                : frame == null ? "" : roadTextForTest(frame);
        DirectTbtFrame.TravelMetrics route = frame == null
                ? DirectTbtFrame.TravelMetrics.unavailable()
                : frame.getTripMetrics().getWholeRoute();
        DirectTbtFrame.TravelMetrics next = frame == null
                ? DirectTbtFrame.TravelMetrics.unavailable()
                : frame.getTripMetrics().getNextStop();
        LanePayload lanes = LanePayload.EMPTY;
        if (HudPrefs.isLaneOutputEnabled(context)) {
            lanes = manualState == null
                    ? lanePayloadForTest(frame == null ? null : frame.getLanes())
                    : lanePayloadForTest(manualState);
        }
        return new Trace(source, safe(owner), generation,
                "tbt-" + transactionSequence.incrementAndGet(), safe(reason),
                nativeId, intermediateAmapIcon, amapIcon, roundaboutExit,
                distance, preserveText(road), route, next, lanes);
    }

    private static String sourceForOwner(String owner) {
        return MANUAL_OWNER.equals(owner) ? "manual"
                : WazeDirectChannel.OWNER_PACKAGE.equals(owner) ? "waze_direct"
                : GMapsDirectChannel.OWNER_PACKAGE.equals(owner) ? "gmaps_direct"
                : "vehicle_tbt";
    }

    private void record(Trace trace, String plane, String operation, String target,
            byte[] argumentBytes, int result, long durationMs, String error) {
        if (trace == null) return;
        TbtTxLog.record(context, TbtTxLog.Entry.builder()
                .source(trace.source)
                .owner(trace.owner)
                .generation(trace.generation)
                .transactionId(trace.transactionId)
                .reason(trace.reason)
                .plane(plane)
                .operation(operation)
                .target(target)
                .nativeId(trace.nativeId)
                .intermediateAmapIcon(trace.intermediateAmapIcon)
                .amapIcon(trace.amapIcon)
                .roundaboutExit(trace.roundaboutExit)
                .distanceMeters((long) trace.distanceMeters)
                .road(trace.road)
                .routeEtaMs(trace.route.getArrivalTimeEpochMs())
                .routeDurationSeconds(trace.route.getRemainingTimeSeconds())
                .routeDistanceMeters(trace.route.getRemainingDistanceMeters())
                .nextStopEtaMs(trace.next.getArrivalTimeEpochMs())
                .nextStopDurationSeconds(trace.next.getRemainingTimeSeconds())
                .nextStopDistanceMeters(trace.next.getRemainingDistanceMeters())
                .argumentBytes(argumentBytes)
                .result(result)
                .durationMs(durationMs)
                .error(error));
    }

    private static byte[] ints(int... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) buffer.putInt(value);
        return buffer.array();
    }

    private static byte[] withInt(int value, byte[] bytes) {
        byte[] safeBytes = bytes == null ? new byte[0] : bytes;
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + safeBytes.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        return buffer.putInt(value).put(safeBytes).array();
    }

    private static byte[] amapBytes(int keyType, int icon, int exit, int distance,
            String road, int remainingDistance, int remainingTime) {
        byte[] text = preserveText(road).getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES * 7 + text.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        return buffer.putInt(keyType).putInt(icon).putInt(exit).putInt(distance)
                .putInt(remainingDistance).putInt(remainingTime).putInt(text.length)
                .put(text).array();
    }

    private static long elapsedMs(long startedAtNanos) {
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    private static String describe(Throwable error) {
        if (error == null) return "";
        Throwable cause = error.getCause() == null ? error : error.getCause();
        String message = safe(cause.getMessage());
        return cause.getClass().getSimpleName() + (message.isEmpty() ? "" : ": " + message);
    }

    private void log(String line) {
        Log.i(TAG, line);
        AppEventLogger.event(context, "vehicle_tbt " + line);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String preserveText(String value) {
        return value == null ? "" : value;
    }

    static LanePayload lanePayloadForTest(List<DirectTbtFrame.Lane> lanes) {
        if (lanes == null || lanes.isEmpty()) return LanePayload.EMPTY;
        int[] directions = new int[Math.min(HudLaneModel.MAX_LANES, lanes.size())];
        int[] recommendations = new int[directions.length];
        int count = 0;
        for (DirectTbtFrame.Lane lane : lanes) {
            if (lane == null || count >= directions.length) continue;
            int direction = Math.max(0, Math.min(254, lane.getAmapCode()));
            directions[count] = direction;
            recommendations[count] = lane.isRecommended() ? direction : 255;
            count++;
        }
        return count == 0 ? LanePayload.EMPTY : new LanePayload(
                Arrays.copyOf(directions, count), Arrays.copyOf(recommendations, count));
    }

    static LanePayload lanePayloadForTest(HudState state) {
        if (state == null || !state.includeLaneBitmap) return LanePayload.EMPTY;
        HudLaneModel.LaneSpec[] lanes = HudLaneModel.parse(state);
        int[] directions = new int[lanes.length];
        int[] recommendations = new int[lanes.length];
        for (int index = 0; index < lanes.length; index++) {
            int direction = Math.max(0, Math.min(254, lanes[index].iconId));
            directions[index] = direction;
            recommendations[index] = lanes[index].recommended ? direction : 255;
        }
        return lanes.length == 0
                ? LanePayload.EMPTY : new LanePayload(directions, recommendations);
    }

    static final class LanePayload {
        static final LanePayload EMPTY = new LanePayload(new int[0], new int[0]);
        final int[] directions;
        final int[] recommendations;

        LanePayload(int[] directions, int[] recommendations) {
            this.directions = directions == null ? new int[0] : directions.clone();
            this.recommendations = recommendations == null
                    ? new int[0] : recommendations.clone();
        }
    }

    static final class ManualMapping {
        final int instrumentId;
        final int amapManeuver;
        final int roundaboutExit;
        final boolean amapSupported;

        ManualMapping(int instrumentId, int amapManeuver,
                int roundaboutExit, boolean amapSupported) {
            this.instrumentId = instrumentId;
            this.amapManeuver = amapManeuver;
            this.roundaboutExit = roundaboutExit;
            this.amapSupported = amapSupported;
        }
    }

    private static final class Trace {
        final String source;
        final String owner;
        final long generation;
        final String transactionId;
        final String reason;
        final int nativeId;
        final int intermediateAmapIcon;
        final int amapIcon;
        final int roundaboutExit;
        final int distanceMeters;
        final String road;
        final DirectTbtFrame.TravelMetrics route;
        final DirectTbtFrame.TravelMetrics next;
        final LanePayload lanes;

        Trace(String source, String owner, long generation, String transactionId,
                String reason, int nativeId, int amapIcon, int roundaboutExit,
                int distanceMeters, String road,
                DirectTbtFrame.TravelMetrics route,
                DirectTbtFrame.TravelMetrics next) {
            this(source, owner, generation, transactionId, reason, nativeId, 0,
                    amapIcon, roundaboutExit, distanceMeters, road, route, next,
                    LanePayload.EMPTY);
        }

        Trace(String source, String owner, long generation, String transactionId,
                String reason, int nativeId, int amapIcon, int roundaboutExit,
                int distanceMeters, String road,
                DirectTbtFrame.TravelMetrics route,
                DirectTbtFrame.TravelMetrics next, LanePayload lanes) {
            this(source, owner, generation, transactionId, reason, nativeId, 0,
                    amapIcon, roundaboutExit, distanceMeters, road, route, next, lanes);
        }

        Trace(String source, String owner, long generation, String transactionId,
                String reason, int nativeId, int intermediateAmapIcon, int amapIcon,
                int roundaboutExit, int distanceMeters, String road,
                DirectTbtFrame.TravelMetrics route,
                DirectTbtFrame.TravelMetrics next) {
            this(source, owner, generation, transactionId, reason, nativeId,
                    intermediateAmapIcon, amapIcon, roundaboutExit, distanceMeters,
                    road, route, next, LanePayload.EMPTY);
        }

        Trace(String source, String owner, long generation, String transactionId,
                String reason, int nativeId, int intermediateAmapIcon, int amapIcon,
                int roundaboutExit, int distanceMeters, String road,
                DirectTbtFrame.TravelMetrics route,
                DirectTbtFrame.TravelMetrics next, LanePayload lanes) {
            this.source = source;
            this.owner = owner;
            this.generation = generation;
            this.transactionId = transactionId;
            this.reason = reason;
            this.nativeId = nativeId;
            this.intermediateAmapIcon = intermediateAmapIcon;
            this.amapIcon = amapIcon;
            this.roundaboutExit = roundaboutExit;
            this.distanceMeters = distanceMeters;
            this.road = road;
            this.route = route;
            this.next = next;
            this.lanes = lanes == null ? LanePayload.EMPTY : lanes;
        }
    }

}
