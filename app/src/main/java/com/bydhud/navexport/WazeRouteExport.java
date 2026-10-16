package com.bydhud.navexport;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.lang.reflect.Proxy;
import java.util.List;

/** Waze 5.25 only. Hooks run on Waze's main callbacks; native queries use its own scheduler. */
public final class WazeRouteExport {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Context context;
    private static RouteExport output;
    private static boolean active;
    private static Object manager;
    private static Object geometry;
    private static Object items;
    private static String routeId = "";
    private static long generation;
    private static boolean queryPending;
    private static long querySerial;
    private static Runnable queryTimeout;
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
        items = null;
        generation++;
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
        failedStages.clear();
        geometry = items = null;
        routeId = "";
        currentSegment = nextSegment = currentInstruction = nextInstruction = -1;
        if (output != null) output.clear(reason);
    }

    private static void publish() throws Exception {
        if (!active || geometry == null || context == null) return;
        if (output == null) output = new RouteExport(context, "waze-5.25.0.1");
        final Object line = geometry;
        final Object instructions = items;
        output.route(routeId, () -> read(line, instructions));
        step();
    }

    private static void requestItems() throws Exception {
        if (!active || geometry == null || queryPending || manager == null) return;
        final long token = generation;
        final long request = ++querySerial;
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
        try {
            // The public wrapper schedules getNavigationItemsNTV on Waze's native task thread.
            manager.getClass().getMethod("getNavigationItems", callbackType).invoke(manager, callback);
            queryTimeout = () -> expireItems(token, request);
            MAIN.postDelayed(queryTimeout, 15_000L);
        } catch (Exception e) {
            queryPending = false;
            throw e;
        }
    }

    private static synchronized void acceptItems(long token, long request, Object result) {
        if (!queryPending || request != querySerial) return;
        queryPending = false;
        if (queryTimeout != null) MAIN.removeCallbacks(queryTimeout);
        try {
            if (token != generation || !active) {
                Log.i("BYD_ROUTE_EXPORT", "waze-5.25.0.1|stale_items_discarded");
                requestItems();
                return;
            }
            if (result == null) {
                Log.w("BYD_ROUTE_EXPORT", "waze-5.25.0.1|maneuvers_unavailable");
                return;
            }
            items = result;
            publish();
        } catch (Exception e) { failed("items", e); }
    }

    private static synchronized void expireItems(long token, long request) {
        if (!queryPending || request != querySerial) return;
        queryPending = false;
        Log.w("BYD_ROUTE_EXPORT", "waze-5.25.0.1|maneuvers_unavailable|reason=query_timeout");
        try {
            if (generation != token) requestItems();
        } catch (Exception e) { failed("items", e); }
    }

    private static RouteData read(Object geometry, Object items) throws Exception {
        List<?> positions = (List<?>) RouteExport.call(geometry, "getPositionList");
        double[] points = new double[Math.multiplyExact(positions.size(), 2)];
        for (int i = 0; i < positions.size(); i++) {
            Object point = positions.get(i);
            points[i * 2] = number(point, "getLatitude") / 1_000_000.0;
            points[i * 2 + 1] = number(point, "getLongitude") / 1_000_000.0;
        }
        List<?> list = items == null ? java.util.Collections.emptyList()
                : (List<?>) RouteExport.call(items, "getNavigationItemList");
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
        return new RouteData(points, maneuvers, items != null);
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
