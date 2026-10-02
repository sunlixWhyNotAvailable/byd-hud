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
                    .remove("map_update_rate_hz")
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
        assertEquals(1, HudPrefs.mapUpdateRateHz(context));
        int before = HudPrefs.outputOptionsRevision();
        HudMapSettings custom = HudMapSettings.defaults().withMode(HudMapSettings.EXPERIMENTAL)
                .withUpdateRateHz(5)
                .withValue(HudMapSettings.CONTROL_MAP_Y, -11)
                .withValue(HudMapSettings.CONTROL_LANE_SCALE, 76);
        HudPrefs.setMapSettings(context, custom);

        assertEquals(custom, HudPrefs.mapSettings(context));
        assertEquals(5, HudPrefs.mapUpdateRateHz(context));
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
        assertEquals(new HudMapSettings.Geometry(142, 12, 110, -6, -31, 90),
                HudPrefs.mapSettings(context).geometry());

        HudMapSettings restored = HudPrefs.mapSettings(context);
        assertEquals(custom.custom, restored.custom);
        HudPrefs.setMapSettings(context, restored.withPreset(HudMapSettings.PRESET_CUSTOM));
        assertEquals(custom.custom, HudPrefs.mapSettings(context).geometry());
    }
    @Test public void readsLegacyIntegersAndPreservesFractionsAcrossSave() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("byd_hud_prefs", Context.MODE_PRIVATE).edit().clear()
                .putInt("map_custom_x", 145).putInt("map_custom_lane_y", 30).commit();
        assertEquals(145f, HudPrefs.mapSettings(context).custom.mapX, 0.001f);
        HudMapSettings edited = HudPrefs.mapSettings(context)
                .withValue(HudMapSettings.CONTROL_MAP_X, -3.5f)
                .withValue(HudMapSettings.CONTROL_LANE_SCALE, 70.2f);
        HudPrefs.setMapSettings(context, edited);
        assertEquals(edited, HudPrefs.mapSettings(context));
        assertEquals(70.2f, HudPrefs.mapSettings(context).custom.laneScale, 0.001f);
    }

}
