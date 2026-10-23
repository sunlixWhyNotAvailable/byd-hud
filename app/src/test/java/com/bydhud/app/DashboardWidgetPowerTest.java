package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DashboardWidgetPowerTest {
    @Test public void vehiclePowerControlsStartCloseAndRestartWithoutChangingPreference() {
        DashboardWidgetLifecyclePolicy.State state = DashboardWidgetLifecyclePolicy.initialState();
        for (int raw : new int[]{-1, 255, -2_147_482_648}) {
            int unknown = DashboardWidgetLifecyclePolicy.retainVehiclePower(-1, raw);
            assertEquals(-1, unknown);
            assertTrue(DashboardWidgetLifecyclePolicy.visibleForVehiclePower(true, unknown));
            assertEquals(0, DashboardWidgetLifecyclePolicy.retainVehiclePower(0, raw));
            assertEquals(2, DashboardWidgetLifecyclePolicy.retainVehiclePower(2, raw));
        }
        assertFalse(DashboardWidgetLifecyclePolicy.visibleForVehiclePower(true, 0));
        for (int raw : new int[]{1, 2, 3, 4}) {
            assertTrue(DashboardWidgetLifecyclePolicy.visibleForVehiclePower(true, raw));
            assertFalse(DashboardWidgetLifecyclePolicy.visibleForVehiclePower(false, raw));
        }
        DashboardWidgetLifecyclePolicy.Transition on = reconcile(state, 2);
        assertEquals(DashboardWidgetLifecyclePolicy.Action.START, on.action);
        state = DashboardWidgetLifecyclePolicy.serviceCreated(on.state, 1L);
        DashboardWidgetLifecyclePolicy.Transition off = reconcile(state, 0);
        assertEquals(DashboardWidgetLifecyclePolicy.Action.CLOSE, off.action);
        assertEquals(DashboardWidgetLifecyclePolicy.ServiceStart.SHOW,
                DashboardWidgetLifecyclePolicy.serviceStart(
                        DashboardWidgetLifecyclePolicy.visibleForVehiclePower(true, -1),
                        DashboardWidgetLifecyclePolicy.Permission.GRANTED, false, false, false));
        DashboardWidgetLifecyclePolicy.Transition destroyed =
                DashboardWidgetLifecyclePolicy.serviceDestroyed(off.state, 1L,
                        DashboardWidgetLifecyclePolicy.visibleForVehiclePower(true, 0),
                        DashboardWidgetLifecyclePolicy.Permission.GRANTED, false);
        assertEquals(DashboardWidgetLifecyclePolicy.Action.NONE, destroyed.action);
        int retainedOff = DashboardWidgetLifecyclePolicy.retainVehiclePower(0, -1);
        assertEquals(DashboardWidgetLifecyclePolicy.Action.NONE, reconcile(destroyed.state, retainedOff).action);
        assertEquals(DashboardWidgetLifecyclePolicy.Action.START,
                reconcile(DashboardWidgetLifecyclePolicy.initialState(), -1).action);
        assertEquals(DashboardWidgetLifecyclePolicy.Action.START, reconcile(destroyed.state, 3).action);
    }

    private static DashboardWidgetLifecyclePolicy.Transition reconcile(
            DashboardWidgetLifecyclePolicy.State state, int raw) {
        return DashboardWidgetLifecyclePolicy.reconcile(state,
                DashboardWidgetLifecyclePolicy.visibleForVehiclePower(true, raw),
                DashboardWidgetLifecyclePolicy.Permission.GRANTED, false, false, false, true);
    }
}
