package com.bydhud.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class ShanghaiRouteTest {
    @Test public void retainedGeometryDropsOnlyFifteenInitialHoldFixes() throws Exception {
        ShanghaiRoute route;
        try (InputStream input = Files.newInputStream(asset())) {
            route = ShanghaiRoute.parse(input);
        }

        assertEquals(15, ShanghaiRoute.PREPARATION_SECONDS);
        assertEquals(295, ShanghaiRoute.DURATION_SECONDS);
        assertEquals(296, route.pointCount());
        assertPoint(route.pointAt(0), 31.2304000, 121.4737000);
        assertPoint(route.pointAt(15), 31.2304000, 121.4737000);
        assertEquals(0f, route.pointAt(15).speedMetersPerSecond, 0f);
        assertPoint(route.pointAt(16), 31.2303848, 121.4737811);
        assertEquals(11.1151f, route.pointAt(16).speedMetersPerSecond, 0.001f);
        assertEquals(57.5060f, route.pointAt(16).bearingDegrees, 0.001f);
        assertPoint(route.pointAt(285), 31.2380680, 121.4818020);
        assertPoint(route.pointAt(295), 31.2380680, 121.4818020);
        assertEquals(0f, route.pointAt(295).speedMetersPerSecond, 0f);
    }

    @Test public void elapsedSecondMustStayInsideRoute() throws Exception {
        ShanghaiRoute route;
        try (InputStream input = Files.newInputStream(asset())) {
            route = ShanghaiRoute.parse(input);
        }
        org.junit.Assert.assertThrows(IllegalArgumentException.class, () -> route.pointAt(-1));
        org.junit.Assert.assertThrows(IllegalArgumentException.class, () -> route.pointAt(296));
    }

    private static void assertPoint(ShanghaiRoute.Point point, double lat, double lon) {
        assertEquals(lat, point.latitude, 0.0000001d);
        assertEquals(lon, point.longitude, 0.0000001d);
        assertEquals(3f, point.accuracyMeters, 0f);
    }

    private static Path asset() {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path path = root.resolve("app/src/main/assets/shanghai/shanghai_east_city_drive.gpx");
        return Files.isRegularFile(path) ? path
                : root.resolve("src/main/assets/shanghai/shanghai_east_city_drive.gpx");
    }
}
