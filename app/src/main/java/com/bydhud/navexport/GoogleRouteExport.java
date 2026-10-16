package com.bydhud.navexport;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import java.lang.reflect.Method;

/** Maps 26.30 engine snapshot, before the stock TBT converter drops geometry. */
public final class GoogleRouteExport {
    private static RouteExport output;
    private static Object lastRoute;
    private static boolean errorLogged;

    private GoogleRouteExport() { }

    public static synchronized void init(Context context) {
        try {
            if (output == null) output = new RouteExport(context, "gmaps-26.30");
        } catch (Exception e) { failed(e); }
    }

    public static synchronized void clear() {
        lastRoute = null;
        errorLogged = false;
        if (output != null) output.clear("navigation_reset");
    }

    public static synchronized void capture(Object aggregate) {
        if (output == null) return;
        try {
            Object state = RouteExport.call(aggregate, "d");
            Object route = RouteExport.field(state, "b");
            if (route == null) {
                lastRoute = null;
                output.clear("no_source_route");
                return;
            }
            if (route != lastRoute) {
                final Object polyline = RouteExport.field(route, "l");
                final Object[] steps = ((Object[]) RouteExport.field(route, "k")).clone();
                String id = String.valueOf(RouteExport.field(route, "ab"));
                output.route(id, () -> read(polyline, steps));
                lastRoute = route;
            }
            Object step = RouteExport.field(state, "c");
            Bundle data = new Bundle();
            data.putBoolean("available", step != null);
            if (step != null) {
                data.putString("id", String.valueOf(RouteExport.field(step, "S")));
                data.putInt("ordinal", number(step, "i").intValue());
                data.putInt("vertexIndex", number(step, "k").intValue());
                data.putString("type", String.valueOf(RouteExport.field(step, "d")));
                data.putString("turnSide", String.valueOf(RouteExport.field(step, "e")));
                Object point = RouteExport.field(step, "c");
                data.putDouble("latitude", ((Number) RouteExport.call(point, "b")).doubleValue());
                data.putDouble("longitude", ((Number) RouteExport.call(point, "d")).doubleValue());
            }
            data.putBoolean("onRoute", (Boolean) RouteExport.field(state, "p"));
            output.step(data.getString("id", "") + ":" + data.getInt("ordinal", -1)
                    + ":" + data.getBoolean("onRoute") + ":" + (step != null), data);
            errorLogged = false;
        } catch (Exception e) {
            lastRoute = null;
            output.clear("source_unavailable");
            failed(e);
        }
    }

    private static RouteData read(Object polyline, Object[] steps) throws Exception {
        int count = ((Number) RouteExport.call(polyline, "g")).intValue();
        double[] points = new double[Math.multiplyExact(count, 2)];
        Method vertex = polyline.getClass().getMethod("n", int.class);
        vertex.setAccessible(true);
        for (int i = 0; i < count; i++) {
            Object point = vertex.invoke(polyline, i);
            points[i * 2] = ((Number) RouteExport.call(point, "b")).doubleValue();
            points[i * 2 + 1] = ((Number) RouteExport.call(point, "d")).doubleValue();
        }
        RouteData.Maneuver[] maneuvers = new RouteData.Maneuver[steps.length];
        for (int i = 0; i < steps.length; i++) {
            Object step = steps[i];
            RouteData.Maneuver m = new RouteData.Maneuver();
            m.id = String.valueOf(RouteExport.field(step, "S"));
            m.ordinal = number(step, "i").intValue();
            Object point = RouteExport.field(step, "c");
            m.latitude = ((Number) RouteExport.call(point, "b")).doubleValue();
            m.longitude = ((Number) RouteExport.call(point, "d")).doubleValue();
            m.vertexIndex = number(step, "k").intValue();
            m.type = String.valueOf(RouteExport.field(step, "d"));
            m.turnSide = String.valueOf(RouteExport.field(step, "e"));
            m.incomingBearing = number(step, "o").doubleValue();
            m.outgoingBearing = number(step, "p").doubleValue();
            m.syntheticPolyline = (Boolean) RouteExport.field(step, "P");
            maneuvers[i] = m;
        }
        return new RouteData(points, maneuvers, true);
    }

    private static Number number(Object value, String field) throws Exception {
        return (Number) RouteExport.field(value, field);
    }

    private static void failed(Exception e) {
        if (!errorLogged) Log.w("BYD_ROUTE_EXPORT", "gmaps-26.30|source_failed|type="
                + e.getClass().getSimpleName());
        errorLogged = true;
    }
}
