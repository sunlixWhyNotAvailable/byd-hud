package com.bydhud.app;

import org.junit.Test;
import static org.junit.Assert.*;

public final class NavigatorManeuverAnchorTest {
    @Test public void followsRouteAroundCornerAndTreatsMockLikeOtherPositions() {
        NavigatorRouteData route = route("waze-5.25.0.1", new double[]{0, 0, 0, .001, .001, .001});
        NavigatorManeuverAnchor resolver = new NavigatorManeuverAnchor(route);
        NavigatorRouteStore.CurrentStep step = step(route, null, null);
        DirectTbtFrame.SourceDistance distance = new DirectTbtFrame.SourceDistance(50, 2_000);
        NavigatorManeuverAnchor.Result live = resolver.resolve(step, pose(0, .0008, false), distance, 2_000);
        NavigatorManeuverAnchor.Result mock = resolver.resolve(step, pose(0, .0008, true), distance, 2_000);
        assertEquals(NavigatorManeuverAnchor.Origin.ROUTE_DERIVED, live.origin);
        assertEquals(.00024966, live.latitude, .000001);
        assertEquals(.001, live.longitude, .000001);
        assertEquals(live.latitude, mock.latitude);
        assertEquals(live.longitude, mock.longitude);
        assertEquals(0, route.availableManeuverCoordinateCount());
        assertEquals(0, route.maneuver(0).latitude, 0);
        assertEquals(0, route.maneuver(0).longitude, 0);
    }

    @Test public void nativeCurrentMapsCoordinateWinsWithoutVehicleOrDistance() {
        NavigatorRouteData route = route("gmaps-26.30", new double[]{0, 0, 0, .001});
        NavigatorManeuverAnchor.Result value = new NavigatorManeuverAnchor(route)
                .resolve(step(route, 50.1, 30.2), null, null, 2_000);
        assertEquals(NavigatorManeuverAnchor.Origin.NATIVE, value.origin);
        assertEquals(50.1, value.latitude, 0);
        assertEquals(30.2, value.longitude, 0);
        assertEquals(1, route.availableManeuverCoordinateCount());
    }

    @Test public void neverInventsAnchorForMissingStaleAmbiguousOrWrongRouteInput() {
        NavigatorRouteData route = route("waze-5.25.0.1", new double[]{0, 0, 0, .001});
        NavigatorManeuverAnchor resolver = new NavigatorManeuverAnchor(route);
        NavigatorRouteStore.CurrentStep step = step(route, null, null);
        DirectTbtFrame.SourceDistance distance = new DirectTbtFrame.SourceDistance(20, 2_000);
        assertMissing("distance_stale", resolver.resolve(step, pose(0, .0002, false), null, 2_000));
        assertMissing("position_stale", resolver.resolve(step, pose(0, .0002, false), distance, 12_001));
        assertMissing("distance_stale", resolver.resolve(step, pose(0, .0002, false),
                new DirectTbtFrame.SourceDistance(20, 500), 2_000));
        assertMissing("outside_route", resolver.resolve(step, pose(.01, .01, false), distance, 2_000));
        assertMissing("beyond_route_end", resolver.resolve(step, pose(0, .0009, false), distance, 2_000));
        assertMissing("step_unavailable", resolver.resolve(step(route("gmaps-26.30", new double[]{0, 0}), null, null),
                pose(0, 0, false), distance, 2_000));
        NavigatorRouteData loop = route("waze-5.25.0.1",
                new double[]{0, 0, 0, .002, .0001, .002, .0001, 0});
        assertMissing("ambiguous_progress", new NavigatorManeuverAnchor(loop)
                .resolve(step(loop, null, null), pose(0, .0005, false), distance, 2_000));
    }

    private static void assertMissing(String reason, NavigatorManeuverAnchor.Result result) {
        assertEquals(NavigatorManeuverAnchor.Origin.UNAVAILABLE, result.origin);
        assertEquals(reason, result.reason);
        assertNull(result.latitude);
        assertNull(result.longitude);
    }

    private static VehiclePosition pose(double lat, double lon, boolean mock) {
        return new VehiclePosition(lat, lon, 2_000, 5, null, mock);
    }

    private static NavigatorRouteData route(String source, double[] points) {
        return new NavigatorRouteData(new NavigatorRouteData.Header(source, 1, 1, "route", 1_000),
                true, true, points, new NavigatorRouteData.Maneuver[]{
                new NavigatorRouteData.Maneuver("0", 0, 0, 0, -1, 11, 12,
                        "turn", "", Double.NaN, Double.NaN, 0, false)});
    }

    private static NavigatorRouteStore.CurrentStep step(NavigatorRouteData route, Double lat, Double lon) {
        return new NavigatorRouteStore.CurrentStep(route.header(), 1, 1_000,
                lat == null ? null : true, true, "0", 0, -1, "turn", "", lat, lon,
                11, 12, 3, 4, "source_indices");
    }
}
