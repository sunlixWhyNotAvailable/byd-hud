package com.bydhud.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class HudMapProfileTest {
    @Test
    public void defaultsAndEditsStayWithinTheFramingContract() {
        HudMapProfile defaults = HudMapProfile.defaults(HudMapProfile.Source.GOOGLE_MAPS);
        assertEquals(0f, defaults.x, 0.001f);
        assertEquals(0f, defaults.y, 0.001f);
        assertEquals(100f, defaults.scale, 0.001f);
        assertEquals("app.revanced.android.apps.maps",
                HudMapProfile.Source.GOOGLE_MAPS.packageName());
        assertEquals("com.waze", HudMapProfile.Source.WAZE_SURFACE.packageName());

        HudMapProfile clamped = new HudMapProfile(
                HudMapProfile.Source.WAZE, -101, 101, 301);
        assertEquals(-100f, clamped.x, 0.001f);
        assertEquals(100f, clamped.y, 0.001f);
        assertEquals(300f, clamped.scale, 0.001f);
        assertEquals(20f, clamped.withScale(0).scale, 0.001f);
        assertEquals(HudMapProfile.Source.WAZE_SURFACE,
                clamped.withSource(HudMapProfile.Source.WAZE_SURFACE).source);
    }
}
