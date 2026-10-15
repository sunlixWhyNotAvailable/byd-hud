package com.bydhud.app;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
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

    @Test public void ordinaryTurnsUseCoherentStraightContextWithoutChangingNativeIds() {
        String straight = null;
        for (int[] maneuver : new int[][] {{9, 11}, {2, 1}, {3, 2}}) {
            DirectTbtFrame frame = navigation(maneuver[0], maneuver[1]);
            DirectTbtPayload.Prepared prepared = DirectTbtPayload.prepare(frame, options(), NOW);
            Map<Integer, Object> fields = road(prepared.build(19));
            assertEquals((long) maneuver[1], fields.get(28));
            assertEquals(60L, fields.get(9));
            assertEquals(121.47832472222223, (Double) fields.get(19), 0.0);
            assertEquals(31.228410277777776, (Double) fields.get(20), 0.0);
            assertEquals(57.50603485107422, (Double) fields.get(32), 0.0);
            assertEquals("", text(fields, 31));
            if (straight == null) straight = text(fields, 30);
            else assertEquals(straight, text(fields, 30));
            assertTrue(straight.startsWith("[[121.47832416666667,"));
            DirectTbtPayload.Prepared edited = prepared.withDisplayText("Updated Street");
            assertEquals(straight, text(road(edited.build(20)), 30));
            assertEquals(prepared.geometryDiagnostics(), edited.geometryDiagnostics());
            assertEquals(prepared.geometryDiagnostics(), DirectTbtPayload.describe(frame, options(), NOW).geometryDiagnostics());
        }
    }

    @Test public void circleGeometryUsesKnownDirectionAndClearsUnknownWithoutChangingCircleSemantics() {
        DirectTbtFrame circle = navigation(25, 99).withVehicleTbt(11, 2);
        String previous = "";
        for (RoundaboutExit.Direction direction : new RoundaboutExit.Direction[] {
                RoundaboutExit.Direction.LEFT, RoundaboutExit.Direction.STRAIGHT, RoundaboutExit.Direction.RIGHT}) {
            DirectTbtFrame frame = circle.withRoundaboutExit(RoundaboutExit.of(
                    direction, RoundaboutExit.Source.GMAPS_EXACT, 25, Double.NaN));
            Map<Integer, Object> fields = road(DirectTbtPayload.prepare(frame, options(), NOW).build(0));
            assertEquals(99L, fields.get(28));
            assertFalse(text(fields, 30).isEmpty());
            assertNotEquals(previous, text(fields, 30));
            previous = text(fields, 30);
            assertEquals(11, frame.getAmapBroadcastManeuver());
            assertEquals(2, frame.getRoundaboutExitNumber());
            DirectTbtFrame laneInput = new DirectTbtFrame(-1, 0, 0, 0,
                    "", "", "", null, null,
                    Collections.singletonList(new DirectTbtFrame.Lane(1, 1, "S*")),
                    DirectTbtFrame.AlertOverlay.inactive());
            Map<Integer, Object> withLanes = road(DirectTbtPayload.prepare(frame.withLanesFrom(laneInput),
                    new DirectTbtPayload.Options(false, true, true, true, true), NOW).build(0));
            assertEquals(99L, withLanes.get(28));
            assertEquals(text(fields, 30), text(withLanes, 30));
        }
        for (RoundaboutExit.Direction direction : new RoundaboutExit.Direction[] {
                RoundaboutExit.Direction.UNKNOWN, RoundaboutExit.Direction.U_TURN}) {
            Map<Integer, Object> fields = road(DirectTbtPayload.prepare(circle.withRoundaboutExit(
                    RoundaboutExit.of(direction, direction == RoundaboutExit.Direction.UNKNOWN
                            ? RoundaboutExit.Source.NONE : RoundaboutExit.Source.GMAPS_EXACT,
                            25, Double.NaN)), options(), NOW).build(0));
            assertGeometryCleared(fields);
            assertEquals("Main Street", text(fields, 10));
            assertEquals(60L, fields.get(9));
            assertEquals(99L, fields.get(28));
        }
    }

    @Test public void disableEndAndReplacingAlertClearGeometryAndNavigationRestoresIt() {
        DirectTbtFrame turn = navigation(3, 2);
        DirectTbtPayload.Options disabled = new DirectTbtPayload.Options(
                false, false, false, true, true, true, false);
        assertGeometryCleared(road(DirectTbtPayload.prepare(turn, disabled, NOW).build(0)));
        assertGeometryCleared(road(DirectTbtPayload.prepare(navigation(15, 99), options(), NOW).build(0)));
        assertGeometryCleared(road(DirectTbtPayload.buildClear()));
        DirectTbtFrame alerted = turn.withAlertOverlay(
                DirectTbtFrame.AlertOverlay.active(1, 100, "Camera", null));
        assertGeometryCleared(road(DirectTbtPayload.prepare(alerted, options(), NOW).build(0)));
        assertEquals("straight", DirectTbtPayload.prepare(
                alerted.withAlertOverlay(DirectTbtFrame.AlertOverlay.inactive()), options(), NOW).geometry().name);
        DirectTbtPayload.Options separate = options().withPresentation(new DirectTbtPayload.Presentation(
                0, HudPrefs.WAZE_ALERT_FIELD_EXPERIMENTAL, 0, false, -1, -1, -1, -1));
        assertEquals("straight", DirectTbtPayload.describe(alerted, separate, NOW).geometry().name);
    }

    @Test public void hudCheckTransitionsReachLiveInputsAndPreserveTheNativeCodeControl() throws Exception {
        HudCheckState state = new HudCheckState().selectMode(HudCheckState.Mode.EXTENDED)
                .withAutomatic(false).stepExtended(31);
        assertTrue(state.isGeometryCheck());
        assertEquals(state.distance(), state.liveFrame().getDistanceMeters());
        state = state.stepExtended(1).withAutomatic(true).toggleRun();
        String controlLine = null;
        int[] controlCodes = {99, 11, 99};
        int controlIndex = 0;
        while (state.isLivePipelineCheck()) {
            assertFalse(state.isGeometryCheck());
            assertEquals(10_000L, NavHudLiveSender.hudCheckIntervalMs(state));
            DirectTbtFrame frame = state.liveFrame();
            Map<Integer, Object> fields = road(DirectTbtPayload.prepare(frame, options(), NOW).build(0));
            assertEquals((long) frame.getBydManeuver(), fields.get(28));
            assertEquals((long) frame.getDistanceMeters(), fields.get(9));
            assertEquals(state.toHudState().maneuverId, frame.getBydManeuver());
            assertEquals(state.toHudState().distanceToIntersection, frame.getDistanceMeters());
            assertEquals(state.liveGeometry().line, text(fields, 30));
            if (state.liveScenarioKey().startsWith("roundabout-native-")) {
                assertEquals(controlCodes[controlIndex++], frame.getBydManeuver());
                assertEquals(60L, fields.get(9));
                if (controlLine == null) controlLine = text(fields, 30);
                else assertEquals(controlLine, text(fields, 30));
            }
            state = state.tick();
        }
        assertEquals(3, controlIndex);
        assertEquals(0, state.extendedIndex);
        assertEquals(state.maneuverIndex, 0);
        assertEquals(state.distance(), state.liveFrame().getDistanceMeters());
        assertEquals(state.stop(), state.stop().tick());
        HudCheckState basic = state.selectMode(HudCheckState.Mode.BASIC)
                .step(HudCheckState.Field.MANEUVER, 8);
        assertEquals(99, basic.liveFrame().getBydManeuver());
        assertEquals(RoundaboutExit.Direction.RIGHT, basic.liveFrame().getRoundaboutExit().getDirection());
        assertEquals(2, basic.liveFrame().getRoundaboutExitNumber());
        assertEquals(basic.liveGeometry().line, basic.withManeuverBitmap(true).liveGeometry().line);

        // Persisted selections must still restore the same geometry/scenario after removing two probes.
        HudCheckState extended = basic.selectMode(HudCheckState.Mode.EXTENDED).withAutomatic(false);
        for (int index = 0; index < HudCheckState.extendedCount(); index++) {
            HudCheckState selected = extended.stepExtended(index);
            assertEquals(selected, HudCheckState.restore(selected.checkpoint()));
            org.json.JSONObject old = selected.checkpoint();
            old.remove("extendedCount");
            old.put("extended", index >= 23 ? index + 2 : index);
            assertEquals(selected, HudCheckState.restore(old));
        }
        for (int removed : new int[] {23, 24}) {
            org.json.JSONObject old = extended.checkpoint().put("extended", removed);
            old.remove("extendedCount");
            HudCheckState restored = HudCheckState.restore(old);
            assertTrue(restored.isGeometryCheck());
            assertEquals(HudCheckGeometry.line(23), HudCheckGeometry.line(restored.extendedIndex));
        }
    }

    @Test public void basicFormatSwitchStopsOutputAndExtendedRetainsSelections() {
        HudCheckState current = new HudCheckState().step(HudCheckState.Field.MANEUVER, 8)
                .withManeuverBitmap(true).toggleRun();
        assertFalse(current.legacyBasic);
        assertSame(current, current.selectBasicFormat(false));
        HudCheckState legacy = current.selectBasicFormat(true);
        assertFalse(legacy.running);
        assertEquals(current.maneuverIndex, legacy.maneuverIndex);
        assertTrue(legacy.maneuverBitmap);
        assertTrue(legacy.isLiveCheck());
        assertEquals(current.liveFrame().getBydManeuver(), legacy.liveFrame().getBydManeuver());
        assertEquals(current.liveGeometry().line, legacy.liveGeometry().line);
        assertFalse(HudOutputCoordinator.sameHudCheckPayload(current, legacy));
        assertTrue(legacy.toggleRun().legacyBasic);
        assertTrue(legacy.toggleRun().stop().legacyBasic);
        HudCheckState extended = legacy.selectMode(HudCheckState.Mode.EXTENDED);
        assertTrue(extended.legacyBasic);
        assertSame(extended, extended.selectBasicFormat(true));
        assertEquals(legacy, extended.selectMode(HudCheckState.Mode.BASIC));
        assertTrue(extended.stop().legacyBasic);
        assertFalse(HudOutputCoordinator.sameHudCheckPayload(extended, extended.withManeuverBitmap(false)));
        assertFalse(HudOutputCoordinator.sameHudCheckPayload(extended, extended.withLaneBitmap(true)));
        assertEquals(current.stop(), legacy.selectBasicFormat(false));
        assertEquals(99, legacy.selectBasicFormat(false).liveFrame().getBydManeuver());
    }

    @Test public void noPngSwitchChangesOnlyManeuverFieldsAgainstUnchangedCurrent() {
        // Real encoders consume image bytes; Android rasterization is outside this check.
        byte[] image = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jEakAAAAASUVORK5CYII=");
        for (int index = 0; index < 11; index++) {
            HudCheckState current = new HudCheckState().step(HudCheckState.Field.MANEUVER, index);
            HudCheckState comparison = current.selectBasicFormat(true);
            byte[] controlPacket = HudCheckPayload.prepareLive(current, image, image).build(0);
            Map<Integer, Object> control = road(controlPacket);
            assertArrayEquals(image, (byte[]) control.get(8));
            assertEquals((long) current.liveFrame().getBydManeuver(), control.get(28));
            assertEquals(0L, control.get(2));
            // With identical artwork input Current still writes both fields in either selection.
            assertArrayEquals(controlPacket, HudCheckPayload.prepareLive(
                    current.withManeuverBitmap(true), image, image).build(0));

            Map<Integer, Object> nativeFields = road(HudCheckPayload.prepareLive(
                    comparison, image, new byte[0]).build(0));
            assertFalse(nativeFields.containsKey(8));
            Map<Integer, Object> expectedNative = new HashMap<>(control);
            expectedNative.remove(8);
            assertFieldsEqual(expectedNative, nativeFields);

            Map<Integer, Object> bitmapFields = road(HudCheckPayload.prepareLive(
                    comparison.withManeuverBitmap(true), image, image).build(0));
            assertFalse(bitmapFields.containsKey(28));
            Map<Integer, Object> expectedBitmap = new HashMap<>(control);
            expectedBitmap.remove(28);
            assertFieldsEqual(expectedBitmap, bitmapFields);
            assertEquals(current.toHudState().maneuverId, comparison.toHudState().maneuverId);
            assertEquals(current.toHudState().turnBitmapId, comparison.toHudState().turnBitmapId);
            assertEquals(current.liveFrame().getAmapBroadcastManeuver(),
                    comparison.liveFrame().getAmapBroadcastManeuver());
            assertEquals(current.liveFrame().getRoundaboutExitNumber(),
                    comparison.liveFrame().getRoundaboutExitNumber());
        }
    }

    @Test public void extendedInheritsBasicPacketAndOverridesOnlySelectedStageFields() {
        byte[] image = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jEakAAAAASUVORK5CYII=");
        // Expected wire values of the existing scalar probes, independent of the encoder.
        Object[][] probes = {
                {26, "12:34"}, {3, 12345L, 4, 1200L, 27, "20 min"}, {3, 12345L}, {11, 60L},
                {12, 25L, 13, 300L, 14, 120L, 15, 40L, 21, 25L}, {17, 1L, 18, 120L}, {23, 1L},
                {24, "[{\"id\":\"diag\",\"lat\":31.2304,\"lon\":121.4737,\"name\":\"TEST\",\"type\":\"0101\"}]"},
                {25, "121.4737,31.2304"}, {19, 121.4737d, 20, 31.2304d}, {22, 12L},
                {30, "[[121.4737,31.2304,0],[121.4740,31.2307,0]]"},
                {31, "121.4740,31.2307,0"}, {32, 90.0d}, {33, 0.42d}
        };
        for (boolean comparison : new boolean[] {false, true}) {
            for (boolean bitmap : new boolean[] {false, true}) {
                for (boolean laneBitmap : new boolean[] {false, true}) {
                    HudCheckState basic = new HudCheckState().withAutomatic(false)
                            .step(HudCheckState.Field.MANEUVER, 1).step(HudCheckState.Field.LANES, 1)
                            .step(HudCheckState.Field.DISTANCE, 4).step(HudCheckState.Field.STREET, 1)
                            .step(HudCheckState.Field.TRAFFIC_LIGHT, 1).withTransliterate(true)
                            .selectBasicFormat(comparison).withManeuverBitmap(bitmap).withLaneBitmap(laneBitmap);
                    byte[] lanes = laneBitmap ? image : new byte[0];
                    byte[] maneuver = bitmap || !comparison ? image : new byte[0];
                    Map<Integer, Object> baseline = road(HudCheckPayload.prepareLive(basic, lanes, maneuver).build(0));
                    HudCheckState extended = basic.selectMode(HudCheckState.Mode.EXTENDED);
                    for (int index = 0; index < HudCheckState.extendedCount(); index++) {
                        HudCheckState sample = extended.stepExtended(index);
                        assertEquals(index, sample.extendedIndex);
                        assertEquals(basic.trafficLightIndex, sample.trafficLightIndex);
                        assertEquals(basic.toHudState().laneString, sample.toHudState().laneString);
                        assertEquals(basic.toHudState().roadName, sample.toHudState().roadName);
                        Map<Integer, Object> fields = road(HudCheckPayload.prepareLive(sample, lanes, maneuver).build(0));
                        assertEquals(!comparison || bitmap, fields.containsKey(8));
                        assertEquals(!comparison || !bitmap, fields.containsKey(28));
                        assertArrayEquals(lanes, (byte[]) fields.get(7));
                        Map<Integer, Object> expected = new HashMap<>(baseline);
                        if (index < probes.length) {
                            Object[] values = probes[index];
                            for (int entry = 0; entry < values.length; entry += 2) {
                                Object value = values[entry + 1];
                                expected.put((Integer) values[entry], value instanceof String
                                        ? ((String) value).getBytes(StandardCharsets.UTF_8) : value);
                            }
                        } else if (sample.isGeometryCheck()) {
                            assertEquals(HudCheckGeometry.line(index), text(fields, 30));
                            assertEquals(HudCheckGeometry.LONGITUDE, fields.get(19));
                            assertEquals(HudCheckGeometry.LATITUDE, fields.get(20));
                            assertEquals(HudCheckGeometry.heading(index), fields.get(32));
                            for (int field : new int[] {19, 20, 30, 32}) {
                                expected.remove(field);
                                fields.remove(field);
                            }
                        } else if (sample.isLivePipelineCheck()) {
                            assertEquals((long) sample.liveFrame().getDistanceMeters(), fields.get(9));
                            if (fields.containsKey(28)) assertEquals((long) sample.liveFrame().getBydManeuver(), fields.get(28));
                            assertEquals(sample.liveGeometry().line, text(fields, 30));
                            for (int field : new int[] {9, 28, 19, 20, 30, 31, 32}) {
                                expected.remove(field);
                                fields.remove(field);
                            }
                        }
                        assertFieldsEqual(expected, fields);
                    }
                }
            }
        }
    }

    @Test public void bitmapToNativeClearTargetsOnlyTheOldImageAndIgnoresNativeStageChanges() {
        HudCheckState bitmap = new HudCheckState().selectBasicFormat(true)
                .withAutomatic(false).withManeuverBitmap(true);
        HudCheckState nativeState = bitmap.withManeuverBitmap(false);
        assertFalse(HudCheckPayload.needsManeuverClear(null, nativeState));
        assertTrue(HudCheckPayload.needsManeuverClear(bitmap, nativeState));
        assertTrue(HudCheckPayload.needsManeuverClear(bitmap,
                nativeState.selectMode(HudCheckState.Mode.EXTENDED)));
        assertFalse(HudCheckPayload.needsManeuverClear(nativeState, nativeState));
        assertFalse(HudCheckPayload.needsManeuverClear(nativeState,
                nativeState.step(HudCheckState.Field.MANEUVER, 1)));
        assertFalse(HudCheckPayload.needsManeuverClear(nativeState,
                nativeState.selectMode(HudCheckState.Mode.EXTENDED).stepExtended(23)));
        assertFalse(HudCheckPayload.needsManeuverClear(bitmap,
                bitmap.selectMode(HudCheckState.Mode.EXTENDED)));
        assertFalse(HudCheckPayload.needsManeuverClear(nativeState, bitmap));
        assertFalse(HudCheckPayload.needsManeuverClear(bitmap, nativeState.selectBasicFormat(false)));

        // Encoder input only: Android PNG rendering and transport acknowledgements are not simulated.
        byte[] blank = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jEakAAAAASUVORK5CYII=");
        assertThrows(IllegalArgumentException.class, () -> HudCheckPayload.buildManeuverClear(
                nativeState, new byte[0], new byte[0]));
        for (HudCheckState selected : new HudCheckState[] {nativeState,
                nativeState.selectMode(HudCheckState.Mode.EXTENDED).stepExtended(23)}) {
            Map<Integer, Object> clear = road(HudCheckPayload.buildManeuverClear(selected, blank, blank));
            assertArrayEquals(blank, (byte[]) clear.remove(8));
            Map<Integer, Object> nativePacket = road(HudCheckPayload.prepareLive(
                    selected, blank, new byte[0]).build(0));
            assertFalse(nativePacket.containsKey(8));
            assertEquals((long) selected.liveFrame().getBydManeuver(), nativePacket.get(28));
            assertFieldsEqual(nativePacket, clear);
        }
    }

    @Test public void mapLiveChannelSelectionOmitsDisabledFieldsWithoutChangingTheRest() {
        byte[] image = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jEakAAAAASUVORK5CYII=");
        DirectTbtFrame input = navigation(3, 2).withManeuverPng(image);
        for (boolean png : new boolean[] {false, true}) {
            for (boolean nativeCode : new boolean[] {false, true}) {
                DirectTbtPayload.Options options = new DirectTbtPayload.Options(
                        png, nativeCode, true, true, true);
                Map<Integer, Object> previous = road(DirectTbtPayload.prepare(input, options).build(0));
                Map<Integer, Object> selected = road(DirectTbtPayload.prepareDiagnostic(
                        input, options, png, nativeCode).build(0));
                assertEquals(png, selected.containsKey(8));
                assertEquals(nativeCode, selected.containsKey(28));
                if (png) assertArrayEquals(image, (byte[]) selected.get(8));
                if (nativeCode) assertEquals(2L, selected.get(28));
                previous.remove(8);
                previous.remove(28);
                selected.remove(8);
                selected.remove(28);
                assertFieldsEqual(previous, selected);
            }
        }
    }

    private static void assertFieldsEqual(Map<Integer, Object> expected, Map<Integer, Object> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        for (int field : expected.keySet()) {
            if (expected.get(field) instanceof byte[]) {
                assertArrayEquals("field " + field, (byte[]) expected.get(field), (byte[]) actual.get(field));
            } else assertEquals("field " + field, expected.get(field), actual.get(field));
        }
    }

    private static DirectTbtFrame navigation(int amap, int nativeId) {
        return new DirectTbtFrame(amap, amap, nativeId, 60, "Main Street", "Continue", "",
                null, null, Collections.emptyList(), DirectTbtFrame.AlertOverlay.inactive());
    }

    private static void assertGeometryCleared(Map<Integer, Object> fields) {
        assertEquals("", text(fields, 30));
        assertEquals("", text(fields, 31));
        for (int field : new int[] {19, 20, 32}) assertEquals(0.0, (Double) fields.get(field), 0.0);
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

    static String text(Map<Integer, Object> fields, int field) {
        return new String((byte[]) fields.get(field), StandardCharsets.UTF_8);
    }

    static Map<Integer, Object> road(byte[] packet) {
        return decode((byte[]) decode(packet).get(1));
    }

    private static Map<Integer, Object> decode(byte[] bytes) {
        Map<Integer, Object> result = new HashMap<>();
        int[] cursor = {0};
        while (cursor[0] < bytes.length) {
            long tag = varint(bytes, cursor);
            int field = (int) (tag >>> 3);
            if ((tag & 7) == 0) result.put(field, varint(bytes, cursor));
            else if ((tag & 7) == 1) {
                assertTrue(cursor[0] + 8 <= bytes.length);
                long bits = 0L;
                for (int i = 0; i < 8; i++) bits |= (bytes[cursor[0]++] & 255L) << (i * 8);
                result.put(field, Double.longBitsToDouble(bits));
            }
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
