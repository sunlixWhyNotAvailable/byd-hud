package com.bydhud.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class NativeHudSpeedLimitTest {
    @Test public void missingLimitRetainsOnlyTheCurrentRouteValue() {
        NativeHudSpeedLimit state = new NativeHudSpeedLimit();
        assertEquals(0, state.update(true, "navigator", 1L, 0));
        assertEquals(60, state.update(true, "navigator", 1L, 60));
        assertEquals(60, state.update(true, "navigator", 1L, 0));
        assertEquals(55, state.update(true, "navigator", 1L, 55));
        assertEquals(0, state.update(true, "navigator", 2L, 0));
        assertEquals(40, state.update(true, "navigator", 2L, 40));
        assertEquals(0, state.update(true, "other", 2L, 0));
    }

    @Test public void modeExitAndRouteEndDiscardTheRetainedLimit() {
        NativeHudSpeedLimit state = new NativeHudSpeedLimit();
        assertEquals(60, state.update(true, "navigator", 1L, 60));
        assertEquals(0, state.update(false, "navigator", 1L, 0));
        assertEquals(0, state.update(true, "navigator", 1L, 0));
        assertEquals(30, state.update(true, "navigator", 1L, 30));
        state.reset();
        assertEquals(0, state.update(true, "navigator", 1L, 0));
    }
}
