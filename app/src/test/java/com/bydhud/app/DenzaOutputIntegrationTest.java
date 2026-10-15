package com.bydhud.app;

import android.app.Application;
import android.content.Context;
import android.os.SystemClock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Verify the actual output loop at its vehicle boundary, including repeated sends. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class, shadows = {
        NativeSpeedLimitTestSupport.InstrumentProxy.class,
        NativeSpeedLimitTestSupport.SomeIpClient.class,
        NativeSpeedLimitTestSupport.TxLog.class,
        NativeSpeedLimitTestSupport.EventsLog.class,
        NativeSpeedLimitTestSupport.DebugWriter.class
})
@LooperMode(LooperMode.Mode.PAUSED)
public final class DenzaOutputIntegrationTest {
    private Context context;
    private HudOutputCoordinator output;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        NativeSpeedLimitTestSupport.reset();
        NativeSpeedLimitTestSupport.resetPreferences(context);
        HudPrefs.setSpeedLimitMode(context, HudPrefs.SPEED_LIMIT_OFF);
        HudPrefs.setMapSettings(context, HudPrefs.mapSettings(context).withMode(HudMapSettings.OFF));
        output = NativeSpeedLimitTestSupport.newCoordinator(context);
    }

    @After public void cleanup() { NativeSpeedLimitTestSupport.stopCoordinator(output); }

    @Test public void denzaRepeatedPacketsKeepStockHeaderValue() {
        HudPrefs.setDenzaArEnabled(context, true);
        start(GMapsDirectChannel.PACKAGE_NAME);
        List<NativeSpeedLimitTestSupport.Packet> sent = packets();
        assertEquals(1, sent.size());
        for (NativeSpeedLimitTestSupport.Packet packet : sent) {
            assertEquals(2, headerValue(packet.payload));
            assertEquals(2, headerValue(packet.semantic));
        }
    }

    @Test public void disabledDenzaProfileKeepsExistingRollingHeader() {
        HudPrefs.setDenzaArEnabled(context, false);
        start(GMapsDirectChannel.PACKAGE_NAME);
        assertRollingHeader();
    }

    @Test public void denzaPreferenceDoesNotChangeOtherNavigatorsHeader() {
        HudPrefs.setDenzaArEnabled(context, true);
        start("com.waze");
        assertRollingHeader();
    }

    @Test public void denzaLoopUsesReferencePacketAndCompactHeartbeat() {
        HudPrefs.setDenzaArEnabled(context, true);
        DirectTbtFrame frame = DenzaRoadParityTest.frame(84);
        output.publishDirect(frame, "test", SystemClock.elapsedRealtime(),
                GMapsDirectChannel.PACKAGE_NAME, 1);
        output.selectNavigationSource(HudOutputCoordinator.Source.DIRECT, "test",
                GMapsDirectChannel.PACKAGE_NAME, 1);
        NativeSpeedLimitTestSupport.idleMainLooperFor(1000);
        assertEquals(1, packets().size());
        assertArrayEquals(DirectTbtPayload.prepareDenza(frame).build(2), packets().get(0).payload);
        NativeSpeedLimitTestSupport.idleMainLooperFor(4500);
        assertEquals(2, packets().size());
        assertTrue(packets().get(1).at - packets().get(0).at >= 5000);
    }

    @Test public void qualifiedGpsRunsArThroughActualLoopAt350ms() {
        HudPrefs.setDenzaArEnabled(context, true);
        org.robolectric.Shadows.shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.ACCESS_FINE_LOCATION);
        android.location.LocationManager manager = context.getSystemService(android.location.LocationManager.class);
        org.robolectric.Shadows.shadowOf(manager).setProviderEnabled("gps", true);
        // Inside the 50 m start at the fix's 8 m/s and outside the 25 m turn hold.
        DirectTbtFrame frame = DenzaRoadParityTest.frame(48);
        output.publishDirect(frame, "test", SystemClock.elapsedRealtime(), null, null,
                GMapsDirectChannel.PACKAGE_NAME, 1, frame.getRoadText(), frame);
        output.selectNavigationSource(HudOutputCoordinator.Source.DIRECT, "test",
                GMapsDirectChannel.PACKAGE_NAME, 1);
        NativeSpeedLimitTestSupport.idleMainLooperFor(200);
        android.location.Location fix = new android.location.Location("gps");
        fix.setLatitude(20); fix.setLongitude(10); fix.setAccuracy(4);
        fix.setSpeed(8); fix.setBearing(0); fix.setAltitude(100);
        fix.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        org.robolectric.Shadows.shadowOf(manager).simulateLocation(fix);
        NativeSpeedLimitTestSupport.idleMainLooperFor(1000);
        List<NativeSpeedLimitTestSupport.Packet> sent = packets();
        assertTrue(sent.size() >= 3);
        for (int i = 1; i < sent.size(); i++) {
            assertEquals(350, sent.get(i).at - sent.get(i - 1).at);
        }
        // No new fixes: loss of usable geometry publishes compact guidance at the next poll.
        NativeSpeedLimitTestSupport.idleMainLooperFor(1800);
        sent = packets();
        assertArrayEquals(DirectTbtPayload.prepareDenza(frame).build(2), sent.get(sent.size() - 1).payload);
    }

    @Test public void denzaField9CarriesTheHeldStepDistance() {
        HudPrefs.setDenzaArEnabled(context, true);
        org.robolectric.Shadows.shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.ACCESS_FINE_LOCATION);
        android.location.LocationManager manager = context.getSystemService(android.location.LocationManager.class);
        org.robolectric.Shadows.shadowOf(manager).setProviderEnabled("gps", true);
        publishRaw(DenzaRoadParityTest.frame(100).withSourceStepIndex(3));
        output.selectNavigationSource(HudOutputCoordinator.Source.DIRECT, "test",
                GMapsDirectChannel.PACKAGE_NAME, 1);
        NativeSpeedLimitTestSupport.idleMainLooperFor(500);
        assertEquals(100, lastField9());
        // Google's distance climbs back within the same step: field 9 keeps the step's minimum.
        long wobbleAt = SystemClock.elapsedRealtime();
        publishRaw(DenzaRoadParityTest.frame(110).withSourceStepIndex(3));
        NativeSpeedLimitTestSupport.idleMainLooperFor(5500);
        List<NativeSpeedLimitTestSupport.Packet> after = packets().stream()
                .filter(p -> p.at >= wobbleAt).collect(Collectors.toList());
        assertFalse(after.isEmpty());
        for (NativeSpeedLimitTestSupport.Packet packet : after) {
            assertEquals(100, DenzaRoadParityTest.varintField(packet.payload, 9));
        }
        // The next Google step starts from its own distance.
        publishRaw(DenzaRoadParityTest.frame(110).withSourceStepIndex(4));
        NativeSpeedLimitTestSupport.idleMainLooperFor(500);
        assertEquals(110, lastField9());
    }

    @Test public void disablingNativeOutputAlsoDisablesDenzaProfile() {
        HudPrefs.setDenzaArEnabled(context, true);
        HudPrefs.setNativeOutputEnabled(context, false);
        start(GMapsDirectChannel.PACKAGE_NAME);
        assertRollingHeader();
    }

    private void start(String owner) {
        output.publishDirect(DirectTbtFrame.empty(), "test", SystemClock.elapsedRealtime(), owner, 1);
        output.selectNavigationSource(HudOutputCoordinator.Source.DIRECT, "test", owner, 1);
        NativeSpeedLimitTestSupport.idleMainLooperFor(500);
    }

    private void publishRaw(DirectTbtFrame frame) {
        output.publishDirect(frame, "test", SystemClock.elapsedRealtime(), null, null,
                GMapsDirectChannel.PACKAGE_NAME, 1, frame.getRoadText(), frame);
    }

    private static long lastField9() {
        List<NativeSpeedLimitTestSupport.Packet> sent = packets();
        return DenzaRoadParityTest.varintField(sent.get(sent.size() - 1).payload, 9);
    }

    private void assertRollingHeader() {
        Set<Integer> values = new HashSet<>();
        for (NativeSpeedLimitTestSupport.Packet packet : packets()) {
            values.add(headerValue(packet.payload));
            assertEquals(0, headerValue(packet.semantic));
        }
        assertTrue(values.size() >= 3);
    }

    private static List<NativeSpeedLimitTestSupport.Packet> packets() {
        return NativeSpeedLimitTestSupport.PACKETS.stream().filter(p -> "payload".equals(p.kind))
                .collect(Collectors.toList());
    }

    private static int headerValue(byte[] payload) {
        assertEquals(0x0a, payload[0]); // Outer RoadInfo message.
        int i = 1;
        while ((payload[i++] & 128) != 0) { }
        assertEquals(0x10, payload[i++]); // First inner field is counter (2).
        int value = 0, shift = 0;
        while (true) {
            int next = payload[i++] & 255;
            value |= (next & 127) << shift;
            if ((next & 128) == 0) return value;
            shift += 7;
        }
    }
}
