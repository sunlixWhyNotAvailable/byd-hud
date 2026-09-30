package com.bydhud.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class HudMapProfileTest {
    @Test
    public void defaultsAndEditsStayWithinTheFramingContract() {
        HudMapProfile defaults = HudMapProfile.defaults(HudMapProfile.Source.GOOGLE_MAPS);
        assertEquals(0, defaults.x);
        assertEquals(0, defaults.y);
        assertEquals(100, defaults.scale);
        assertEquals("app.revanced.android.apps.maps",
                HudMapProfile.Source.GOOGLE_MAPS.packageName());
        assertEquals("com.waze", HudMapProfile.Source.WAZE_SURFACE.packageName());

        HudMapProfile clamped = new HudMapProfile(
                HudMapProfile.Source.WAZE, -101, 101, 301);
        assertEquals(-100, clamped.x);
        assertEquals(100, clamped.y);
        assertEquals(300, clamped.scale);
        assertEquals(50, clamped.withScale(0).scale);
        assertEquals(HudMapProfile.Source.WAZE_SURFACE,
                clamped.withSource(HudMapProfile.Source.WAZE_SURFACE).source);
    }
}
