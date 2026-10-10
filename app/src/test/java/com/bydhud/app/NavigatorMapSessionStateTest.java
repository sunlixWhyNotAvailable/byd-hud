package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NavigatorMapSessionStateTest {

    @Test public void cadenceUsesFreshCropsAndResetsOnChangeErrorsProfilesAndExpiry() {
        NavigatorMapSessionState state = new NavigatorMapSessionState();
        state.activate("com.waze", 1, "s");
        assertEquals(200, state.requestIntervalMs());
        for (long now = 0; now <= 2000; now += 200) {
            // The surface changes every time, while the final crop remains identical.
            state.publish("com.waze", 1, "s", "surface-" + now, "crop-a",
                    new byte[]{1}, HudMapProfile.Source.WAZE, 1, now, now);
            assertEquals(now < 2000 ? 200 : 1000, state.requestIntervalMs());
        }
        state.refreshSameInput("com.waze", 1, "s", "surface-2000",
                HudMapProfile.Source.WAZE, 1, 3000, 3000);
        assertEquals(1000, state.requestIntervalMs());
        state.publish("com.waze", 1, "s", "surface-new", "crop-b",
                new byte[]{2}, HudMapProfile.Source.WAZE, 1, 4000, 4000);
        assertEquals(200, state.requestIntervalMs());
        state.refreshSameInput("com.waze", 1, "s", "surface-new",
                HudMapProfile.Source.WAZE, 1, 6000, 6000);
        assertEquals(1000, state.requestIntervalMs());
        state.resetCadence(); // Failed or timed-out request: silence is not a stable crop.
        state.refreshSameInput("com.waze", 1, "s", "surface-new",
                HudMapProfile.Source.WAZE, 1, 6200, 6200);
        assertEquals(200, state.requestIntervalMs());
        state.publish("com.waze", 1, "s", "surface-new", "crop-b",
                new byte[]{2}, HudMapProfile.Source.WAZE, 2, 8200, 8200);
        assertEquals(200, state.requestIntervalMs());
        state.refreshSameInput("com.waze", 1, "s", "surface-new",
                HudMapProfile.Source.WAZE, 2, 10200, 10200);
        assertEquals(1000, state.requestIntervalMs());
        assertTrue(state.expireIfStale(13700));
        assertEquals(200, state.requestIntervalMs());
        state.activate("com.waze", 2, "replacement");
        assertEquals(NavigatorMapSessionState.FrameUpdate.REJECTED,
                state.publish("com.waze", 1, "s", "late", "crop-b", new byte[]{2}, 14000, 14000));
        assertEquals(200, state.requestIntervalMs());
    }

}
