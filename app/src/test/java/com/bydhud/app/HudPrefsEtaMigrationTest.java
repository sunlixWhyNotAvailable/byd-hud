package com.bydhud.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class HudPrefsEtaMigrationTest {
    @Test public void legacyOffBecomesOffAndNextStop() {
        HudPrefs.EtaPreferenceMigration migration = HudPrefs.etaPreferenceMigration(
                false, HudPrefs.ETA_OUTPUT_OFF,
                true, HudPrefs.ROUTE_METRICS_OFF,
                false, false, false, false);

        assertEquals(HudPrefs.ETA_OUTPUT_OFF, migration.outputMode);
        assertEquals(HudPrefs.ROUTE_METRICS_NEXT_STOP, migration.routeMetricsMode);
    }

    @Test public void legacyNextAndWholeScopesBecomeAlternativeWithSameScope() {
        HudPrefs.EtaPreferenceMigration nextStop = HudPrefs.etaPreferenceMigration(
                false, HudPrefs.ETA_OUTPUT_OFF,
                true, HudPrefs.ROUTE_METRICS_NEXT_STOP,
                false, false, false, false);
        HudPrefs.EtaPreferenceMigration wholeRoute = HudPrefs.etaPreferenceMigration(
                false, HudPrefs.ETA_OUTPUT_OFF,
                true, HudPrefs.ROUTE_METRICS_WHOLE_ROUTE,
                false, false, false, false);

        assertEquals(HudPrefs.ETA_OUTPUT_ALTERNATIVE, nextStop.outputMode);
        assertEquals(HudPrefs.ROUTE_METRICS_NEXT_STOP, nextStop.routeMetricsMode);
        assertEquals(HudPrefs.ETA_OUTPUT_ALTERNATIVE, wholeRoute.outputMode);
        assertEquals(HudPrefs.ROUTE_METRICS_WHOLE_ROUTE, wholeRoute.routeMetricsMode);
    }

    @Test public void olderMetricAndWholeRouteBooleansMigrateTogether() {
        HudPrefs.EtaPreferenceMigration enabledWholeRoute = HudPrefs.etaPreferenceMigration(
                false, HudPrefs.ETA_OUTPUT_OFF,
                false, HudPrefs.ROUTE_METRICS_NEXT_STOP,
                false, true, false, true);
        HudPrefs.EtaPreferenceMigration noEnabledMetric = HudPrefs.etaPreferenceMigration(
                false, HudPrefs.ETA_OUTPUT_OFF,
                false, HudPrefs.ROUTE_METRICS_NEXT_STOP,
                false, false, false, true);

        assertEquals(HudPrefs.ETA_OUTPUT_ALTERNATIVE, enabledWholeRoute.outputMode);
        assertEquals(HudPrefs.ROUTE_METRICS_WHOLE_ROUTE, enabledWholeRoute.routeMetricsMode);
        assertEquals(HudPrefs.ETA_OUTPUT_OFF, noEnabledMetric.outputMode);
        assertEquals(HudPrefs.ROUTE_METRICS_NEXT_STOP, noEnabledMetric.routeMetricsMode);
    }

    @Test public void existingOutputModeIsPreservedWhileLegacyScopeIsNormalized() {
        HudPrefs.EtaPreferenceMigration existingMode = HudPrefs.etaPreferenceMigration(
                true, HudPrefs.ETA_OUTPUT_NATIVE,
                true, HudPrefs.ROUTE_METRICS_WHOLE_ROUTE,
                true, true, true, false);
        HudPrefs.EtaPreferenceMigration oldOffScope = HudPrefs.etaPreferenceMigration(
                true, HudPrefs.ETA_OUTPUT_ALTERNATIVE,
                true, HudPrefs.ROUTE_METRICS_OFF,
                false, false, false, false);

        assertEquals(HudPrefs.ETA_OUTPUT_NATIVE, existingMode.outputMode);
        assertEquals(HudPrefs.ROUTE_METRICS_WHOLE_ROUTE, existingMode.routeMetricsMode);
        assertEquals(HudPrefs.ETA_OUTPUT_ALTERNATIVE, oldOffScope.outputMode);
        assertEquals(HudPrefs.ROUTE_METRICS_NEXT_STOP, oldOffScope.routeMetricsMode);
    }
}
