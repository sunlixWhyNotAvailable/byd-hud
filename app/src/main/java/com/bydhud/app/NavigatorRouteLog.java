package com.bydhud.app;

import android.content.Context;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;

final class NavigatorRouteLog {
    private static final int MAX_ERROR_KEYS = 32;
    private static final long ERROR_WINDOW_MS = 60_000L;
    private static final Object ERROR_LOCK = new Object();
    private static final LinkedHashMap<String, ErrorWindow> ERROR_WINDOWS =
            new LinkedHashMap<>();

    private NavigatorRouteLog() {
    }

    static void accepted(Context context, NavigatorRouteData route,
            long bytes, long sourceAgeMs, long assemblyMs, long decodeWaitMs, long decodeMs) {
        if (context == null || route == null) return;
        NavigatorRouteData.Header header = route.header();
        String source = NavigatorRouteArtifact.safeSource(header.navigator);
        if (source == null) {
            error(context, "unknown", header.producerEpoch, header.routeRevision,
                    "unsupported_source");
            return;
        }
        event(context, "capture_complete source=" + source
                + " epoch=" + header.producerEpoch
                + " revision=" + header.routeRevision
                + " points=" + route.pointCount()
                + " maneuvers=" + route.maneuverCount()
                + " maneuver_coordinates_available="
                + route.availableManeuverCoordinateCount()
                + " maneuver_coordinates_total=" + route.maneuverCount()
                + " geometry_available=" + route.geometryAvailable()
                + " maneuvers_available=" + route.maneuversAvailable()
                + " bytes=" + Math.max(0L, bytes)
                + " source_age_ms=" + Math.max(0L, sourceAgeMs)
                + " assembly_ms=" + Math.max(0L, assemblyMs)
                + " decode_wait_ms=" + Math.max(0L, decodeWaitMs)
                + " decode_ms=" + Math.max(0L, decodeMs));
    }

    static void saveDetailedCurrent(Context context, String targetDay,
            NavigatorRouteData route) {
        if (context == null || route == null
                || !HudPrefs.isDetailedDebugArtifactsEnabled(context)) return;
        saveArtifact(context, targetDay, route);
    }

    static void step(Context context, NavigatorRouteStore.CurrentStep step) {
        if (context == null || step == null) return;
        NavigatorRouteData.Header header = step.header;
        String source = NavigatorRouteArtifact.safeSource(header.navigator);
        if (source == null) {
            error(context, "unknown", header.producerEpoch, header.routeRevision,
                    "unsupported_source");
            return;
        }
        try {
            boolean detailed = HudPrefs.isDetailedDebugArtifactsEnabled(context);
            JSONObject record = new JSONObject()
                    .put("event", "step")
                    .put("source", source)
                    .put("epoch", header.producerEpoch)
                    .put("revision", header.routeRevision)
                    .put("serial", step.stepSerial)
                    .put("elapsed_ms", step.stepElapsedMs)
                    .put("available", nullable(step.available))
                    .put("on_route", nullable(step.onRoute))
                    .put("id", nullable(detailed ? step.id : shortText(step.id, 64)))
                    .put("ordinal", step.ordinal)
                    .put("vertex_index", step.vertexIndex)
                    .put("type", nullable(detailed ? step.type : shortText(step.type, 48)))
                    .put("turn_side", nullable(detailed ? step.turnSide : shortText(step.turnSide, 48)))
                    .put("current_segment_index", step.currentSegmentIndex)
                    .put("next_segment_index", step.nextSegmentIndex)
                    .put("current_instruction", nullable(step.currentInstruction))
                    .put("next_instruction", nullable(step.nextInstruction))
                    .put("association", nullable(detailed ? step.association : shortText(step.association, 48)));
            if (detailed) {
                record.put("latitude", finiteOrNull(step.latitude))
                        .put("longitude", finiteOrNull(step.longitude));
            }
            AppEventLogger.event(context, "navigator_route_step " + record);
        } catch (JSONException error) {
            error(context, source, header.producerEpoch, header.routeRevision, "step_record");
        }
    }

    static void event(Context context, String details) {
        Context app = applicationContext(context);
        if (app == null || details == null) return;
        String line = compactEvent(details);
        if (line == null) return;
        line = rateLimit(line);
        if (line == null) return;
        AppEventLogger.event(app, "navigator_route " + line);
    }

    static void error(Context context, String source, long epoch, long revision, String reason) {
        String safeSource = NavigatorRouteArtifact.safeSource(source);
        event(context, "error source=" + (safeSource == null ? "unknown" : safeSource)
                + " epoch=" + epoch + " revision=" + revision
                + " reason=" + safeLabel(reason, "unknown"));
    }

    static void artifactError(Context context, String source, long epoch, long revision) {
        error(context, source, epoch, revision, "artifact_write");
    }

    static void artifactRejected(Context context, NavigatorRouteData route,
            String reason, long lossCount) {
        if (context == null || route == null) return;
        NavigatorRouteData.Header header = route.header();
        String source = NavigatorRouteArtifact.safeSource(header.navigator);
        event(context, "artifact_rejected source=" + (source == null ? "unknown" : source)
                + " epoch=" + header.producerEpoch
                + " revision=" + header.routeRevision
                + " reason=" + safeLabel(reason, "queue_rejected")
                + " loss_count=" + Math.max(1L, lossCount));
    }

    static void artifactWrite(Context context, NavigatorRouteData route,
            long waitMs, long writeMs, boolean written) {
        if (context == null || route == null) return;
        NavigatorRouteData.Header header = route.header();
        String source = NavigatorRouteArtifact.safeSource(header.navigator);
        event(context, "artifact_write source=" + (source == null ? "unknown" : source)
                + " epoch=" + header.producerEpoch
                + " revision=" + header.routeRevision
                + " artifact_wait_ms=" + Math.max(0L, waitMs)
                + " artifact_write_ms=" + Math.max(0L, writeMs)
                + " outcome=" + (written ? "stored" : "failed"));
    }

    private static void saveArtifact(Context context, String targetDay,
            NavigatorRouteData route) {
        NavigatorRouteData.Header header = route.header();
        String source = NavigatorRouteArtifact.safeSource(header.navigator);
        if (source == null) {
            error(context, "unknown", header.producerEpoch, header.routeRevision,
                    "unsupported_source");
            return;
        }
        WazeCaptureDebugWriter.get().routeArtifact(context, targetDay, route);
    }

    private static String compactEvent(String details) {
        String[] tokens = details.trim().replace('\r', ' ').replace('\n', ' ')
                .split("\\s+");
        if (tokens.length == 0) return null;
        String action = safeLabel(tokens[0], "");
        if (action.isEmpty()) return null;
        StringBuilder line = new StringBuilder(action);
        for (int i = 1; i < tokens.length; i++) {
            int separator = tokens[i].indexOf('=');
            if (separator <= 0 || separator == tokens[i].length() - 1) continue;
            String key = tokens[i].substring(0, separator);
            String value = tokens[i].substring(separator + 1);
            if (isPrivateField(key)) continue;
            String safeValue = safeEventValue(key, value);
            if (safeValue == null) continue;
            if (line.length() + key.length() + safeValue.length() + 2 > 384) break;
            line.append(' ').append(key).append('=').append(safeValue);
        }
        return line.toString();
    }

    private static String safeEventValue(String key, String value) {
        if ("source".equals(key) || "navigator".equals(key)) {
            String source = NavigatorRouteArtifact.safeSource(value);
            return source == null ? "unknown" : source;
        }
        if ("reason".equals(key) || "outcome".equals(key)) return safeLabel(value, "other");
        if (value.matches("-?[0-9]{1,19}") || "true".equals(value) || "false".equals(value)) {
            return value;
        }
        return safeLabel(value, null);
    }

    private static String safeLabel(String value, String fallback) {
        if (value != null && value.matches("[A-Za-z0-9_.:-]{1,48}")) return value;
        return fallback;
    }

    private static boolean isPrivateField(String key) {
        return "lat".equalsIgnoreCase(key) || "latitude".equalsIgnoreCase(key)
                || "lon".equalsIgnoreCase(key) || "longitude".equalsIgnoreCase(key)
                || "coords".equalsIgnoreCase(key) || "coordinates".equalsIgnoreCase(key)
                || "polyline".equalsIgnoreCase(key) || "payload".equalsIgnoreCase(key)
                || "routeid".equalsIgnoreCase(key) || "route_id".equalsIgnoreCase(key);
    }

    private static String rateLimit(String line) {
        String category = line.substring(0, line.indexOf(' ') < 0
                ? line.length() : line.indexOf(' '));
        if (!isErrorCategory(category)) return line;
        String source = field(line, "source");
        String epoch = field(line, "epoch");
        String revision = field(line, "revision");
        String reason = field(line, "reason");
        String key = category + ":" + (source == null ? "unknown" : source) + ":"
                + (epoch == null ? "*" : epoch) + ":"
                + (revision == null ? "*" : revision) + ":"
                + (reason == null ? "other" : reason);
        long now = SystemClock.elapsedRealtime();
        synchronized (ERROR_LOCK) {
            ErrorWindow window = ERROR_WINDOWS.get(key);
            if (window == null) {
                if (ERROR_WINDOWS.size() >= MAX_ERROR_KEYS) {
                    Iterator<String> keys = ERROR_WINDOWS.keySet().iterator();
                    if (keys.hasNext()) {
                        keys.next();
                        keys.remove();
                    }
                }
                ERROR_WINDOWS.put(key, new ErrorWindow(now));
                return line;
            }
            if (now - window.lastLoggedAt < ERROR_WINDOW_MS) {
                window.suppressed++;
                return null;
            }
            int suppressed = window.suppressed;
            window.suppressed = 0;
            window.lastLoggedAt = now;
            return suppressed == 0 ? line : line + " suppressed=" + suppressed;
        }
    }

    private static boolean isErrorCategory(String category) {
        return "replay_timeout".equals(category)
                || "retry_exhausted".equals(category)
                || "artifact_rejected".equals(category)
                || "rejected".equals(category)
                || "decode_failed".equals(category)
                || "unavailable".equals(category)
                || "error".equals(category);
    }

    private static String field(String line, String key) {
        String marker = key + "=";
        for (String part : line.split(" ")) {
            if (part.startsWith(marker)) return part.substring(marker.length());
        }
        return null;
    }

    private static final class ErrorWindow {
        long lastLoggedAt;
        int suppressed;

        ErrorWindow(long lastLoggedAt) {
            this.lastLoggedAt = lastLoggedAt;
        }
    }

    private static Object nullable(Object value) {
        return value == null ? JSONObject.NULL : value;
    }

    private static Object finiteOrNull(Double value) {
        return value == null || value.isNaN() || value.isInfinite()
                ? JSONObject.NULL : value;
    }

    private static String shortText(String value, int maximumLength) {
        return value == null || value.length() <= maximumLength
                ? value : value.substring(0, maximumLength);
    }

    private static Context applicationContext(Context context) {
        return context == null ? null : context.getApplicationContext();
    }
}
