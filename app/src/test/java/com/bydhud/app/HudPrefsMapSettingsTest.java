package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
public final class HudPrefsMapSettingsTest {
    private Context context;

    @After
    public void clearMapPreferences() {
        if (context != null) {
            context.getSharedPreferences("byd_hud_prefs", Context.MODE_PRIVATE).edit()
                    .remove("map_mode")
                    .remove("map_preset")
                    .remove("map_custom_x")
                    .remove("map_custom_y")
                    .remove("map_custom_scale")
                    .remove("map_custom_lane_x")
                    .remove("map_custom_lane_y")
                    .remove("map_custom_lane_scale")
                    .apply();
        }
    }

    @Test
    public void defaultsPersistedStateAndOutputRevisionAreStable() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("byd_hud_prefs", Context.MODE_PRIVATE).edit().clear().apply();

        assertEquals(HudMapSettings.defaults(), HudPrefs.mapSettings(context));
        int before = HudPrefs.outputOptionsRevision();
        HudMapSettings custom = HudMapSettings.defaults().withMode(HudMapSettings.EXPERIMENTAL)
                .withValue(HudMapSettings.CONTROL_MAP_Y, -11)
                .withValue(HudMapSettings.CONTROL_LANE_SCALE, 76);
        HudPrefs.setMapSettings(context, custom);

        assertEquals(custom, HudPrefs.mapSettings(context));
        assertTrue(HudPrefs.outputOptionsRevision() > before);
    }

    @Test
    public void customGeometrySurvivesPresetSwitchAndRestartRead() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("byd_hud_prefs", Context.MODE_PRIVATE).edit().clear().apply();
        HudMapSettings custom = HudMapSettings.defaults().withMode(HudMapSettings.EXPERIMENTAL)
                .withValue(HudMapSettings.CONTROL_MAP_X, 22);
        HudMapSettings right = custom.withPreset(HudMapSettings.PRESET_LARGER_RIGHT);
        HudPrefs.setMapSettings(context, right);
        assertEquals(new HudMapSettings.Geometry(145, 12, 110, 0, 30, 70),
                HudPrefs.mapSettings(context).geometry());

        HudMapSettings restored = HudPrefs.mapSettings(context);
        assertEquals(custom.custom, restored.custom);
        HudPrefs.setMapSettings(context, restored.withPreset(HudMapSettings.PRESET_CUSTOM));
        assertEquals(custom.custom, HudPrefs.mapSettings(context).geometry());
    }
}
