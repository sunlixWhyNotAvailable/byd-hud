package com.bydhud.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.TimeZone;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Publishes the normalized route frame to the dashboard TBT compatibility planes. */
final class VehicleTbtPublisher {
    static final int STATUS_IDLE = 1;
    static final int STATUS_ACTIVE = 2;
    static final int STATUS_TEARDOWN = 4;
    static final String MANUAL_OWNER = "bydhud.manual";

    private static final String TAG = "BydHudVehicleTbt";
    private static final String AMAP_ACTION = NativeEtaTransportPolicy.AMAP_ACTION;
    private static final String AMAP_PACKAGE = NativeEtaTransportPolicy.AMAP_PACKAGE_NAME;
    private static final int FLAG_RECEIVER_INCLUDE_BACKGROUND = 0x01000000;
    private static final int FID_NAV_STATUS = 1_138_753_594;
    private static final int FID_SIMPLE_ICON = 1_139_806_224;
    private static final int FID_DUAL_ICON = 1_139_806_256;
    private static final int FID_DISTANCE = 1_139_806_232;
    private static final int FID_ROAD = 1_140_461_576;
    private static final int FID_NATIVE_ETA_DAYS = 1_139_810_340;
    private static final int FID_NATIVE_ETA_HOURS = 1_139_810_320;
    private static final int FID_NATIVE_ETA_MINUTES = 1_139_810_328;
    private static final int FID_NATIVE_ETA_UNDER_30 = 1_139_810_334;
    private static final int FID_NATIVE_ETA_DISTANCE = 1_139_810_344;
    private static final int FID_NATIVE_ETA_ARRIVAL_DAY = 1_139_838_992;
    private static final int FID_NATIVE_ETA_ARRIVAL_HOUR = 1_139_839_000;
    private static final int FID_NATIVE_ETA_ARRIVAL_MINUTE = 1_139_839_008;
    private static final int FID_NATIVE_ETA_ARRIVAL_SECOND = 1_139_839_016;
    private static final int CACHE_KEY_STATISTIC_DAY_PERIOD = Integer.MIN_VALUE;
    private static final int[] NATIVE_ETA_INSTRUMENT_FIDS = {
            FID_NATIVE_ETA_DAYS, FID_NATIVE_ETA_HOURS, FID_NATIVE_ETA_MINUTES,
            FID_NATIVE_ETA_UNDER_30, FID_NATIVE_ETA_DISTANCE,
            FID_NATIVE_ETA_ARRIVAL_DAY, FID_NATIVE_ETA_ARRIVAL_HOUR,
            FID_NATIVE_ETA_ARRIVAL_MINUTE, FID_NATIVE_ETA_ARRIVAL_SECOND,
            CACHE_KEY_STATISTIC_DAY_PERIOD
    };
    private static final long NATIVE_ETA_PERIOD_MS =
            NativeEtaTransportPolicy.PERIODIC_DISPATCH_INTERVAL_MS;
    private static final long DIRECT_ETA_RETRY_AFTER_FAILED_STATISTIC_MS = 3_500L;
    private final Context context;
    private final Handler ownerHandler;
    private final InstrumentProxyManager instrument;
    private final AtomicLong transactionSequence = new AtomicLong();
    private final AtomicLong nativeEtaAdmissionRevision = new AtomicLong();
    private final Map<Integer, NativeEtaFieldCache> nativeEtaFieldCache = new HashMap<>();
    private final Runnable nativeEtaTick = this::handleNativeEtaTick;
    private volatile String ownerPackage = "";
    private volatile long ownerGeneration = Long.MIN_VALUE;
    private volatile boolean routeActive;
    private int routeManeuverCompatibilityProfile = ManeuverCompatibilityProfile.AMAP;
    private int hudCheckManeuverCompatibilityProfile = ManeuverCompatibilityProfile.AMAP;
    private int teardownManeuverCompatibilityProfile = ManeuverCompatibilityProfile.AMAP;
    private boolean hudCheckProfileActive;
    private String lastManeuverMappingLogKey = "";
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
    private boolean handoffPendingInstrumentRecovery;
    private boolean hasLastInstrumentSemantic;
    private boolean hasLastAmapSemantic;
    private String lastAmapSemanticKey = "";
    private volatile int hudCheckInstrumentResult;
    private volatile int hudCheckAmapResult;
    private volatile int hudCheckLightResult;
    private volatile int hudCheckLightIndex = -1;
    private boolean hudCheckLightOwned;
    private long hudCheckLightLastAttemptMs;
    private NativeEtaTransportPolicy.ReceiverState amapReceiverState =
            NativeEtaTransportPolicy.ReceiverState.UNKNOWN;
    private String amapReceiverReason = "not-resolved";
    private DirectTbtFrame latestNativeEtaFrame;
    private Trace latestNativeEtaTrace;
    private NativeEtaSnapshot latestNativeEta;
    private String lastNativeEtaSemanticKey = "";
    private boolean nativeEtaTickScheduled;
    private boolean nativeEtaInFlight;
    private boolean nativeEtaDispatchPending;
    private boolean nativeEtaPendingPeriodic;
    private long nativeEtaDispatchSequence;
    private long activeNativeEtaDispatchSequence;
    private String activeNativeEtaSemanticKey = "";
    private long lastNativeEtaFailureLogAtMs = Long.MIN_VALUE;
    private int nativeEtaOutputMode = HudPrefs.ETA_OUTPUT_OFF;
    private int failedStatisticDayPeriod = -1;
    private long failedStatisticDayPeriodAtMs = Long.MIN_VALUE;
    private final HudCheckDiagnostics hudCheckDiagnostics = new HudCheckDiagnostics();
    private HudCheckState latestHudCheck;
    private String hudCheckInstrumentReason = "pending";
    private String hudCheckAmapReason = "pending";
    private String hudCheckLightReason = "pending";

    String hudCheckStatus(boolean ukrainian) {
        return hudCheckStatus(ukrainian ? "uk" : "en");
    }

    String hudCheckStatus(String language) {
        String result = "Instrument: " + checkResultText(hudCheckInstrumentResult, language)
                + " · AMap: " + checkResultText(hudCheckAmapResult, language);
        if (hudCheckLightIndex >= 0) {
            result += ("uk".equals(language) ? " · Світлофор: "
                    : "ru".equals(language) ? " · Светофор: " : " · Traffic light: ")
                    + checkResultText(hudCheckLightResult, language);
        }
        return result;
    }

    private static String checkResultText(int result, String language) {
        if (result > 0) return "uk".equals(language) ? "надіслано" : "ru".equals(language) ? "отправлено" : "sent";
        if (result < 0) return "uk".equals(language) ? "недоступно/помилка" : "ru".equals(language) ? "недоступно/ошибка" : "unavailable/error";
        return "uk".equals(language) ? "очікування" : "ru".equals(language) ? "ожидание" : "waiting";
    }

    private static String checkResultText(int result, boolean ukrainian) {
        if (result > 0) return ukrainian ? "надіслано" : "sent";
        if (result < 0) return ukrainian ? "недоступно/помилка" : "unavailable/error";
        return ukrainian ? "очікування" : "waiting";
    }

    VehicleTbtPublisher(Context context, Handler ownerHandler) {
        this.context = context.getApplicationContext();
        this.ownerHandler = ownerHandler;
        this.instrument = InstrumentProxyManager.get(this.context);
        this.instrument.addReadyListener(
                () -> this.ownerHandler.post(this::replayInstrumentState));
        this.instrument.addUnavailableListener(reason -> this.ownerHandler.post(
                () -> handleInstrumentUnavailable(reason)));
    }

    private void captureRouteManeuverCompatibilityProfile(String reason) {
        routeManeuverCompatibilityProfile = ManeuverCompatibilityProfile.normalize(
                HudPrefs.maneuverCompatibilityProfile(context));
        hudCheckProfileActive = false;
        lastManeuverMappingLogKey = "";
        log("tbt_maneuver_profile profile="
                + compatibilityProfileName(routeManeuverCompatibilityProfile)
                + " owner=" + ownerPackage + " generation=" + ownerGeneration
                + " reason=" + safe(reason));
    }

    private int compatibilityProfileFor(String owner, HudState manualState) {
        if (!routeActive && ownerPackage.isEmpty() && safe(owner).equals(teardownOwner)) {
            return teardownManeuverCompatibilityProfile;
        }
        if (MANUAL_OWNER.equals(owner)
                && ((manualState != null && manualState.hudCheck != null)
                || hudCheckProfileActive)) {
            return hudCheckManeuverCompatibilityProfile;
        }
        return routeManeuverCompatibilityProfile;
    }

    private static String compatibilityProfileName(int profile) {
        return ManeuverCompatibilityProfile.normalize(profile)
                == ManeuverCompatibilityProfile.BAIDU ? "baidu" : "amap";
    }

    private void logManeuverMapping(Trace trace) {
        if (trace == null || trace.mapping == null) return;
        ManeuverCompatibilityProfile.Mapping mapping = trace.mapping;
        String key = trace.compatibilityProfile + "|" + mapping.sourceIcon + "|"
                + mapping.sourceRoundaboutExit + "|" + mapping.instrumentIcon + "|"
                + mapping.broadcastIcon + "|" + mapping.broadcastRoundaboutExit
                + "|" + mapping.supported;
        if (key.equals(lastManeuverMappingLogKey)) return;
        lastManeuverMappingLogKey = key;
        log("tbt_maneuver profile=" + compatibilityProfileName(trace.compatibilityProfile)
                + " source=" + mapping.sourceIcon + "/" + mapping.sourceRoundaboutExit
                + " instrument=" + mapping.instrumentIcon + " broadcast="
                + mapping.broadcastIcon + "/" + mapping.broadcastRoundaboutExit
                + (mapping.supported ? "" : " mapped=false"));
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
            instrument.setOutputDemand(true, "route:" + sourceForOwner(owner));
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
                resetNativeEtaState("route-generation-replaced");
                resolveAmapReceiver("route-generation-replaced");
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
            if (replaceDirectRoute(
                    ownerPackage, ownerGeneration, owner, generation,
                    switchDashboard, hasHudPriority, reason, null,
                    dashboardCompletion)) {
                return;
            }
            boolean replacingDirectWithManual = MANUAL_OWNER.equals(owner)
                    && !MANUAL_OWNER.equals(ownerPackage);
            endRoute(ownerPackage, ownerGeneration, "source-replaced",
                    !replacingDirectWithManual);
        }
        resetNativeEtaState("route-start");
        resolveAmapReceiver("route-start");
        ownerPackage = owner;
        ownerGeneration = generation;
        routeActive = true;
        captureRouteManeuverCompatibilityProfile(reason);
        instrument.setOutputDemand(true, "route:" + sourceForOwner(owner));
        handoffPendingInstrumentRecovery = false;
        hasInstrumentGuidance = false;
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

    /**
     * Transfers an active direct TBT owner without exposing a terminal frame.
     * The owner/token advance happens before any successor Binder/broadcast work,
     * so callbacks from the superseded owner are fenced at the shared boundary.
     */
    boolean replaceDirectRoute(
            String currentPackage, long currentGeneration,
            String successorPackage, long successorGeneration,
            boolean switchDashboard, boolean successorHasHudPriority,
            String reason, DirectTbtFrame successorFrame,
            Runnable dashboardCompletion) {
        String current = safe(currentPackage);
        String successor = safe(successorPackage);
        if (!routeActive || !matches(current, currentGeneration)
                || !isDirectOwner(current) || !isDirectOwner(successor)
                || current.equals(successor)) {
            return false;
        }
        ownerPackage = successor;
        ownerGeneration = successorGeneration;
        ownerHasHudPriority = successorHasHudPriority;
        instrument.setOutputDemand(true, "handoff:" + sourceForOwner(successor));
        ++routeToken;
        resetNativeEtaState("direct-owner-replaced");
        resolveAmapReceiver("direct-owner-replaced");
        teardownEligible = false;
        teardownOwner = "";
        teardownGeneration = Long.MIN_VALUE;
        teardownReason = "";
        resetGuidanceDedup();
        hasInstrumentGuidance = false;
        lastGuidanceHasAmapFallback = false;
        handoffPendingInstrumentRecovery = successorFrame == null;

        Trace successorTrace = trace(successor, successorGeneration,
                reason, successorFrame, null);
        sendStatus(STATUS_ACTIVE, successorTrace);
        if (successorFrame != null) {
            lastGuidanceHasAmapFallback = true;
            sendInstrumentFrame(successorFrame, successorTrace);
            NativeEtaSnapshot etaSnapshot = updateNativeEta(
                    successorFrame, successorTrace, reason);
            dispatchNativeEtaSnapshot(etaSnapshot, successorFrame,
                    successorTrace, reason, false, true);
        }
        if (switchDashboard) {
            dispatchDashboard(successor, successorGeneration,
                    successorTrace, dashboardCompletion);
        } else if (dashboardCompletion != null) {
            dashboardCompletion.run();
        }
        record(successorTrace, "lifecycle", "replace", "navigation",
                null, 0, 0L, "direct-owner-handoff");
        log("tbt_route_replace previous=" + current
                + " successor=" + successor
                + " generation=" + successorGeneration);
        return true;
    }

    private static boolean isDirectOwner(String owner) {
        return WazeDirectChannel.OWNER_PACKAGE.equals(owner)
                || GMapsDirectChannel.OWNER_PACKAGE.equals(owner);
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
        handoffPendingInstrumentRecovery = false;
        Trace trace = trace(packageName, generation, reason, frame, null);
        lastGuidanceHasAmapFallback = true;
        sendInstrumentFrame(frame, trace);
        NativeEtaSnapshot etaSnapshot = updateNativeEta(frame, trace, reason);
        dispatchNativeEtaSnapshot(etaSnapshot, frame, trace, reason, false, true);
    }

    void publishManualFrame(String packageName, long generation,
            HudState state, String reason) {
        if (state == null || !routeActive || !matches(packageName, generation)) return;
        if (state.hudCheck != null && !hudCheckProfileActive) {
            hudCheckManeuverCompatibilityProfile = ManeuverCompatibilityProfile.normalize(
                    HudPrefs.maneuverCompatibilityProfile(context));
            hudCheckProfileActive = true;
            lastManeuverMappingLogKey = "";
            log("tbt_maneuver_profile profile="
                    + compatibilityProfileName(hudCheckManeuverCompatibilityProfile)
                    + " owner=" + ownerPackage + " generation=" + ownerGeneration
                    + " reason=hud-check-start");
        } else if (state.hudCheck == null) {
            hudCheckProfileActive = false;
        }
        latestHudCheck = state.hudCheck;
        if (state.hudCheck != null) {
            int lightIndex = state.hudCheck.mode == HudCheckState.Mode.BASIC
                    ? state.hudCheck.trafficLightIndex : -1;
            publishHudCheckLight(lightIndex, reason, false);
        }
        ManualMapping mapping = manualMappingForTest(
                state.turnBitmapId, state.maneuverId);
        Trace trace = trace(packageName, generation, reason, null, state);
        int distance = Math.max(0, state.distanceToIntersection);
        String road = safe(state.roadName);
        logManeuverMapping(trace);
        boolean publishBroadcast = (state.hudCheck != null && state.hudCheck.isLiveCheck())
                || mapping.amapSupported
                || trace.compatibilityProfile == ManeuverCompatibilityProfile.BAIDU;
        lastGuidanceHasAmapFallback = publishBroadcast;
        sendInstrumentGuidance(
                trace.nativeId, distance, road, trace.lanes, trace, true);
        if (publishBroadcast) {
            sendAmapFrame(trace.amapIcon, trace.roundaboutExit,
                    distance, road, DirectTbtFrame.TravelMetrics.unavailable(), trace, true);
        } else {
            if (state.hudCheck != null) {
                hudCheckAmapResult = -1;
                hudCheckAmapReason = "no-exact-mapping";
                recordHudCheck(trace, "AMap", -1, hudCheckAmapReason);
            }
            record(trace, "amap_broadcast", "skip", "no_exact_mapping",
                    null, 0, 0L, "");
        }
        if (state.hudCheck != null && hudCheckLightIndex >= 0) {
            recordHudCheck(trace, "Instrument-light", hudCheckLightResult, hudCheckLightReason);
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
        if (MANUAL_OWNER.equals(ownerPackage)) publishHudCheckLight(-1, reason, true);
        String endedOwner = ownerPackage;
        long endedGeneration = ownerGeneration;
        Trace trace = trace(endedOwner, endedGeneration, reason, null, null);
        ++routeToken;
        resetNativeEtaState("route-end:" + safe(reason));
        handoffPendingInstrumentRecovery = false;
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
        teardownManeuverCompatibilityProfile = trace.compatibilityProfile;
        latestHudCheck = null;
        hudCheckProfileActive = false;
        hudCheckDiagnostics.reset();
        instrument.setOutputDemand(false, "route-end:" + safe(reason));
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
        long startedAt = System.nanoTime();
        NavAppDisplayController.get(context).requestAutomaticTbt(
                () -> isCurrentRoute(owner, generation, currentRouteToken),
                result -> {
                    try {
                        record(trace, "dashboard_30011", "dispatch", "type=2",
                                ints(2), result.isEmpty() ? 0 : -1, elapsedMs(startedAt), result);
                        log("tbt_dashboard operation=2 result=" + safe(result));
                    } finally {
                        if (completion != null) completion.run();
                    }
                });
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
        logManeuverMapping(trace);
        int distance = Math.max(0, frame.getDistanceMeters());
        sendInstrumentGuidance(
                trace.nativeId, distance, roadTextForTest(frame), trace.lanes, trace, true);
    }

    private void sendTerminalGuidanceClear(Trace trace) {
        handoffPendingInstrumentRecovery = false;
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
        boolean extendedBaiduIcon = trace != null
                && trace.compatibilityProfile == ManeuverCompatibilityProfile.BAIDU
                && ManeuverCompatibilityProfile.isBaiduInstrumentIcon(icon);
        int nextIcon = extendedBaiduIcon ? icon : Math.max(0, Math.min(49, icon));
        int nextDistance = Math.max(-1, Math.min(2_000_000, distance));
        String normalizedRoad = preserveText(road);
        String nextRoad = normalizedRoad.length() <= 512
                ? normalizedRoad : normalizedRoad.substring(0, 512);
        LanePayload nextLanes = lanes == null ? LanePayload.EMPTY : lanes;
        boolean sameGuidance = lastInstrumentIcon == nextIcon
                && lastInstrumentDistance == nextDistance
                && lastInstrumentRoad.equals(nextRoad)
                && Arrays.equals(lastInstrumentLaneDirections, nextLanes.directions)
                && Arrays.equals(lastInstrumentLaneRecommendations,
                        nextLanes.recommendations);
        if (deduplicate && hasLastInstrumentSemantic && sameGuidance) {
            record(trace, "instrument", "dedup", "guidance", guidanceBytes(
                    lastInstrumentIcon, lastInstrumentDistance, lastInstrumentRoad,
                    nextLanes), 0, 0L, "");
            recordHudCheck(trace, "Instrument", hudCheckInstrumentResult, hudCheckInstrumentReason);
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
        if (trace.hudCheck != null && !sameGuidance) {
            hudCheckInstrumentResult = 0;
            hudCheckInstrumentReason = "pending";
            recordHudCheck(trace, "Instrument", 0, hudCheckInstrumentReason);
        }
        instrument.sendGuidance(
                sentIcon, sentDistance, sentRoad,
                sentLaneDirections, sentLaneRecommendations,
                result -> ownerHandler.post(() -> recordInstrumentGuidanceResult(
                        result, trace, sentIcon, sentDistance, sentRoad,
                        sentLaneDirections, sentLaneRecommendations)));
        hasLastInstrumentSemantic = true;
    }

    void refreshNativeEta() {
        DirectTbtFrame frame = latestNativeEtaFrame;
        resetNativeEtaCaches("preference-change");
        lastNativeEtaSemanticKey = "";
        hasLastAmapSemantic = false;
        if (!routeActive || frame == null) {
            lastNativeEtaSemanticKey = "";
            return;
        }
        Trace trace = trace(ownerPackage, ownerGeneration,
                "native-eta-preference-change", frame, null);
        NativeEtaSnapshot snapshot = updateNativeEta(
                frame, trace, "preference-change");
        dispatchNativeEtaSnapshot(snapshot, frame, trace,
                "preference-change", false, true);
    }

    void onPackageChanged(String packageName) {
        if (!AMAP_PACKAGE.equals(packageName)) return;
        resolveAmapReceiver("package-change");
        if (!routeActive || latestNativeEtaFrame == null) return;
        DirectTbtFrame frame = latestNativeEtaFrame;
        resetNativeEtaCaches("package-change");
        lastNativeEtaSemanticKey = "";
        hasLastAmapSemantic = false;
        Trace trace = trace(ownerPackage, ownerGeneration,
                "native-eta-package-change", frame, null);
        NativeEtaSnapshot snapshot = updateNativeEta(
                frame, trace, "package-change");
        dispatchNativeEtaSnapshot(snapshot, frame, trace,
                "package-change", false, true);
    }

    private NativeEtaSnapshot updateNativeEta(
            DirectTbtFrame frame, Trace trace, String reason) {
        if (frame == null) return null;
        DirectTbtPayload.Options options = DirectTbtPayload.Options.from(context);
        nativeEtaOutputMode = options.etaOutputMode;
        latestNativeEtaFrame = frame;
        latestNativeEtaTrace = trace;
        if (options.etaOutputMode != HudPrefs.ETA_OUTPUT_NATIVE) {
            if (latestNativeEta != null || nativeEtaTickScheduled
                    || !lastNativeEtaSemanticKey.isEmpty()) {
                resetNativeEtaState("mode-disabled");
                latestNativeEtaFrame = frame;
                latestNativeEtaTrace = trace;
            }
            return null;
        }
        long nowWallMs = System.currentTimeMillis();
        NativeEtaData data = NativeEtaData.from(frame, options, nowWallMs);
        NativeEtaFormatter.Formatted formatted = NativeEtaFormatter.format(data,
                nowWallMs, TimeZone.getDefault(), options.nativeEta24Hour,
                java.util.Locale.getDefault());
        NativeEtaTransportPolicy.Writer writer = NativeEtaTransportPolicy.writerFor(
                options.etaOutputMode, amapReceiverState);
        String semanticKey = nativeEtaSemanticKey(
                options, data, formatted, writer);
        boolean semanticChanged = !semanticKey.equals(lastNativeEtaSemanticKey);
        if (semanticChanged) nativeEtaAdmissionRevision.incrementAndGet();
        NativeEtaSnapshot snapshot = new NativeEtaSnapshot(
                frame, trace, options, data, formatted, writer, semanticKey,
                nowWallMs, semanticChanged);
        latestNativeEtaFrame = frame;
        latestNativeEtaTrace = trace;
        latestNativeEta = snapshot;
        lastNativeEtaSemanticKey = semanticKey;
        ensureNativeEtaTick();
        if (semanticChanged) logNativeEtaSelection(snapshot, reason);
        return snapshot;
    }

    private void dispatchNativeEtaSnapshot(NativeEtaSnapshot snapshot,
            DirectTbtFrame frame, Trace trace, String reason,
            boolean periodic, boolean publishAmapGuidance) {
        if (!routeActive || frame == null || trace == null) return;
        NativeEtaTransportPolicy.Writer writer = snapshot == null
                ? NativeEtaTransportPolicy.Writer.NONE : snapshot.writer;
        if (publishAmapGuidance
                || (periodic && writer == NativeEtaTransportPolicy.Writer.AMAP)) {
            sendAmapFrame(frame, trace, snapshot, !periodic);
        }
        if (snapshot == null) return;
        if (snapshot.writer == NativeEtaTransportPolicy.Writer.INSTRUMENT) {
            sendNativeEtaInstrument(snapshot, reason, periodic);
        } else if (snapshot.writer == NativeEtaTransportPolicy.Writer.UNRESOLVED
                && snapshot.semanticChanged) {
            log("native_eta mode=" + snapshot.options.etaOutputMode
                    + " writer=unresolved reason=" + safe(reason)
                    + " receiver=" + amapReceiverReason);
        }
    }

    private void sendNativeEtaInstrument(
            NativeEtaSnapshot snapshot, String reason, boolean periodic) {
        if (snapshot == null || snapshot.writer
                != NativeEtaTransportPolicy.Writer.INSTRUMENT) return;
        long nowElapsedMs = SystemClock.elapsedRealtime();
        int[] values = nativeEtaInstrumentValues(snapshot.formatted);
        int[] dispatchValues = new int[values.length];
        Arrays.fill(dispatchValues, -1);
        for (int index = 0; index < values.length; index++) {
            int value = values[index];
            if (value < 0) continue;
            int cacheKey = NATIVE_ETA_INSTRUMENT_FIDS[index];
            NativeEtaFieldCache cached = nativeEtaFieldCache.get(cacheKey);
            boolean eligible = NativeEtaTransportPolicy.shouldDispatchDirectValue(
                    cached != null, cached == null ? -1 : cached.value,
                    cached == null ? -1L : cached.lastSuccessElapsedMs,
                    value, nowElapsedMs);
            if (index == 9 && failedStatisticDayPeriod == value
                    && nowElapsedMs >= failedStatisticDayPeriodAtMs
                    && nowElapsedMs - failedStatisticDayPeriodAtMs
                    < DIRECT_ETA_RETRY_AFTER_FAILED_STATISTIC_MS) {
                continue;
            }
            if (eligible) dispatchValues[index] = value;
        }
        boolean hasValue = false;
        for (int value : dispatchValues) hasValue |= value >= 0;
        if (nativeEtaInFlight) {
            if (!snapshot.semanticKey.equals(activeNativeEtaSemanticKey)) {
                nativeEtaDispatchPending = true;
                nativeEtaPendingPeriodic |= periodic;
            }
            return;
        }
        if (!hasValue) return;

        nativeEtaInFlight = true;
        nativeEtaDispatchPending = false;
        nativeEtaPendingPeriodic = false;
        activeNativeEtaSemanticKey = snapshot.semanticKey;
        long routeRequestToken = routeToken;
        long dispatchId = ++nativeEtaDispatchSequence;
        activeNativeEtaDispatchSequence = dispatchId;
        long admissionRevision = nativeEtaAdmissionRevision.get();
        int[] sentValues = dispatchValues.clone();
        long startedAtMs = SystemClock.elapsedRealtime();
        instrument.sendNativeEta(sentValues, nativeEtaAdmissionRevision,
                admissionRevision, result -> ownerHandler.post(() -> {
            long finishedAtMs = SystemClock.elapsedRealtime();
            recordNativeEtaInstrumentResult(result, snapshot.trace,
                    sentValues, elapsedBetween(startedAtMs, finishedAtMs));
            if (dispatchId != activeNativeEtaDispatchSequence
                    || routeRequestToken != routeToken) return;
            nativeEtaInFlight = false;
            cacheSuccessfulNativeEtaFields(result, sentValues, finishedAtMs);
            rememberFailedStatisticDayPeriod(result, sentValues, finishedAtMs);
            if (!nativeEtaResultSucceeded(result)) {
                if (lastNativeEtaFailureLogAtMs == Long.MIN_VALUE
                        || finishedAtMs - lastNativeEtaFailureLogAtMs >= 3_500L) {
                    lastNativeEtaFailureLogAtMs = finishedAtMs;
                    log("native_eta mode=" + snapshot.options.etaOutputMode
                            + " writer=instrument reason=" + safe(reason)
                            + " result=" + nativeEtaResultReason(result)
                            + " values=" + nativeEtaValuesText(sentValues));
                }
            }
            if (nativeEtaDispatchPending && latestNativeEta != null) {
                boolean nextPeriodic = nativeEtaPendingPeriodic;
                nativeEtaDispatchPending = false;
                nativeEtaPendingPeriodic = false;
                sendNativeEtaInstrument(latestNativeEta,
                        nextPeriodic ? "periodic" : "semantic-change", nextPeriodic);
            }
        }));
        if (snapshot.semanticChanged || periodic) {
            record(snapshot.trace, "instrument_proxy", "dispatch", "native_eta",
                    nativeEtaInstrumentBytes(sentValues), 0, 0L,
                    "writer=instrument reason=" + safe(reason));
        }
    }

    private void recordNativeEtaInstrumentResult(
            InstrumentProxyManager.Result result, Trace trace,
            int[] values, long durationMs) {
        if (result == null || !result.available || result.operations.isEmpty()) {
            String error = result == null ? "no result"
                    : result.error.isEmpty() ? "proxy unavailable" : result.error;
            record(trace, "instrument_proxy", "invoke", "native_eta",
                    nativeEtaInstrumentBytes(values), -1, durationMs, error);
            return;
        }
        for (InstrumentProxyContract.Operation operation : result.operations) {
            String name = operation.name;
            int separator = name.indexOf(':');
            String plane = separator > 0 ? name.substring(0, separator) : "instrument_proxy";
            String target = separator > 0 ? name.substring(separator + 1) : name;
            int featureId = parseTargetInt(target, -1);
            int value = nativeEtaValueForOperation(name, values);
            byte[] arguments = featureId >= 0 && value >= 0
                    ? ints(featureId, value) : nativeEtaInstrumentBytes(values);
            record(trace, plane, plane.endsWith("fid") ? "set" : "invoke",
                    target, arguments, operation.result, operation.durationMs,
                    operation.error.isEmpty() ? result.error : operation.error);
        }
    }

    private void cacheSuccessfulNativeEtaFields(
            InstrumentProxyManager.Result result, int[] sentValues, long nowElapsedMs) {
        if (result == null || !result.available) return;
        for (InstrumentProxyContract.Operation operation : result.operations) {
            if (operation.result != 0 || !operation.error.isEmpty()) continue;
            int separator = operation.name.indexOf(':');
            if (separator <= 0) continue;
            String prefix = operation.name.substring(0, separator);
            String target = operation.name.substring(separator + 1);
            if ("instrument_fid".equals(prefix)) {
                int featureId = parseTargetInt(target, -1);
                int value = nativeEtaValueForFid(featureId, sentValues);
                if (value >= 0) nativeEtaFieldCache.put(featureId,
                        new NativeEtaFieldCache(value, nowElapsedMs));
            } else if ("statistic_eta_period".equals(prefix)) {
                int value = sentValues[9];
                if (value >= 0 && parseTargetInt(target, -1) >= 0) {
                    nativeEtaFieldCache.put(CACHE_KEY_STATISTIC_DAY_PERIOD,
                            new NativeEtaFieldCache(value, nowElapsedMs));
                    failedStatisticDayPeriod = -1;
                    failedStatisticDayPeriodAtMs = Long.MIN_VALUE;
                }
            }
        }
    }

    private void rememberFailedStatisticDayPeriod(
            InstrumentProxyManager.Result result, int[] sentValues, long nowElapsedMs) {
        if (sentValues.length <= 9 || sentValues[9] < 0
                || result == null || !result.available) return;
        for (InstrumentProxyContract.Operation operation : result.operations) {
            if (operation.name.startsWith("statistic_eta_period:")
                    && (operation.result != 0 || !operation.error.isEmpty())) {
                failedStatisticDayPeriod = sentValues[9];
                failedStatisticDayPeriodAtMs = nowElapsedMs;
                return;
            }
        }
    }

    private boolean nativeEtaResultSucceeded(InstrumentProxyManager.Result result) {
        if (result == null || !result.available || result.operations.isEmpty()) return false;
        boolean hasWrite = false;
        for (InstrumentProxyContract.Operation operation : result.operations) {
            if (operation.name.startsWith("instrument_fid:")
                    || operation.name.startsWith("statistic_eta_period:")) {
                hasWrite = true;
                if (operation.result != 0 || !operation.error.isEmpty()) return false;
            }
        }
        return hasWrite;
    }

    private String nativeEtaResultReason(InstrumentProxyManager.Result result) {
        if (result == null || !result.available) {
            return result == null ? "no-result" : safe(result.error);
        }
        for (InstrumentProxyContract.Operation operation : result.operations) {
            if (operation.result != 0 || !operation.error.isEmpty()) {
                return operation.name + ":" + (operation.error.isEmpty()
                        ? operation.result : operation.error);
            }
        }
        return safe(result.error);
    }

    private static int nativeEtaValueForOperation(String name, int[] values) {
        if (name != null && name.startsWith("statistic_eta_period:")) {
            return values != null && values.length > 9 ? values[9] : -1;
        }
        int separator = name == null ? -1 : name.indexOf(':');
        if (separator <= 0 || !"instrument_fid".equals(name.substring(0, separator))) {
            return -1;
        }
        return nativeEtaValueForFid(parseTargetInt(name.substring(separator + 1), -1), values);
    }

    private static int nativeEtaValueForFid(int featureId, int[] values) {
        if (values == null || values.length != NATIVE_ETA_INSTRUMENT_FIDS.length) return -1;
        for (int index = 0; index < NATIVE_ETA_INSTRUMENT_FIDS.length - 1; index++) {
            if (NATIVE_ETA_INSTRUMENT_FIDS[index] == featureId) return values[index];
        }
        return -1;
    }

    private static int[] nativeEtaInstrumentValues(NativeEtaFormatter.Formatted value) {
        return NativeEtaTransportPolicy.instrumentValues(value);
    }

    private static byte[] nativeEtaInstrumentBytes(int[] values) {
        int[] safeValues = values == null ? new int[0] : values;
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES * safeValues.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int value : safeValues) buffer.putInt(value);
        return buffer.array();
    }

    private static byte[] nativeEtaAmapBytes(
            int distance, int seconds, String durationText, String arrivalText) {
        byte[] duration = preserveText(durationText).getBytes(StandardCharsets.UTF_8);
        byte[] arrival = preserveText(arrivalText).getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES * 4
                        + duration.length + arrival.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        return buffer.putInt(distance).putInt(seconds)
                .putInt(duration.length).put(duration)
                .putInt(arrival.length).put(arrival).array();
    }

    private static byte[] appendBytes(byte[] left, byte[] right) {
        byte[] first = left == null ? new byte[0] : left;
        byte[] second = right == null ? new byte[0] : right;
        ByteBuffer buffer = ByteBuffer.allocate(first.length + second.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        return buffer.put(first).put(second).array();
    }

    private static String amapString(String value) {
        return value == null ? "-1" : value;
    }

    private static long elapsedBetween(long startMs, long finishMs) {
        return finishMs >= startMs ? finishMs - startMs : 0L;
    }

    private static int parseTargetInt(String value, int fallback) {
        try { return Integer.parseInt(value); }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static String nativeEtaValuesText(int[] values) {
        if (values == null) return "";
        return "days=" + values[0] + " hours=" + values[1]
                + " minutes=" + values[2] + " under30=" + values[3]
                + " distanceM=" + values[4] + " arrivalDay=" + values[5]
                + " arrivalHour=" + values[6] + " arrivalMinute=" + values[7]
                + " arrivalSecond=" + values[8] + " dayPeriod=" + values[9];
    }

    private static String nativeEtaSemanticKey(
            DirectTbtPayload.Options options, NativeEtaData data,
            NativeEtaFormatter.Formatted formatted,
            NativeEtaTransportPolicy.Writer writer) {
        return options.etaOutputMode + "|" + options.routeMetricsMode + "|"
                + options.wholeRouteMetrics + "|" + options.showEta + "|"
                + options.showRemainingTime + "|" + options.showRemainingDistance + "|"
                + options.nativeEta24Hour + "|" + java.util.Locale.getDefault() + "|"
                + writer + "|" + data.remainingSeconds + "|" + data.remainingMeters + "|"
                + formatted.arrivalClock + "|" + formatted.arrivalQualifier + "|"
                + formatted.routeRemainTimeAuto + "|" + formatted.etaText + "|"
                + formatted.durationDays + "|" + formatted.durationHours + "|"
                + formatted.durationMinutes + "|" + formatted.remainingUnder30 + "|"
                + formatted.distanceMeters + "|" + formatted.arrivalDay + "|"
                + formatted.arrivalHour + "|" + formatted.arrivalMinute + "|"
                + formatted.arrivalSecond + "|" + formatted.dayPeriod;
    }

    private void logNativeEtaSelection(NativeEtaSnapshot snapshot, String reason) {
        log("native_eta mode=" + snapshot.options.etaOutputMode
                + " writer=" + snapshot.writer
                + " scope=" + snapshot.options.routeMetricsMode
                + " preferWhole=" + snapshot.options.wholeRouteMetrics
                + " showEta=" + snapshot.options.showEta
                + " showTime=" + snapshot.options.showRemainingTime
                + " showDistance=" + snapshot.options.showRemainingDistance
                + " reason=" + safe(reason)
                + " receiver=" + amapReceiverReason
                + " metrics=" + snapshot.data.diagnostics
                + " arrival=" + snapshot.formatted.arrivalClock
                + " qualifier=" + snapshot.formatted.arrivalQualifier
                + " duration=" + snapshot.formatted.routeRemainTimeAuto
                + " etaText=" + snapshot.formatted.etaText
                + " remainingSeconds=" + snapshot.data.remainingSeconds
                + " distanceMeters=" + snapshot.formatted.distanceMeters
                + " dayPeriod=" + snapshot.formatted.dayPeriod);
    }

    private void ensureNativeEtaTick() {
        if (nativeEtaTickScheduled || !routeActive) return;
        nativeEtaTickScheduled = true;
        ownerHandler.postDelayed(nativeEtaTick, NATIVE_ETA_PERIOD_MS);
    }

    private void handleNativeEtaTick() {
        nativeEtaTickScheduled = false;
        if (!routeActive || latestNativeEtaFrame == null) return;
        DirectTbtFrame frame = latestNativeEtaFrame;
        Trace trace = trace(ownerPackage, ownerGeneration,
                "native-eta-periodic", frame, null);
        NativeEtaSnapshot snapshot = updateNativeEta(
                frame, trace, "periodic");
        dispatchNativeEtaSnapshot(snapshot, frame, trace,
                "periodic", true, false);
    }

    private void resetNativeEtaCaches(String reason) {
        nativeEtaAdmissionRevision.incrementAndGet();
        nativeEtaFieldCache.clear();
        failedStatisticDayPeriod = -1;
        failedStatisticDayPeriodAtMs = Long.MIN_VALUE;
        nativeEtaDispatchSequence++;
        activeNativeEtaDispatchSequence = nativeEtaDispatchSequence;
        nativeEtaInFlight = false;
        nativeEtaDispatchPending = false;
        nativeEtaPendingPeriodic = false;
        activeNativeEtaSemanticKey = "";
        if (!safe(reason).isEmpty()) {
            log("native_eta cache_reset reason=" + safe(reason));
        }
    }

    private void resetNativeEtaState(String reason) {
        ownerHandler.removeCallbacks(nativeEtaTick);
        nativeEtaTickScheduled = false;
        resetNativeEtaCaches(reason);
        latestNativeEtaFrame = null;
        latestNativeEtaTrace = null;
        latestNativeEta = null;
        lastNativeEtaSemanticKey = "";
    }

    private void resolveAmapReceiver(String reason) {
        AmapReceiverResolution resolution = resolveAmapReceiver(context);
        amapReceiverState = resolution.state;
        amapReceiverReason = resolution.reason;
        log("native_eta receiver=" + amapReceiverState + " reason="
                + amapReceiverReason + " trigger=" + safe(reason));
    }

    private static AmapReceiverResolution resolveAmapReceiver(Context context) {
        PackageManager packageManager = context.getPackageManager();
        try {
            PackageInfo packageInfo = packageManager.getPackageInfo(AMAP_PACKAGE, 0);
            ApplicationInfo applicationInfo = packageInfo.applicationInfo;
            if (applicationInfo == null || !applicationInfo.enabled) {
                return new AmapReceiverResolution(
                        NativeEtaTransportPolicy.ReceiverState.UNAVAILABLE,
                        "package-disabled");
            }
            List<ResolveInfo> receivers = packageManager.queryBroadcastReceivers(
                    new Intent(AMAP_ACTION).setPackage(AMAP_PACKAGE),
                    PackageManager.MATCH_DISABLED_COMPONENTS);
            if (receivers == null) {
                return new AmapReceiverResolution(
                        NativeEtaTransportPolicy.ReceiverState.UNKNOWN,
                        "receiver-query-null");
            }
            for (ResolveInfo resolved : receivers) {
                ActivityInfo receiver = resolved == null ? null : resolved.activityInfo;
                if (receiver == null || !AMAP_PACKAGE.equals(receiver.packageName)) continue;
                if (!receiver.exported) continue;
                ComponentName component = new ComponentName(
                        receiver.packageName, receiver.name);
                int componentState = packageManager.getComponentEnabledSetting(component);
                boolean enabled = componentState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        || (componentState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                                && receiver.enabled);
                if (!enabled) {
                    continue;
                }
                if (receiver.permission != null
                        && context.checkSelfPermission(receiver.permission)
                        != PackageManager.PERMISSION_GRANTED) {
                    continue;
                }
                return new AmapReceiverResolution(
                        NativeEtaTransportPolicy.ReceiverState.AVAILABLE,
                        "enabled-accessible-receiver");
            }
            return new AmapReceiverResolution(
                    NativeEtaTransportPolicy.ReceiverState.UNAVAILABLE,
                    "no-enabled-accessible-receiver");
        } catch (PackageManager.NameNotFoundException absent) {
            return new AmapReceiverResolution(
                    NativeEtaTransportPolicy.ReceiverState.UNAVAILABLE,
                    "package-absent");
        } catch (RuntimeException uncertain) {
            return new AmapReceiverResolution(
                    NativeEtaTransportPolicy.ReceiverState.UNKNOWN,
                    uncertain.getClass().getSimpleName());
        }
    }

    private void sendAmapFrame(DirectTbtFrame frame, Trace trace,
            NativeEtaSnapshot etaSnapshot, boolean deduplicate) {
        sendAmapFrame(frame.getAmapBroadcastManeuver(), frame.getRoundaboutExitNumber(),
                frame.getDistanceMeters(), roadTextForTest(frame),
                selectMetrics(frame), trace, deduplicate, etaSnapshot);
    }

    private void sendAmapFrame(int amapIcon, int roundaboutExit, int distance,
            String road, DirectTbtFrame.TravelMetrics metrics, Trace trace) {
        sendAmapFrame(amapIcon, roundaboutExit, distance, road, metrics, trace, false);
    }

    private void sendAmapFrame(int amapIcon, int roundaboutExit, int distance,
            String road, DirectTbtFrame.TravelMetrics metrics, Trace trace,
            boolean deduplicate) {
        sendAmapFrame(amapIcon, roundaboutExit, distance, road,
                metrics, trace, deduplicate, null);
    }

    private void sendAmapFrame(int amapIcon, int roundaboutExit, int distance,
            String road, DirectTbtFrame.TravelMetrics metrics, Trace trace,
            boolean deduplicate, NativeEtaSnapshot etaSnapshot) {
        boolean amapOwnsNativeEta = etaSnapshot != null
                && etaSnapshot.writer == NativeEtaTransportPolicy.Writer.AMAP;
        int routeDistance = amapOwnsNativeEta
                ? etaSnapshot.formatted.distanceMeters : -1;
        int routeTime = amapOwnsNativeEta
                ? toInt(etaSnapshot.data.remainingSeconds, -1) : -1;
        String durationText = amapOwnsNativeEta
                ? amapString(etaSnapshot.formatted.routeRemainTimeAuto) : "-1";
        String arrivalText = amapOwnsNativeEta
                ? amapString(etaSnapshot.formatted.etaText) : "-1";
        String nextRoad = preserveText(road);
        int publishedIcon = trace == null ? amapIcon : trace.amapIcon;
        int publishedRoundaboutExit = trace == null
                ? roundaboutExit : trace.roundaboutExit;
        String semanticKey = amapSemanticKey(publishedIcon, publishedRoundaboutExit, distance,
                nextRoad, new DirectTbtFrame.TravelMetrics(
                        -1L, DirectTbtFrame.TravelMetrics.UNKNOWN_ZONE_OFFSET_SECONDS,
                        routeTime, routeDistance))
                + "|maneuverProfile:" + (trace == null
                        ? ManeuverCompatibilityProfile.AMAP : trace.compatibilityProfile)
                + "|etaMode:" + nativeEtaOutputMode
                + "|etaStrings:" + durationText + "|" + arrivalText
                + (etaSnapshot == null ? "" : "|native:" + etaSnapshot.semanticKey
                        + "|writer:" + etaSnapshot.writer);
        if (deduplicate && hasLastAmapSemantic && semanticKey.equals(lastAmapSemanticKey)) {
            record(trace, "amap_broadcast", "dedup", "guidance", new byte[0], 0, 0L, "");
            recordHudCheck(trace, "AMap", hudCheckAmapResult, hudCheckAmapReason);
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
        intent.putExtra("IS_BYD_BAIDU_MAP", trace != null
                && trace.compatibilityProfile == ManeuverCompatibilityProfile.BAIDU);
        intent.putExtra("NEW_ICON", publishedIcon);
        intent.putExtra("ROUNG_ABOUT_NUM", publishedRoundaboutExit);
        intent.putExtra("SEG_REMAIN_DIS", distance);
        intent.putExtra("NEXT_ROAD_NAME", nextRoad);
        intent.putExtra("ROUTE_REMAIN_DIS", routeDistance);
        intent.putExtra("ROUTE_REMAIN_TIME", routeTime);
        intent.putExtra("ROUTE_REMAIN_TIME_AUTO", durationText);
        intent.putExtra("ETA_TEXT", arrivalText);
        byte[] arguments = amapBytes(10001, publishedIcon, publishedRoundaboutExit, distance,
                nextRoad, routeDistance, routeTime);
        arguments = appendBytes(arguments, nativeEtaAmapBytes(
                routeDistance, routeTime, durationText, arrivalText));
        if (sendAmap(intent, "frame", trace, arguments)) {
            hasLastAmapSemantic = true;
            lastAmapSemanticKey = semanticKey;
        }
    }

    private void sendAmapTerminal(Trace trace) {
        handoffPendingInstrumentRecovery = false;
        Intent intent = new Intent(AMAP_ACTION);
        intent.setPackage(AMAP_PACKAGE);
        addStockAmapFlags(intent);
        intent.putExtra("KEY_TYPE", 10019);
        intent.putExtra("EXTRA_STATE", 9);
        intent.putExtra("EXTRA_IS_FOREGROUND", 1);
        intent.putExtra("IS_BYD_MAP", true);
        intent.putExtra("IS_BYD_BAIDU_MAP", trace != null
                && trace.compatibilityProfile == ManeuverCompatibilityProfile.BAIDU);
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
        hudCheckInstrumentResult = 0;
        hudCheckAmapResult = 0;
        hudCheckInstrumentReason = "pending";
        hudCheckAmapReason = "pending";
    }

    private void publishHudCheckLight(int index, String reason, boolean force) {
        if (index < 0 && !hudCheckLightOwned) return;
        long now = SystemClock.elapsedRealtime();
        if (!force && index == hudCheckLightIndex
                && now - hudCheckLightLastAttemptMs < 1000L) return;
        if (index != hudCheckLightIndex) {
            hudCheckLightResult = 0;
            hudCheckLightReason = "pending";
        }
        hudCheckLightIndex = index;
        hudCheckLightLastAttemptMs = now;
        if (index >= 0) hudCheckLightOwned = true;
        long token = routeToken;
        HudCheckState sample = latestHudCheck;
        instrument.sendHudCheckTrafficLight(index, reason, result -> ownerHandler.post(() -> {
            int outcome = successfulCheckResult(result) ? 1 : -1;
            String outcomeReason = checkResultReason(result);
            if (routeActive && token == routeToken && sample != null) {
                recordHudCheck(sample, "Instrument-light", outcome, outcomeReason);
            }
            if (index != hudCheckLightIndex || (index >= 0 && token != routeToken)) return;
            hudCheckLightReason = outcomeReason;
            if (index < 0 && outcome > 0) hudCheckLightOwned = false;
            if (hudCheckLightResult != outcome) {
                hudCheckLightResult = outcome;
                MainActivity.publishSharedUiStateChange();
            }
        }));
    }

    private static boolean successfulCheckResult(InstrumentProxyManager.Result result) {
        if (result == null || !result.available || !result.error.isEmpty()
                || result.operations.isEmpty()) return false;
        for (InstrumentProxyContract.Operation operation : result.operations) {
            if (operation.result != 0 || !operation.error.isEmpty()) return false;
        }
        return true;
    }

    private static int toInt(long value, int fallback) {
        if (value < 0L) return fallback;
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    void resumeAfterShanghai() {
        hasLastAmapSemantic = false;
        hasLastInstrumentSemantic = false;
        replayInstrumentState();
    }

    private void replayNativeEtaInstrument(String reason) {
        NativeEtaSnapshot snapshot = latestNativeEta;
        if (!routeActive || snapshot == null
                || snapshot.writer != NativeEtaTransportPolicy.Writer.INSTRUMENT) return;
        resetNativeEtaCaches(reason);
        sendNativeEtaInstrument(snapshot, reason, false);
    }

    private void replayInstrumentState() {
        if (hudCheckLightOwned && hudCheckLightIndex < 0) {
            publishHudCheckLight(-1, "hud-check-ready-clear", true);
        }
        if (!routeActive) return;
        if (!hasInstrumentStatus && !hasInstrumentGuidance) {
            replayNativeEtaInstrument("proxy-ready");
            return;
        }
        String owner = ownerPackage.isEmpty() ? teardownOwner : ownerPackage;
        long generation = ownerGeneration == Long.MIN_VALUE
                ? teardownGeneration : ownerGeneration;
        String source = sourceForOwner(owner);
        Trace trace = new Trace(source, safe(owner), generation,
                "tbt-" + transactionSequence.incrementAndGet(),
                "proxy-ready-replay", lastInstrumentIcon, 0, 0, 0,
                lastInstrumentDistance, lastInstrumentRoad,
                DirectTbtFrame.TravelMetrics.unavailable(),
                DirectTbtFrame.TravelMetrics.unavailable(),
                new LanePayload(lastInstrumentLaneDirections,
                        lastInstrumentLaneRecommendations),
                compatibilityProfileFor(owner, null),
                new ManeuverCompatibilityProfile.Mapping(0, 0,
                        lastInstrumentIcon, 0, 0, true));
        trace.routeToken = routeToken;
        trace.hudCheck = latestHudCheck;
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
        if (routeActive && MANUAL_OWNER.equals(ownerPackage) && hudCheckLightIndex >= 0) {
            publishHudCheckLight(hudCheckLightIndex, "hud-check-ready-replay", true);
        }
        log("tbt_proxy_replay status="
                + (hasInstrumentStatus ? lastInstrumentStatus : -1)
                + " icon=" + (hasInstrumentGuidance ? lastInstrumentIcon : -1));
        replayNativeEtaInstrument("proxy-ready");
    }

    private void handleInstrumentUnavailable(String reason) {
        resetNativeEtaCaches("proxy-unavailable:" + safe(reason));
        if (routeActive && MANUAL_OWNER.equals(ownerPackage)) {
            boolean changed = hudCheckInstrumentResult != -1
                    || (hudCheckLightIndex >= 0 && hudCheckLightResult != -1);
            hudCheckInstrumentResult = -1;
            hudCheckInstrumentReason = "proxy-unavailable";
            recordHudCheck(latestHudCheck, "Instrument", -1, hudCheckInstrumentReason);
            if (hudCheckLightIndex >= 0) {
                hudCheckLightResult = -1;
                hudCheckLightReason = "proxy-unavailable";
                recordHudCheck(latestHudCheck, "Instrument-light", -1, hudCheckLightReason);
            }
            if (changed) MainActivity.publishSharedUiStateChange();
        }
        if (!hasInstrumentStatus && !hasInstrumentGuidance) return;
        String owner = ownerPackage.isEmpty() ? teardownOwner : ownerPackage;
        long generation = ownerGeneration == Long.MIN_VALUE
                ? teardownGeneration : ownerGeneration;
        Trace trace = new Trace(sourceForOwner(owner), safe(owner), generation,
                "tbt-" + transactionSequence.incrementAndGet(),
                "proxy-unavailable:" + safe(reason), lastInstrumentIcon, 0, 0, 0,
                lastInstrumentDistance, lastInstrumentRoad,
                DirectTbtFrame.TravelMetrics.unavailable(),
                DirectTbtFrame.TravelMetrics.unavailable(), LanePayload.EMPTY,
                compatibilityProfileFor(owner, null),
                new ManeuverCompatibilityProfile.Mapping(0, 0,
                        lastInstrumentIcon, 0, 0, true));
        trace.routeToken = routeToken;
        if (shouldPreserveAmapFallbackForTest(
                routeActive, lastGuidanceHasAmapFallback)
                || handoffPendingInstrumentRecovery) {
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
        if (trace == null || !matches(trace.owner, trace.generation)
                || trace.routeToken != routeToken) return;
        boolean latestGuidance = lastInstrumentIcon == icon
                && lastInstrumentDistance == distance
                && lastInstrumentRoad.equals(road)
                && Arrays.equals(lastInstrumentLaneDirections, laneDirections)
                && Arrays.equals(lastInstrumentLaneRecommendations, laneRecommendations);
        int outcome = successfulCheckResult(result) ? 1 : -1;
        String outcomeReason = checkResultReason(result);
        recordHudCheck(trace, "Instrument", outcome, outcomeReason);
        if (MANUAL_OWNER.equals(trace.owner) && latestGuidance) {
            hudCheckInstrumentReason = outcomeReason;
            if (hudCheckInstrumentResult != outcome) {
                hudCheckInstrumentResult = outcome;
                MainActivity.publishSharedUiStateChange();
            }
        }
        if (laneOperationsSucceededForTest(result == null ? null : result.operations)) return;
        if (latestGuidance) {
            hasLastInstrumentSemantic = false;
        }
    }

    private static String checkResultReason(InstrumentProxyManager.Result result) {
        if (successfulCheckResult(result)) return "sent";
        if (result == null || !result.available) return "proxy-unavailable";
        return "invoke-failed";
    }

    private void recordHudCheck(Trace trace, String plane, int result, String reason) {
        if (trace == null || !isCurrentRoute(trace.owner, trace.generation, trace.routeToken)) return;
        recordHudCheck(trace.hudCheck, plane, result, reason);
    }

    private void recordHudCheck(HudCheckState sample, String plane, int result, String reason) {
        String line = hudCheckDiagnostics.changed(sample, plane, result, reason);
        if (line != null) log(line);
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
        if (!ShanghaiOutputGate.enterWrite()) return false;
        try { return sendAmapWhenAllowed(intent, operation, trace, arguments); }
        finally { ShanghaiOutputGate.leaveWrite(); }
    }

    private boolean sendAmapWhenAllowed(Intent intent, String operation, Trace trace, byte[] arguments) {
        long startedAt = System.nanoTime();
        try {
            context.sendBroadcast(intent);
            if ("frame".equals(operation)) {
                hudCheckAmapReason = "sent";
                recordHudCheck(trace, "AMap", 1, hudCheckAmapReason);
            }
            if (MANUAL_OWNER.equals(trace.owner) && "frame".equals(operation)
                    && hudCheckAmapResult != 1) {
                hudCheckAmapResult = 1;
                MainActivity.publishSharedUiStateChange();
            }
            record(trace, "amap_broadcast", operation, AMAP_ACTION,
                    arguments, 0, elapsedMs(startedAt), "");
            return true;
        } catch (RuntimeException error) {
            if ("frame".equals(operation)) {
                hudCheckAmapReason = "broadcast-failed";
                recordHudCheck(trace, "AMap", -1, hudCheckAmapReason);
            }
            if (MANUAL_OWNER.equals(trace.owner) && "frame".equals(operation)
                    && hudCheckAmapResult != -1) {
                hudCheckAmapResult = -1;
                MainActivity.publishSharedUiStateChange();
            }
            record(trace, "amap_broadcast", operation, AMAP_ACTION,
                    arguments, -1, elapsedMs(startedAt), describe(error));
            Log.w(TAG, "TBT AMap broadcast failed: " + operation, error);
            return false;
        }
    }

    private Trace trace(String owner, long generation, String reason,
            DirectTbtFrame frame, HudState manualState) {
        String source = sourceForOwner(owner);
        boolean liveCheck = manualState != null && manualState.hudCheck != null
                && manualState.hudCheck.isLiveCheck();
        if (liveCheck) frame = manualState.hudCheck.liveFrame();
        ManualMapping manualMapping = manualState == null || liveCheck ? null
                : manualMappingForTest(
                        manualState.turnBitmapId, manualState.maneuverId);
        int legacyInstrumentIcon = manualMapping != null
                ? manualMapping.instrumentId
                : frame == null ? 0 : instrumentManeuverForAmap(
                        frame.getAmapBroadcastManeuver(), frame.getRoundaboutExitNumber());
        int intermediateAmapIcon = manualMapping != null
                ? manualMapping.amapManeuver
                : frame == null ? 0 : frame.getAmapManeuver();
        int sourceAmapIcon = manualMapping != null
                ? manualMapping.amapManeuver
                : frame == null ? 0 : frame.getAmapBroadcastManeuver();
        int sourceRoundaboutExit = manualMapping != null
                ? manualMapping.roundaboutExit
                : frame == null ? 0 : frame.getRoundaboutExitNumber();
        int profile = compatibilityProfileFor(owner, manualState);
        ManeuverCompatibilityProfile.Mapping mapping = ManeuverCompatibilityProfile.map(
                profile, sourceAmapIcon, sourceRoundaboutExit, legacyInstrumentIcon);
        int nativeId = mapping.instrumentIcon;
        int amapIcon = mapping.broadcastIcon;
        int roundaboutExit = mapping.broadcastRoundaboutExit;
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
        if (shouldPublishLanes(HudPrefs.isLaneOutputEnabled(context), manualState)) {
            lanes = manualState == null
                    ? lanePayloadForTest(frame == null ? null : frame.getLanes())
                    : lanePayloadForTest(manualState);
        }
        Trace trace = new Trace(source, safe(owner), generation,
                "tbt-" + transactionSequence.incrementAndGet(), safe(reason),
                nativeId, intermediateAmapIcon, amapIcon, roundaboutExit,
                distance, preserveText(road), route, next, lanes, profile, mapping);
        trace.routeToken = routeToken;
        trace.hudCheck = manualState == null ? null : manualState.hudCheck;
        return trace;
    }

    static boolean shouldPublishLanes(boolean enabled, HudState manualState) {
        return enabled || (manualState != null && manualState.hudCheck != null);
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
            recommendations[count] = lane.getAmapRecommendationCode();
            count++;
        }
        return count == 0 ? LanePayload.EMPTY : new LanePayload(
                Arrays.copyOf(directions, count), Arrays.copyOf(recommendations, count));
    }

    static LanePayload lanePayloadForTest(HudState state) {
        if (state == null || (!state.includeLaneBitmap && state.hudCheck == null)) {
            return LanePayload.EMPTY;
        }
        HudLaneModel.LaneSpec[] lanes = HudLaneModel.parse(state);
        int[] directions = new int[lanes.length];
        int[] recommendations = new int[lanes.length];
        for (int index = 0; index < lanes.length; index++) {
            int direction = lanes[index].instrumentCode();
            if (direction < 0) return LanePayload.EMPTY;
            direction = Math.max(0, Math.min(254, direction));
            directions[index] = direction;
            recommendations[index] = lanes[index].recommendationCode();
        }
        return lanes.length == 0
                ? LanePayload.EMPTY : new LanePayload(directions, recommendations);
    }

    private static final class NativeEtaSnapshot {
        final DirectTbtFrame frame;
        final Trace trace;
        final DirectTbtPayload.Options options;
        final NativeEtaData data;
        final NativeEtaFormatter.Formatted formatted;
        final NativeEtaTransportPolicy.Writer writer;
        final String semanticKey;
        final long createdAtWallMs;
        final boolean semanticChanged;

        NativeEtaSnapshot(DirectTbtFrame frame, Trace trace,
                DirectTbtPayload.Options options, NativeEtaData data,
                NativeEtaFormatter.Formatted formatted,
                NativeEtaTransportPolicy.Writer writer, String semanticKey,
                long createdAtWallMs, boolean semanticChanged) {
            this.frame = frame;
            this.trace = trace;
            this.options = options;
            this.data = data;
            this.formatted = formatted;
            this.writer = writer;
            this.semanticKey = semanticKey;
            this.createdAtWallMs = createdAtWallMs;
            this.semanticChanged = semanticChanged;
        }
    }

    private static final class NativeEtaFieldCache {
        final int value;
        final long lastSuccessElapsedMs;

        NativeEtaFieldCache(int value, long lastSuccessElapsedMs) {
            this.value = value;
            this.lastSuccessElapsedMs = lastSuccessElapsedMs;
        }
    }

    private static final class AmapReceiverResolution {
        final NativeEtaTransportPolicy.ReceiverState state;
        final String reason;

        AmapReceiverResolution(NativeEtaTransportPolicy.ReceiverState state,
                String reason) {
            this.state = state;
            this.reason = safe(reason);
        }
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
        final int compatibilityProfile;
        final ManeuverCompatibilityProfile.Mapping mapping;
        long routeToken = Long.MIN_VALUE;
        HudCheckState hudCheck;

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
            this(source, owner, generation, transactionId, reason, nativeId,
                    intermediateAmapIcon, amapIcon, roundaboutExit, distanceMeters,
                    road, route, next, lanes, ManeuverCompatibilityProfile.AMAP,
                    new ManeuverCompatibilityProfile.Mapping(amapIcon, roundaboutExit,
                            nativeId, amapIcon, roundaboutExit, true));
        }

        Trace(String source, String owner, long generation, String transactionId,
                String reason, int nativeId, int intermediateAmapIcon, int amapIcon,
                int roundaboutExit, int distanceMeters, String road,
                DirectTbtFrame.TravelMetrics route,
                DirectTbtFrame.TravelMetrics next, LanePayload lanes,
                int compatibilityProfile,
                ManeuverCompatibilityProfile.Mapping mapping) {
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
            this.compatibilityProfile = ManeuverCompatibilityProfile.normalize(
                    compatibilityProfile);
            this.mapping = mapping;
        }
    }

}
