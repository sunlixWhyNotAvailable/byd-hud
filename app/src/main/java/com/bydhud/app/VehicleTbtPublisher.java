package com.bydhud.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
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
    private static final String INSTRUMENT_CLASS =
            "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice";
    private static final String EVENT_VALUE_CLASS =
            "android.hardware.bydauto.BYDAutoEventValue";

    private final Context context;
    private final InstrumentApi instrument;
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

    VehicleTbtPublisher(Context context) {
        this.context = context.getApplicationContext();
        this.instrument = InstrumentApi.open(this.context);
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
            boolean generationChanged = generation != ownerGeneration;
            boolean gainedHudPriority = !ownerHasHudPriority && hasHudPriority;
            if (generationChanged) {
                ownerGeneration = generation;
                ++routeToken;
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
        sendDirectFrame(frame, trace);
        sendSdkFrame(frame, trace);
        sendAmapFrame(frame, trace);
    }

    void publishManualFrame(String packageName, long generation,
            HudState state, String reason) {
        if (state == null || !routeActive || !matches(packageName, generation)) return;
        ManualMapping mapping = manualMappingForTest(state.maneuverId);
        Trace trace = trace(packageName, generation, reason, null, state);
        int distance = Math.max(0, state.distanceToIntersection);
        String road = safe(state.roadName);
        setInt(FID_SIMPLE_ICON, mapping.instrumentId, trace);
        setInt(FID_DUAL_ICON, mapping.instrumentId, trace);
        setInt(FID_DISTANCE, distance, trace);
        setBytes(FID_ROAD, road.getBytes(StandardCharsets.UTF_16LE), trace);
        invokeSdk(instrument == null ? null : instrument.simple,
                "sendSimpleGuidanceInfo", trace,
                ints(mapping.instrumentId, distance), mapping.instrumentId, distance);
        invokeSdk(instrument == null ? null : instrument.next,
                "sendNextPathName", trace, road.getBytes(StandardCharsets.UTF_8), road);
        if (mapping.amapSupported) {
            sendAmapFrame(mapping.amapManeuver, mapping.roundaboutExit,
                    distance, road, DirectTbtFrame.TravelMetrics.unavailable(), trace);
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
        clearDirectFrame(trace);
        clearSdkFrame(trace);
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
        String road = safe(frame.getRoadText());
        return road.isEmpty() ? safe(frame.getCueText()) : road;
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
        setInt(FID_NAV_STATUS, status, trace);
        invokeSdk(instrument == null ? null : instrument.status,
                "sendAutoNaviStatus", trace, ints(status), status);
        log("tbt_status=" + status);
    }

    private void sendDirectFrame(DirectTbtFrame frame, Trace trace) {
        int icon = instrumentManeuverForAmap(frame.getAmapManeuver());
        int distance = Math.max(0, frame.getDistanceMeters());
        setInt(FID_SIMPLE_ICON, icon, trace);
        setInt(FID_DUAL_ICON, icon, trace);
        setInt(FID_DISTANCE, distance, trace);
        setBytes(FID_ROAD, roadTextForTest(frame).getBytes(StandardCharsets.UTF_16LE), trace);
    }

    private void clearDirectFrame(Trace trace) {
        setInt(FID_SIMPLE_ICON, 0, trace);
        setInt(FID_DUAL_ICON, 0, trace);
        setInt(FID_DISTANCE, -1, trace);
        setBytes(FID_ROAD, new byte[0], trace);
    }

    private void sendSdkFrame(DirectTbtFrame frame, Trace trace) {
        int icon = instrumentManeuverForAmap(frame.getAmapManeuver());
        int distance = Math.max(0, frame.getDistanceMeters());
        invokeSdk(instrument == null ? null : instrument.simple,
                "sendSimpleGuidanceInfo", trace, ints(icon, distance), icon, distance);
        String road = roadTextForTest(frame);
        invokeSdk(instrument == null ? null : instrument.next,
                "sendNextPathName", trace, road.getBytes(StandardCharsets.UTF_8), road);
    }

    private void clearSdkFrame(Trace trace) {
        invokeSdk(instrument == null ? null : instrument.simple,
                "sendSimpleGuidanceInfo", trace, ints(0, -1), 0, -1);
        invokeSdk(instrument == null ? null : instrument.next,
                "sendNextPathName", trace, new byte[0], "");
    }

    private void sendAmapFrame(DirectTbtFrame frame, Trace trace) {
        sendAmapFrame(frame.getAmapBroadcastManeuver(), frame.getRoundaboutExitNumber(),
                frame.getDistanceMeters(), roadTextForTest(frame), selectMetrics(frame), trace);
    }

    private void sendAmapFrame(int amapIcon, int roundaboutExit, int distance,
            String road, DirectTbtFrame.TravelMetrics metrics, Trace trace) {
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
        intent.putExtra("NEXT_ROAD_NAME", safe(road));
        intent.putExtra("ROUTE_REMAIN_DIS", toInt(metrics.getRemainingDistanceMeters(), -1));
        intent.putExtra("ROUTE_REMAIN_TIME", toInt(metrics.getRemainingTimeSeconds(), -1));
        sendAmap(intent, "frame", trace, amapBytes(
                10001, amapIcon, roundaboutExit, distance, road,
                toInt(metrics.getRemainingDistanceMeters(), -1),
                toInt(metrics.getRemainingTimeSeconds(), -1)));
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

    static DirectTbtFrame.TravelMetrics selectMetricsForTest(DirectTbtFrame frame) {
        return selectMetrics(frame);
    }

    private static int toInt(long value, int fallback) {
        if (value < 0L) return fallback;
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private void setInt(int featureId, int value, Trace trace) {
        invokeDirect(featureId, value, null, trace);
    }

    private void setBytes(int featureId, byte[] value, Trace trace) {
        invokeDirect(featureId, 0, value, trace);
    }

    private void invokeDirect(int featureId, int intValue, byte[] bytes, Trace trace) {
        long startedAt = System.nanoTime();
        byte[] arguments = bytes == null ? ints(featureId, intValue) : withInt(featureId, bytes);
        if (instrument == null || instrument.writer == null) {
            record(trace, "instrument_fid", "set", String.valueOf(featureId),
                    arguments, -1, elapsedMs(startedAt), "unavailable");
            return;
        }
        try {
            Object eventValue = instrument.writer.constructor.newInstance();
            if (bytes == null) instrument.writer.intField.setInt(eventValue, intValue);
            else instrument.writer.bytesField.set(eventValue, bytes);
            Object result = instrument.writer.set.invoke(
                    instrument.writer.device, new int[]{featureId}, eventValue);
            int resultCode = resultCode(result);
            record(trace, "instrument_fid", "set", String.valueOf(featureId),
                    arguments, resultCode, elapsedMs(startedAt),
                    success(result) ? "" : "call returned failure");
            if (!success(result)) log("tbt_fid_failed fid=" + featureId);
        } catch (Throwable error) {
            record(trace, "instrument_fid", "set", String.valueOf(featureId),
                    arguments, -1, elapsedMs(startedAt), describe(error));
            log("tbt_fid_exception fid=" + featureId + " type="
                    + error.getClass().getSimpleName());
        }
    }

    private void invokeSdk(Method method, String target, Trace trace,
            byte[] arguments, Object... args) {
        long startedAt = System.nanoTime();
        if (method == null || instrument == null || instrument.device == null) {
            record(trace, "instrument_sdk", "invoke", target,
                    arguments, -1, elapsedMs(startedAt), "unavailable");
            return;
        }
        try {
            Object result = method.invoke(instrument.device, args);
            record(trace, "instrument_sdk", "invoke", target,
                    arguments, resultCode(result), elapsedMs(startedAt),
                    success(result) ? "" : "call returned failure");
            if (!success(result)) Log.w(TAG, "TBT SDK call returned failure: " + method.getName());
        } catch (Throwable error) {
            record(trace, "instrument_sdk", "invoke", target,
                    arguments, -1, elapsedMs(startedAt), describe(error));
            Log.w(TAG, "TBT SDK call failed: " + method.getName(), error);
        }
    }

    private void sendAmap(Intent intent, String operation, Trace trace, byte[] arguments) {
        long startedAt = System.nanoTime();
        try {
            context.sendBroadcast(intent);
            record(trace, "amap_broadcast", operation, AMAP_ACTION,
                    arguments, 0, elapsedMs(startedAt), "");
        } catch (RuntimeException error) {
            record(trace, "amap_broadcast", operation, AMAP_ACTION,
                    arguments, -1, elapsedMs(startedAt), describe(error));
            Log.w(TAG, "TBT AMap broadcast failed: " + operation, error);
        }
    }

    private static boolean success(Object result) {
        if (result instanceof Number) return ((Number) result).intValue() == 0;
        if (result instanceof Boolean) return (Boolean) result;
        return true;
    }

    private static int resultCode(Object result) {
        if (result instanceof Number) return ((Number) result).intValue();
        if (result instanceof Boolean) return (Boolean) result ? 0 : -1;
        return 0;
    }

    private Trace trace(String owner, long generation, String reason,
            DirectTbtFrame frame, HudState manualState) {
        String source = MANUAL_OWNER.equals(owner) ? "manual"
                : WazeDirectChannel.OWNER_PACKAGE.equals(owner) ? "waze_direct"
                : GMapsDirectChannel.OWNER_PACKAGE.equals(owner) ? "gmaps_direct"
                : "vehicle_tbt";
        int nativeId = manualState != null
                ? manualMappingForTest(manualState.maneuverId).instrumentId
                : frame == null ? 0 : instrumentManeuverForAmap(frame.getAmapManeuver());
        int amapIcon = manualState != null
                ? manualMappingForTest(manualState.maneuverId).amapManeuver
                : frame == null ? 0 : frame.getAmapBroadcastManeuver();
        int roundaboutExit = manualState != null
                ? manualMappingForTest(manualState.maneuverId).roundaboutExit
                : frame == null ? 0 : frame.getRoundaboutExitNumber();
        int distance = manualState != null ? manualState.distanceToIntersection
                : frame == null ? -1 : frame.getDistanceMeters();
        String road = manualState != null ? manualState.roadName
                : frame == null ? "" : roadTextForTest(frame);
        DirectTbtFrame.TravelMetrics route = frame == null
                ? DirectTbtFrame.TravelMetrics.unavailable()
                : frame.getTripMetrics().getWholeRoute();
        DirectTbtFrame.TravelMetrics next = frame == null
                ? DirectTbtFrame.TravelMetrics.unavailable()
                : frame.getTripMetrics().getNextStop();
        return new Trace(source, safe(owner), generation,
                "tbt-" + transactionSequence.incrementAndGet(), safe(reason),
                nativeId, amapIcon, roundaboutExit, distance, safe(road), route, next);
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
        byte[] text = safe(road).getBytes(StandardCharsets.UTF_8);
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
        final int amapIcon;
        final int roundaboutExit;
        final int distanceMeters;
        final String road;
        final DirectTbtFrame.TravelMetrics route;
        final DirectTbtFrame.TravelMetrics next;

        Trace(String source, String owner, long generation, String transactionId,
                String reason, int nativeId, int amapIcon, int roundaboutExit,
                int distanceMeters, String road,
                DirectTbtFrame.TravelMetrics route,
                DirectTbtFrame.TravelMetrics next) {
            this.source = source;
            this.owner = owner;
            this.generation = generation;
            this.transactionId = transactionId;
            this.reason = reason;
            this.nativeId = nativeId;
            this.amapIcon = amapIcon;
            this.roundaboutExit = roundaboutExit;
            this.distanceMeters = distanceMeters;
            this.road = road;
            this.route = route;
            this.next = next;
        }
    }

    private static final class InstrumentApi {
        final Object device;
        final Method status;
        final Method simple;
        final Method next;
        final DirectWriter writer;

        private InstrumentApi(Object device, Method status, Method simple, Method next,
                DirectWriter writer) {
            this.device = device;
            this.status = status;
            this.simple = simple;
            this.next = next;
            this.writer = writer;
        }

        static InstrumentApi open(Context context) {
            try {
                Class<?> instrumentClass = Class.forName(INSTRUMENT_CLASS);
                Class<?> eventClass = Class.forName(EVENT_VALUE_CLASS);
                Method getInstance = instrumentClass.getMethod("getInstance", Context.class);
                Object device = getInstance.invoke(null, new BydPermissionContext(context));
                Method status = instrumentClass.getMethod("sendAutoNaviStatus", int.class);
                Method simple = instrumentClass.getMethod(
                        "sendSimpleGuidanceInfo", int.class, int.class);
                Method next = instrumentClass.getMethod("sendNextPathName", String.class);
                Method set = instrumentClass.getMethod("set", int[].class, eventClass);
                Constructor<?> constructor = eventClass.getConstructor();
                Field intField = eventClass.getField("intValue");
                Field bytesField = eventClass.getField("bufferDataValue");
                return new InstrumentApi(device, status, simple, next,
                        new DirectWriter(device, set, constructor, intField, bytesField));
            } catch (Throwable error) {
                Log.i(TAG, "BYD Instrument API unavailable: "
                        + error.getClass().getSimpleName());
                return null;
            }
        }

        private static Method findMethod(Class<?> type, String name, Class<?>... parameters) {
            try {
                return type.getMethod(name, parameters);
            } catch (NoSuchMethodException ignored) {
                return null;
            }
        }
    }

    private static final class DirectWriter {
        final Object device;
        final Method set;
        final Constructor<?> constructor;
        final Field intField;
        final Field bytesField;

        DirectWriter(Object device, Method set, Constructor<?> constructor,
                Field intField, Field bytesField) {
            this.device = device;
            this.set = set;
            this.constructor = constructor;
            this.intField = intField;
            this.bytesField = bytesField;
        }
    }

    private static final class BydPermissionContext extends ContextWrapper {
        BydPermissionContext(Context base) {
            super(base);
        }

        @Override public int checkCallingOrSelfPermission(String permission) {
            return PackageManager.PERMISSION_GRANTED;
        }

        @Override public int checkCallingPermission(String permission) {
            return PackageManager.PERMISSION_GRANTED;
        }

        @Override public int checkPermission(String permission, int pid, int uid) {
            return PackageManager.PERMISSION_GRANTED;
        }

        @Override public void enforceCallingOrSelfPermission(String permission, String message) {
        }

        @Override public void enforceCallingPermission(String permission, String message) {
        }

        @Override public void enforcePermission(
                String permission, int pid, int uid, String message) {
        }
    }
}
