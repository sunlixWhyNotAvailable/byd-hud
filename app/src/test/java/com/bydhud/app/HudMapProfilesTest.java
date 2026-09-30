package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
public final class HudMapProfilesTest {
    private Context context;

    @Before
    public void clearProfiles() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("byd_hud_prefs", Context.MODE_PRIVATE).edit()
                .remove("map_profiles")
                .remove("map_profiles_revision")
                .apply();
    }

    @After
    public void restoreProfiles() {
        clearProfiles();
    }

    @Test
    public void savesEditsRejectsDuplicateSourcesAndDeletesToDefaults() {
        HudMapProfile.Source maps = HudMapProfile.Source.GOOGLE_MAPS;
        HudMapProfile.Source waze = HudMapProfile.Source.WAZE;
        HudMapProfile editedMaps = HudMapProfile.defaults(maps).withX(-18).withY(9)
                .withScale(140);
        assertTrue(HudMapProfiles.save(context, null, editedMaps));
        assertEquals(1L, HudMapProfiles.revision(context));
        assertEquals(editedMaps, HudMapProfiles.resolve(context, maps));
        assertFalse(HudMapProfiles.save(context, null, HudMapProfile.defaults(maps)));
        assertEquals(1L, HudMapProfiles.revision(context));

        assertTrue(HudMapProfiles.save(context, null, HudMapProfile.defaults(waze)));
        assertEquals(2L, HudMapProfiles.revision(context));
        assertFalse(HudMapProfiles.save(context, maps,
                editedMaps.withSource(waze)));
        assertTrue(HudMapProfiles.save(context, maps, editedMaps.withX(-20)));
        assertEquals(3L, HudMapProfiles.revision(context));
        assertEquals(-20, HudMapProfiles.resolve(context, maps).x);
        assertEquals(HudMapProfile.defaults(waze), HudMapProfiles.resolve(context, waze));

        assertTrue(HudMapProfiles.delete(context, waze));
        assertEquals(4L, HudMapProfiles.revision(context));
        assertEquals(HudMapProfile.defaults(waze), HudMapProfiles.resolve(context, waze));
        assertFalse(HudMapProfiles.delete(context, waze));
    }
}
