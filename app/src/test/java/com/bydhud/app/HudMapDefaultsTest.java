package com.bydhud.app;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class HudMapDefaultsTest {
    @Test public void profilesUseTheOwnerAcceptedCalibration() {
        assertEquals(new HudMapProfile(HudMapProfile.Source.GOOGLE_MAPS, -3, -10, 60),
                HudMapProfile.defaults(HudMapProfile.Source.GOOGLE_MAPS));
        assertEquals(new HudMapProfile(HudMapProfile.Source.WAZE, 0, -20, 75),
                HudMapProfile.defaults(HudMapProfile.Source.WAZE));
        assertEquals(new HudMapProfile(HudMapProfile.Source.WAZE_SURFACE, 0, -20, 75),
                HudMapProfile.defaults(HudMapProfile.Source.WAZE_SURFACE));
    }

    @Test public void compositionResetPreservesModeAndCadence() {
        HudMapSettings original = new HudMapSettings(HudMapSettings.EXPERIMENTAL,
                HudMapSettings.PRESET_LARGER_RIGHT,
                new HudMapSettings.Geometry(5, 6, 70, 8, 9, 80), 5);
        HudMapSettings restored = original.withDefaultGeometry();
        assertEquals(HudMapSettings.PRESET_CUSTOM, restored.preset);
        assertEquals(HudMapSettings.EXPERIMENTAL, restored.mode);
        assertEquals(5, restored.updateRateHz);
        assertEquals(new HudMapSettings.Geometry(142, 12, 110, -6, -31, 65), restored.geometry());
        assertEquals(restored, restored.withDefaultGeometry());
        assertEquals(new HudMapSettings.Geometry(5, 6, 70, 8, 9, 80), original.custom);
        assertEquals(HudMapSettings.OFF, HudMapSettings.defaults().mode);
        assertEquals(1, HudMapSettings.defaults().updateRateHz);
        assertEquals(restored.geometry(), HudMapSettings.defaults().geometry());
    }

    @Test public void existingCustomAndLegacyLayoutsKeepTheirGeometry() {
        HudMapSettings.Geometry custom = new HudMapSettings.Geometry(5, 6, 70, 8, 9, 80);
        assertEquals(custom, new HudMapSettings(HudMapSettings.NATIVE,
                HudMapSettings.PRESET_CUSTOM, custom).geometry());
        assertEquals(new HudMapSettings.Geometry(142, 12, 110, -6, -31, 90),
                new HudMapSettings(HudMapSettings.EXPERIMENTAL,
                        HudMapSettings.PRESET_LARGER_RIGHT, custom).geometry());
        assertEquals(new HudMapSettings.Geometry(0, -8, 85, 0, 30, 70),
                new HudMapSettings(HudMapSettings.EXPERIMENTAL, 2, custom).geometry());
    }
}
