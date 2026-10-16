package com.bydhud.navexport;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Objects;

/** Waze 5.25 only. Hooks run on Waze's main callbacks; native queries use its own scheduler. */
public final class WazeRouteExport {
    public static final int PAYLOAD_REVISION = 2;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Context context;
    private static RouteExport output;
    private static boolean active;
    private static Object manager;
    private static Object geometry;
    private static RouteData.Maneuver[] maneuvers = new RouteData.Maneuver[0];
    private static boolean maneuversAvailable;
    private static boolean routePublished;
    private static String routeId = "";
    private static long generation;
    private static boolean queryPending;
    private static long querySerial;
    private static Runnable queryTimeout;
    private static Runnable retryTask;
    private static final WazeManeuverRecovery recovery = new WazeManeuverRecovery();
    private static final java.util.Set<String> failedStages = new java.util.HashSet<>();
    private static int currentSegment = -1;
    private static int nextSegment = -1;
    private static int currentInstruction = -1;
    private static int nextInstruction = -1;

    private WazeRouteExport() { }

    public static synchronized void init(Context value) {
        context = value.getApplicationContext();
    }

    public static synchronized void route(Object owner, Object route) {
        try {
            manager = owner;
            if (route == null) {
                reset("no_source_route");
                return;
            }
            String id = (String) RouteExport.call(route, "getRouteUUID");
            Object line = RouteExport.call(route, "getGeometry");
            if (id.equals(routeId) && line.equals(geometry)) return;
            routeId = id;
            replaceGeometry(line);
        } catch (Exception e) {
            reset("route_unavailable");
            failed("route", e);
        }
    }

    public static synchronized void geometry(Object owner, Object line) {
        try {
            manager = owner;
            if (line == null || !line.equals(geometry)) replaceGeometry(line);
        } catch (Exception e) {
            reset("geometry_unavailable");
            failed("geometry", e);
        }
    }

    private static void replaceGeometry(Object line) throws Exception {
        if (line == null) {
            reset("geometry_unavailable");
            return;
        }
        geometry = line;
        maneuvers = new RouteData.Maneuver[0];
        maneuversAvailable = false;
        routePublished = false;
        generation++;
        resetRecovery();
        failedStages.clear();
        currentSegment = nextSegment = currentInstruction = nextInstruction = -1;
        publish();
        requestItems();
    }

    public static synchronized void navigating(Object owner, boolean value, int reason) {
        try {
            manager = owner;
            if (active == value && (value || geometry == null)) return;
            active = value;
            if (!value) reset("navigation_stopped_" + reason);
            else {
                publish();
                requestItems();
            }
        } catch (Exception e) { failed("state", e); }
    }

    public static synchronized void currentStreet(Object update) {
        try {
            currentSegment = ((Number) RouteExport.call(update, "getSegmentIdx")).intValue();
            step();
        } catch (Exception e) {
            currentSegment = -1;
            step();
            failed("current_street", e);
        }
    }

    public static synchronized void nextStreet(Object update) {
        try {
            nextSegment = ((Number) RouteExport.call(update, "getSegmentIdx")).intValue();
            step();
        } catch (Exception e) {
            nextSegment = -1;
            step();
            failed("next_street", e);
        }
    }

    public static synchronized void currentInstruction(int value) {
        currentInstruction = value;
        step();
    }

    public static synchronized void nextInstruction(int value) {
        nextInstruction = value;
        step();
    }

    private static void step() {
        if (!active || output == null || geometry == null) return;
        Bundle data = new Bundle();
        data.putInt("currentSegmentIndex", currentSegment);
        data.putInt("nextSegmentIndex", nextSegment);
        data.putInt("currentInstruction", currentInstruction);
        data.putInt("nextInstruction", nextInstruction);
        // Preserve raw indices until their association with NavigationItem is verified on-device.
        data.putString("association", "source_indices");
        output.step(currentSegment + ":" + nextSegment + ":" + currentInstruction
                + ":" + nextInstruction, data);
    }

    private static void reset(String reason) {
        generation++;
        resetRecovery();
        failedStages.clear();
        geometry = null;
        maneuvers = new RouteData.Maneuver[0];
        maneuversAvailable = false;
        routePublished = false;
        routeId = "";
        currentSegment = nextSegment = currentInstruction = nextInstruction = -1;
        if (output != null) output.clear(reason);
    }

    private static void publish() throws Exception {
        if (!active || geometry == null || context == null) return;
        if (output == null) output = new RouteExport(context, "waze-5.25.0.1");
        if (!routePublished) {
            final Object line = geometry;
            final RouteData.Maneuver[] instructions = maneuvers;
            final boolean available = maneuversAvailable;
            output.route(routeId, () -> read(line, instructions, available));
            routePublished = true;
        }
        step();
    }

    private static void requestItems() {
        if (!active || geometry == null || queryPending || retryTask != null || manager == null) return;
        int attempt = recovery.beginAttempt();
        if (attempt == 0) return;
        final long token = generation;
        final long request = ++querySerial;
        try {
            ClassLoader loader = manager.getClass().getClassLoader();
            Class<?> callbackType = Class.forName("h.g.a.l", false, loader);
            final Object unit = Class.forName("h.ab", false, loader).getField("a").get(null);
            Object callback = Proxy.newProxyInstance(loader, new Class<?>[] {callbackType},
                    (proxy, method, args) -> {
                        if ("invoke".equals(method.getName())) {
                            Object result = args == null || args.length == 0 ? null : args[0];
                            MAIN.post(() -> acceptItems(token, request, result));
                            return unit;
                        }
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        if ("equals".equals(method.getName())) return proxy == args[0];
                        return "BYD route items callback";
                    });
            queryPending = true;
            // The public wrapper schedules getNavigationItemsNTV on Waze's native task thread.
            manager.getClass().getMethod("getNavigationItems", callbackType).invoke(manager, callback);
            queryTimeout = () -> expireItems(token, request);
            MAIN.postDelayed(queryTimeout, 15_000L);
        } catch (Exception e) {
            queryPending = false;
            failed("items", e);
            finishItemsAttempt(token, true, "query_failure", maneuvers.length,
                    maneuvers.length - WazeManeuverRecovery.missingCoordinateCount(maneuvers));
        }
    }

    private static synchronized void acceptItems(long token, long request, Object result) {
        if (!queryPending || request != querySerial) return;
        queryPending = false;
        if (queryTimeout != null) MAIN.removeCallbacks(queryTimeout);
        queryTimeout = null;
        try {
            if (token != generation || !active) {
                Log.i("BYD_ROUTE_EXPORT", "waze-5.25.0.1|stale_items_discarded");
                requestItems();
                return;
            }
            if (result == null) {
                finishItemsAttempt(token, true, "empty_response", maneuvers.length,
                        maneuvers.length - WazeManeuverRecovery.missingCoordinateCount(maneuvers));
                return;
            }
            RouteData.Maneuver[] received = readManeuvers(result);
            int missing = WazeManeuverRecovery.missingCoordinateCount(received);
            int available = received.length - missing;
            RouteData.Maneuver[] accepted = WazeManeuverRecovery.preserveUsefulResponse(
                    maneuvers, received);
            boolean changed = !maneuversAvailable || !sameManeuvers(maneuvers, accepted);
            maneuvers = accepted;
            maneuversAvailable = true;
            if (changed) {
                routePublished = false;
                publish();
            }
            finishItemsAttempt(token, WazeManeuverRecovery.needsRetry(received),
                    received.length == 0 ? "empty_list" : "response",
                    received.length, available);
        } catch (Exception e) {
            failed("items", e);
            finishItemsAttempt(token, true, "response_failure", maneuvers.length,
                    maneuvers.length - WazeManeuverRecovery.missingCoordinateCount(maneuvers));
        }
    }

    private static synchronized void expireItems(long token, long request) {
        if (!queryPending || request != querySerial) return;
        queryPending = false;
        queryTimeout = null;
        try {
            if (generation != token || !active) {
                requestItems();
                return;
            }
            finishItemsAttempt(token, true, "query_timeout", maneuvers.length,
                    maneuvers.length - WazeManeuverRecovery.missingCoordinateCount(maneuvers));
        } catch (Exception e) { failed("items", e); }
    }

    private static void finishItemsAttempt(long token, boolean retryNeeded, String reason,
            int itemCount, int availableCoordinateCount) {
        long now = SystemClock.uptimeMillis();
        recovery.finishAttempt(now, retryNeeded);
        int attempt = recovery.attempts();
        int missingCoordinateCount = Math.max(0, itemCount - availableCoordinateCount);
        Log.i("BYD_ROUTE_EXPORT", "waze-5.25.0.1|items_" + reason
                + "|attempt=" + attempt + "|retryCount=" + Math.max(0, attempt - 1)
                + "|items=" + itemCount
                + "|coordinateAvailable=" + availableCoordinateCount
                + "|coordinateMissing=" + missingCoordinateCount);
        cancelRetryTask();
        long delay = recovery.retryDelayMs(now);
        if (delay < 0 || token != generation || !active) return;
        retryTask = () -> {
            synchronized (WazeRouteExport.class) {
                if (token != generation || !active) return;
                retryTask = null;
                requestItems();
            }
        };
        MAIN.postDelayed(retryTask, delay);
    }

    private static void resetRecovery() {
        cancelRetryTask();
        recovery.reset();
    }

    private static void cancelRetryTask() {
        if (retryTask != null) MAIN.removeCallbacks(retryTask);
        retryTask = null;
    }

    private static RouteData read(Object geometry, RouteData.Maneuver[] maneuvers,
            boolean maneuversAvailable) throws Exception {
        List<?> positions = (List<?>) RouteExport.call(geometry, "getPositionList");
        double[] points = new double[Math.multiplyExact(positions.size(), 2)];
        for (int i = 0; i < positions.size(); i++) {
            Object point = positions.get(i);
            points[i * 2] = number(point, "getLatitude") / 1_000_000.0;
            points[i * 2 + 1] = number(point, "getLongitude") / 1_000_000.0;
        }
        return new RouteData(points, maneuvers, maneuversAvailable);
    }

    private static RouteData.Maneuver[] readManeuvers(Object items) throws Exception {
        List<?> list = (List<?>) RouteExport.call(items, "getNavigationItemList");
        RouteData.Maneuver[] maneuvers = new RouteData.Maneuver[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            RouteData.Maneuver m = new RouteData.Maneuver();
            m.ordinal = i;
            m.latitude = number(item, "getLat") / 1_000_000.0;
            m.longitude = number(item, "getLon") / 1_000_000.0;
            m.instructionSegmentIndex = (int) number(item, "getInstructionSegmentIdx");
            m.navigationSegmentIndex = (int) number(item, "getNavSegmentIdx");
            m.type = String.valueOf((int) number(item, "getInstructionValue"));
            m.id = m.instructionSegmentIndex + ":" + m.navigationSegmentIndex;
            m.sourceRotation = (int) number(item, "getRotation");
            maneuvers[i] = m;
        }
        return maneuvers;
    }

    private static boolean sameManeuvers(RouteData.Maneuver[] left,
            RouteData.Maneuver[] right) {
        if (left.length != right.length) return false;
        for (int i = 0; i < left.length; i++) {
            RouteData.Maneuver a = left[i];
            RouteData.Maneuver b = right[i];
            if (a.ordinal != b.ordinal || Double.compare(a.latitude, b.latitude) != 0
                    || Double.compare(a.longitude, b.longitude) != 0
                    || a.vertexIndex != b.vertexIndex
                    || a.instructionSegmentIndex != b.instructionSegmentIndex
                    || a.navigationSegmentIndex != b.navigationSegmentIndex
                    || !Objects.equals(a.id, b.id) || !Objects.equals(a.type, b.type)
                    || !Objects.equals(a.turnSide, b.turnSide)
                    || Double.compare(a.incomingBearing, b.incomingBearing) != 0
                    || Double.compare(a.outgoingBearing, b.outgoingBearing) != 0
                    || a.sourceRotation != b.sourceRotation
                    || a.syntheticPolyline != b.syntheticPolyline) return false;
        }
        return true;
    }

    private static double number(Object value, String getter) throws Exception {
        return ((Number) RouteExport.call(value, getter)).doubleValue();
    }

    private static void failed(String stage, Exception e) {
        if (!failedStages.add(stage)) return;
        Log.w("BYD_ROUTE_EXPORT", "waze-5.25.0.1|source_failed|stage=" + stage
                + "|type=" + e.getClass().getSimpleName());
    }
}
