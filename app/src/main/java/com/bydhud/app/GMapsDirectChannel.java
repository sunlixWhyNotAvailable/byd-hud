package com.bydhud.app;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.SystemClock;
import android.util.Base64;

import com.bydhud.gmapswire.GmapsWireParser;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Receives bounded navigation snapshots from the separately patched ReVanced GMaps process. */
final class GMapsDirectChannel {
    static final String PACKAGE_NAME = "app.revanced.android.apps.maps";
    static final String OWNER_PACKAGE = PACKAGE_NAME;

    private static final String RECEIVER =
            "com.google.android.libraries.geo.navcore.navinfo.NavigationInfoBroadcastReceiver";
    private static final String ACTION_REGISTER = "ACTION_START_CHANNEL";
    private static final String ACTION_UNREGISTER = "com.bydhud.gmapsbridge.UNREGISTER";
    private static final String EXTRA_CLIENT = "com.bydhud.gmapsbridge.CLIENT";
    private static final String EXTRA_PROTOCOL = "com.bydhud.gmapsbridge.PROTOCOL_VERSION";
    private static final String EXTRA_IDENTITY = "com.bydhud.gmapsbridge.IDENTITY";
    private static final String EXTRA_CHANNEL_ID = "com.bydhud.gmapsbridge.CHANNEL_ID";
    private static final int PROTOCOL_VERSION = 3;
    private static final int MESSAGE_HELLO = 1;
    private static final int MESSAGE_START = 2;
    private static final int MESSAGE_FRAME = 3;
    private static final int MESSAGE_STOP = 4;
    private static final int MESSAGE_MANEUVER_BITMAP = 5;
    private static final int MESSAGE_SPEED_LIMIT = 6;
    private static final int MESSAGE_HEARTBEAT = 7;
    private static final int CAP_STATE_REPLAY = 1;
    private static final int CAP_HEARTBEAT = 1 << 1;
    private static final int CAP_BITMAP_GENERATION = 1 << 2;
    private static final int CAP_ROUTE_GENERATION = 1 << 3;
    private static final int MAX_FRAME_BYTES = 512 * 1024;
    private static final int MAX_BITMAP_DIMENSION = 256;
    static final int MAX_MANEUVER_BITMAP_CACHE = 64;
    private static final long REGISTER_RETRY_MS = 5000L;
    private static final long PRODUCER_LEASE_MS = 5000L;
    private static final long LEASE_CHECK_MS = 2000L;

    private final Context context;
    private final Listener listener;
    private final HandlerThread thread;
    private final Handler handler;
    private final Messenger inbound;
    private final GMapsDirectManeuverMap maneuverMap = new GMapsDirectManeuverMap();
    private final Map<String, ManeuverBitmap> maneuverBitmaps = newManeuverBitmapCache();
    private final Runnable registrationRetry = this::retryRegistration;
    private final Runnable leaseCheck = this::checkProducerLease;

    private boolean running;
    private boolean connected;
    private boolean navigating;
    private boolean terminalLatched;
    private boolean firstStructuredFrame;
    private volatile long sessionGeneration;
    private long registrationNonce;
    private long producerEpoch = -1L;
    private long producerRouteGeneration = -1L;
    private long activeRouteGeneration = -1L;
    private long terminalRouteGeneration = -1L;
    private long lastHeartbeatElapsedMs;
    private boolean heartbeatCapable;
    private boolean generationAwareProducer;
    private boolean routeGenerationCapable;
    private volatile String channelId = "";
    private long lastSequence = -1L;
    private long currentStructuredFrameAtMs;
    private String currentManeuver = "";
    private byte[] currentFallbackPng = new byte[0];
    private String lastBitmapDiagnosticKey = "";
    private DirectTbtFrame currentFrame;

    GMapsDirectChannel(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        thread = new HandlerThread("BydHudGMapsDirect", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        handler = new Handler(thread.getLooper(), this::handleMessage);
        inbound = new Messenger(handler);
    }

    void start(String reason) {
        handler.post(() -> {
            sessionGeneration++;
            running = true;
            connected = false;
            producerEpoch = -1L;
            producerRouteGeneration = -1L;
            activeRouteGeneration = -1L;
            terminalRouteGeneration = -1L;
            heartbeatCapable = false;
            generationAwareProducer = false;
            routeGenerationCapable = false;
            lastHeartbeatElapsedMs = 0L;
            navigating = false;
            terminalLatched = false;
            resetSession();
            registerClient("start:" + safe(reason));
            handler.removeCallbacks(leaseCheck);
            handler.postDelayed(leaseCheck, LEASE_CHECK_MS);
        });
    }

    void ensureRegistered(String reason) {
        handler.post(() -> {
            if (!running) return;
            connected = false;
            registerClient("retry:" + safe(reason));
        });
    }

    void stop(String reason) {
        handler.post(() -> {
            if (!running) return;
            sendRegistration(ACTION_UNREGISTER, false, PROTOCOL_VERSION);
            sessionGeneration++;
            running = false;
            connected = false;
            navigating = false;
            terminalLatched = false;
            handler.removeCallbacks(registrationRetry);
            handler.removeCallbacks(leaseCheck);
            resetSession();
            listener.onLog("stopped reason=" + safe(reason));
        });
    }

    long sessionGeneration() {
        return sessionGeneration;
    }

    boolean isRunning() {
        return running;
    }

    boolean isNavigating() {
        return navigating;
    }

    private boolean handleMessage(Message message) {
        long handlerEntryElapsedMs = SystemClock.elapsedRealtime();
        return runMessageBoundary(
                () -> handleMessageSafely(message, handlerEntryElapsedMs),
                error -> listener.onLog("message rejected reason=exception error="
                        + error.getClass().getSimpleName()));
    }

    static boolean runMessageBoundary(MessageWork work, Consumer<Throwable> onError) {
        try {
            return work.run();
        } catch (Throwable error) {
            onError.accept(error);
            return true;
        }
    }

    interface MessageWork {
        boolean run();
    }

    static Map<String, ManeuverBitmap> newManeuverBitmapCache() {
        return new LinkedHashMap<String, ManeuverBitmap>(
                MAX_MANEUVER_BITMAP_CACHE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ManeuverBitmap> eldest) {
                return size() > MAX_MANEUVER_BITMAP_CACHE;
            }
        };
    }

    private boolean handleMessageSafely(Message message, long handlerEntryElapsedMs) {
        if (!running || message == null) return true;
        Bundle data = message.getData();
        int protocol = data == null ? -1 : data.getInt("protocolVersion", -1);
        String incomingChannelId = data == null ? "" : safe(data.getString("channelId"));
        long bridgeElapsedMs = data == null
                ? -1L : data.getLong("bridgeElapsedMs", -1L);
        long sourceElapsedMs = data == null
                ? -1L : data.getLong("sourceElapsedMs", -1L);
        long messageSession = data == null
                ? -1L : data.getLong("sessionGeneration", -1L);
        long sequence = data == null ? -1L : data.getLong("sequence", -1L);
        long incomingProducerEpoch = data == null
                ? -1L : data.getLong("producerEpoch", -1L);
        long incomingRouteGeneration = routeGeneration(data);
        boolean routeActivePresent = data != null && data.containsKey("routeActive");
        boolean advertisedRouteActive = !routeActivePresent
                || data.getBoolean("routeActive", false);
        int advertisedCapabilities = data == null ? 0 : data.getInt("capabilities", 0);
        long timingSessionGeneration = timingSessionGenerationForTest(
                message.what, sessionGeneration);
        GMapsTimingDiagnostics.Frame timing = GMapsTimingDiagnostics.frame(
                protocol,
                incomingChannelId.isEmpty() ? channelId : incomingChannelId,
                message.what,
                timingSessionGeneration,
                messageSession,
                sequence,
                bridgeElapsedMs,
                sourceElapsedMs,
                handlerEntryElapsedMs);
        boolean accepted = acceptsProtocolMessageForTest(protocol, channelId, incomingChannelId);
        timing = timing.withProtocolValidated(SystemClock.elapsedRealtime(), accepted);
        if (!accepted) {
            listener.onLog("gmaps_timing " + timing.protocolLine(false));
            return true;
        }
        boolean routeGenerationRequired = message.what == MESSAGE_HELLO
                ? (advertisedCapabilities & CAP_ROUTE_GENERATION) != 0
                : routeGenerationCapable;
        if (routeGenerationRequired && incomingRouteGeneration < 0L) {
            listener.onLog("message ignored reason=missing-route-generation what="
                    + message.what);
            return true;
        }
        boolean producerEpochChanged = incomingProducerEpoch >= 0L && producerEpoch >= 0L
                && incomingProducerEpoch != producerEpoch;
        if (!acceptsProducerEpochForTest(
                message.what, producerEpoch, incomingProducerEpoch)) {
            listener.onLog("message ignored reason=stale-producer-epoch incoming="
                    + incomingProducerEpoch + " current=" + producerEpoch);
            return true;
        }
        switch (message.what) {
            case MESSAGE_HELLO:
                boolean hadActiveRoute = navigating || currentFrame != null;
                // A producer that was previously unknown may identify itself while
                // an old route is retained; treat that as a replacement. Initial
                // no-route registration remains a normal HELLO.
                producerEpochChanged = producerEpochChanged
                        || (producerEpoch < 0L && incomingProducerEpoch >= 0L
                        && hadActiveRoute);
                boolean staleHelloRoute = !producerEpochChanged && incomingRouteGeneration >= 0L
                        && producerRouteGeneration >= 0L
                        && incomingRouteGeneration < producerRouteGeneration;
                if (staleHelloRoute) {
                    listener.onLog("message ignored reason=stale-hello-route-generation incoming="
                            + incomingRouteGeneration + " current=" + producerRouteGeneration);
                }
                if (producerEpochChanged) {
                    if (hadActiveRoute && advertisedRouteActive) {
                        finishNavigation("hello-producer-replaced");
                    } else if (hadActiveRoute) {
                        finishNavigation("hello-inactive");
                    }
                    if (!hadActiveRoute) sessionGeneration++;
                    navigating = false;
                    terminalLatched = false;
                    activeRouteGeneration = -1L;
                    terminalRouteGeneration = -1L;
                    resetSession();
                }
                if (!producerEpochChanged && !staleHelloRoute
                        && !advertisedRouteActive && hadActiveRoute) {
                    finishNavigation("hello-inactive");
                }
                if (!producerEpochChanged && !staleHelloRoute
                        && advertisedRouteActive && hadActiveRoute) {
                    // Re-registration replay must refresh the current owner without
                    // changing the route callback generation.
                    lastSequence = -1L;
                    firstStructuredFrame = false;
                }
                connected = true;
                producerEpoch = incomingProducerEpoch;
                if (!staleHelloRoute && incomingRouteGeneration >= 0L) {
                    producerRouteGeneration = incomingRouteGeneration;
                    if (!advertisedRouteActive) terminalRouteGeneration = incomingRouteGeneration;
                }
                int capabilities = data.getInt("capabilities", 0);
                heartbeatCapable = (capabilities & CAP_HEARTBEAT) != 0;
                generationAwareProducer = (capabilities & CAP_BITMAP_GENERATION) != 0;
                routeGenerationCapable = (capabilities & CAP_ROUTE_GENERATION) != 0;
                lastHeartbeatElapsedMs = SystemClock.elapsedRealtime();
                handler.removeCallbacks(registrationRetry);
                String helloReason = producerEpochChanged
                        ? "hello-producer-replaced" : (staleHelloRoute
                        ? "hello-stale-route" : "hello");
                listener.onLog("gmaps_timing " + timing.protocolLine(true)
                        + " control=hello");
                listener.onHandshakeAvailable(OWNER_PACKAGE, sessionGeneration, helloReason);
                if (acceptsHelloLivenessForTest(
                        producerEpochChanged, staleHelloRoute, navigating,
                        advertisedRouteActive, incomingRouteGeneration, activeRouteGeneration)) {
                    listener.onLiveness(OWNER_PACKAGE, sessionGeneration, helloReason);
                }
                return true;
            case MESSAGE_HEARTBEAT:
                boolean staleHeartbeatRoute = incomingRouteGeneration >= 0L
                        && producerRouteGeneration >= 0L
                        && incomingRouteGeneration < producerRouteGeneration;
                connected = true;
                lastHeartbeatElapsedMs = SystemClock.elapsedRealtime();
                if (staleHeartbeatRoute) {
                    listener.onLog("heartbeat ignored reason=stale-route-generation incoming="
                            + incomingRouteGeneration + " current=" + producerRouteGeneration);
                } else if (incomingRouteGeneration >= 0L) {
                    producerRouteGeneration = Math.max(
                            producerRouteGeneration, incomingRouteGeneration);
                }
                if (!staleHeartbeatRoute && routeActivePresent
                        && !advertisedRouteActive && (navigating || currentFrame != null)) {
                    if (incomingRouteGeneration >= 0L && activeRouteGeneration >= 0L
                            && incomingRouteGeneration < activeRouteGeneration) {
                        listener.onLog("heartbeat ignored reason=stale-terminal-generation incoming="
                                + incomingRouteGeneration + " current=" + activeRouteGeneration);
                        return true;
                    }
                    finishNavigation("heartbeat-inactive");
                    if (incomingRouteGeneration >= 0L) {
                        terminalRouteGeneration = incomingRouteGeneration;
                    }
                }
                if (!staleHeartbeatRoute && routeActivePresent && !advertisedRouteActive
                        && incomingRouteGeneration >= 0L) {
                    terminalRouteGeneration = Math.max(
                            terminalRouteGeneration, incomingRouteGeneration);
                }
                boolean exactActiveRoute = navigating && advertisedRouteActive
                        && (incomingRouteGeneration < 0L
                        || incomingRouteGeneration == activeRouteGeneration);
                if (exactActiveRoute) {
                    listener.onLiveness(OWNER_PACKAGE, sessionGeneration, "heartbeat");
                }
                return true;
            case MESSAGE_START:
                if (incomingRouteGeneration >= 0L && producerRouteGeneration >= 0L
                        && incomingRouteGeneration < producerRouteGeneration) {
                    listener.onLog("message ignored reason=stale-route-generation incoming="
                            + incomingRouteGeneration + " current=" + producerRouteGeneration);
                    return true;
                }
                if (!navigating && incomingRouteGeneration >= 0L
                        && terminalRouteGeneration >= 0L
                        && incomingRouteGeneration <= terminalRouteGeneration) {
                    listener.onLog("message ignored reason=terminal-route-generation incoming="
                            + incomingRouteGeneration + " terminal=" + terminalRouteGeneration);
                    return true;
                }
                if (incomingProducerEpoch >= 0L && producerEpoch < 0L) {
                    producerEpoch = incomingProducerEpoch;
                }
                if (!acceptsRouteGenerationForTest(
                        MESSAGE_START, activeRouteGeneration, incomingRouteGeneration, navigating)) {
                    listener.onLog("message ignored reason=stale-route-generation incoming="
                            + incomingRouteGeneration + " current=" + activeRouteGeneration);
                    return true;
                }
                if (incomingRouteGeneration >= 0L) {
                    producerRouteGeneration = Math.max(
                            producerRouteGeneration, incomingRouteGeneration);
                }
                if (navigating && (incomingRouteGeneration < 0L
                        || incomingRouteGeneration == activeRouteGeneration)) {
                    connected = true;
                    listener.onLiveness(OWNER_PACKAGE, sessionGeneration, "start-replay");
                    return true;
                }
                sessionGeneration++;
                connected = true;
                navigating = true;
                terminalLatched = false;
                activeRouteGeneration = incomingRouteGeneration >= 0L
                        ? incomingRouteGeneration : producerRouteGeneration;
                terminalRouteGeneration = -1L;
                resetSession();
                listener.onLog("gmaps_timing " + timing.protocolLine(true)
                        + " control=start");
                listener.onNavigationStarted(OWNER_PACKAGE, sessionGeneration, "start");
                listener.onLiveness(OWNER_PACKAGE, sessionGeneration, "start");
                return true;
            case MESSAGE_FRAME:
                if (!acceptsFrameRoute(data)) return true;
                handleFrame(data, timing);
                return true;
            case MESSAGE_STOP:
                if (!navigating && currentFrame == null) {
                    if (incomingRouteGeneration >= 0L
                            && (producerRouteGeneration < 0L
                            || incomingRouteGeneration >= producerRouteGeneration)) {
                        producerRouteGeneration = incomingRouteGeneration;
                        terminalRouteGeneration = Math.max(
                                terminalRouteGeneration, incomingRouteGeneration);
                    }
                    listener.onLog("message ignored reason=stop-without-active-route generation="
                            + incomingRouteGeneration);
                    return true;
                }
                if (incomingRouteGeneration >= 0L && activeRouteGeneration >= 0L
                        && incomingRouteGeneration > activeRouteGeneration) {
                    producerRouteGeneration = Math.max(
                            producerRouteGeneration, incomingRouteGeneration);
                    terminalRouteGeneration = incomingRouteGeneration;
                }
                if (!acceptsRouteGenerationForTest(
                        MESSAGE_STOP, activeRouteGeneration, incomingRouteGeneration, navigating)) {
                    listener.onLog("message ignored reason=stale-route-generation incoming="
                            + incomingRouteGeneration + " current=" + activeRouteGeneration);
                    return true;
                }
                listener.onLog("gmaps_timing " + timing.protocolLine(true)
                        + " control=stop");
                finishNavigation("stop");
                return true;
            case MESSAGE_MANEUVER_BITMAP:
                handleManeuverBitmap(data);
                return true;
            case MESSAGE_SPEED_LIMIT:
                if (!acceptsRouteGenerationForTest(
                        MESSAGE_SPEED_LIMIT, activeRouteGeneration, incomingRouteGeneration, navigating)) {
                    listener.onLog("speed limit ignored reason=stale-route-generation incoming="
                            + incomingRouteGeneration + " current=" + activeRouteGeneration);
                    return true;
                }
                handleSpeedLimit(data);
                return true;
            default:
                listener.onLog("message ignored what=" + message.what);
                return true;
        }
    }

    static boolean acceptsProtocolMessageForTest(
            int protocol, String expectedChannelId, String incomingChannelId) {
        if (protocol != PROTOCOL_VERSION) return false;
        String incoming = safe(incomingChannelId);
        return incoming.isEmpty() || safe(expectedChannelId).equals(incoming);
    }

    static long timingSessionGenerationForTest(
            int messageWhat, long currentSessionGeneration) {
        return messageWhat == MESSAGE_START
                ? currentSessionGeneration + 1L : currentSessionGeneration;
    }

    static boolean acceptsProducerEpochForTest(
            int messageWhat, long currentEpoch, long incomingEpoch) {
        if (messageWhat != MESSAGE_HELLO) {
            return currentEpoch < 0L || incomingEpoch == currentEpoch;
        }
        if (currentEpoch < 0L) return true;
        return incomingEpoch >= currentEpoch;
    }

    static boolean acceptsHelloLivenessForTest(
            boolean producerEpochChanged, boolean staleRoute, boolean navigating,
            boolean advertisedActive, long incomingGeneration, long activeGeneration) {
        return !producerEpochChanged && !staleRoute && navigating && advertisedActive
                && (incomingGeneration < 0L || incomingGeneration == activeGeneration);
    }

    static long routeGenerationForTest(Bundle data) {
        if (data == null) return -1L;
        long route = data.getLong("routeGeneration", -1L);
        return route >= 0L ? route : data.getLong("stateEpoch", -1L);
    }

    static boolean acceptsRouteGenerationForTest(
            int messageWhat, long currentGeneration, long incomingGeneration,
            boolean routeActive) {
        if (incomingGeneration < 0L) {
            return routeActive || messageWhat == MESSAGE_FRAME || messageWhat == MESSAGE_START;
        }
        if (currentGeneration < 0L) {
            return messageWhat == MESSAGE_FRAME || messageWhat == MESSAGE_START;
        }
        if (incomingGeneration < currentGeneration) return false;
        if (incomingGeneration > currentGeneration) {
            return messageWhat == MESSAGE_FRAME || messageWhat == MESSAGE_START
                    || (messageWhat == MESSAGE_STOP && routeActive);
        }
        return true;
    }

    private static long routeGeneration(Bundle data) {
        return routeGenerationForTest(data);
    }

    private boolean acceptsFrameRoute(Bundle data) {
        long incoming = routeGeneration(data);
        if (incoming >= 0L && producerRouteGeneration >= 0L
                && incoming < producerRouteGeneration) {
            listener.onLog("frame ignored reason=stale-observed-route-generation incoming="
                    + incoming + " observed=" + producerRouteGeneration);
            return false;
        }
        if (!navigating && incoming >= 0L && terminalRouteGeneration >= 0L
                && incoming <= terminalRouteGeneration) {
            listener.onLog("frame ignored reason=terminal-route-generation incoming="
                    + incoming + " terminal=" + terminalRouteGeneration);
            return false;
        }
        if (!acceptsRouteGenerationForTest(
                MESSAGE_FRAME, activeRouteGeneration, incoming, navigating)) {
            listener.onLog("frame ignored reason=stale-route-generation incoming="
                    + incoming + " current=" + activeRouteGeneration);
            return false;
        }
        if (incoming >= 0L && (activeRouteGeneration < 0L
                || incoming > activeRouteGeneration)) {
            producerRouteGeneration = Math.max(producerRouteGeneration, incoming);
            activeRouteGeneration = incoming;
            sessionGeneration++;
            navigating = true;
            terminalLatched = false;
            resetSession();
            listener.onNavigationStarted(
                    OWNER_PACKAGE, sessionGeneration, "frame-missed-start");
        } else if (!navigating) {
            sessionGeneration++;
            navigating = true;
            terminalLatched = false;
            activeRouteGeneration = incoming >= 0L ? incoming : producerRouteGeneration;
            resetSession();
            listener.onNavigationStarted(
                    OWNER_PACKAGE, sessionGeneration, "frame-missed-start");
        }
        return true;
    }

    private void handleFrame(Bundle data, GMapsTimingDiagnostics.Frame timing) {
        byte[] payload = data.getByteArray("payload");
        long sequence = data.getLong("sequence", -1L);
        String frameCase = safe(data.getString("case"));
        if (sequence <= lastSequence) {
            listener.onLog("frame ignored reason=stale sequence=" + sequence
                    + " last=" + lastSequence);
            return;
        }
        if (terminalLatched) {
            listener.onLog("frame ignored reason=terminal-latched sequence=" + sequence);
            return;
        }
        if (payload == null || payload.length == 0 || payload.length > MAX_FRAME_BYTES) {
            listener.onLog("frame rejected reason=size sequence=" + sequence
                    + " bytes=" + (payload == null ? 0 : payload.length));
            return;
        }
        lastSequence = sequence;
        logRawFrame(sequence, frameCase, payload);
        if ("4".equals(frameCase)) {
            finishNavigation("terminal-case-4");
            return;
        }

        Map<String, Object> summary;
        long parseStartElapsedMs = SystemClock.elapsedRealtime();
        try {
            summary = GmapsWireParser.summarize(payload);
        } catch (Exception error) {
            long parseEndElapsedMs = SystemClock.elapsedRealtime();
            timing = timing.withParse(parseStartElapsedMs, parseEndElapsedMs);
            listener.onLog("gmaps_timing " + timing.protocolLine(true)
                    + " parse=failed parseDurationMs="
                    + GMapsTimingDiagnostics.duration(
                    parseStartElapsedMs, parseEndElapsedMs));
            listener.onLog("frame rejected reason=parse sequence=" + sequence
                    + " error=" + error.getClass().getSimpleName());
            return;
        }
        long parseEndElapsedMs = SystemClock.elapsedRealtime();
        timing = timing.withParse(parseStartElapsedMs, parseEndElapsedMs);
        try {
            String shape = stringValue(summary.get("shape"));
            if ("navigation_terminal".equals(shape)) {
                finishNavigation("terminal-shape");
                return;
            }
            if (!"route_state".equals(shape)
                    || !Boolean.TRUE.equals(summary.get("currentStepAvailable"))) {
                listener.onLog("frame ignored sequence=" + sequence + " shape=" + shape);
                return;
            }
            int wireValue = intValue(summary.get("maneuverEnum"), -1);
            int distance = Math.max(0, intValue(summary.get("distanceMeters"), 0));
            GMapsDirectManeuverMap.Result mapping = maneuverMap.map(wireValue, distance > 0);
            String road = joinedLines(summary.get("cueLines"));
            if (road.isEmpty()) road = joinedLines(summary.get("longCueLines"));
            long frameAtMs = data.getLong(
                    "sourceElapsedMs", SystemClock.elapsedRealtime());
            DirectTbtFrame.TripMetrics tripMetrics = tripMetrics(
                    summary, System.currentTimeMillis());
            byte[] fallbackPng = HudGraphicPayload.buildOemTurnPng(mapping.fallbackSource);
            BitmapSelection bitmapSelection = BitmapSelection.select(
                    sequence,
                    mapping.maneuverName,
                    maneuverBitmaps.get(mapping.maneuverName),
                    fallbackPng,
                    frameAtMs,
                    "matched");
            byte[] maneuverPng = bitmapSelection.selectedPng;
            currentManeuver = mapping.maneuverName;
            currentStructuredFrameAtMs = frameAtMs;
            currentFallbackPng = fallbackPng;
            List<DirectTbtFrame.Lane> lanes = mapLanes(summary.get("lanes"));
            currentFrame = new DirectTbtFrame(
                    wireValue,
                    mapping.intermediate,
                    mapping.nativeManeuver,
                    distance,
                    road,
                    "",
                    road,
                    maneuverPng,
                    null,
                    lanes,
                    DirectTbtFrame.AlertOverlay.inactive(),
                    tripMetrics).withVehicleTbt(
                    mapping.amapBroadcastManeuver, mapping.roundaboutExitNumber);
            boolean firstFrame = !firstStructuredFrame;
            if (!navigating) {
                navigating = true;
                listener.onNavigationStarted(OWNER_PACKAGE, sessionGeneration, "first-frame");
            }
            connected = true;
            listener.onLiveness(OWNER_PACKAGE, sessionGeneration, "frame");
            firstStructuredFrame = true;
            timing = timing.withListenerHandoff(SystemClock.elapsedRealtime(), firstFrame);
            int laneCount = summary.get("lanes") instanceof List
                    ? ((List<?>) summary.get("lanes")).size() : 0;
            if (timing.shouldLogAtHandoff(
                    HudPrefs.isDetailedDebugArtifactsEnabled(context))) {
                listener.onLog("frame sequence=" + sequence
                        + " maneuver=" + mapping.maneuverName
                        + " wire=" + wireValue
                        + " intermediate=" + mapping.intermediate
                        + " source=" + mapping.fallbackSource
                        + " native=" + mapping.nativeManeuver
                        + " distanceM=" + distance
                        + " nextStopSeconds="
                        + tripMetrics.getNextStop().getRemainingTimeSeconds()
                        + " wholeRouteSeconds="
                        + tripMetrics.getWholeRoute().getRemainingTimeSeconds()
                        + " lanesParsed=" + laneCount
                        + " lanesMapped=" + lanes.size()
                        + " bitmap=" + bitmapSelection.selected);
            }
            logBitmapSelection(bitmapSelection);
            listener.onFrame(OWNER_PACKAGE, sessionGeneration,
                    currentFrame, "frame-" + sequence, bitmapSelection, timing);
        } catch (Exception error) {
            listener.onLog("frame rejected reason=parse sequence=" + sequence
                    + " error=" + error.getClass().getSimpleName());
        }
    }

    private void handleManeuverBitmap(Bundle data) {
        String maneuver = safe(data.getString("maneuver"));
        String viewId = safe(data.getString("viewId"));
        long incomingProducerEpoch = data.getLong("producerEpoch", -1L);
        long incomingRouteGeneration = routeGeneration(data);
        long renderGeneration = data.getLong("renderGeneration", -1L);
        long sourceSequence = data.getLong("sourceSequence", -1L);
        byte[] png = data.getByteArray("png");
        int width = data.getInt("width", 0);
        int height = data.getInt("height", 0);
        boolean generationAware = generationAwareProducer;
        boolean routeAccepted = routeGenerationCapable
                ? incomingRouteGeneration >= 0L
                    && activeRouteGeneration >= 0L
                    && incomingRouteGeneration == activeRouteGeneration
                : navigating;
        boolean generationValid = !generationAware
                || (incomingProducerEpoch >= 0L && renderGeneration > 0L);
        if (maneuver.isEmpty() || png == null || png.length == 0
                || png.length > MAX_FRAME_BYTES || width <= 0 || height <= 0
                || width > MAX_BITMAP_DIMENSION || height > MAX_BITMAP_DIMENSION
                || !generationValid || !routeAccepted
                || !validPngBounds(png, width, height)) {
            listener.onLog("maneuver bitmap rejected maneuver=" + maneuver
                    + " producerEpoch=" + incomingProducerEpoch
                    + " routeGeneration=" + incomingRouteGeneration
                    + " renderGeneration=" + renderGeneration
                    + " sourceSequence=" + sourceSequence
                    + " width=" + width + " height=" + height
                    + " bytes=" + (png == null ? 0 : png.length));
            return;
        }
        long receivedAtMs = SystemClock.elapsedRealtime();
        long sourceAtMs = data.getLong("bridgeElapsedMs", receivedAtMs);
        ManeuverBitmap previous = maneuverBitmaps.get(maneuver);
        ManeuverBitmap candidate = new ManeuverBitmap(
                maneuver, viewId, png, width, height, sourceAtMs,
                incomingProducerEpoch, renderGeneration, incomingRouteGeneration,
                sourceSequence);
        if (!candidate.isNewerThan(previous, generationAware)) {
            if (!Arrays.equals(previous.png, png)) {
                listener.onLog("maneuver bitmap ignored reason=stale maneuver=" + maneuver
                        + " sourceElapsedMs=" + sourceAtMs
                        + " previousElapsedMs=" + previous.sourceAtMs);
            }
            return;
        }
        maneuverBitmaps.put(maneuver, candidate);
        boolean ordered = sourceSequence < 0L || sourceSequence <= lastSequence;
        boolean currentMatch = ordered && candidate.matches(currentManeuver)
                && currentFrame != null;
        long frameDelayMs = currentStructuredFrameAtMs <= 0L
                ? -1L : sourceAtMs - currentStructuredFrameAtMs;
        listener.onLog("bitmap_rx sequence=" + lastSequence
                + " maneuver=" + maneuver
                + " viewId=" + viewId
                + " sha=" + candidate.sha
                + " producerEpoch=" + incomingProducerEpoch
                + " renderGeneration=" + renderGeneration
                + " sourceSequence=" + sourceSequence
                + " bytes=" + candidate.png.length
                + " width=" + width
                + " height=" + height
                + " currentMatch=" + currentMatch
                + " frameDelayMs=" + frameDelayMs);
        if (currentMatch) {
            currentFrame = currentFrame.withManeuverPng(candidate.png);
            BitmapSelection bitmapSelection = BitmapSelection.google(
                    lastSequence,
                    candidate,
                    currentFallbackPng,
                    currentStructuredFrameAtMs,
                    "late-match");
            logBitmapSelection(bitmapSelection);
            listener.onLiveness(OWNER_PACKAGE, sessionGeneration, "maneuver-bitmap");
            listener.onFrame(OWNER_PACKAGE, sessionGeneration,
                    currentFrame, "maneuver-bitmap", bitmapSelection, null);
        }
    }

    private void finishNavigation(String reason) {
        terminalLatched = true;
        if (!navigating && currentFrame == null) return;
        long routeGeneration = sessionGeneration;
        long callbackGeneration = ++sessionGeneration;
        navigating = false;
        if (terminalRouteGeneration < 0L) {
            terminalRouteGeneration = activeRouteGeneration;
        }
        activeRouteGeneration = -1L;
        resetSession();
        listener.onNavigationEnded(
                OWNER_PACKAGE, routeGeneration, callbackGeneration, reason);
    }

    private void resetSession() {
        lastSequence = -1L;
        currentStructuredFrameAtMs = 0L;
        currentManeuver = "";
        currentFallbackPng = new byte[0];
        lastBitmapDiagnosticKey = "";
        currentFrame = null;
        firstStructuredFrame = false;
        maneuverBitmaps.clear();
        maneuverMap.reset();
    }

    private void registerClient(String reason) {
        handler.removeCallbacks(registrationRetry);
        boolean current = sendRegistration(ACTION_REGISTER, true, PROTOCOL_VERSION);
        if (current) {
            listener.onLog("registration sent reason=" + reason);
        } else {
            listener.onHandshakeUnavailable(
                    OWNER_PACKAGE, sessionGeneration, "registration-failed");
        }
        if (running && !connected) handler.postDelayed(registrationRetry, REGISTER_RETRY_MS);
    }

    private void retryRegistration() {
        if (running && !connected) registerClient("periodic");
    }

    private void checkProducerLease() {
        if (!running) return;
        long now = SystemClock.elapsedRealtime();
        if (connected && heartbeatCapable
                && now - lastHeartbeatElapsedMs > PRODUCER_LEASE_MS) {
            connected = false;
            listener.onLog("producer lease expired elapsedMs="
                    + (now - lastHeartbeatElapsedMs));
            registerClient("producer-lease-expired");
        }
        handler.postDelayed(leaseCheck, LEASE_CHECK_MS);
    }

    private boolean sendRegistration(String action, boolean includeClient, int protocol) {
        long beforeElapsedMs = SystemClock.elapsedRealtime();
        boolean sent = false;
        try {
            Intent intent = new Intent(action);
            intent.setComponent(new ComponentName(PACKAGE_NAME, RECEIVER));
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            intent.putExtra(EXTRA_IDENTITY, identity());
            if (includeClient) {
                channelId = "byd_hud_" + sessionGeneration + "_"
                        + (++registrationNonce) + "_" + SystemClock.elapsedRealtime();
                intent.putExtra(EXTRA_CHANNEL_ID, channelId);
                intent.putExtra(EXTRA_PROTOCOL, protocol);
                intent.putExtra(EXTRA_CLIENT, inbound);
            }
            context.sendOrderedBroadcast(intent, null);
            sent = true;
            return true;
        } catch (Throwable error) {
            listener.onLog("registration failed action=" + action
                    + " error=" + error.getClass().getSimpleName());
            return false;
        } finally {
            listener.onLog("gmaps_timing "
                    + GMapsTimingDiagnostics.registrationLine(
                    action, channelId, sessionGeneration, beforeElapsedMs,
                    SystemClock.elapsedRealtime(), sent));
        }
    }

    private PendingIntent identity() {
        Intent intent = new Intent(context, HudRuntimeService.class)
                .setAction("com.bydhud.app.gmaps.DIRECT_IDENTITY");
        return PendingIntent.getService(context, 2101, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void logRawFrame(long sequence, String frameCase, byte[] payload) {
        if (!HudPrefs.isDetailedDebugArtifactsEnabled(context)) return;
        NavCaptureStore.rawEvent(context, "gmaps_direct", PACKAGE_NAME,
                "protocol=" + PROTOCOL_VERSION + " sequence=" + sequence
                        + " case=" + frameCase
                        + " bytes=" + payload.length
                        + " sha256=" + sha256(payload)
                        + " protobufBase64="
                        + Base64.encodeToString(payload, Base64.NO_WRAP));
    }

    private static boolean validPngBounds(byte[] png, int expectedWidth, int expectedHeight) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(png, 0, png.length, options);
        return options.outWidth == expectedWidth && options.outHeight == expectedHeight
                && options.outWidth <= MAX_BITMAP_DIMENSION
                && options.outHeight <= MAX_BITMAP_DIMENSION;
    }

    private static String joinedLines(Object value) {
        if (!(value instanceof List)) return "";
        StringBuilder output = new StringBuilder();
        for (Object line : (List<?>) value) {
            String text = stringValue(line).trim();
            if (text.isEmpty()) continue;
            if (output.length() > 0) output.append(' ');
            output.append(text);
        }
        return output.toString();
    }

    private void handleSpeedLimit(Bundle data) {
        int limit = data.getInt("speedLimit", -1);
        int kph = data.getInt("speedLimitKph", -1);
        String unit = safe(data.getString("speedUnit"));
        long sourceElapsedMs = data.getLong(
                "bridgeElapsedMs", SystemClock.elapsedRealtime());
        if (limit < 0 || limit > 300 || kph < 0 || kph > 300
                || !(unit.isEmpty() || "km/h".equals(unit) || "mph".equals(unit))) {
            listener.onLog("speed limit rejected value=" + limit + " kph=" + kph
                    + " unit=" + unit);
            return;
        }
        connected = true;
        listener.onLiveness(OWNER_PACKAGE, sessionGeneration, "speed-limit");
        listener.onSpeedLimit(
                OWNER_PACKAGE, sessionGeneration, limit, kph, unit, sourceElapsedMs);
        listener.onLog("speed_limit value=" + limit + " kph=" + kph + " unit=" + unit);
    }

    private List<DirectTbtFrame.Lane> mapLanes(Object value) {
        List<DirectTbtFrame.Lane> mapped = mapLanesForTest(value);
        int rawCount = value instanceof List ? ((List<?>) value).size() : 0;
        if (mapped.size() < rawCount) {
            listener.onLog("lanes skipped unsupported=" + (rawCount - mapped.size()));
        }
        return mapped;
    }

    static List<DirectTbtFrame.Lane> mapLanesForTest(Object value) {
        if (!(value instanceof List)) return Collections.emptyList();
        List<DirectTbtFrame.Lane> mapped = new ArrayList<>();
        for (Object laneValue : (List<?>) value) {
            if (!(laneValue instanceof Map)) continue;
            Map<?, ?> lane = (Map<?, ?>) laneValue;
            Object arrowsValue = lane.get("arrows");
            if (!(arrowsValue instanceof List)) continue;
            Map<?, ?> firstSupported = null;
            Map<?, ?> recommendedSupported = null;
            StringBuilder raw = new StringBuilder();
            for (Object arrowValue : (List<?>) arrowsValue) {
                if (!(arrowValue instanceof Map)) continue;
                Map<?, ?> arrow = (Map<?, ?>) arrowValue;
                if (raw.length() > 0) raw.append('|');
                raw.append("shape=").append(stringValue(arrow.get("shape")))
                        .append("(").append(intValue(arrow.get("shapeEnum"), -1)).append(')')
                        .append(",side=").append(stringValue(arrow.get("side")))
                        .append("(").append(intValue(arrow.get("sideEnum"), -1)).append(')')
                        .append(",recommended=")
                        .append(Boolean.TRUE.equals(arrow.get("recommended")));
                int direction = mapLaneDirection(arrow);
                if (direction == 0) continue;
                if (firstSupported == null) firstSupported = arrow;
                if (Boolean.TRUE.equals(arrow.get("recommended"))
                        && recommendedSupported == null) {
                    recommendedSupported = arrow;
                }
            }
            Map<?, ?> selected = recommendedSupported == null
                    ? firstSupported : recommendedSupported;
            if (selected == null) continue;
            int direction = mapLaneDirection(selected);
            mapped.add(new DirectTbtFrame.Lane(
                    direction,
                    Boolean.TRUE.equals(selected.get("recommended")),
                    raw.toString()));
        }
        return mapped;
    }

    private static int mapLaneDirection(Map<?, ?> arrow) {
        int shape = intValue(arrow.get("shapeEnum"), -1);
        if (shape == 1 || shape == 2) return 9;
        if (shape < 3 || shape > 10) return 0;
        int side = intValue(arrow.get("sideEnum"), -1);
        if (side == 1) return 2;
        if (side == 2) return 3;
        String sideText = safe(stringValue(arrow.get("side"))).toUpperCase(Locale.US);
        if (sideText.contains("LEFT")) return 2;
        if (sideText.contains("RIGHT")) return 3;
        return 0;
    }

    private static int intValue(Object value, int fallback) {
        if (!(value instanceof Number)) return fallback;
        long number = ((Number) value).longValue();
        return number < Integer.MIN_VALUE || number > Integer.MAX_VALUE
                ? fallback : (int) number;
    }

    private static long longValue(Object value, long fallback) {
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    private static DirectTbtFrame.TripMetrics tripMetrics(
            Map<String, Object> summary, long frameWallTimeMs) {
        DirectTbtFrame.TravelMetrics nextStop = travelMetrics(
                longValue(summary.get("nextStopRemainingSeconds"), -1L),
                longValue(summary.get("nextStopRemainingDistanceMeters"), -1L),
                frameWallTimeMs);
        DirectTbtFrame.TravelMetrics wholeRoute = travelMetrics(
                longValue(summary.get("wholeRouteRemainingSeconds"), -1L),
                longValue(summary.get("wholeRouteRemainingDistanceMeters"), -1L),
                frameWallTimeMs);
        return new DirectTbtFrame.TripMetrics(nextStop, wholeRoute);
    }

    private static DirectTbtFrame.TravelMetrics travelMetrics(
            long seconds, long meters, long frameWallTimeMs) {
        long arrivalTimeMs = -1L;
        if (seconds >= 0L && seconds <= (Long.MAX_VALUE - frameWallTimeMs) / 1000L) {
            arrivalTimeMs = frameWallTimeMs + seconds * 1000L;
        }
        return new DirectTbtFrame.TravelMetrics(arrivalTimeMs, seconds, meters);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder output = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                output.append(String.format(Locale.US, "%02X", value & 0xff));
            }
            return output.toString();
        } catch (Exception ignored) {
            return "unavailable";
        }
    }

    private void logBitmapSelection(BitmapSelection selection) {
        String key = selection.diagnosticKey();
        if (key.equals(lastBitmapDiagnosticKey)) return;
        lastBitmapDiagnosticKey = key;
        listener.onLog(selection.message());
    }

    static final class ManeuverBitmap {
        final String maneuver;
        final String viewId;
        final byte[] png;
        final int width;
        final int height;
        final long sourceAtMs;
        final long producerEpoch;
        final long renderGeneration;
        final long routeGeneration;
        final long sourceSequence;
        final String sha;

        ManeuverBitmap(String maneuver, String viewId, byte[] png,
                int width, int height, long sourceAtMs) {
            this(maneuver, viewId, png, width, height, sourceAtMs, 0L,
                    Math.max(0L, sourceAtMs), -1L, -1L);
        }

        ManeuverBitmap(String maneuver, String viewId, byte[] png,
                int width, int height, long sourceAtMs,
                long producerEpoch, long renderGeneration) {
            this(maneuver, viewId, png, width, height, sourceAtMs,
                    producerEpoch, renderGeneration, -1L, -1L);
        }

        ManeuverBitmap(String maneuver, String viewId, byte[] png,
                int width, int height, long sourceAtMs,
                long producerEpoch, long renderGeneration, long routeGeneration) {
            this(maneuver, viewId, png, width, height, sourceAtMs,
                    producerEpoch, renderGeneration, routeGeneration, -1L);
        }

        ManeuverBitmap(String maneuver, String viewId, byte[] png,
                int width, int height, long sourceAtMs,
                long producerEpoch, long renderGeneration, long routeGeneration,
                long sourceSequence) {
            this.maneuver = safe(maneuver);
            this.viewId = safe(viewId);
            this.png = png == null ? new byte[0] : png.clone();
            this.width = Math.max(0, width);
            this.height = Math.max(0, height);
            this.sourceAtMs = sourceAtMs;
            this.producerEpoch = producerEpoch;
            this.renderGeneration = renderGeneration;
            this.routeGeneration = routeGeneration;
            this.sourceSequence = sourceSequence;
            this.sha = DirectTbtPayload.shortSha256(this.png);
        }

        boolean matches(String currentManeuver) {
            return !maneuver.isEmpty() && maneuver.equals(safe(currentManeuver));
        }

        boolean isNewerThan(ManeuverBitmap previous) {
            return previous == null
                    || producerEpoch > previous.producerEpoch
                    || (producerEpoch == previous.producerEpoch
                    && renderGeneration > previous.renderGeneration);
        }

        boolean isNewerThan(ManeuverBitmap previous, boolean generationAware) {
            if (previous == null) return true;
            if (sourceSequence >= 0L && previous.sourceSequence >= 0L
                    && sourceSequence != previous.sourceSequence) {
                return sourceSequence > previous.sourceSequence;
            }
            if (!generationAware) return sourceAtMs > previous.sourceAtMs;
            return isNewerThan(previous);
        }
    }

    static final class BitmapSelection {
        final long sequence;
        final String maneuver;
        final String selected;
        final String reason;
        final String viewId;
        final byte[] selectedPng;
        final String sha;
        final int width;
        final int height;
        final boolean currentMatch;
        final long frameDelayMs;
        final long structuredFrameAtMs;
        final String fallbackSha;

        private BitmapSelection(long sequence, String maneuver, String selected,
                String reason, String viewId, byte[] selectedPng, int width, int height,
                boolean currentMatch, long frameDelayMs, long structuredFrameAtMs,
                String fallbackSha) {
            this.sequence = sequence;
            this.maneuver = safe(maneuver);
            this.selected = safe(selected);
            this.reason = safe(reason);
            this.viewId = safe(viewId);
            this.selectedPng = selectedPng == null ? new byte[0] : selectedPng.clone();
            this.sha = DirectTbtPayload.shortSha256(this.selectedPng);
            this.width = Math.max(0, width);
            this.height = Math.max(0, height);
            this.currentMatch = currentMatch;
            this.frameDelayMs = frameDelayMs;
            this.structuredFrameAtMs = structuredFrameAtMs;
            this.fallbackSha = safe(fallbackSha);
        }

        static BitmapSelection select(long sequence, String maneuver,
                ManeuverBitmap candidate, byte[] fallbackPng,
                long structuredFrameAtMs, String googleReason) {
            if (candidate != null && candidate.matches(maneuver)
                    && (candidate.sourceSequence < 0L
                    || candidate.sourceSequence <= sequence)) {
                return google(sequence, candidate, fallbackPng,
                        structuredFrameAtMs, googleReason);
            }
            return fallback(sequence, maneuver, fallbackPng, structuredFrameAtMs);
        }

        static BitmapSelection google(long sequence, ManeuverBitmap candidate,
                byte[] fallbackPng, long structuredFrameAtMs, String reason) {
            return new BitmapSelection(
                    sequence,
                    candidate.maneuver,
                    "google",
                    reason,
                    candidate.viewId,
                    candidate.png,
                    candidate.width,
                    candidate.height,
                    true,
                    structuredFrameAtMs <= 0L
                            ? -1L : candidate.sourceAtMs - structuredFrameAtMs,
                    structuredFrameAtMs,
                    DirectTbtPayload.shortSha256(fallbackPng));
        }

        private static BitmapSelection fallback(long sequence, String maneuver,
                byte[] fallbackPng, long structuredFrameAtMs) {
            return new BitmapSelection(
                    sequence,
                    maneuver,
                    "fallback",
                    "missing",
                    "",
                    fallbackPng,
                    DirectTbtPayload.pngWidth(fallbackPng),
                    DirectTbtPayload.pngHeight(fallbackPng),
                    false,
                    0L,
                    structuredFrameAtMs,
                    DirectTbtPayload.shortSha256(fallbackPng));
        }

        String diagnosticKey() {
            return maneuver + '|' + selected + '|' + viewId + '|' + sha + '|'
                    + currentMatch + '|' + fallbackSha;
        }

        String txKey(DirectTbtPayload.Prepared prepared) {
            if (prepared == null || prepared.maneuverPngBytes() == 0) return "";
            return diagnosticKey() + '|' + prepared.maneuverMode() + '|'
                    + prepared.maneuverPngSha() + '|' + prepared.nativeManeuver();
        }

        String message() {
            return "bitmap_selection sequence=" + sequence
                    + " maneuver=" + maneuver
                    + " selected=" + selected
                    + " reason=" + reason
                    + " viewId=" + viewId
                    + " sha=" + sha
                    + " bytes=" + selectedPng.length
                    + " width=" + width
                    + " height=" + height
                    + " currentMatch=" + currentMatch
                    + " frameDelayMs=" + frameDelayMs
                    + " fallbackSha=" + fallbackSha;
        }

        String txMessage(DirectTbtPayload.Prepared prepared, long sentAtMs) {
            long frameToTxMs = structuredFrameAtMs <= 0L
                    ? -1L : Math.max(0L, sentAtMs - structuredFrameAtMs);
            return "bitmap_tx sequence=" + sequence
                    + " maneuver=" + maneuver
                    + " selected=" + selected
                    + " viewId=" + viewId
                    + " pngSha=" + prepared.maneuverPngSha()
                    + " pngBytes=" + prepared.maneuverPngBytes()
                    + " width=" + prepared.maneuverPngWidth()
                    + " height=" + prepared.maneuverPngHeight()
                    + " mode=" + prepared.maneuverMode()
                    + " native=" + prepared.nativeManeuver()
                    + " distanceM=" + prepared.distanceMeters()
                    + " currentMatch=" + currentMatch
                    + " frameDelayMs=" + frameDelayMs
                    + " frameToTxMs=" + frameToTxMs
                    + " fallbackSha=" + fallbackSha;
        }
    }

    interface Listener {
        void onHandshakeAvailable(String ownerPackage, long sessionGeneration, String reason);
        void onHandshakeUnavailable(String ownerPackage, long sessionGeneration, String reason);
        void onNavigationStarted(String ownerPackage, long sessionGeneration, String reason);
        void onFrame(String ownerPackage, long sessionGeneration,
                DirectTbtFrame frame, String reason, BitmapSelection bitmapSelection,
                GMapsTimingDiagnostics.Frame timing);
        void onSpeedLimit(String ownerPackage, long sessionGeneration,
                int displayValue, int kph, String unit, long eventElapsedMs);
        void onNavigationEnded(String ownerPackage, long routeGeneration,
                long callbackGeneration, String reason);
        void onLiveness(String ownerPackage, long sessionGeneration, String reason);
        void onLog(String message);
    }
}
