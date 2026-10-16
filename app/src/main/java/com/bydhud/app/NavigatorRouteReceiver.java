package com.bydhud.app;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Receives authenticated full-route snapshots without changing the live TBT path. */
final class NavigatorRouteReceiver {
    static final String ACTION = "com.bydhud.app.action.NAV_ROUTE_GEOMETRY_V1";
    private static final String REPLAY_ACTION =
            "com.bydhud.navexport.REQUEST_ROUTE_GEOMETRY_V1";
    private static final String HUD_IDENTITY_ACTION = "com.bydhud.navexport.IDENTITY";
    private static final String GMAPS = "gmaps-26.30";
    private static final String WAZE = "waze-5.25.0.1";
    private static final String GMAPS_PACKAGE = "app.revanced.android.apps.maps";
    private static final String WAZE_PACKAGE = "com.waze";
    private static final int HUD_IDENTITY_REQUEST_CODE = 81;
    private static final long REPLAY_COALESCE_MS = 100L;
    private static final NavigatorRouteStore STORE = new NavigatorRouteStore();
    private static final Object LIFECYCLE_LOCK = new Object();
    private static final Map<String, Integer> PACKAGE_UIDS = new HashMap<>();
    private static long nextGeneration;
    private static RuntimeState current;

    private NavigatorRouteReceiver() {
    }

    /** Start one dynamic receiver for the active HUD runtime. Registration precedes replay. */
    static void start(Context context) {
        Context app = applicationContext(context);
        if (app == null) return;
        if (!runtimeAllowed(app)) {
            stop("runtime_disabled");
            return;
        }

        RuntimeState existing;
        synchronized (LIFECYCLE_LOCK) {
            existing = current;
        }
        if (existing != null && existing.active) {
            refreshEligibility(app);
            return;
        }

        Map<String, Boolean> eligibility = computeEligibility(app);
        boolean detailed = HudPrefs.isDetailedDebugArtifactsEnabled(app);
        RuntimeState state;
        RuntimeException registrationError = null;
        synchronized (LIFECYCLE_LOCK) {
            if (current != null && current.active) return;
            state = new RuntimeState(app, ++nextGeneration);
            state.eligible.putAll(eligibility);
            state.detailedEnabled = detailed;
            current = state;
            try {
                IntentFilter filter = new IntentFilter(ACTION);
                if (Build.VERSION.SDK_INT >= 33) {
                    app.registerReceiver(state.receiver, filter, null, state.ingress,
                            Context.RECEIVER_EXPORTED);
                } else {
                    app.registerReceiver(state.receiver, filter, null, state.ingress);
                }
                state.registered = true;
            } catch (RuntimeException error) {
                state.active = false;
                current = null;
                STORE.clearAll();
                registrationError = error;
            }
        }
        if (registrationError != null) {
            shutdownThreads(state);
            NavigatorRouteLog.error(app, "unknown", 0L, 0L, "receiver_register");
            return;
        }

        NavigatorRouteLog.event(app, "receiver_started");
        state.ingress.post(() -> initializeSources(state));
    }

    /** Stop collecting and synchronously fence any decode already in flight. */
    static void stop(String reason) {
        RuntimeState state;
        synchronized (LIFECYCLE_LOCK) {
            state = current;
            current = null;
            if (state != null) state.active = false;
            STORE.clearAll();
            if (state != null && state.registered) {
                try {
                    state.app.unregisterReceiver(state.receiver);
                } catch (IllegalArgumentException ignored) {
                    // Registration may have been removed while the runtime was stopping.
                }
                state.registered = false;
            }
        }
        if (state == null) return;

        state.ingress.removeCallbacksAndMessages(null);
        synchronized (state.decodeLock) {
            state.pendingDecodes.clear();
            state.pendingArtifacts.clear();
            state.decodePumpScheduled = false;
        }
        state.decode.removeCallbacksAndMessages(null);
        shutdownThreads(state);
        NavigatorRouteLog.event(state.app, "receiver_stopped reason=" + safeLabel(reason));
    }

    /** Re-read existing HUD, TBT-observer and log-only choices without creating a session. */
    static void refreshEligibility(Context context) {
        Context app = applicationContext(context);
        RuntimeState state;
        synchronized (LIFECYCLE_LOCK) {
            state = current;
            if (app == null && state != null) app = state.app;
        }
        if (app == null) return;
        if (!runtimeAllowed(app)) {
            stop("runtime_disabled");
            return;
        }
        if (state == null || !state.active) return;

        Map<String, Boolean> eligibility = computeEligibility(app);
        boolean detailed = HudPrefs.isDetailedDebugArtifactsEnabled(app);
        state.ingress.post(() -> applyEligibility(state, eligibility, detailed, "preferences"));
    }

    /** Called by an already-established HUD route/TBT connection; never starts a navigator. */
    static void sourceConnected(String packageOrNavigator, String connectionToken, String reason) {
        String source = normalizeSource(packageOrNavigator);
        RuntimeState state = activeState();
        if (source == null || state == null) return;
        String token = connectionToken == null ? "" : connectionToken;
        String safeReason = safeLabel(reason);
        state.ingress.post(() -> {
            if (!isActive(state) || !isEligible(state, source)) return;
            String prior = state.connectionTokens.put(source, token);
            if (token.equals(prior)) return;
            NavigatorRouteLog.event(state.app, "source_connected source=" + source
                    + " reason=" + safeReason);
            reconnectSource(state, source);
            requestReplay(state, source, "connected");
        });
    }

    /** Clear one source after its generation-guarded owner reports the connection ended. */
    static void sourceEnded(String packageOrNavigator, String reason) {
        String source = normalizeSource(packageOrNavigator);
        if (source == null) return;
        RuntimeState state = activeState();
        if (state == null) {
            synchronized (LIFECYCLE_LOCK) {
                STORE.clearSource(source);
            }
            return;
        }
        String safeReason = safeLabel(reason);
        state.ingress.post(() -> {
            if (!isActive(state)) return;
            clearSourceState(state, source);
            NavigatorRouteLog.event(state.app, "source_ended source=" + source
                    + " reason=" + safeReason);
        });
    }

    /** Invalidate the cached UID and route state after a supported navigator package change. */
    static void packageChanged(String packageName) {
        String source = normalizeSource(packageName);
        if (source == null) return;
        String packageId = packageForSource(source);
        synchronized (PACKAGE_UIDS) {
            PACKAGE_UIDS.remove(packageId);
        }
        RuntimeState state = activeState();
        if (state == null) return;
        state.ingress.post(() -> {
            if (!isActive(state)) return;
            NavigatorRouteLog.event(state.app, "package_changed source=" + source);
            clearSourceState(state, source);
            applyEligibility(state, computeEligibility(state.app),
                    HudPrefs.isDetailedDebugArtifactsEnabled(state.app), "package_changed");
            if (isEligible(state, source)) {
                reconnectSource(state, source);
                requestReplay(state, source, "package_changed");
            }
        });
    }

    /** Return the immutable current route and step for an eligible, active source. */
    static NavigatorRouteStore.CurrentSnapshot snapshot(String packageOrNavigator) {
        String source = normalizeSource(packageOrNavigator);
        RuntimeState state = activeState();
        if (source == null || state == null || !isEligible(state, source)) return null;
        return STORE.snapshot(source);
    }

    private static void initializeSources(RuntimeState state) {
        if (!isActive(state)) return;
        for (String source : supportedSources()) {
            NavigatorRouteStore.CurrentSnapshot snapshot = STORE.snapshot(source);
            if (snapshot != null && snapshot.header != null) {
                state.lastHeaders.put(source, snapshot.header);
            }
            if (!isEligible(state, source)) continue;
            reconnectSource(state, source);
            requestReplay(state, source, "initial");
        }
    }

    private static void applyEligibility(RuntimeState state, Map<String, Boolean> next,
            boolean detailed, String reason) {
        if (!isActive(state)) return;
        for (String source : supportedSources()) {
            boolean wasEligible = isEligible(state, source);
            boolean isNowEligible = Boolean.TRUE.equals(next.get(source));
            if (wasEligible == isNowEligible) continue;
            state.eligible.put(source, isNowEligible);
            NavigatorRouteLog.event(state.app, "eligibility_changed source=" + source
                    + " eligible=" + isNowEligible + " reason=" + safeLabel(reason));
            if (isNowEligible) {
                reconnectSource(state, source);
                requestReplay(state, source, "eligible");
            } else {
                clearSourceState(state, source);
            }
        }

        if (!state.detailedEnabled && detailed) {
            for (String source : supportedSources()) {
                if (!isEligible(state, source)) continue;
                NavigatorRouteStore.CurrentSnapshot snapshot = STORE.snapshot(source);
                if (snapshot != null && snapshot.route != null) {
                    enqueueArtifact(state, snapshot.route);
                }
            }
        }
        state.detailedEnabled = detailed;
    }

    private static void reconnectSource(RuntimeState state, String source) {
        long now = SystemClock.elapsedRealtime();
        NavigatorRouteStore.Result result;
        synchronized (LIFECYCLE_LOCK) {
            if (!isActiveLocked(state)) return;
            result = STORE.reconnect(source, now);
        }
        handleStoreResult(state, result, null, false);
    }

    private static void clearSourceState(RuntimeState state, String source) {
        cancelDeadline(state, source);
        cancelReplay(state, source);
        state.connectionTokens.remove(source);
        state.assemblyClocks.remove(source);
        clearPendingDecode(state, source);
        synchronized (LIFECYCLE_LOCK) {
            if (isActiveLocked(state)) STORE.clearSource(source);
        }
    }

    private static void handleBroadcast(RuntimeState state, Intent intent) {
        if (!isActive(state)) return;
        try {
            if (intent == null || !ACTION.equals(intent.getAction())
                    || !state.app.getPackageName().equals(intent.getPackage())) {
                reject(state, null, null, "bad_action");
                return;
            }
            Bundle extras = intent.getExtras();
            if (extras == null) {
                reject(state, null, null, "missing_bundle");
                return;
            }
            extras.setClassLoader(PendingIntent.class.getClassLoader());
            Object sourceValue = extras.get("navigator");
            Object tokenValue = extras.get("producerIdentity");
            if (!(sourceValue instanceof String) || !(tokenValue instanceof PendingIntent)) {
                reject(state, null, null, "bad_identity");
                return;
            }
            String source = (String) sourceValue;
            String expectedPackage = packageForSource(source);
            PendingIntent producerIdentity = (PendingIntent) tokenValue;
            if (expectedPackage == null || !validProducerIdentity(
                    state.app, expectedPackage, producerIdentity)) {
                reject(state, null, null, "bad_identity");
                return;
            }
            if (!isEligible(state, source)) return;

            long now = SystemClock.elapsedRealtime();
            String event = requiredString(extras, "event");
            NavigatorRouteData.Header header = readHeader(extras, source, event, now);
            if (header == null) {
                reject(state, source, null, "bad_header");
                return;
            }
            if (!hasExactKeys(extras, keysForEvent(event))) {
                rejectMalformedEvent(state, extras, header, event, "bad_fields", now);
                return;
            }
            if (requiredInt(extras, "schemaVersion", -1) != 1
                    || !"gzip-byr1".equals(requiredString(extras, "encoding"))
                    || !"navigator_source_degrees".equals(
                    requiredString(extras, "coordinateFrame"))) {
                rejectMalformedEvent(state, extras, header, event, "bad_schema", now);
                return;
            }

            NavigatorRouteStore.Result result;
            if ("route_chunk".equals(event)) {
                result = acceptChunk(state, extras, header, now);
            } else if ("current_step".equals(event)) {
                result = acceptStep(state, extras, header, now);
            } else if ("clear".equals(event)) {
                String reason = requiredString(extras, "reason");
                if (!safeProducerReason(reason)) {
                    rejectAuthenticated(state, header, "bad_clear", now);
                    return;
                }
                synchronized (LIFECYCLE_LOCK) {
                    if (!isActiveLocked(state)) return;
                    result = STORE.acceptClear(header, reason, now);
                }
            } else if ("unavailable".equals(event)) {
                String reason = requiredString(extras, "reason");
                if (header.routeRevision == 0 || !safeProducerReason(reason)) {
                    rejectAuthenticated(state, header, "bad_unavailable", now);
                    return;
                }
                synchronized (LIFECYCLE_LOCK) {
                    if (!isActiveLocked(state)) return;
                    result = STORE.acceptUnavailable(header, reason, now);
                }
            } else {
                rejectAuthenticated(state, header, "bad_event", now);
                return;
            }
            handleStoreResult(state, result, null, false);
        } catch (RuntimeException malformed) {
            if (isActive(state)) reject(state, null, null, "malformed_bundle");
        }
    }

    private static NavigatorRouteStore.Result acceptChunk(RuntimeState state, Bundle extras,
            NavigatorRouteData.Header header, long now) {
        Object payloadValue = extras.get("payload");
        int index = requiredInt(extras, "chunkIndex", -1);
        int count = requiredInt(extras, "chunkCount", -1);
        int encodedBytes = requiredInt(extras, "encodedBytes", -1);
        int points = requiredInt(extras, "pointCount", -1);
        int maneuvers = requiredInt(extras, "maneuverCount", -1);
        Object geometryValue = extras.get("geometryAvailable");
        Object maneuverValue = extras.get("maneuversAvailable");
        boolean typed = payloadValue instanceof byte[]
                && geometryValue instanceof Boolean && maneuverValue instanceof Boolean
                && hasInt(extras, "chunkIndex") && hasInt(extras, "chunkCount")
                && hasInt(extras, "encodedBytes") && hasInt(extras, "pointCount")
                && hasInt(extras, "maneuverCount");
        byte[] payload = payloadValue instanceof byte[] ? (byte[]) payloadValue : new byte[0];
        boolean invalidGeometryAvailability = typed && (Boolean) geometryValue
                && points == 0;
        if (!typed || payload.length > NavigatorRouteStore.CHUNK_BYTES
                || invalidGeometryAvailability) {
            return invalidateMalformedChunk(state, header, index, count, encodedBytes,
                    points, maneuvers, geometryValue instanceof Boolean
                            && (Boolean) geometryValue,
                    maneuverValue instanceof Boolean && (Boolean) maneuverValue, now);
        }

        NavigatorRouteStore.Chunk chunk = new NavigatorRouteStore.Chunk(header, index, count,
                encodedBytes, points, maneuvers, (Boolean) geometryValue,
                (Boolean) maneuverValue, payload);
        synchronized (LIFECYCLE_LOCK) {
            if (!isActiveLocked(state)) return null;
            return STORE.acceptChunk(chunk, now);
        }
    }

    private static NavigatorRouteStore.Result invalidateMalformedChunk(RuntimeState state,
            NavigatorRouteData.Header header, int index, int count, int encodedBytes,
            int points, int maneuvers, boolean geometryAvailable,
            boolean maneuversAvailable, long now) {
        NavigatorRouteStore.CurrentSnapshot snapshot = STORE.snapshot(header.navigator);
        boolean newer = isNewer(header, snapshot == null ? null : snapshot.header);
        boolean sameActiveRevision = snapshot != null && snapshot.header != null
                && snapshot.header.sameRevision(header)
                && (snapshot.outcome == NavigatorRouteStore.Outcome.COLLECTING
                || snapshot.outcome == NavigatorRouteStore.Outcome.REPLAY_REQUIRED
                || snapshot.outcome == NavigatorRouteStore.Outcome.READY_TO_DECODE
                || snapshot.outcome == NavigatorRouteStore.Outcome.PUBLISHED);
        if (newer || sameActiveRevision) {
            byte[] invalidPayload = new byte[0];
            NavigatorRouteStore.Chunk invalid = new NavigatorRouteStore.Chunk(header,
                    index, count, encodedBytes, points, maneuvers, geometryAvailable,
                    maneuversAvailable, invalidPayload);
            NavigatorRouteStore.Result result = null;
            synchronized (LIFECYCLE_LOCK) {
                if (isActiveLocked(state)) {
                    result = STORE.acceptChunk(invalid, now);
                }
            }
            if (result != null) {
                handleStoreResult(state, result, null, false);
                return null;
            }
        }
        reject(state, header.navigator, header, "bad_chunk");
        return null;
    }

    private static void rejectMalformedEvent(RuntimeState state, Bundle extras,
            NavigatorRouteData.Header header, String event, String reason, long now) {
        if ("route_chunk".equals(event)) {
            Object geometry = extras.get("geometryAvailable");
            Object maneuversAvailable = extras.get("maneuversAvailable");
            invalidateMalformedChunk(state, header,
                    requiredInt(extras, "chunkIndex", -1),
                    requiredInt(extras, "chunkCount", -1),
                    requiredInt(extras, "encodedBytes", -1),
                    requiredInt(extras, "pointCount", -1),
                    requiredInt(extras, "maneuverCount", -1),
                    geometry instanceof Boolean && (Boolean) geometry,
                    maneuversAvailable instanceof Boolean && (Boolean) maneuversAvailable,
                    now);
        } else {
            rejectAuthenticated(state, header, reason, now);
        }
    }

    private static NavigatorRouteStore.Result acceptStep(RuntimeState state, Bundle extras,
            NavigatorRouteData.Header header, long now) {
        if (header.routeRevision == 0) {
            rejectAuthenticated(state, header, "bad_step", now);
            return null;
        }
        Object stepValue = extras.get("step");
        if (!(stepValue instanceof Bundle)) {
            rejectAuthenticated(state, header, "bad_step", now);
            return null;
        }
        Bundle step = (Bundle) stepValue;
        long serial = requiredLong(extras, "stepSerial", -1L);
        long stepElapsed = requiredLong(step, "stepElapsedMs", -1L);
        if (serial < 0 || stepElapsed < 0 || stepElapsed > now) {
            rejectAuthenticated(state, header, "bad_step", now);
            return null;
        }

        NavigatorRouteStore.CurrentStep parsed;
        if (GMAPS.equals(header.navigator)) {
            parsed = readMapsStep(step, header, serial, stepElapsed, now);
        } else {
            parsed = readWazeStep(step, header, serial, stepElapsed);
        }
        if (parsed == null) {
            rejectAuthenticated(state, header, "bad_step", now);
            return null;
        }
        synchronized (LIFECYCLE_LOCK) {
            if (!isActiveLocked(state)) return null;
            return STORE.acceptStep(parsed, now);
        }
    }

    private static NavigatorRouteStore.CurrentStep readMapsStep(Bundle step,
            NavigatorRouteData.Header header, long serial, long stepElapsed, long now) {
        Object availableValue = step.get("available");
        Object onRouteValue = step.get("onRoute");
        if (!(availableValue instanceof Boolean) || !(onRouteValue instanceof Boolean)) return null;
        boolean available = (Boolean) availableValue;
        Set<String> expected = available
                ? keys("stepElapsedMs", "available", "onRoute", "id", "ordinal",
                "vertexIndex", "type", "turnSide", "latitude", "longitude")
                : keys("stepElapsedMs", "available", "onRoute");
        if (!hasExactKeys(step, expected)) return null;

        String id = "";
        String type = "";
        String side = "";
        int ordinal = -1;
        int vertex = -1;
        Double latitude = null;
        Double longitude = null;
        if (available) {
            Object idValue = step.get("id");
            Object ordinalValue = step.get("ordinal");
            Object vertexValue = step.get("vertexIndex");
            Object typeValue = step.get("type");
            Object sideValue = step.get("turnSide");
            Object latitudeValue = step.get("latitude");
            Object longitudeValue = step.get("longitude");
            if (!(idValue instanceof String) || !(ordinalValue instanceof Integer)
                    || !(vertexValue instanceof Integer) || !(typeValue instanceof String)
                    || !(sideValue instanceof String) || !(latitudeValue instanceof Double)
                    || !(longitudeValue instanceof Double)) return null;
            id = (String) idValue;
            type = (String) typeValue;
            side = (String) sideValue;
            ordinal = (Integer) ordinalValue;
            vertex = (Integer) vertexValue;
            latitude = (Double) latitudeValue;
            longitude = (Double) longitudeValue;
            if (id.length() > 512 || type.length() > 512 || side.length() > 512
                    || !validCoordinate(latitude, 90)
                    || !validCoordinate(longitude, 180)) return null;
        }
        try {
            return new NavigatorRouteStore.CurrentStep(header, serial, stepElapsed,
                    (Boolean) availableValue, (Boolean) onRouteValue, id, ordinal, vertex,
                    type, side, latitude, longitude, -1, -1, null, null, "");
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static NavigatorRouteStore.CurrentStep readWazeStep(Bundle step,
            NavigatorRouteData.Header header, long serial, long stepElapsed) {
        if (!hasExactKeys(step, keys("stepElapsedMs", "currentSegmentIndex",
                "nextSegmentIndex", "currentInstruction", "nextInstruction", "association"))) {
            return null;
        }
        Object currentSegment = step.get("currentSegmentIndex");
        Object nextSegment = step.get("nextSegmentIndex");
        Object currentInstruction = step.get("currentInstruction");
        Object nextInstruction = step.get("nextInstruction");
        Object association = step.get("association");
        if (!(currentSegment instanceof Integer) || !(nextSegment instanceof Integer)
                || !(currentInstruction instanceof Integer) || !(nextInstruction instanceof Integer)
                || !"source_indices".equals(association)) return null;
        try {
            return new NavigatorRouteStore.CurrentStep(header, serial, stepElapsed,
                    null, null, "", -1, -1, "", "", null, null,
                    (Integer) currentSegment, (Integer) nextSegment,
                    (Integer) currentInstruction, (Integer) nextInstruction,
                    (String) association);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static NavigatorRouteData.Header readHeader(Bundle extras, String source,
            String event, long now) {
        if (packageForSource(source) == null
                || !(extras.get("schemaVersion") instanceof Integer)
                || !(extras.get("producerEpoch") instanceof Long)
                || !(extras.get("routeRevision") instanceof Long)
                || !(extras.get("routeId") instanceof String)
                || !(extras.get("sourceElapsedMs") instanceof Long)) return null;
        long epoch = (Long) extras.get("producerEpoch");
        long revision = (Long) extras.get("routeRevision");
        long sourceElapsed = (Long) extras.get("sourceElapsedMs");
        String routeId = (String) extras.get("routeId");
        if (epoch <= 0 || revision < 0 || sourceElapsed < 0 || sourceElapsed > now
                || routeId.length() > 512 || (revision == 0 && !"clear".equals(event))) {
            return null;
        }
        try {
            return new NavigatorRouteData.Header(source, epoch, revision, routeId, sourceElapsed);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static void handleStoreResult(RuntimeState state, NavigatorRouteStore.Result result,
            RouteCompletion completion, boolean suppressUnavailableLog) {
        if (result == null || !isActive(state)) return;
        String source = result.header == null ? null : result.header.navigator;
        long now = SystemClock.elapsedRealtime();
        if (result.newRevision && completion == null && source != null) {
            cancelDeadline(state, source);
            clearPendingDecode(state, source);
            NavigatorRouteData.Header prior = state.lastHeaders.put(source, result.header);
            if (prior == null) {
                NavigatorRouteLog.event(state.app, "capture_begin source=" + source
                        + " epoch=" + result.header.producerEpoch
                        + " revision=" + result.header.routeRevision);
            } else if (isNewer(result.header, prior)) {
                NavigatorRouteLog.event(state.app, "replaced source=" + source
                        + " epoch=" + result.header.producerEpoch
                        + " revision=" + result.header.routeRevision);
            }
            state.assemblyClocks.put(source, new AssemblyClock(result.header, now));
        }

        switch (result.outcome) {
            case COLLECTING:
            case DUPLICATE:
            case REPLAY_REQUIRED:
                if (source != null) {
                    ensureAssemblyClock(state, result.header, now);
                    scheduleDeadline(state, source, result.deadlineElapsedMs);
                }
                if (result.outcome == NavigatorRouteStore.Outcome.REPLAY_REQUIRED) {
                    NavigatorRouteLog.event(state.app, "replay_timeout source=" + source
                            + " attempt=" + Math.max(1, result.replayCount));
                    requestReplay(state, source, "timeout");
                }
                break;
            case READY_TO_DECODE:
                if (source != null) cancelDeadline(state, source);
                if (result.decodeWork != null) enqueueDecode(state, result.decodeWork);
                break;
            case PUBLISHED:
                if (source != null) {
                    cancelDeadline(state, source);
                    if (completion != null && result.newRevision) {
                        long age = Math.max(0L, now - completion.route.header().sourceElapsedMs);
                        NavigatorRouteLog.accepted(state.app, completion.route,
                                completion.work.encodedBytes, age,
                                completion.assemblyMs, completion.decodeMs);
                        enqueueArtifact(state, completion.route);
                    }
                }
                break;
            case INVALIDATED:
                if (source != null) {
                    cancelDeadline(state, source);
                    state.assemblyClocks.remove(source);
                    clearPendingDecode(state, source);
                }
                NavigatorRouteLog.event(state.app, "rejected source=" + safeSource(source)
                        + " epoch=" + epoch(result) + " revision=" + revision(result)
                        + " reason=" + safeLabel(result.reason));
                break;
            case CLEARED:
                if (source != null) {
                    cancelDeadline(state, source);
                    state.assemblyClocks.remove(source);
                    clearPendingDecode(state, source);
                }
                NavigatorRouteLog.event(state.app, "clear source=" + safeSource(source)
                        + " epoch=" + epoch(result) + " revision=" + revision(result)
                        + " reason=" + safeLabel(result.reason));
                break;
            case UNAVAILABLE:
                if (source != null) {
                    cancelDeadline(state, source);
                    state.assemblyClocks.remove(source);
                    clearPendingDecode(state, source);
                }
                if (!suppressUnavailableLog) {
                    NavigatorRouteLog.event(state.app, "unavailable source=" + safeSource(source)
                            + " epoch=" + epoch(result) + " revision=" + revision(result)
                            + " reason=" + safeLabel(result.reason));
                }
                break;
            case RETRY_EXHAUSTED:
                if (source != null) {
                    cancelDeadline(state, source);
                    state.assemblyClocks.remove(source);
                    clearPendingDecode(state, source);
                }
                NavigatorRouteLog.event(state.app, "retry_exhausted source=" + safeSource(source)
                        + " epoch=" + epoch(result) + " revision=" + revision(result)
                        + " attempt=" + Math.max(2, result.replayCount));
                break;
            case STALE:
            default:
                break;
        }
        if (result.stepChanged && result.header != null) {
            NavigatorRouteStore.CurrentSnapshot snapshot = STORE.snapshot(result.header.navigator);
            if (snapshot != null && snapshot.step != null) {
                NavigatorRouteLog.step(state.app, snapshot.step);
            }
        }
    }

    private static void scheduleDeadline(RuntimeState state, String source, long deadline) {
        cancelDeadline(state, source);
        if (deadline == NavigatorRouteStore.NO_DEADLINE || !isActive(state)) return;
        Runnable[] timerReference = new Runnable[1];
        Runnable timer = () -> {
            if (!isActive(state) || state.deadlines.get(source) != timerReference[0]) {
                return;
            }
            state.deadlines.remove(source);
            long now = SystemClock.elapsedRealtime();
            NavigatorRouteStore.Result result;
            synchronized (LIFECYCLE_LOCK) {
                if (!isActiveLocked(state)) return;
                result = STORE.expire(source, now);
            }
            handleStoreResult(state, result, null, false);
        };
        timerReference[0] = timer;
        state.deadlines.put(source, timer);
        long delay = Math.max(0L, deadline - SystemClock.elapsedRealtime());
        state.ingress.postDelayed(timer, delay);
    }

    private static void cancelDeadline(RuntimeState state, String source) {
        Runnable prior = state.deadlines.remove(source);
        if (prior != null) state.ingress.removeCallbacks(prior);
    }

    private static void requestReplay(RuntimeState state, String source, String reason) {
        if (!isActive(state) || !isEligible(state, source)
                || state.replayRequests.containsKey(source)) return;
        Runnable request = () -> {
            state.replayRequests.remove(source);
            if (!isActive(state) || !isEligible(state, source)) return;
            String targetPackage = packageForSource(source);
            if (targetPackage == null || installedUid(state.app, targetPackage) < 0) return;
            try {
                PendingIntent identity = PendingIntent.getBroadcast(state.app,
                        HUD_IDENTITY_REQUEST_CODE,
                        new Intent(HUD_IDENTITY_ACTION).setPackage(state.app.getPackageName()),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                Intent replay = new Intent(REPLAY_ACTION).setPackage(targetPackage)
                        .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                                | Intent.FLAG_RECEIVER_FOREGROUND)
                        .putExtra("schemaVersion", 1)
                        .putExtra("bydhudIdentity", identity);
                state.app.sendBroadcast(replay);
                NavigatorRouteLog.event(state.app, "replay_requested source=" + source
                        + " reason=" + safeLabel(reason));
            } catch (RuntimeException failure) {
                NavigatorRouteLog.error(state.app, source, 0L, 0L, "replay_send");
            }
        };
        state.replayRequests.put(source, request);
        state.ingress.postDelayed(request, REPLAY_COALESCE_MS);
    }

    private static void cancelReplay(RuntimeState state, String source) {
        Runnable prior = state.replayRequests.remove(source);
        if (prior != null) state.ingress.removeCallbacks(prior);
    }

    private static void enqueueDecode(RuntimeState state, NavigatorRouteStore.DecodeWork work) {
        if (!isActive(state)) return;
        long now = SystemClock.elapsedRealtime();
        long started = assemblyStartedAt(state, work.header, now);
        DecodeEntry entry = new DecodeEntry(work, started);
        synchronized (state.decodeLock) {
            if (!state.active) return;
            state.pendingDecodes.put(work.header.navigator, entry);
            if (!state.decodePumpScheduled) {
                state.decodePumpScheduled = true;
                if (!state.decode.post(() -> decodeNext(state))) {
                    state.decodePumpScheduled = false;
                    state.pendingDecodes.clear();
                    state.pendingArtifacts.clear();
                }
            }
        }
    }

    private static void enqueueArtifact(RuntimeState state, NavigatorRouteData route) {
        if (!isActive(state) || !HudPrefs.isDetailedDebugArtifactsEnabled(state.app)) return;
        NavigatorRouteData.Header header = route.header();
        String key = header.navigator + ":" + header.producerEpoch + ":" + header.routeRevision;
        synchronized (state.decodeLock) {
            if (!state.active) return;
            state.pendingArtifacts.put(key, route);
            if (!state.decodePumpScheduled) {
                state.decodePumpScheduled = true;
                if (!state.decode.post(() -> decodeNext(state))) {
                    state.decodePumpScheduled = false;
                    state.pendingDecodes.clear();
                    state.pendingArtifacts.clear();
                }
            }
        }
    }

    private static void decodeNext(RuntimeState state) {
        DecodeEntry entry = null;
        NavigatorRouteData artifact = null;
        synchronized (state.decodeLock) {
            if (!state.active) {
                state.pendingDecodes.clear();
                state.pendingArtifacts.clear();
                state.decodePumpScheduled = false;
                return;
            }
            // Persist an accepted revision before decoding another; only this worker may wait.
            if (!state.pendingArtifacts.isEmpty()) {
                String key = state.pendingArtifacts.keySet().iterator().next();
                artifact = state.pendingArtifacts.remove(key);
            } else if (!state.pendingDecodes.isEmpty()) {
                String source = choosePendingSource(state);
                state.lastDecodeSource = source;
                entry = state.pendingDecodes.remove(source);
            } else {
                state.decodePumpScheduled = false;
                return;
            }
        }
        if (artifact != null) {
            try {
                NavigatorRouteLog.saveDetailedCurrent(state.app, artifact);
            } catch (RuntimeException failure) {
                NavigatorRouteData.Header header = artifact.header();
                NavigatorRouteLog.artifactError(state.app, header.navigator,
                        header.producerEpoch, header.routeRevision);
            } finally {
                state.ingress.post(() -> scheduleNextDecode(state));
            }
            return;
        }

        DecodeEntry decoding = entry;
        long decodeStart = SystemClock.elapsedRealtime();
        NavigatorRouteData route = null;
        String failure = null;
        try {
            route = entry.work.decode();
        } catch (IOException | RuntimeException invalid) {
            failure = "decode_invalid";
        } catch (OutOfMemoryError exhausted) {
            failure = "decode_memory";
        }
        long decodeEnd = SystemClock.elapsedRealtime();
        NavigatorRouteData decoded = route;
        String decodeFailure = failure;
        long assemblyMs = Math.max(0L, decodeStart - entry.assemblyStartedMs);
        long decodeMs = Math.max(0L, decodeEnd - decodeStart);
        if (!state.ingress.post(() -> finishDecode(state, decoding, decoded,
                decodeFailure, assemblyMs, decodeMs))) {
            synchronized (state.decodeLock) {
                state.pendingDecodes.clear();
                state.pendingArtifacts.clear();
                state.decodePumpScheduled = false;
            }
        }
    }

    private static void finishDecode(RuntimeState state, DecodeEntry entry,
            NavigatorRouteData route, String failure, long assemblyMs, long decodeMs) {
        if (!isActive(state)) return;
        long now = SystemClock.elapsedRealtime();
        NavigatorRouteStore.Result result;
        synchronized (LIFECYCLE_LOCK) {
            if (!isActiveLocked(state)) return;
            if (failure == null) {
                result = STORE.publish(entry.work, route, now);
            } else {
                result = STORE.fail(entry.work, failure, now);
            }
        }
        if (failure != null && result != null
                && result.outcome == NavigatorRouteStore.Outcome.UNAVAILABLE) {
            NavigatorRouteLog.event(state.app, "decode_failed source=" + entry.work.header.navigator
                    + " epoch=" + entry.work.header.producerEpoch
                    + " revision=" + entry.work.header.routeRevision
                    + " reason=" + failure);
            handleStoreResult(state, result, null, true);
        } else {
            handleStoreResult(state, result, failure == null && route != null
                    ? new RouteCompletion(entry.work, route, assemblyMs, decodeMs) : null, false);
        }
        scheduleNextDecode(state);
    }

    private static void scheduleNextDecode(RuntimeState state) {
        synchronized (state.decodeLock) {
            if (!state.active) {
                state.pendingDecodes.clear();
                state.pendingArtifacts.clear();
                state.decodePumpScheduled = false;
                return;
            }
            if (state.pendingDecodes.isEmpty() && state.pendingArtifacts.isEmpty()) {
                state.decodePumpScheduled = false;
                return;
            }
            if (!state.decode.post(() -> decodeNext(state))) {
                state.pendingDecodes.clear();
                state.pendingArtifacts.clear();
                state.decodePumpScheduled = false;
            }
        }
    }

    private static String choosePendingSource(RuntimeState state) {
        String other = WAZE.equals(state.lastDecodeSource) ? GMAPS : WAZE;
        if (state.pendingDecodes.containsKey(other)) return other;
        if (state.pendingDecodes.containsKey(GMAPS)) return GMAPS;
        return WAZE;
    }

    private static void clearPendingDecode(RuntimeState state, String source) {
        synchronized (state.decodeLock) {
            state.pendingDecodes.remove(source);
        }
    }

    private static void ensureAssemblyClock(RuntimeState state,
            NavigatorRouteData.Header header, long now) {
        if (header == null) return;
        AssemblyClock clock = state.assemblyClocks.get(header.navigator);
        if (clock == null || !clock.header.sameRevision(header)) {
            state.assemblyClocks.put(header.navigator, new AssemblyClock(header, now));
        }
    }

    private static long assemblyStartedAt(RuntimeState state,
            NavigatorRouteData.Header header, long now) {
        ensureAssemblyClock(state, header, now);
        AssemblyClock clock = state.assemblyClocks.get(header.navigator);
        return clock == null ? now : clock.startedAtMs;
    }

    private static void rejectAuthenticated(RuntimeState state,
            NavigatorRouteData.Header header, String reason, long now) {
        if (header != null) {
            NavigatorRouteStore.CurrentSnapshot snapshot = STORE.snapshot(header.navigator);
            if (isNewer(header, snapshot == null ? null : snapshot.header)) {
                NavigatorRouteStore.Result result;
                synchronized (LIFECYCLE_LOCK) {
                    if (!isActiveLocked(state)) return;
                    result = STORE.acceptUnavailable(header, "invalid_event", now);
                }
                handleStoreResult(state, result, null, true);
            }
        }
        reject(state, header == null ? null : header.navigator, header, reason);
    }

    private static void reject(RuntimeState state, String source,
            NavigatorRouteData.Header header, String reason) {
        if (!isActive(state)) return;
        NavigatorRouteLog.event(state.app, "rejected source=" + safeSource(source)
                + " epoch=" + (header == null ? 0L : header.producerEpoch)
                + " revision=" + (header == null ? 0L : header.routeRevision)
                + " reason=" + safeLabel(reason));
    }

    private static boolean validProducerIdentity(Context context, String expectedPackage,
            PendingIntent identity) {
        try {
            int uid = installedUid(context, expectedPackage);
            return uid >= 0 && expectedPackage.equals(identity.getCreatorPackage())
                    && uid == identity.getCreatorUid();
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static int installedUid(Context context, String packageName) {
        synchronized (PACKAGE_UIDS) {
            Integer cached = PACKAGE_UIDS.get(packageName);
            if (cached != null) return cached;
        }
        int uid = -1;
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(packageName, 0);
            uid = info.uid;
        } catch (PackageManager.NameNotFoundException | RuntimeException absent) {
            // A missing source is normal; the receiver does not launch or poll for it.
        }
        synchronized (PACKAGE_UIDS) {
            PACKAGE_UIDS.put(packageName, uid);
        }
        return uid;
    }

    private static Map<String, Boolean> computeEligibility(Context context) {
        Map<String, Boolean> result = new HashMap<>();
        boolean runtime = runtimeAllowed(context);
        for (String source : supportedSources()) {
            String packageName = packageForSource(source);
            boolean installed = packageName != null && installedUid(context, packageName) >= 0;
            boolean eligible = runtime && installed && (
                    NavCapturePrefs.isHudEnabled(context, packageName)
                            || NavHudLiveSender.shouldObserveTbtWithoutHud(context, packageName)
                            || NavCapturePrefs.isLogOnlyEnabled(context, packageName));
            result.put(source, eligible);
        }
        return result;
    }

    private static boolean runtimeAllowed(Context context) {
        return context != null && !HudPrefs.isUserShutdownActive(context)
                && UserRuntimeSession.allowsRuntime(context);
    }

    private static RuntimeState activeState() {
        synchronized (LIFECYCLE_LOCK) {
            return current != null && current.active ? current : null;
        }
    }

    private static boolean isActive(RuntimeState state) {
        return state != null && state.active;
    }

    private static boolean isActiveLocked(RuntimeState state) {
        return state != null && state.active && current == state;
    }

    private static boolean isEligible(RuntimeState state, String source) {
        return state != null && Boolean.TRUE.equals(state.eligible.get(source));
    }

    private static boolean isNewer(NavigatorRouteData.Header candidate,
            NavigatorRouteData.Header currentHeader) {
        if (candidate == null) return false;
        if (currentHeader == null) return true;
        return candidate.producerEpoch > currentHeader.producerEpoch
                || (candidate.producerEpoch == currentHeader.producerEpoch
                && candidate.routeRevision > currentHeader.routeRevision);
    }

    private static boolean hasExactKeys(Bundle bundle, Set<String> expected) {
        return bundle != null && expected != null && expected.equals(bundle.keySet());
    }

    private static Set<String> keysForEvent(String event) {
        Set<String> result = keys("schemaVersion", "navigator", "producerEpoch", "routeRevision",
                "routeId", "sourceElapsedMs", "producerIdentity", "event", "encoding",
                "coordinateFrame");
        if ("route_chunk".equals(event)) {
            result.addAll(keys("chunkIndex", "chunkCount", "encodedBytes", "pointCount",
                    "maneuverCount", "geometryAvailable", "maneuversAvailable", "payload"));
        } else if ("current_step".equals(event)) {
            result.addAll(keys("stepSerial", "step"));
        } else if ("clear".equals(event) || "unavailable".equals(event)) {
            result.add("reason");
        } else {
            return null;
        }
        return result;
    }

    private static Set<String> keys(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    private static String requiredString(Bundle bundle, String key) {
        Object value = bundle.get(key);
        return value instanceof String ? (String) value : null;
    }

    private static int requiredInt(Bundle bundle, String key, int fallback) {
        Object value = bundle.get(key);
        return value instanceof Integer ? (Integer) value : fallback;
    }

    private static long requiredLong(Bundle bundle, String key, long fallback) {
        Object value = bundle.get(key);
        return value instanceof Long ? (Long) value : fallback;
    }

    private static boolean hasInt(Bundle bundle, String key) {
        return bundle.get(key) instanceof Integer;
    }

    private static boolean validCoordinate(double value, double limit) {
        return Double.isFinite(value) && Math.abs(value) <= limit;
    }

    private static boolean safeProducerReason(String reason) {
        return reason != null && reason.matches("[A-Za-z0-9_.:-]{1,96}");
    }

    private static String safeLabel(String value) {
        return value != null && value.matches("[A-Za-z0-9_.:-]{1,48}") ? value : "unknown";
    }

    private static String safeSource(String source) {
        return source == null ? "unknown" : source;
    }

    private static long epoch(NavigatorRouteStore.Result result) {
        return result.header == null ? 0L : result.header.producerEpoch;
    }

    private static long revision(NavigatorRouteStore.Result result) {
        return result.header == null ? 0L : result.header.routeRevision;
    }

    private static String normalizeSource(String packageOrNavigator) {
        if (GMAPS.equals(packageOrNavigator) || GMAPS_PACKAGE.equals(packageOrNavigator)) {
            return GMAPS;
        }
        if (WAZE.equals(packageOrNavigator) || WAZE_PACKAGE.equals(packageOrNavigator)) {
            return WAZE;
        }
        return null;
    }

    private static String packageForSource(String source) {
        if (GMAPS.equals(source)) return GMAPS_PACKAGE;
        if (WAZE.equals(source)) return WAZE_PACKAGE;
        return null;
    }

    private static String[] supportedSources() {
        return new String[] {GMAPS, WAZE};
    }

    private static Context applicationContext(Context context) {
        return context == null ? null : context.getApplicationContext();
    }

    private static void shutdownThreads(RuntimeState state) {
        state.ingressThread.quitSafely();
        state.decodeThread.interrupt();
        state.decodeThread.quitSafely();
    }

    private static final class RuntimeState {
        final Context app;
        final long generation;
        final HandlerThread ingressThread;
        final HandlerThread decodeThread;
        final Handler ingress;
        final Handler decode;
        final BroadcastReceiver receiver;
        final Object decodeLock = new Object();
        final Map<String, Boolean> eligible = new ConcurrentHashMap<>();
        final Map<String, Runnable> deadlines = new HashMap<>();
        final Map<String, Runnable> replayRequests = new HashMap<>();
        final Map<String, String> connectionTokens = new HashMap<>();
        final Map<String, AssemblyClock> assemblyClocks = new HashMap<>();
        final Map<String, NavigatorRouteData.Header> lastHeaders = new HashMap<>();
        final Map<String, DecodeEntry> pendingDecodes = new HashMap<>();
        final Map<String, NavigatorRouteData> pendingArtifacts = new LinkedHashMap<>();
        volatile boolean active = true;
        boolean registered;
        boolean detailedEnabled;
        boolean decodePumpScheduled;
        String lastDecodeSource = WAZE;

        RuntimeState(Context app, long generation) {
            this.app = app;
            this.generation = generation;
            ingressThread = new HandlerThread("byd-route-ingress-" + generation,
                    Process.THREAD_PRIORITY_BACKGROUND);
            decodeThread = new HandlerThread("byd-route-decode-" + generation,
                    Process.THREAD_PRIORITY_BACKGROUND);
            ingressThread.start();
            decodeThread.start();
            ingress = new Handler(ingressThread.getLooper());
            decode = new Handler(decodeThread.getLooper());
            receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    handleBroadcast(RuntimeState.this, intent);
                }
            };
        }
    }

    private static final class AssemblyClock {
        final NavigatorRouteData.Header header;
        final long startedAtMs;

        AssemblyClock(NavigatorRouteData.Header header, long startedAtMs) {
            this.header = header;
            this.startedAtMs = startedAtMs;
        }
    }

    private static final class DecodeEntry {
        final NavigatorRouteStore.DecodeWork work;
        final long assemblyStartedMs;

        DecodeEntry(NavigatorRouteStore.DecodeWork work, long assemblyStartedMs) {
            this.work = work;
            this.assemblyStartedMs = assemblyStartedMs;
        }
    }

    private static final class RouteCompletion {
        final NavigatorRouteStore.DecodeWork work;
        final NavigatorRouteData route;
        final long assemblyMs;
        final long decodeMs;

        RouteCompletion(NavigatorRouteStore.DecodeWork work, NavigatorRouteData route,
                long assemblyMs, long decodeMs) {
            this.work = work;
            this.route = route;
            this.assemblyMs = assemblyMs;
            this.decodeMs = decodeMs;
        }
    }
}
