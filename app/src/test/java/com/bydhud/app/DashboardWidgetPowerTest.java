package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DashboardWidgetPowerTest {
    @Test public void powerReadingsRetainLastKnownStateAndOnlyOffSuppressesVisibility() {
        int power = -1;
        for (int invalid : new int[]{-1, 255, -2_147_482_648}) {
            power = DashboardWidgetLifecyclePolicy.retainVehiclePower(power, invalid);
            assertEquals(-1, power);
            assertTrue(DashboardWidgetLifecyclePolicy.visibleForVehiclePower(true, power));
        }

        power = DashboardWidgetLifecyclePolicy.retainVehiclePower(power, 0);
        assertEquals(0, power);
        assertFalse(DashboardWidgetLifecyclePolicy.visibleForVehiclePower(true, power));
        for (int invalid : new int[]{-1, 255, -2_147_482_648}) {
            power = DashboardWidgetLifecyclePolicy.retainVehiclePower(power, invalid);
            assertEquals(0, power);
            assertFalse(DashboardWidgetLifecyclePolicy.visibleForVehiclePower(true, power));
        }

        for (int reading : new int[]{2, 1, 3, 4}) {
            power = DashboardWidgetLifecyclePolicy.retainVehiclePower(power, reading);
            assertEquals(reading, power);
            assertTrue(DashboardWidgetLifecyclePolicy.visibleForVehiclePower(true, power));
            power = DashboardWidgetLifecyclePolicy.retainVehiclePower(power, -1);
            assertEquals(reading, power);
            assertTrue(DashboardWidgetLifecyclePolicy.visibleForVehiclePower(true, power));
            assertFalse(DashboardWidgetLifecyclePolicy.visibleForVehiclePower(false, power));
        }
    }
}
