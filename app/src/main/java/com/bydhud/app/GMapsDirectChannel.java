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
    private static final int PROTOCOL_VERSION = 2;
    private static final int MESSAGE_HELLO = 1;
    private static final int MESSAGE_START = 2;
    private static final int MESSAGE_FRAME = 3;
    private static final int MESSAGE_STOP = 4;
    private static final int MESSAGE_MANEUVER_BITMAP = 5;
    private static final int MAX_FRAME_BYTES = 512 * 1024;
    private static final int MAX_BITMAP_DIMENSION = 256;
    static final int MAX_MANEUVER_BITMAP_CACHE = 64;
    private static final long REGISTER_RETRY_MS = 5000L;

    private final Context context;
    private final Listener listener;
    private final HandlerThread thread;
    private final Handler handler;
    private final Messenger inbound;
    private final GMapsDirectManeuverMap maneuverMap = new GMapsDirectManeuverMap();
    private final Map<String, ManeuverBitmap> maneuverBitmaps = newManeuverBitmapCache();
    private final Runnable registrationRetry = this::retryRegistration;

    private boolean running;
    private boolean connected;
    private boolean navigating;
    private boolean terminalLatched;
    private volatile long sessionGeneration;
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
            navigating = false;
            terminalLatched = false;
            resetSession();
            registerClient("start:" + safe(reason));
        });
    }

    void ensureRegistered(String reason) {
        handler.post(() -> {
            if (!running) return;
            sessionGeneration++;
            connected = false;
            resetSession();
            registerClient("retry:" + safe(reason));
        });
    }

    void stop(String reason) {
        handler.post(() -> {
            if (!running) return;
            sendRegistration(ACTION_UNREGISTER, false);
            sessionGeneration++;
            running = false;
            connected = false;
            navigating = false;
            terminalLatched = false;
            handler.removeCallbacks(registrationRetry);
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

    private boolean handleMessage(Message message) {
        return runMessageBoundary(
                () -> handleMessageSafely(message),
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

    private boolean handleMessageSafely(Message message) {
        if (!running || message == null) return true;
        Bundle data = message.getData();
        if (data == null || data.getInt("protocolVersion", -1) != PROTOCOL_VERSION) {
            listener.onLog("message rejected reason=protocol what="
                    + (message == null ? -1 : message.what));
            return true;
        }
        switch (message.what) {
            case MESSAGE_HELLO:
                connected = true;
                handler.removeCallbacks(registrationRetry);
                listener.onHandshakeAvailable(OWNER_PACKAGE, sessionGeneration, "hello");
                listener.onLiveness(OWNER_PACKAGE, sessionGeneration, "hello");
                return true;
            case MESSAGE_START:
                sessionGeneration++;
                connected = true;
                navigating = true;
                terminalLatched = false;
                resetSession();
                listener.onNavigationStarted(OWNER_PACKAGE, sessionGeneration, "start");
                listener.onLiveness(OWNER_PACKAGE, sessionGeneration, "start");
                return true;
            case MESSAGE_FRAME:
                handleFrame(data);
                return true;
            case MESSAGE_STOP:
                finishNavigation("stop");
                return true;
            case MESSAGE_MANEUVER_BITMAP:
                handleManeuverBitmap(data);
                return true;
            default:
                listener.onLog("message ignored what=" + message.what);
                return true;
        }
    }

    private void handleFrame(Bundle data) {
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

        try {
            Map<String, Object> summary = GmapsWireParser.summarize(payload);
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
                    tripMetrics);
            if (!navigating) {
                navigating = true;
                listener.onNavigationStarted(OWNER_PACKAGE, sessionGeneration, "first-frame");
            }
            connected = true;
            listener.onLiveness(OWNER_PACKAGE, sessionGeneration, "frame");
            int laneCount = summary.get("lanes") instanceof List
                    ? ((List<?>) summary.get("lanes")).size() : 0;
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
            logBitmapSelection(bitmapSelection);
            listener.onFrame(OWNER_PACKAGE, sessionGeneration,
                    currentFrame, "frame-" + sequence, bitmapSelection);
        } catch (Exception error) {
            listener.onLog("frame rejected reason=parse sequence=" + sequence
                    + " error=" + error.getClass().getSimpleName());
        }
    }

    private void handleManeuverBitmap(Bundle data) {
        String maneuver = safe(data.getString("maneuver"));
        String viewId = safe(data.getString("viewId"));
        byte[] png = data.getByteArray("png");
        int width = data.getInt("width", 0);
        int height = data.getInt("height", 0);
        if (maneuver.isEmpty() || png == null || png.length == 0
                || png.length > MAX_FRAME_BYTES || width <= 0 || height <= 0
                || width > MAX_BITMAP_DIMENSION || height > MAX_BITMAP_DIMENSION
                || !validPngBounds(png, width, height)) {
            listener.onLog("maneuver bitmap rejected maneuver=" + maneuver
                    + " width=" + width + " height=" + height
                    + " bytes=" + (png == null ? 0 : png.length));
            return;
        }
        long receivedAtMs = SystemClock.elapsedRealtime();
        long sourceAtMs = data.getLong("bridgeElapsedMs", receivedAtMs);
        ManeuverBitmap previous = maneuverBitmaps.get(maneuver);
        ManeuverBitmap candidate = new ManeuverBitmap(
                maneuver, viewId, png, width, height, sourceAtMs);
        if (!candidate.isNewerThan(previous)) {
            if (!Arrays.equals(previous.png, png)) {
                listener.onLog("maneuver bitmap ignored reason=stale maneuver=" + maneuver
                        + " sourceElapsedMs=" + sourceAtMs
                        + " previousElapsedMs=" + previous.sourceAtMs);
            }
            return;
        }
        maneuverBitmaps.put(maneuver, candidate);
        boolean currentMatch = candidate.matches(currentManeuver) && currentFrame != null;
        long frameDelayMs = currentStructuredFrameAtMs <= 0L
                ? -1L : sourceAtMs - currentStructuredFrameAtMs;
        listener.onLog("bitmap_rx sequence=" + lastSequence
                + " maneuver=" + maneuver
                + " viewId=" + viewId
                + " sha=" + candidate.sha
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
                    currentFrame, "maneuver-bitmap", bitmapSelection);
        }
    }

    private void finishNavigation(String reason) {
        long endedGeneration = ++sessionGeneration;
        terminalLatched = true;
        if (!navigating && currentFrame == null) return;
        navigating = false;
        resetSession();
        listener.onNavigationEnded(OWNER_PACKAGE, endedGeneration, reason);
    }

    private void resetSession() {
        lastSequence = -1L;
        currentStructuredFrameAtMs = 0L;
        currentManeuver = "";
        currentFallbackPng = new byte[0];
        lastBitmapDiagnosticKey = "";
        currentFrame = null;
        maneuverBitmaps.clear();
        maneuverMap.reset();
    }

    private void registerClient(String reason) {
        handler.removeCallbacks(registrationRetry);
        if (sendRegistration(ACTION_REGISTER, true)) {
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

    private boolean sendRegistration(String action, boolean includeClient) {
        try {
            Intent intent = new Intent(action);
            intent.setComponent(new ComponentName(PACKAGE_NAME, RECEIVER));
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            intent.putExtra(EXTRA_IDENTITY, identity());
            if (includeClient) {
                intent.putExtra("channel_identifier",
                        "byd_hud_" + SystemClock.elapsedRealtime());
                intent.putExtra(EXTRA_PROTOCOL, PROTOCOL_VERSION);
                intent.putExtra(EXTRA_CLIENT, inbound);
            }
            context.sendOrderedBroadcast(intent, null);
            return true;
        } catch (Throwable error) {
            listener.onLog("registration failed action=" + action
                    + " error=" + error.getClass().getSimpleName());
            return false;
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
                "protocol=2 sequence=" + sequence
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
        final String sha;

        ManeuverBitmap(String maneuver, String viewId, byte[] png,
                int width, int height, long sourceAtMs) {
            this.maneuver = safe(maneuver);
            this.viewId = safe(viewId);
            this.png = png == null ? new byte[0] : png.clone();
            this.width = Math.max(0, width);
            this.height = Math.max(0, height);
            this.sourceAtMs = sourceAtMs;
            this.sha = DirectTbtPayload.shortSha256(this.png);
        }

        boolean matches(String currentManeuver) {
            return !maneuver.isEmpty() && maneuver.equals(safe(currentManeuver));
        }

        boolean isNewerThan(ManeuverBitmap previous) {
            return previous == null || sourceAtMs > previous.sourceAtMs;
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
            if (candidate != null && candidate.matches(maneuver)) {
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
                DirectTbtFrame frame, String reason, BitmapSelection bitmapSelection);
        void onNavigationEnded(String ownerPackage, long sessionGeneration, String reason);
        void onLiveness(String ownerPackage, long sessionGeneration, String reason);
        void onLog(String message);
    }
}
