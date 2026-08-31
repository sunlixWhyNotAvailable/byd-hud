package com.bydhud.app;

import static org.junit.Assert.*;

import org.junit.Test;

public final class HudCheckDiagnosticsTest {
    @Test public void heldCaseLogsOnlyChangedPlaneResultOrReason() {
        HudCheckDiagnostics log = new HudCheckDiagnostics();
        HudCheckState sample = new HudCheckState().toggleRun();
        assertNotNull(log.changed(sample, "instrument", -1, "proxy-unavailable"));
        for (int i = 0; i < 100; i++) {
            assertNull(log.changed(sample, "instrument", -1, "proxy-unavailable"));
        }
        assertNotNull(log.changed(sample, "roadinfo", 1, "sent"));
        assertNotNull(log.changed(sample, "instrument", -1, "invoke-failed"));
        assertNotNull(log.changed(sample, "instrument", 1, "sent"));
        assertNull(log.changed(sample, "instrument", 1, "sent"));
    }

    @Test public void fullExtendedCycleAndRestartReportEachCaseEvenWhenOutcomeIsSame() {
        HudCheckDiagnostics log = new HudCheckDiagnostics();
        HudCheckState sample = new HudCheckState().selectMode(HudCheckState.Mode.EXTENDED).toggleRun();
        for (int i = 0; i < HudCheckState.extendedCount() * 2; i++) {
            String line = log.changed(sample, "instrument", -1, "proxy-unavailable");
            assertNotNull(line);
            assertTrue(line.contains("case=extended-" + (sample.extendedIndex + 1) + " "));
            assertNull(log.changed(sample, "instrument", -1, "proxy-unavailable"));
            sample = sample.tick();
        }
        assertNull(log.changed(sample.stop(), "instrument", -1, "proxy-unavailable"));
        assertNotNull(log.changed(sample, "instrument", -1, "proxy-unavailable"));
        log.reset();
        assertNotNull(log.changed(sample, "instrument", -1, "proxy-unavailable"));
    }

    @Test public void sampleIdentityTracksBasicSelectionsButNotAutomaticPause() {
        HudCheckState basic = new HudCheckState().toggleRun();
        for (HudCheckState.Field field : HudCheckState.Field.values()) {
            assertNotEquals(HudCheckDiagnostics.sampleKey(basic),
                    HudCheckDiagnostics.sampleKey(basic.step(field, 1)));
        }
        assertNotEquals(HudCheckDiagnostics.sampleKey(basic),
                HudCheckDiagnostics.sampleKey(basic.withManeuverBitmap(true)));
        assertEquals(HudCheckDiagnostics.sampleKey(basic),
                HudCheckDiagnostics.sampleKey(basic.withAutomatic(false)));
        String line = new HudCheckDiagnostics().changed(basic, "roadinfo", 1, "sent\nnext");
        assertFalse(line.contains("\n"));
        assertFalse(line.contains("ТЕСТ"));
        assertFalse(line.contains("Continue straight"));
    }
}
