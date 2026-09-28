package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class HudMapSettingsTest {
    @Test
    public void defaultsStartOffWithApprovedCustomGeometry() {
        HudMapSettings settings = HudMapSettings.defaults();
        assertEquals(HudMapSettings.OFF, settings.mode);
        assertEquals(HudMapSettings.PRESET_CUSTOM, settings.preset);
        assertEquals(new HudMapSettings.Geometry(0, -8, 85, 0, 30, 70), settings.geometry());
    }

    @Test
    public void selectingPresetsKeepsCustomAndEditingCopiesVisiblePresetWithoutJump() {
        HudMapSettings custom = HudMapSettings.defaults().withMode(HudMapSettings.EXPERIMENTAL)
                .withValue(HudMapSettings.CONTROL_MAP_X, -25);
        HudMapSettings right = custom.withPreset(HudMapSettings.PRESET_LARGER_RIGHT);
        assertEquals(new HudMapSettings.Geometry(145, 12, 110, 0, 30, 70), right.geometry());
        assertEquals(custom.custom, right.custom);

        HudMapSettings edited = right.withValue(HudMapSettings.CONTROL_LANE_Y, 35);
        assertEquals(HudMapSettings.PRESET_CUSTOM, edited.preset);
        assertEquals(new HudMapSettings.Geometry(145, 12, 110, 0, 35, 70), edited.geometry());
        assertNotEquals(right.geometry(), edited.geometry());

        HudMapSettings switchedBack = edited.withPreset(HudMapSettings.PRESET_LARGER_RIGHT)
                .withPreset(HudMapSettings.PRESET_CUSTOM);
        assertEquals(edited.custom, switchedBack.geometry());
    }

    @Test
    public void geometryClampsEveryControlAndSettingsHaveValueEquality() {
        HudMapSettings.Geometry bounded = new HudMapSettings.Geometry(
                -900, 900, 0, 900, -900, 1000);
        assertEquals(new HudMapSettings.Geometry(-250, 60, 40, 50, -50, 100), bounded);
        HudMapSettings first = new HudMapSettings(HudMapSettings.EXPERIMENTAL,
                HudMapSettings.PRESET_CUSTOM, bounded);
        HudMapSettings second = new HudMapSettings(HudMapSettings.EXPERIMENTAL,
                HudMapSettings.PRESET_CUSTOM,
                new HudMapSettings.Geometry(-250, 60, 40, 50, -50, 100));
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertTrue(first.diagnostics().startsWith("map(mode=2,preset=0,custom=-250,60,40,50,-50,100)"));
    }
}
