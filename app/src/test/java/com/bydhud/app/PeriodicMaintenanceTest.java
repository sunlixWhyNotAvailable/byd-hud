package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PeriodicMaintenanceTest {
    @Test public void onlyConfirmedOffPausesAndWakeRequiresNewConfirmation() {
        HudRuntimeState.PeriodicMaintenance state = new HudRuntimeState.PeriodicMaintenance();
        state.onScreen(true);
        assertFalse(state.isPaused());
        state.onPower(2);
        assertFalse(state.isPaused());
        state.onPower(0);
        assertTrue(state.isPaused());
        state.onScreen(false);
        assertFalse(state.isPaused());
        state.onScreen(true);
        assertFalse(state.isPaused());

        state.onScreen(false);
        state.onPower(0);
        assertFalse(state.isPaused());
        state.onScreen(true);
        assertTrue(state.isPaused());
        state.onPower(-1);
        assertFalse(state.isPaused());
        state.onPower(0);
        assertTrue(state.isPaused());
        state.onPower(1);
        assertFalse(state.isPaused());
    }
}
