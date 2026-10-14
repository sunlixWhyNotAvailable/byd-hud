package com.bydhud.app;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class NativeRoadInfoTest {
    private static final long NOW = Instant.parse("2026-10-14T10:00:00Z").toEpochMilli();

    @Test public void nativeEtaAndHudLimitUseNumericFieldsAndLeaveStreetAlone() {
        DirectTbtFrame frame = frame().withSpeedLimit(
                new DirectTbtFrame.SpeedLimit(40, 64, "mph", 1L));
        DirectTbtPayload.Options options = options().withSpeedLimitMode(HudPrefs.SPEED_LIMIT_NATIVE_HUD);
        Map<Integer, Object> fields = road(DirectTbtPayload.prepare(frame, options, NOW).build(7));
        assertEquals(12345L, fields.get(3));
        assertEquals(1200L, fields.get(4));
        assertEquals("Main Street", text(fields, 10));
        assertEquals(64L, fields.get(11));
        assertEquals(64L, fields.get(15));
        assertEquals("10:20", text(fields, 26));
        assertEquals("", text(fields, 27));
        assertEquals(0, DirectTbtPayload.speedPlacement(frame, options));
    }

    @Test public void missingNativeValuesAreNotFabricatedAsZeroOrRetainedInPayload() {
        Map<Integer, Object> fields = road(DirectTbtPayload.prepare(
                DirectTbtFrame.empty(), options().withSpeedLimitMode(HudPrefs.SPEED_LIMIT_NATIVE_HUD), NOW).build(0));
        assertFalse(fields.containsKey(3));
        assertFalse(fields.containsKey(4));
        assertFalse(fields.containsKey(11));
        assertFalse(fields.containsKey(15));
        assertEquals("", text(fields, 26));
        assertEquals("", text(fields, 27));
    }

    @Test public void nonNativeModesDoNotPublishNativeEtaOrHudSpeed() {
        for (int etaMode : new int[] {HudPrefs.ETA_OUTPUT_OFF, HudPrefs.ETA_OUTPUT_ALTERNATIVE}) {
            DirectTbtPayload.Options options = options().withEtaOutputMode(etaMode)
                    .withSpeedLimitMode(HudPrefs.SPEED_LIMIT_NATIVE);
            Map<Integer, Object> fields = road(DirectTbtPayload.prepare(frame().withSpeedLimit(
                    new DirectTbtFrame.SpeedLimit(60, 60, "km/h", 1L)), options, NOW).build(0));
            assertFalse(fields.containsKey(3));
            assertFalse(fields.containsKey(4));
            assertFalse(fields.containsKey(11));
            assertFalse(fields.containsKey(15));
            assertEquals("", text(fields, 26));
            if (etaMode == HudPrefs.ETA_OUTPUT_OFF) assertEquals("Main Street", text(fields, 10));
            else assertTrue(text(fields, 10).contains("Main Street"));
        }
    }

    @Test public void optionCopiesPreserveNativeModeAndTimeFormat() {
        DirectTbtPayload.Options original = options().withEtaConfiguration(HudPrefs.ETA_OUTPUT_NATIVE, false);
        DirectTbtPayload.Options copy = original.withSpeedLimitMode(HudPrefs.SPEED_LIMIT_NATIVE_HUD)
                .withMapSettings(HudMapSettings.defaults()).withMapCalibration(true)
                .withMapPng(new byte[] {1}).withPresentation(DirectTbtPayload.Presentation.DEFAULT);
        assertEquals(HudPrefs.ETA_OUTPUT_NATIVE, copy.etaOutputMode);
        assertFalse(copy.nativeEta24Hour);
        assertEquals(original.routeMetricsMode, copy.routeMetricsMode);
        assertNotEquals(HudOutputPreferenceSnapshot.from(original, false),
                HudOutputPreferenceSnapshot.from(original.withEtaOutputMode(HudPrefs.ETA_OUTPUT_OFF), false));
    }

    private static DirectTbtPayload.Options options() {
        return new DirectTbtPayload.Options(false, true, false, true, true, true,
                false, false, true, true, true, null).withEtaOutputMode(HudPrefs.ETA_OUTPUT_NATIVE);
    }

    private static DirectTbtFrame frame() {
        DirectTbtFrame.TravelMetrics next = new DirectTbtFrame.TravelMetrics(
                NOW + 1_200_000L, 0, 1200L, 12345L);
        return DirectTbtFrame.empty().withNavigationText("Main Street", "Turn left")
                .withTripMetrics(new DirectTbtFrame.TripMetrics(next,
                        DirectTbtFrame.TravelMetrics.unavailable()));
    }

    private static String text(Map<Integer, Object> fields, int field) {
        return new String((byte[]) fields.get(field), StandardCharsets.UTF_8);
    }

    private static Map<Integer, Object> road(byte[] packet) {
        return decode((byte[]) decode(packet).get(1));
    }

    private static Map<Integer, Object> decode(byte[] bytes) {
        Map<Integer, Object> result = new HashMap<>();
        int[] cursor = {0};
        while (cursor[0] < bytes.length) {
            long tag = varint(bytes, cursor);
            int field = (int) (tag >>> 3);
            if ((tag & 7) == 0) result.put(field, varint(bytes, cursor));
            else if ((tag & 7) == 2) {
                int length = Math.toIntExact(varint(bytes, cursor));
                assertTrue(length >= 0 && length <= bytes.length - cursor[0]);
                result.put(field, Arrays.copyOfRange(bytes, cursor[0], cursor[0] + length));
                cursor[0] += length;
            } else fail("Unsupported wire type: " + (tag & 7));
        }
        return result;
    }

    private static long varint(byte[] bytes, int[] cursor) {
        long result = 0L;
        for (int shift = 0; shift < 64; shift += 7) {
            assertTrue(cursor[0] < bytes.length);
            int value = bytes[cursor[0]++] & 0xff;
            result |= (long) (value & 0x7f) << shift;
            if ((value & 0x80) == 0) return result;
        }
        throw new AssertionError("Invalid varint");
    }
}
