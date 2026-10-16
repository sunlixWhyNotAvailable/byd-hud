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

    @Test public void liveSpatialModesSerializeAsWholeGroupsAndKeepDiagnosticsExplicit() {
        final long nowElapsedMs = 100_000L;
        DirectTbtFrame frame = navigation(3, 2);
        HudSpatialGeometry.Result real = realSpatialGeometry(nowElapsedMs);
        assertEquals(HudSpatialGeometry.Mode.REAL, real.mode);
        assertTrue(real.usableAt(nowElapsedMs));

        Map<Integer, Object> ordinary = road(DirectTbtPayload.prepare(frame, options(), NOW).build(0));
        DirectTbtPayload.Prepared live = DirectTbtPayload.prepareLive(
                frame, options(), NOW, nowElapsedMs, real, false);
        Map<Integer, Object> actual = road(live.build(0));
        assertEquals(HudSpatialGeometry.Mode.REAL, live.spatialMode());
        assertFalse(live.spatialReset());
        assertEquals(real.longitude, (Double) actual.get(19), 0.0);
        assertEquals(real.latitude, (Double) actual.get(20), 0.0);
        assertEquals(real.headingDegrees, (Double) actual.get(32), 0.0);
        assertEquals(real.line, text(actual, 30));
        assertEquals("", text(actual, 31));
        assertOrdinaryFieldsEqual(ordinary, actual);

        HudSpatialGeometry.Result fallback = HudSpatialGeometry.Result.fallback(
                real.header, real.stepSerial, "position_stale");
        DirectTbtPayload.Prepared schematic = DirectTbtPayload.prepareLive(
                frame, options(), NOW, nowElapsedMs, fallback, false);
        Map<Integer, Object> schematicFields = road(schematic.build(0));
        assertEquals(HudSpatialGeometry.Mode.SCHEMATIC, schematic.spatialMode());
        assertEquals(HudManeuverGeometry.LONGITUDE, (Double) schematicFields.get(19), 0.0);
        assertEquals(HudManeuverGeometry.LATITUDE, (Double) schematicFields.get(20), 0.0);
        assertEquals(HudManeuverGeometry.HEADING, (Double) schematicFields.get(32), 0.0);
        assertEquals(HudManeuverGeometry.STRAIGHT.line, text(schematicFields, 30));
        assertEquals("", text(schematicFields, 31));
        assertFieldsEqual(ordinary, schematicFields);

        DirectTbtPayload.Prepared off = DirectTbtPayload.prepareLive(
                frame, options(), NOW, nowElapsedMs, HudSpatialGeometry.Result.off(), false);
        Map<Integer, Object> offFields = road(off.build(0));
        assertEquals(HudSpatialGeometry.Mode.OFF, off.spatialMode());
        assertNoSpatialFields(offFields);
        assertOrdinaryFieldsEqual(ordinary, offFields);

        DirectTbtPayload.Prepared expired = DirectTbtPayload.prepareLive(
                frame, options(), NOW, real.validUntilElapsedMs, real, false);
        Map<Integer, Object> expiredFields = road(expired.build(0));
        assertEquals(HudSpatialGeometry.Mode.SCHEMATIC, expired.spatialMode());
        assertEquals(HudManeuverGeometry.STRAIGHT.line, text(expiredFields, 30));

        Map<Integer, Object> diagnostic = road(DirectTbtPayload.prepareDiagnostic(
                frame, options(), false, true).build(0));
        assertEquals(HudManeuverGeometry.LONGITUDE, (Double) diagnostic.get(19), 0.0);
        assertEquals(HudManeuverGeometry.LATITUDE, (Double) diagnostic.get(20), 0.0);
        assertEquals(HudManeuverGeometry.STRAIGHT.line, text(diagnostic, 30));
        assertEquals("", text(diagnostic, 31));

        DirectTbtFrame alerted = frame.withAlertOverlay(
                DirectTbtFrame.AlertOverlay.active(7, 25, "Hazard", null));
        Map<Integer, Object> alertBaseline = road(DirectTbtPayload.prepare(
                alerted, options(), NOW).build(0));
        Map<Integer, Object> alertWithSpatial = road(DirectTbtPayload.prepareLive(
                alerted, options(), NOW, nowElapsedMs, real, false).build(0));
        assertEquals(HudSpatialGeometry.Mode.OFF, DirectTbtPayload.prepareLive(
                alerted, options(), NOW, nowElapsedMs, real, false).spatialMode());
        assertGeometryCleared(alertWithSpatial);
        assertFieldsEqual(alertBaseline, alertWithSpatial);
    }

    @Test public void selectiveSpatialResetKeepsOrdinaryFieldsAndWaitsForSuccessfulPayload() {
        final long nowElapsedMs = 100_000L;
        DirectTbtFrame frame = navigation(3, 2);
        HudSpatialGeometry.Result real = realSpatialGeometry(nowElapsedMs);
        HudOutputCoordinator.SpatialOutputState state = new HudOutputCoordinator.SpatialOutputState();
        HudSpatialGeometry.Result off = HudSpatialGeometry.Result.off();

        assertFalse(state.resetRequired(off));
        DirectTbtPayload.Prepared spatial = DirectTbtPayload.prepareLive(
                frame, options(), NOW, nowElapsedMs, real, false);
        assertFalse(state.spatialSent()); // Preparing a packet does not record a transmission.
        state.recordSuccessfulPayload(spatial);
        assertTrue(state.spatialSent());
        assertTrue(state.resetRequired(off));

        DirectTbtPayload.Prepared reset = DirectTbtPayload.prepareLive(
                frame, options(), NOW, nowElapsedMs, off, true);
        Map<Integer, Object> resetFields = road(reset.build(0));
        assertTrue(reset.spatialReset());
        assertEquals(HudSpatialGeometry.Mode.OFF, reset.spatialMode());
        assertEquals(0.0, (Double) resetFields.get(19), 0.0);
        assertEquals(0.0, (Double) resetFields.get(20), 0.0);
        assertEquals("", text(resetFields, 30));
        assertEquals("", text(resetFields, 31));
        assertEquals(0.0, (Double) resetFields.get(32), 0.0);
        Map<Integer, Object> ordinary = road(DirectTbtPayload.prepare(frame, options(), NOW).build(0));
        assertOrdinaryFieldsEqual(ordinary, resetFields);
        assertTrue(state.resetRequired(off)); // A prepared reset is still pending until send success.

        state.recordSuccessfulPayload(reset);
        assertFalse(state.spatialSent());
        assertFalse(state.resetRequired(off));
        state.recordSuccessfulPayload(spatial);
        state.recordSuccessfulClear();
        assertFalse(state.spatialSent());
    }

    @Test public void spatialIdentityInvalidatesOnlyWhenGeometryContentChanges() {
        final long nowElapsedMs = 100_000L;
        NavigatorRouteData route = spatialTestRoute(nowElapsedMs);
        NavigatorRouteStore.CurrentStep step = spatialTestStep(route, nowElapsedMs);
        HudSpatialGeometry resolver = new HudSpatialGeometry(route);
        VehiclePosition firstPosition = new VehiclePosition(0.00005, 0.0005, nowElapsedMs,
                5f, 90f, 10f, 5f, false);
        DirectTbtFrame.SourceDistance distance = new DirectTbtFrame.SourceDistance(150, nowElapsedMs);
        HudSpatialGeometry.Result first = resolver.resolve(step, firstPosition, distance, nowElapsedMs);
        assertEquals(HudSpatialGeometry.Mode.REAL, first.mode);
        HudSpatialGeometry.Result repeated = resolver.resolve(step, firstPosition, distance, nowElapsedMs + 1);
        assertEquals(HudSpatialGeometry.Mode.REAL, repeated.mode);
        assertTrue(HudOutputCoordinator.sameSpatialResult(first, true, repeated, true));

        VehiclePosition moved = new VehiclePosition(0.00005, 0.00051, nowElapsedMs + 100,
                5f, 90f, 10f, 5f, false);
        HudSpatialGeometry.Result changed = resolver.resolve(step, moved,
                new DirectTbtFrame.SourceDistance(150, nowElapsedMs + 100), nowElapsedMs + 100);
        assertEquals(HudSpatialGeometry.Mode.REAL, changed.mode);
        assertNotEquals(first.identity, changed.identity);
        assertNotEquals(first.line, changed.line);
        assertFalse(HudOutputCoordinator.sameSpatialResult(first, true, changed, true));
        assertTrue(changed.usableAt(nowElapsedMs + 100));
        assertFalse(changed.usableAt(changed.validUntilElapsedMs));
        assertFalse(HudOutputCoordinator.sameSpatialResult(
                changed, changed.usableAt(nowElapsedMs + 100),
                changed, changed.usableAt(changed.validUntilElapsedMs)));
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

    @Test public void checkSelectionsSurviveFormatsRunsAndCheckpointMigration() throws Exception {
        HudCheckState original = new HudCheckState().step(HudCheckState.Field.MANEUVER, 8)
                .withManeuverBitmap(true).withLaneBitmap(true);
        assertEquals(HudCheckState.Format.NO_PNG, original.format);
        assertEquals(HudCheckState.CodeTable.BYD, original.effectiveCodeTable());
        HudCheckState selected = original.selectBasicFormat(HudCheckState.Format.B)
                .selectCodeTable(HudCheckState.CodeTable.HUAWEI);
        assertFalse(selected.effectiveManeuverBitmap());
        assertFalse(selected.effectiveLaneBitmap());
        assertFalse(HudOutputCoordinator.sameHudCheckPayload(original, selected));
        assertFalse(HudOutputCoordinator.sameHudCheckPayload(selected,
                selected.selectCodeTable(HudCheckState.CodeTable.AMAP)));
        HudCheckState running = selected.toggleRun();
        assertEquals(selected.selectBasicFormat(HudCheckState.Format.D).toggleRun(),
                running.selectBasicFormat(HudCheckState.Format.D));
        assertEquals(selected.selectCodeTable(HudCheckState.CodeTable.BYD).toggleRun(),
                running.selectCodeTable(HudCheckState.CodeTable.BYD));
        assertTrue(running.selectMode(HudCheckState.Mode.EXTENDED).running);
        assertEquals(running, running.selectMode(HudCheckState.Mode.EXTENDED)
                .selectMode(HudCheckState.Mode.BASIC));
        assertFalse(running.selectMode(HudCheckState.Mode.SHANGHAI).running);
        assertSame(running, running.withManeuverBitmap(true));
        assertSame(running, running.withLaneBitmap(true));
        assertEquals(selected, running.stop());
        HudCheckState denza = selected.selectBasicFormat(HudCheckState.Format.D);
        assertEquals(HudCheckState.CodeTable.HUAWEI, denza.effectiveCodeTable());
        assertEquals(selected, denza.selectBasicFormat(HudCheckState.Format.B));
        HudCheckState restoredImages = denza.selectBasicFormat(HudCheckState.Format.NO_PNG);
        assertTrue(restoredImages.effectiveManeuverBitmap());
        assertTrue(restoredImages.effectiveLaneBitmap());
        for (HudCheckState.Format format : HudCheckState.Format.values()) {
            HudCheckState basic = selected.selectBasicFormat(format);
            assertEquals(HudCheckState.CodeTable.HUAWEI, basic.effectiveCodeTable());
            assertEquals(HudCheckState.CodeTable.BYD, original.selectBasicFormat(format).effectiveCodeTable());
            for (HudCheckState.CodeTable table : HudCheckState.CodeTable.values()) {
                HudCheckState changed = basic.selectCodeTable(table);
                assertEquals(table, changed.effectiveCodeTable());
                assertEquals(changed.toggleRun(), basic.toggleRun().selectCodeTable(table));
                assertEquals(changed, HudCheckState.restore(changed.checkpoint()));
                assertEquals(changed, changed.selectBasicFormat(HudCheckState.Format.A).selectBasicFormat(format));
            }
            HudCheckState extended = basic.selectMode(HudCheckState.Mode.EXTENDED);
            assertEquals(basic, extended.selectMode(HudCheckState.Mode.BASIC));
            assertSame(extended, extended.selectBasicFormat(HudCheckState.Format.A));
            assertEquals(extended, HudCheckState.restore(extended.checkpoint()));
        }
        for (boolean oldFormat : new boolean[]{false, true}) {
            org.json.JSONObject checkpoint = original.checkpoint();
            checkpoint.remove("format");
            checkpoint.remove("codeTable");
            checkpoint.put("legacyBasic", oldFormat);
            assertEquals(original, HudCheckState.restore(checkpoint));
        }
        HudCheckState lanes = original.step(HudCheckState.Field.LANES, 2);
        HudCheckState list = lanes.selectBasicFormat(HudCheckState.Format.C);
        assertEquals(lanes, list.selectBasicFormat(HudCheckState.Format.NO_PNG));
        for (int index = 0; index < 16; index++) {
            assertFalse(HudLaneModel.hasUnrepresentableSemanticDirections(list.toHudState()));
            list = list.step(HudCheckState.Field.LANES, 1);
        }
    }

    @Test public void diagnosticFormatsEncodeOnlyTheirDeclaredFieldDifferences() {
        // Real encoders consume image bytes; Android rasterization is outside this check.
        byte[] image = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jEakAAAAASUVORK5CYII=");
        for (int index = 0; index < 11; index++) {
            HudCheckState baseline = new HudCheckState().step(HudCheckState.Field.MANEUVER, index);
            Map<Integer, Object> nativeFields = road(HudCheckPayload.prepareLive(
                    baseline, new byte[0], new byte[0]).build(0));
            assertFalse(nativeFields.containsKey(8));
            assertEquals((long) baseline.liveFrame().getBydManeuver(), nativeFields.get(28));
            Map<Integer, Object> bitmapFields = road(HudCheckPayload.prepareLive(
                    baseline.withManeuverBitmap(true), new byte[0], image).build(0));
            assertEquals(nativeFields.get(28), bitmapFields.get(28));
            Map<Integer, Object> expectedBitmap = new HashMap<>(nativeFields);
            expectedBitmap.put(8, image);
            assertFieldsEqual(expectedBitmap, bitmapFields);
            for (HudCheckState.Format format : new HudCheckState.Format[]{
                    HudCheckState.Format.A, HudCheckState.Format.B, HudCheckState.Format.C}) {
                HudCheckState candidate = baseline.selectBasicFormat(format);
                // Deliberately supplied images must never leak into candidate list fields.
                Map<Integer, Object> actual = road(HudCheckPayload.prepareLive(candidate, image, image).build(0));
                Map<Integer, Object> expected = new HashMap<>(nativeFields);
                expected.remove(7);
                if (format != HudCheckState.Format.A) {
                    expected.put(8, new byte[]{(byte) baseline.liveNativeManeuver()});
                }
                if (format == HudCheckState.Format.C) expected.put(7, new byte[]{0, 0, 0, 0, 0});
                assertFieldsEqual(expected, actual);
                assertEquals(baseline.toHudState().maneuverId, candidate.toHudState().maneuverId);
                assertEquals(baseline.liveFrame().getAmapBroadcastManeuver(),
                        candidate.liveFrame().getAmapBroadcastManeuver());
            }
            HudCheckState denza = baseline.selectBasicFormat(HudCheckState.Format.D);
            DirectTbtPayload.Prepared prepared = HudCheckPayload.prepareLive(denza, image, image);
            Map<Integer, Object> alternate = road(prepared.build(7));
            assertEquals(new java.util.HashSet<>(Arrays.asList(2, 3, 4, 9, 10, 11, 16, 26, 27, 28)), alternate.keySet());
            assertEquals(2L, alternate.get(2));
            assertEquals(2L, alternate.get(16));
            assertEquals(12345L, alternate.get(3));
            assertEquals(1200L, alternate.get(4));
            assertEquals(60L, alternate.get(11));
            assertEquals("12 км", text(alternate, 26));
            assertEquals("20 мин", text(alternate, 27));
            assertEquals(nativeFields.get(9), alternate.get(9));
            assertEquals(nativeFields.get(28), alternate.get(28));
            assertEquals(text(nativeFields, 10), text(alternate, 10));
            alternate.put(10, "New Street".getBytes(StandardCharsets.UTF_8));
            assertFieldsEqual(alternate, road(prepared.withDisplayText("New Street").build(8)));
        }
        Map<Integer, Object> clear = road(DirectTbtPayload.buildClear());
        assertFalse(clear.containsKey(7));
        assertFalse(clear.containsKey(8));
        assertEquals(1L, clear.get(16));
    }

    @Test public void alternateTablesReachRoadInfoWithoutChangingCompanionCodes() {
        int[][] ids = {
                {11, 1, 2, 3, 5, 1, 7, 8, 99, 1, 2},
                {9, 2, 3, 4, 5, 6, 8, 19, 22, 65, 66},
                {8, 1, 2, 3, 4, 5, 7, 18, 47, 42, 41}
        };
        for (HudCheckState.CodeTable table : HudCheckState.CodeTable.values()) {
            for (int index = 0; index < ids[0].length; index++) {
                HudCheckState original = new HudCheckState().step(HudCheckState.Field.MANEUVER, index);
                for (HudCheckState.Format format : HudCheckState.Format.values()) {
                    HudCheckState state = original.selectBasicFormat(format).selectCodeTable(table);
                    Map<Integer, Object> fields = road(HudCheckPayload.prepareLive(
                            state, new byte[0], new byte[0]).build(0));
                    int expected = ids[table.ordinal()][index];
                    assertEquals((long) expected, fields.get(28));
                    if (format == HudCheckState.Format.B || format == HudCheckState.Format.C) {
                        assertArrayEquals(new byte[]{(byte) expected}, (byte[]) fields.get(8));
                    } else assertFalse(fields.containsKey(8));
                    Map<Integer, Object> baseline = road(HudCheckPayload.prepareLive(
                            original.selectBasicFormat(format), new byte[0], new byte[0]).build(0));
                    baseline.put(28, (long) expected);
                    if (format == HudCheckState.Format.B || format == HudCheckState.Format.C) {
                        baseline.put(8, new byte[]{(byte) expected});
                    }
                    assertFieldsEqual(baseline, fields);
                    assertEquals(original.toHudState().maneuverId, state.toHudState().maneuverId);
                    assertEquals(original.liveFrame().getAmapManeuver(), state.liveFrame().getAmapManeuver());
                    assertEquals(original.liveFrame().getAmapBroadcastManeuver(), state.liveFrame().getAmapBroadcastManeuver());
                }
            }
            HudCheckState extended = new HudCheckState().selectBasicFormat(HudCheckState.Format.B)
                    .selectCodeTable(table).selectMode(HudCheckState.Mode.EXTENDED).withAutomatic(false);
            for (int index = HudCheckLiveScenario.FIRST_EXTENDED_INDEX; index < HudCheckState.extendedCount(); index++) {
                HudCheckState state = extended.stepExtended(index);
                if (!state.liveFrame().getRoundaboutExit().hasDirection()) continue;
                int expected;
                if (state.liveScenarioKey().startsWith("roundabout-native-")) {
                    expected = state.liveScenarioKey().equals("roundabout-native-11") ? 11 : 99;
                } else if (table == HudCheckState.CodeTable.BYD) expected = 99;
                else {
                    RoundaboutExit.Direction direction = state.liveFrame().getRoundaboutExit().getDirection();
                    expected = table == HudCheckState.CodeTable.AMAP
                            ? direction == RoundaboutExit.Direction.RIGHT ? 22 : direction == RoundaboutExit.Direction.STRAIGHT ? 23 : 21
                            : direction == RoundaboutExit.Direction.RIGHT ? 47 : direction == RoundaboutExit.Direction.STRAIGHT ? 48 : 49;
                }
                Map<Integer, Object> fields = road(HudCheckPayload.prepareLive(state, new byte[0], new byte[0]).build(0));
                assertEquals((long) expected, fields.get(28));
                assertArrayEquals(new byte[]{(byte) expected}, (byte[]) fields.get(8));
            }
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
        for (HudCheckState.Format format : HudCheckState.Format.values()) {
            for (boolean bitmap : new boolean[] {false, true}) {
                for (boolean laneBitmap : new boolean[] {false, true}) {
                    if (format != HudCheckState.Format.NO_PNG && (bitmap || laneBitmap)) continue;
                    HudCheckState basic = new HudCheckState().withAutomatic(false)
                            .step(HudCheckState.Field.MANEUVER, 1).step(HudCheckState.Field.LANES, 1)
                            .step(HudCheckState.Field.DISTANCE, 4).step(HudCheckState.Field.STREET, 1)
                            .step(HudCheckState.Field.TRAFFIC_LIGHT, 1).withTransliterate(true)
                            .withManeuverBitmap(bitmap).withLaneBitmap(laneBitmap)
                            .selectBasicFormat(format).selectCodeTable(HudCheckState.CodeTable.HUAWEI);
                    byte[] lanes = laneBitmap ? image : new byte[0];
                    byte[] maneuver = bitmap ? image : new byte[0];
                    Map<Integer, Object> baseline = road(HudCheckPayload.prepareLive(basic, lanes, maneuver).build(0));
                    HudCheckState extended = basic.selectMode(HudCheckState.Mode.EXTENDED);
                    for (int index = 0; index < HudCheckState.extendedCount(); index++) {
                        HudCheckState sample = extended.stepExtended(index);
                        assertEquals(index, sample.extendedIndex);
                        assertEquals(basic.trafficLightIndex, sample.trafficLightIndex);
                        assertEquals(basic.toHudState().laneString, sample.toHudState().laneString);
                        assertEquals(basic.toHudState().roadName, sample.toHudState().roadName);
                        Map<Integer, Object> fields = road(HudCheckPayload.prepareLive(sample, lanes, maneuver).build(0));
                        boolean packed = format == HudCheckState.Format.B || format == HudCheckState.Format.C;
                        assertEquals(bitmap || packed, fields.containsKey(8));
                        assertTrue(fields.containsKey(28));
                        if (format == HudCheckState.Format.NO_PNG) assertArrayEquals(lanes, (byte[]) fields.get(7));
                        else if (format == HudCheckState.Format.C) assertArrayEquals(new byte[]{1, 0, 4}, (byte[]) fields.get(7));
                        else assertFalse(fields.containsKey(7));
                        if (packed) assertArrayEquals(new byte[]{(byte) sample.liveNativeManeuver()}, (byte[]) fields.get(8));
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
                            if (fields.containsKey(28)) assertEquals((long) sample.liveNativeManeuver(), fields.get(28));
                            if (format == HudCheckState.Format.D) assertFalse(fields.containsKey(30));
                            else assertEquals(sample.liveGeometry().line, text(fields, 30));
                            for (int field : new int[] {9, 28, 19, 20, 30, 31, 32}) {
                                expected.remove(field);
                                fields.remove(field);
                            }
                            if (packed) {
                                expected.remove(8);
                                fields.remove(8);
                            }
                        }
                        assertFieldsEqual(expected, fields);
                    }
                }
            }
        }
    }

    @Test public void comparisonTransitionsClearPreviousEncodingWithoutChangingTargetPackets() {
        HudCheckState bitmap = new HudCheckState()
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
        for (HudCheckState.Format format : new HudCheckState.Format[]{HudCheckState.Format.A,
                HudCheckState.Format.D, HudCheckState.Format.B, HudCheckState.Format.C}) {
            assertFalse(HudCheckPayload.needsManeuverClear(bitmap, nativeState.selectBasicFormat(format)));
        }

        // Encoder input only: Android PNG rendering and transport acknowledgements are not simulated.
        byte[] blank = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jEakAAAAASUVORK5CYII=");
        assertFalse(HudCheckPayload.needsComparisonClear(null, nativeState));
        assertFalse(HudCheckPayload.needsComparisonClear(bitmap, nativeState));
        assertTrue(HudCheckPayload.needsComparisonClear(bitmap,
                bitmap.selectMode(HudCheckState.Mode.EXTENDED)));
        for (HudCheckState.Format from : HudCheckState.Format.values()) {
            HudCheckState previous = bitmap.withLaneBitmap(true).selectBasicFormat(from).toggleRun();
            for (HudCheckState.Format to : HudCheckState.Format.values()) {
                HudCheckState next = previous.selectBasicFormat(to);
                assertTrue(next.running);
                assertEquals(from != to, HudCheckPayload.needsComparisonClear(previous, next));
                if (from == to) continue;
                Map<Integer, Object> reset = road(HudCheckPayload.buildComparisonClear(previous, blank));
                assertEquals(1L, reset.get(16));
                assertArrayEquals(new byte[0], (byte[]) reset.get(7));
                if (from == HudCheckState.Format.NO_PNG) assertArrayEquals(blank, (byte[]) reset.get(8));
                else if (from == HudCheckState.Format.B || from == HudCheckState.Format.C) {
                    assertArrayEquals(new byte[0], (byte[]) reset.get(8));
                } else assertFalse(reset.containsKey(8));
                Map<Integer, Object> expectedReset = road(DirectTbtPayload.buildClear());
                reset.remove(7);
                reset.remove(8);
                assertFieldsEqual(expectedReset, reset);
                Map<Integer, Object> target = road(HudCheckPayload.prepareLive(next, blank, blank).build(0));
                assertEquals(2L, target.get(16));
                assertEquals((long) next.liveNativeManeuver(), target.get(28));
                if (to == HudCheckState.Format.A || to == HudCheckState.Format.D) {
                    assertFalse(target.containsKey(7));
                    assertFalse(target.containsKey(8));
                }
                assertFalse(HudCheckPayload.needsComparisonClear(null, next));
                assertFalse(HudCheckPayload.needsComparisonClear(next, next));
            }
        }
        HudCheckState table = nativeState.selectBasicFormat(HudCheckState.Format.B);
        assertFalse(HudCheckPayload.needsComparisonClear(table,
                table.selectCodeTable(HudCheckState.CodeTable.HUAWEI)));
        assertThrows(IllegalArgumentException.class, () -> HudCheckPayload.buildComparisonClear(bitmap, null));
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

    private static void assertOrdinaryFieldsEqual(Map<Integer, Object> expected,
            Map<Integer, Object> actual) {
        Map<Integer, Object> expectedOrdinary = new HashMap<>(expected);
        Map<Integer, Object> actualOrdinary = new HashMap<>(actual);
        for (int field : new int[] {19, 20, 30, 31, 32}) {
            expectedOrdinary.remove(field);
            actualOrdinary.remove(field);
        }
        assertFieldsEqual(expectedOrdinary, actualOrdinary);
    }

    private static void assertNoSpatialFields(Map<Integer, Object> fields) {
        for (int field : new int[] {19, 20, 30, 31, 32}) assertFalse(fields.containsKey(field));
    }

    private static HudSpatialGeometry.Result realSpatialGeometry(long nowElapsedMs) {
        NavigatorRouteData route = spatialTestRoute(nowElapsedMs);
        return new HudSpatialGeometry(route).resolve(spatialTestStep(route, nowElapsedMs),
                new VehiclePosition(0.00005, 0.0005, nowElapsedMs,
                        5f, 90f, 10f, 5f, false),
                new DirectTbtFrame.SourceDistance(150, nowElapsedMs), nowElapsedMs);
    }

    private static NavigatorRouteData spatialTestRoute(long nowElapsedMs) {
        NavigatorRouteData.Header header = new NavigatorRouteData.Header(
                "waze-5.25.0.1", 1, 1, "route", Math.max(0, nowElapsedMs - 1_000));
        return new NavigatorRouteData(header, true, true,
                new double[] {0, 0, 0, .001, .001, .001, .001, .002},
                new NavigatorRouteData.Maneuver[] {
                        new NavigatorRouteData.Maneuver("0", 0, 0, 0, -1, 11, 12,
                                "turn", "", Double.NaN, Double.NaN, 0, false)
                });
    }

    private static NavigatorRouteStore.CurrentStep spatialTestStep(
            NavigatorRouteData route, long nowElapsedMs) {
        return new NavigatorRouteStore.CurrentStep(route.header(), 1,
                Math.max(0, nowElapsedMs - 500), null, true, "0", 0, -1,
                "turn", "", null, null, 11, 12, 3, 4, "source_indices");
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
