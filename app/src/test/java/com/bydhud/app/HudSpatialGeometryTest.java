package com.bydhud.app;

import org.junit.Test;

import static org.junit.Assert.*;

public final class HudSpatialGeometryTest {
    @Test public void buildsRealPoseAndRouteLineFromOneFreshFix() {
        NavigatorRouteData route = route("waze-5.25.0.1", new double[]{
                0, 0, 0, .001, .001, .001, .001, .002});
        VehiclePosition position = position(.00005, .0005, 2_000, 5, 90f, 10f, 5f, true);
        NavigatorRouteStore.CurrentStep step = step(route, null, null);
        HudSpatialGeometry geometry = new HudSpatialGeometry(route);

        HudSpatialGeometry.Result result = geometry.resolve(step, position, distance(150, 2_000), 2_000);

        assertEquals(HudSpatialGeometry.Mode.REAL, result.mode);
        assertEquals(NavigatorManeuverAnchor.Origin.ROUTE_DERIVED, result.origin);
        assertEquals(HudSpatialGeometry.HeadingOrigin.MEASURED, result.headingOrigin);
        assertEquals(.00005, result.latitude, 0);
        assertEquals(.0005, result.longitude, 0);
        assertEquals(90, result.headingDegrees, 0.001);
        assertTrue(result.usableAt(12_000));
        assertFalse(result.usableAt(12_001));
        String[] points = linePoints(result.line);
        assertEquals(4, points.length);
        String[] projectedVehicle = points[0].split(",");
        assertEquals(.0005, Double.parseDouble(projectedVehicle[0]), 1e-12);
        assertEquals(0, Double.parseDouble(projectedVehicle[1]), 0);
        assertEquals(0, Double.parseDouble(projectedVehicle[2]), 0);
        String[] interveningVertex = points[1].split(",");
        assertEquals(.001, Double.parseDouble(interveningVertex[0]), 1e-12);
        assertEquals(0, Double.parseDouble(interveningVertex[1]), 0);
        assertEquals(0, Double.parseDouble(interveningVertex[2]), 0);
        String[] anchor = points[2].split(",");
        assertEquals(.001, Double.parseDouble(anchor[0]), 1e-12);
        assertTrue(Double.parseDouble(anchor[1]) > .0008);
        assertTrue(Double.parseDouble(anchor[1]) < .0009);
        assertEquals("0", anchor[2]);
        String[] departureVertex = points[3].split(",");
        assertEquals(.001, Double.parseDouble(departureVertex[0]), 1e-12);
        assertEquals(.001, Double.parseDouble(departureVertex[1]), 1e-12);
        assertEquals(0, Double.parseDouble(departureVertex[2]), 0);

        HudSpatialGeometry.Result nonMock = new HudSpatialGeometry(route).resolve(
                step, position(.00005, .0005, 2_000, 5, 90f, 10f, 5f, false),
                distance(150, 2_000), 2_000);
        assertEquals(result.mode, nonMock.mode);
        assertEquals(result.line, nonMock.line);
        assertEquals(result.headingDegrees, nonMock.headingDegrees);
        assertTrue(result.identity != nonMock.identity);

        HudSpatialGeometry.Result refreshed = geometry.resolve(step,
                position(.00005, .0005, 2_100, 5, 90f, 10f, 5f, true), distance(150, 2_100), 2_100);
        assertEquals(result.identity, refreshed.identity);
        assertEquals(12_101, refreshed.validUntilElapsedMs);
        assertSame(refreshed, geometry.resolve(step,
                position(.00005, .0005, 2_100, 5, 90f, 10f, 5f, true), distance(150, 2_100), 2_100));
    }

    @Test public void acceptsMapsAnchorAndARealZeroCoordinateFix() {
        NavigatorRouteData route = route("gmaps-26.30", new double[]{
                0, 0, 0, .001, .001, .001});
        HudSpatialGeometry.Result result = new HudSpatialGeometry(route).resolve(
                step(route, 0d, .001d), position(0, 0, 2_000, 5, null, null, null, false),
                null, 2_000);

        assertEquals(HudSpatialGeometry.Mode.REAL, result.mode);
        assertEquals(NavigatorManeuverAnchor.Origin.NATIVE, result.origin);
        assertEquals(.001, result.anchorLongitude, 0);
        assertEquals(0, result.latitude, 0);
        assertEquals(0, result.longitude, 0);
        assertEquals(HudSpatialGeometry.HeadingOrigin.ROUTE_TANGENT, result.headingOrigin);
        assertEquals(3, linePoints(result.line).length);

        HudSpatialGeometry.Result withoutVehicle = new HudSpatialGeometry(route).resolve(
                step(route, 0d, .001d), null, null, 2_000);
        assertEquals(HudSpatialGeometry.Mode.SCHEMATIC, withoutVehicle.mode);
        assertEquals("position_stale", withoutVehicle.reason);
        assertNull(withoutVehicle.latitude);
        assertNull(withoutVehicle.line);
    }

    @Test public void rejectsPoorFixesAndContradictoryMeasuredBearing() {
        NavigatorRouteData route = route("waze-5.25.0.1", new double[]{0, 0, 0, .001});
        NavigatorRouteStore.CurrentStep step = step(route, null, null);
        HudSpatialGeometry geometry = new HudSpatialGeometry(route);

        HudSpatialGeometry.Result inaccurate = geometry.resolve(step,
                position(0, .0002, 2_000, 50.1f, 90f, 10f, 5f, false),
                distance(20, 2_000), 2_000);
        assertEquals(HudSpatialGeometry.Mode.SCHEMATIC, inaccurate.mode);
        assertEquals("position_accuracy_unavailable", inaccurate.reason);
        assertNull(inaccurate.longitude);
        assertNull(inaccurate.line);

        HudSpatialGeometry.Result conflict = new HudSpatialGeometry(route).resolve(step,
                position(0, .0002, 2_000, 5, 270f, 10f, 5f, false),
                distance(20, 2_000), 2_000);
        assertEquals(HudSpatialGeometry.Mode.SCHEMATIC, conflict.mode);
        assertEquals("bearing_conflicts_with_route", conflict.reason);
        assertNull(conflict.headingDegrees);

        HudSpatialGeometry.Result unqualified = new HudSpatialGeometry(route).resolve(step,
                position(0, .0002, 2_000, 5, 270f, 1f, 5f, false),
                distance(20, 2_000), 2_000);
        assertEquals(HudSpatialGeometry.Mode.REAL, unqualified.mode);
        assertEquals(HudSpatialGeometry.HeadingOrigin.ROUTE_TANGENT, unqualified.headingOrigin);
        assertEquals(90, unqualified.headingDegrees, 0.001);
    }

    @Test public void finalApproachEndsAtDestinationButRejectsOnePointLine() {
        NavigatorRouteData maps = route("gmaps-26.30", new double[]{0, 0, 0, .001, .001, .001});
        HudSpatialGeometry geometry = new HudSpatialGeometry(maps);
        HudSpatialGeometry.Result approach = geometry.resolve(step(maps, .001d, .001d),
                position(0, .0005, 2_000, 5, null, null, null, false), null, 2_000);
        assertEquals(HudSpatialGeometry.Mode.REAL, approach.mode);
        assertEquals(NavigatorManeuverAnchor.Origin.NATIVE, approach.origin);
        String[] approachPoints = linePoints(approach.line);
        assertEquals(3, approachPoints.length);
        assertEquals(.0005, Double.parseDouble(approachPoints[0].split(",")[0]), 1e-12);
        assertEquals(.001, Double.parseDouble(approachPoints[2].split(",")[0]), 1e-12);
        assertEquals(.001, Double.parseDouble(approachPoints[2].split(",")[1]), 1e-12);

        HudSpatialGeometry.Result outsideEndpoint = geometry.resolve(step(maps, .0011d, .001d),
                position(0, .0005, 2_000, 5, null, null, null, false), null, 2_000);
        assertEquals(HudSpatialGeometry.Mode.SCHEMATIC, outsideEndpoint.mode);
        assertEquals("route_line_unavailable", outsideEndpoint.reason);

        NavigatorRouteData route = route("waze-5.25.0.1", new double[]{0, 0, 0, .001});
        HudSpatialGeometry.Result result = new HudSpatialGeometry(route).resolve(step(route, null, null),
                position(0, .001, 2_000, 5, 90f, 10f, 5f, false), distance(0, 2_000), 2_000);

        assertEquals(HudSpatialGeometry.Mode.SCHEMATIC, result.mode);
        assertEquals("route_line_unavailable", result.reason);
        assertNull(result.latitude);
        assertNull(result.headingDegrees);
        assertNull(result.line);
    }

    @Test public void nearbyAmbiguousRoutePassesFailClosedWithoutAHeadingFix() {
        double metersPerDegreeAtEquator = 111_195.08;
        double east40m = 40.0 / metersPerDegreeAtEquator;
        double north10m = 10.0 / metersPerDegreeAtEquator;
        NavigatorRouteData route = route("waze-5.25.0.1", new double[]{
                0, 0, 0, east40m, north10m, east40m, north10m, 0});

        HudSpatialGeometry.Result result = new HudSpatialGeometry(route).resolve(
                step(route, null, null),
                position(north10m / 2, east40m / 2, 2_000, 5, null, null, null, false),
                distance(0, 2_000), 2_000);

        assertEquals(HudSpatialGeometry.Mode.SCHEMATIC, result.mode);
        assertEquals("ambiguous_progress", result.reason);
        assertNull(result.latitude);
        assertNull(result.headingDegrees);
        assertNull(result.line);

        double east20m = 20.0 / metersPerDegreeAtEquator;
        double north15m = 15.0 / metersPerDegreeAtEquator;
        double north30m = 30.0 / metersPerDegreeAtEquator;
        double north40m = 40.0 / metersPerDegreeAtEquator;
        NavigatorRouteData boundaryRoute = route("waze-5.25.0.1", new double[]{
                0, 0, 0, east20m, north30m, east20m, north30m, 0, north40m, 0});
        HudSpatialGeometry.Result boundary = new HudSpatialGeometry(boundaryRoute).resolve(
                step(boundaryRoute, null, null),
                position(north15m, east20m / 4, 2_000, 5, null, null, null, false),
                distance(0, 2_000), 2_000);

        assertEquals(HudSpatialGeometry.Mode.SCHEMATIC, boundary.mode);
        assertEquals("ambiguous_progress", boundary.reason);
        assertNull(boundary.latitude);
        assertNull(boundary.headingDegrees);
        assertNull(boundary.line);
    }

    @Test public void denseStraightRouteDoesNotLookLikeSeparateRoutePasses() {
        double metersPerDegreeAtEquator = 111_195.08;
        double[] coordinates = new double[81 * 2];
        for (int i = 0; i < 81; i++) coordinates[i * 2 + 1] = i / metersPerDegreeAtEquator;
        NavigatorRouteData route = route("waze-5.25.0.1", coordinates);

        HudSpatialGeometry.Result result = new HudSpatialGeometry(route).resolve(
                step(route, null, null),
                position(5.0 / metersPerDegreeAtEquator, 40.0 / metersPerDegreeAtEquator,
                        2_000, 5, null, null, null, false),
                distance(0, 2_000), 2_000);

        assertEquals(HudSpatialGeometry.Mode.REAL, result.mode);
        assertEquals(HudSpatialGeometry.HeadingOrigin.ROUTE_TANGENT, result.headingOrigin);
        assertEquals(90, result.headingDegrees, 0.001);
        assertEquals(2, linePoints(result.line).length);
    }

    @Test public void perpendicularSelfCrossingFailsClosedWithoutAHeadingFix() {
        double metersPerDegreeAtEquator = 111_195.08;
        double east15m = 15.0 / metersPerDegreeAtEquator;
        double north5m = 5.0 / metersPerDegreeAtEquator;
        double south15m = 15.0 / metersPerDegreeAtEquator;
        NavigatorRouteData route = route("waze-5.25.0.1", new double[]{
                0, -east15m, 0, east15m, north5m, east15m,
                north5m, 0, -south15m, 0});

        HudSpatialGeometry.Result result = new HudSpatialGeometry(route).resolve(
                step(route, null, null), position(0, 0, 2_000, 5, null, null, null, false),
                distance(0, 2_000), 2_000);

        assertEquals(HudSpatialGeometry.Mode.SCHEMATIC, result.mode);
        assertEquals("ambiguous_progress", result.reason);
        assertNull(result.latitude);
        assertNull(result.headingDegrees);
        assertNull(result.line);
    }

    @Test public void ordinaryRightAngleTurnRemainsRealNearTheCorner() {
        double metersPerDegreeAtEquator = 111_195.08;
        double east40m = 40.0 / metersPerDegreeAtEquator;
        double north40m = 40.0 / metersPerDegreeAtEquator;
        NavigatorRouteData route = route("waze-5.25.0.1", new double[]{
                0, 0, 0, east40m, north40m, east40m});

        HudSpatialGeometry.Result result = new HudSpatialGeometry(route).resolve(
                step(route, null, null),
                position(north40m / 2, east40m / 2, 2_000, 5, null, null, null, false),
                distance(0, 2_000), 2_000);

        assertEquals(HudSpatialGeometry.Mode.REAL, result.mode);
        assertEquals(HudSpatialGeometry.HeadingOrigin.ROUTE_TANGENT, result.headingOrigin);
        assertEquals(90, result.headingDegrees, 0.001);
    }

    @Test public void expiredFixClearsPreviouslyRealPoseAndLine() {
        NavigatorRouteData route = route("waze-5.25.0.1", new double[]{0, 0, 0, .001, 0, .002});
        NavigatorRouteStore.CurrentStep step = step(route, null, null);
        HudSpatialGeometry geometry = new HudSpatialGeometry(route);
        VehiclePosition position = position(0, .0005, 2_000, 5, null, null, null, false);

        HudSpatialGeometry.Result real = geometry.resolve(step, position, distance(20, 2_000), 2_000);
        HudSpatialGeometry.Result stale = geometry.resolve(step, position, distance(20, 2_000), 12_001);

        assertEquals(HudSpatialGeometry.Mode.REAL, real.mode);
        assertEquals(HudSpatialGeometry.Mode.SCHEMATIC, stale.mode);
        assertEquals("position_stale", stale.reason);
        assertTrue(real.identity != stale.identity);
        assertNull(stale.latitude);
        assertNull(stale.headingDegrees);
        assertNull(stale.line);
    }

    @Test public void reroutedSnapshotRejectsOldResolverAndResolvesOnNewRoute() {
        NavigatorRouteData oldRoute = route("waze-5.25.0.1", new double[]{0, 0, 0, .001, 0, .002});
        NavigatorRouteData newRoute = route("waze-5.25.0.1", 2,
                new double[]{0, 0, 0, .001, .001, .001, .002, .001});
        VehiclePosition position = position(0, .0005, 2_000, 5, null, null, null, false);
        DirectTbtFrame.SourceDistance distance = distance(20, 2_000);

        HudSpatialGeometry.Result oldAgainstNewStep = new HudSpatialGeometry(oldRoute).resolve(
                step(newRoute, null, null), position, distance, 2_000);
        HudSpatialGeometry.Result newSnapshot = new HudSpatialGeometry(newRoute).resolve(
                step(newRoute, null, null), position, distance, 2_000);

        assertEquals(HudSpatialGeometry.Mode.SCHEMATIC, oldAgainstNewStep.mode);
        assertEquals("step_unavailable", oldAgainstNewStep.reason);
        assertNull(oldAgainstNewStep.line);
        assertEquals(HudSpatialGeometry.Mode.REAL, newSnapshot.mode);
        assertEquals(90, newSnapshot.headingDegrees, 0.001);
    }

    @Test public void oversizedF30FallsBackWithoutTruncation() {
        int count = 5_200;
        double[] coordinates = new double[count * 2];
        for (int i = 0; i < count; i++) coordinates[i * 2 + 1] = i * .00001;
        NavigatorRouteData route = route("waze-5.25.0.1", coordinates);
        HudSpatialGeometry.Result result = new HudSpatialGeometry(route).resolve(
                step(route, null, null), position(0, 0, 2_000, 5, null, null, null, false),
                distance(5_000, 2_000), 2_000);

        assertEquals(HudSpatialGeometry.Mode.SCHEMATIC, result.mode);
        assertEquals("route_line_unavailable", result.reason);
        assertNull(result.line);
        assertNull(result.latitude);
    }

    private static String[] linePoints(String line) {
        assertNotNull(line);
        assertTrue(line.startsWith("[["));
        assertTrue(line.endsWith("]]"));
        return line.substring(2, line.length() - 2).split("\\],\\[");
    }

    private static VehiclePosition position(double lat, double lon, long elapsedMs,
            float accuracy, Float bearing, Float speed, Float bearingAccuracy, boolean mock) {
        return new VehiclePosition(lat, lon, elapsedMs, accuracy, bearing,
                speed, bearingAccuracy, mock);
    }

    private static DirectTbtFrame.SourceDistance distance(int meters, long elapsedMs) {
        return new DirectTbtFrame.SourceDistance(meters, elapsedMs);
    }

    private static NavigatorRouteData route(String source, double[] points) {
        return route(source, 1, points);
    }

    private static NavigatorRouteData route(String source, long revision, double[] points) {
        return new NavigatorRouteData(new NavigatorRouteData.Header(source, 1, revision,
                "route-" + revision, 1_000),
                true, true, points, new NavigatorRouteData.Maneuver[]{
                new NavigatorRouteData.Maneuver("0", 0, 0, 0, -1, 11, 12,
                        "turn", "", Double.NaN, Double.NaN, 0, false)});
    }

    private static NavigatorRouteStore.CurrentStep step(NavigatorRouteData route, Double lat, Double lon) {
        return new NavigatorRouteStore.CurrentStep(route.header(), 1, 1_500,
                lat == null ? null : true, true, "0", 0, -1, "turn", "", lat, lon,
                11, 12, 3, 4, "source_indices");
    }
}
