package com.bydhud.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.json.JSONObject;

public final class ShanghaiRouteTest {
    @Test public void packagedProvenanceMatchesExactAssetBytes() throws Exception {
        Path directory = asset().getParent();
        JSONObject provenance = new JSONObject(new String(
                Files.readAllBytes(directory.resolve("production-route.json")),
                java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(provenance.getString("packagedGpxSha256"), sha256(asset()));
        assertEquals(provenance.getString("packagedMetadataSha256"),
                sha256(directory.resolve("shanghai_east_city_drive.source.json")));
        assertEquals("FF1D7DCF5325A174B60CE0AEC6EF35346A3D77EC6330D67EEB15F5F62A23422B",
                provenance.getString("sourceGpxSha256"));
        assertEquals("6242A8C34B634D166AEDA3E59AF4E47CF0744A55B73BCB80C846C59CCC014BA8",
                provenance.getString("sourceMetadataSha256"));
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().withUpperCase().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

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
