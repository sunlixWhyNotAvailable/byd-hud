package com.bydhud.app;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import java.util.Arrays;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class HudMapLiveFixtureTest {
    private Context context;
    private DirectTbtFrame source;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("byd_hud_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        HudPrefs.setMapSettings(context, HudMapSettings.defaults().withMode(HudMapSettings.EXPERIMENTAL));
        HudPrefs.setPngOutputEnabled(context, true);
        HudPrefs.setNativeOutputEnabled(context, true);
        HudPrefs.setLaneOutputEnabled(context, true);
        HudPrefs.setWazeAlertsEnabled(context, true);
        HudPrefs.setWazeAlertField(context, HudPrefs.WAZE_ALERT_FIELD_EXPERIMENTAL);
        HudPrefs.setRouteMetricsMode(context, HudPrefs.ROUTE_METRICS_WHOLE_ROUTE);
        HudPrefs.setEtaOutputEnabled(context, true);
        HudPrefs.setRemainingTimeOutputEnabled(context, true);
        HudPrefs.setRemainingDistanceOutputEnabled(context, true);
        HudPrefs.setEtaOutputField(context, HudPrefs.ETA_OUTPUT_FIELD_EXPERIMENTAL);
        HudPrefs.setSpeedLimitMode(context, HudPrefs.SPEED_LIMIT_OFF);
        source = HudMapLiveFixture.frame(context, 1_800_000_000_000L, 1_000L);
    }

    @Test public void realFixtureRendersLanesManeuverWarningAndEtaThroughProductionPayload() {
        assertVisible(source.getManeuverPng());
        assertVisible(source.getLanePng());
        assertVisible(source.getAlertOverlay().getManeuverPng());
        assertEquals(3, HudMapLiveFixture.manualState().turnBitmapId);
        DirectTbtPayload.Prepared prepared = DirectTbtPayload.prepare(selected(1_000L), options());
        assertEquals(2, prepared.nativeManeuver());
        assertEquals(4, prepared.laneCount());
        assertEquals(155, prepared.distanceMeters());
        assertEquals("Typical Street", prepared.displayText());
        HudEtaText eta = HudEtaText.from(source, options());
        assertEquals("18:45", eta.arrival);
        assertTrue(eta.compactDuration.startsWith("99"));
        assertFalse(eta.remainingDistance.isEmpty());
        byte[] packet = prepared.build(0);
        assertEquals(720, decode(field(packet, 8)).getWidth());
        assertEquals(960, decode(field(packet, 7)).getWidth());
        assertVisible(field(packet, 8));
        assertVisible(field(packet, 7));
    }

    @Test public void currentOutputSwitchesRemoveOnlyTheirRequestedContent() {
        byte[] all = packet();
        HudPrefs.setNativeOutputEnabled(context, false);
        assertEquals(0, DirectTbtPayload.prepare(selected(1_000L), options()).nativeManeuver());
        HudPrefs.setPngOutputEnabled(context, false);
        byte[] noManeuver = packet();
        assertChanged(all, noManeuver, 8);
        assertArrayEquals(field(all, 7), field(noManeuver, 7));
        assertVisible(field(noManeuver, 8)); // Map and ETA survive PNG maneuver Off.
        HudPrefs.setLaneOutputEnabled(context, false);
        byte[] noLanes = packet();
        assertChanged(noManeuver, noLanes, 7);
        assertEquals(0, DirectTbtPayload.prepare(selected(1_000L), options()).laneCount());
        HudPrefs.setWazeAlertsEnabled(context, false);
        byte[] noWarning = packet();
        assertChanged(noLanes, noWarning, 7);
        HudPrefs.setRouteMetricsMode(context, HudPrefs.ROUTE_METRICS_OFF);
        byte[] mapOnly = packet();
        assertChanged(noWarning, mapOnly, 8);
        assertChanged(noWarning, mapOnly, 7);
        assertVisible(field(mapOnly, 8));
        assertVisible(field(mapOnly, 7));
    }

    @Test public void allSixPersistedGeometryControlsChangeTheActualWireImages() {
        HudMapSettings base = HudPrefs.mapSettings(context);
        byte[] before = packet();
        int[] values = {50, 15, 110, 15, 0, 90};
        for (int control = 0; control < values.length; control++) {
            HudPrefs.setMapSettings(context, base.withValue(control, values[control]));
            byte[] after = packet();
            assertChanged(before, after, control < 3 ? 8 : 7);
            if (control >= 3) assertArrayEquals(field(before, 8), field(after, 8));
        }
        HudPrefs.setMapSettings(context, base);
        assertArrayEquals(before, packet());
    }

    @Test public void sharedWarningAlternatesWithoutReplacingTheSessionOrSpeedSample() {
        HudPrefs.setWazeAlertField(context, HudPrefs.WAZE_ALERT_FIELD_MANEUVER);
        for (long elapsed : new long[]{1_000L, 5_999L, 11_000L}) {
            DirectTbtFrame route = selected(elapsed);
            assertFalse(route.getAlertOverlay().isActive());
            assertEquals(2, DirectTbtPayload.prepare(route, options()).nativeManeuver());
            assertEquals(2, HudMapLiveFixture.manualState(context, route).maneuverId);
        }
        DirectTbtFrame warning = selected(6_000L);
        assertTrue(warning.getAlertOverlay().isActive());
        assertEquals(99, DirectTbtPayload.prepare(warning, options()).nativeManeuver());
        assertEquals(100, DirectTbtPayload.prepare(warning, options()).distanceMeters());
        assertEquals(99, HudMapLiveFixture.manualState(context, warning).maneuverId);
        assertSame(source.getSpeedLimit(), warning.getSpeedLimit());
        HudPrefs.setWazeAlertsEnabled(context, false);
        assertFalse(selected(6_000L).getAlertOverlay().isActive());
        HudPrefs.setWazeAlertsEnabled(context, true);
        HudPrefs.setWazeAlertField(context, HudPrefs.WAZE_ALERT_FIELD_EXPERIMENTAL);
        assertTrue(selected(1_000L).getAlertOverlay().isActive());
        assertEquals(2, DirectTbtPayload.prepare(selected(6_000L), options()).nativeManeuver());
    }

    @Test public void calibrationMapNeverPaintsOverOpaqueNavigationArtwork() {
        HudMapSettings base = HudPrefs.mapSettings(context);
        byte[] navigation = DirectTbtPayload.prepare(selected(1_000L), options().withMapCalibration(false)).build(0);
        for (int x : new int[]{-250, 0, 145, 250}) {
            HudPrefs.setMapSettings(context, base.withValue(HudMapSettings.CONTROL_MAP_X, x));
            byte[] combined = packet();
            for (int field : new int[]{7, 8}) {
                Bitmap nav = decode(field(navigation, field));
                Bitmap map = decode(field(combined, field));
                int checked = 0;
                for (int py = 0; py < nav.getHeight(); py++) {
                    for (int px = 0; px < nav.getWidth(); px++) {
                        int color = nav.getPixel(px, py);
                        if (Color.alpha(color) == 255) {
                            assertEquals("map must remain behind navigation", color, map.getPixel(px, py));
                            checked++;
                        }
                    }
                }
                assertTrue(checked > 0);
                nav.recycle();
                map.recycle();
            }
        }
    }

    private DirectTbtFrame selected(long elapsed) {
        return HudMapLiveFixture.selectFrame(context, source, elapsed);
    }

    private DirectTbtPayload.Options options() {
        return DirectTbtPayload.Options.from(context).withMapCalibration(true);
    }

    private byte[] packet() {
        return DirectTbtPayload.prepare(selected(1_000L), options()).build(0);
    }

    private static void assertChanged(byte[] before, byte[] after, int field) {
        assertFalse("output field " + field + " must change", Arrays.equals(field(before, field), field(after, field)));
    }

    private static Bitmap decode(byte[] png) {
        Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length);
        assertNotNull("missing/invalid PNG", bitmap);
        return bitmap;
    }

    private static void assertVisible(byte[] png) {
        Bitmap bitmap = decode(png);
        int visible = 0;
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 0) visible++;
            }
        }
        bitmap.recycle();
        assertTrue("PNG must contain visible pixels", visible > 0);
    }

    private static byte[] field(byte[] packet, int wanted) {
        int[] at = {0};
        assertEquals(10, varint(packet, at));
        int end = varint(packet, at) + at[0];
        while (at[0] < end) {
            int tag = varint(packet, at);
            if ((tag & 7) == 0) {
                varint(packet, at);
            } else {
                assertEquals(2, tag & 7);
                int length = varint(packet, at);
                if ((tag >>> 3) == wanted) return Arrays.copyOfRange(packet, at[0], at[0] + length);
                at[0] += length;
            }
        }
        throw new AssertionError("Missing field " + wanted);
    }

    private static int varint(byte[] bytes, int[] at) {
        int value = 0;
        for (int shift = 0; shift < 32; shift += 7) {
            int b = bytes[at[0]++] & 255;
            value |= (b & 127) << shift;
            if ((b & 128) == 0) return value;
        }
        throw new AssertionError("Invalid test payload varint");
    }
}
