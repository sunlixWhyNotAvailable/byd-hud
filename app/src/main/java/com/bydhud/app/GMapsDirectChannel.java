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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Receives bounded navigation snapshots from the separately patched ReVanced GMaps process. */
final class GMapsDirectChannel {
    static final String PACKAGE_NAME = "app.revanced.android.apps.maps";

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
    private static final long REGISTER_RETRY_MS = 5000L;

    private final Context context;
    private final Listener listener;
    private final HandlerThread thread;
    private final Handler handler;
    private final Messenger inbound;
    private final GMapsDirectManeuverMap maneuverMap = new GMapsDirectManeuverMap();
    private final Map<String, byte[]> maneuverBitmaps = new HashMap<>();
    private final Map<String, Long> maneuverBitmapReceivedAtMs = new HashMap<>();
    private final Runnable registrationRetry = this::retryRegistration;

    private boolean running;
    private boolean connected;
    private boolean navigating;
    private boolean terminalLatched;
    private long lastSequence = -1L;
    private String currentManeuver = "";
    private boolean currentBitmapAllowed;
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
            connected = false;
            resetSession();
            registerClient("retry:" + safe(reason));
        });
    }

    void stop(String reason) {
        handler.post(() -> {
            if (!running) return;
            sendRegistration(ACTION_UNREGISTER, false);
            running = false;
            connected = false;
            navigating = false;
            terminalLatched = false;
            handler.removeCallbacks(registrationRetry);
            resetSession();
            listener.onLog("stopped reason=" + safe(reason));
        });
    }

    private boolean handleMessage(Message message) {
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
                listener.onHandshakeAvailable("hello");
                return true;
            case MESSAGE_START:
                connected = true;
                navigating = true;
                terminalLatched = false;
                resetSession();
                listener.onNavigationStarted("start");
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
            currentBitmapAllowed = !(wireValue == 1 && distance == 0);
            byte[] googleManeuverPng = currentBitmapAllowed
                    ? maneuverBitmaps.get(mapping.maneuverName) : null;
            boolean googleBitmapSelected = googleManeuverPng != null
                    && googleManeuverPng.length > 0;
            byte[] maneuverPng = googleBitmapSelected ? googleManeuverPng : null;
            if (!googleBitmapSelected) {
                maneuverPng = HudGraphicPayload.buildOemTurnPng(mapping.fallbackSource);
            }
            long now = SystemClock.elapsedRealtime();
            long bitmapAgeMs = googleBitmapSelected
                    ? Math.max(0L, now - maneuverBitmapReceivedAtMs.getOrDefault(
                    mapping.maneuverName, now)) : -1L;
            currentManeuver = mapping.maneuverName;
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
                    Collections.emptyList(),
                    DirectTbtFrame.AlertOverlay.inactive());
            if (!navigating) {
                navigating = true;
                listener.onNavigationStarted("first-frame");
            }
            connected = true;
            int laneCount = summary.get("lanes") instanceof List
                    ? ((List<?>) summary.get("lanes")).size() : 0;
            listener.onLog("frame sequence=" + sequence
                    + " maneuver=" + mapping.maneuverName
                    + " wire=" + wireValue
                    + " intermediate=" + mapping.intermediate
                    + " source=" + mapping.fallbackSource
                    + " native=" + mapping.nativeManeuver
                    + " distanceM=" + distance
                    + " lanesParsed=" + laneCount
                    + " bitmap=" + (googleBitmapSelected ? "google" : "fallback"));
            logBitmapSelection(sequence, mapping.maneuverName,
                    googleBitmapSelected ? "google" : "fallback",
                    googleBitmapSelected ? "matched"
                            : currentBitmapAllowed ? "missing" : "not-allowed",
                    googleManeuverPng, maneuverPng, mapping.nativeManeuver,
                    distance, bitmapAgeMs);
            listener.onFrame(currentFrame, "frame-" + sequence);
        } catch (Exception error) {
            listener.onLog("frame rejected reason=parse sequence=" + sequence
                    + " error=" + error.getClass().getSimpleName());
        }
    }

    private void handleManeuverBitmap(Bundle data) {
        String maneuver = safe(data.getString("maneuver"));
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
        byte[] previous = maneuverBitmaps.get(maneuver);
        if (Arrays.equals(previous, png)) return;
        maneuverBitmaps.put(maneuver, png.clone());
        long receivedAtMs = SystemClock.elapsedRealtime();
        maneuverBitmapReceivedAtMs.put(maneuver, receivedAtMs);
        listener.onLog("bitmap_rx maneuver=" + maneuver
                + " width=" + width + " height=" + height
                + " bytes=" + png.length + " sha=" + shortSha256(png)
                + " receivedAtElapsedMs=" + receivedAtMs);
        if (currentFrame != null && currentBitmapAllowed && maneuver.equals(currentManeuver)) {
            currentFrame = currentFrame.withManeuverPng(png);
            logBitmapSelection(lastSequence, maneuver, "google", "late-match",
                    png, png, currentFrame.getBydManeuver(),
                    currentFrame.getDistanceMeters(), 0L);
            listener.onFrame(currentFrame, "maneuver-bitmap");
        }
    }

    private void finishNavigation(String reason) {
        terminalLatched = true;
        if (!navigating && currentFrame == null) return;
        navigating = false;
        resetSession();
        listener.onNavigationEnded(reason);
    }

    private void resetSession() {
        lastSequence = -1L;
        currentManeuver = "";
        currentBitmapAllowed = false;
        lastBitmapDiagnosticKey = "";
        currentFrame = null;
        maneuverBitmaps.clear();
        maneuverBitmapReceivedAtMs.clear();
        maneuverMap.reset();
    }

    private void registerClient(String reason) {
        handler.removeCallbacks(registrationRetry);
        if (sendRegistration(ACTION_REGISTER, true)) {
            listener.onLog("registration sent reason=" + reason);
        } else {
            listener.onHandshakeUnavailable("registration-failed");
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

    private static int intValue(Object value, int fallback) {
        if (!(value instanceof Number)) return fallback;
        long number = ((Number) value).longValue();
        return number < Integer.MIN_VALUE || number > Integer.MAX_VALUE
                ? fallback : (int) number;
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

    private static String shortSha256(byte[] bytes) {
        String hash = sha256(bytes);
        return hash.length() <= 12 ? hash : hash.substring(0, 12);
    }

    private void logBitmapSelection(long sequence, String maneuver, String selected,
            String reason, byte[] candidatePng, byte[] transmittedPng,
            int nativeManeuver, int distanceMeters, long rxToTxMs) {
        String candidateSha = candidatePng == null ? "" : shortSha256(candidatePng);
        String transmittedSha = shortSha256(transmittedPng);
        String key = maneuver + '|' + selected + '|' + reason + '|'
                + candidateSha + '|' + transmittedSha + '|' + nativeManeuver;
        if (key.equals(lastBitmapDiagnosticKey)) return;
        lastBitmapDiagnosticKey = key;
        listener.onLog("bitmap_selection sequence=" + sequence
                + " maneuver=" + maneuver
                + " selected=" + selected
                + " reason=" + reason
                + " candidateSha=" + candidateSha
                + " candidateAgeMs=" + rxToTxMs);
        listener.onLog("bitmap_tx sequence=" + sequence
                + " maneuver=" + maneuver
                + " pngBytes=" + transmittedPng.length
                + " pngSha=" + transmittedSha
                + " native=" + nativeManeuver
                + " distanceM=" + distanceMeters
                + " rxToTxMs=" + rxToTxMs);
    }

    interface Listener {
        void onHandshakeAvailable(String reason);
        void onHandshakeUnavailable(String reason);
        void onNavigationStarted(String reason);
        void onFrame(DirectTbtFrame frame, String reason);
        void onNavigationEnded(String reason);
        void onLog(String message);
    }
}
