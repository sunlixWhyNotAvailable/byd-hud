package com.bydhud.app;

import org.junit.Test;
import org.json.JSONObject;
import com.bydhud.mapcapture.MapPalette;
import java.util.EnumMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

public class HudMapDefaultsTest {
    @Test public void profilePaletteSurvivesCatalogRoundTripAndGeometryEdits() throws Exception {
        MapPalette palette = new MapPalette(0xff123456, 0xffabcdef, 0xffee1122, 0xff010203);
        HudMapProfile edited = HudMapProfile.defaults(HudMapProfile.Source.GOOGLE_MAPS).withPalette(palette);
        assertEquals(palette, edited.withX(17).withY(-23).withScale(80).palette);
        assertEquals(palette, edited.withSource(HudMapProfile.Source.WAZE).palette);
        assertNotEquals(edited, HudMapProfile.defaults(edited.source));
        EnumMap<HudMapProfile.Source,HudMapProfile> catalog = new EnumMap<>(HudMapProfile.Source.class);
        catalog.put(edited.source, edited);
        catalog.put(HudMapProfile.Source.WAZE, HudMapProfile.defaults(HudMapProfile.Source.WAZE));
        Path file = Files.createTempFile("hud-profile", ".json");
        try {
            Files.write(file, HudMapProfiles.encode(catalog).getBytes(StandardCharsets.UTF_8));
            assertEquals(catalog, HudMapProfiles.decode(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)));
        } finally { Files.delete(file); }
        assertEquals(edited, HudMapProfile.fromJson(new JSONObject(edited.toJson().toString())));
    }

    @Test public void oldProfileKeepsGeometryAndReceivesDefaultColors() {
        HudMapProfile profile = HudMapProfiles.decode("[{\"source\":\"WAZE_SURFACE\",\"x\":24,\"y\":-18,\"scale\":90}]")
                .get(HudMapProfile.Source.WAZE_SURFACE);
        assertEquals(new HudMapProfile(profile.source, 24, -18, 90), profile);
        assertEquals(0xff41464c, profile.palette.road);
        assertEquals(0xff4c5258, profile.palette.casing());
        assertEquals(0xff66b5ff, profile.palette.route);
        assertEquals(0xffffb81a, profile.palette.marker);
        assertEquals(0xff0a0c0e, profile.palette.background);
    }

    @Test public void paletteWireIsVersionedAndOpaque() {
        MapPalette palette = new MapPalette(0x12123456, 0x00abcdef, 0x77ee1122, 0x00010203);
        assertEquals(palette, MapPalette.decode(palette.encode()));
        assertEquals(0xff123456, palette.road);
        assertEquals(MapPalette.DEFAULT, MapPalette.decode(null));
        assertThrows(IllegalArgumentException.class, () -> MapPalette.decode(new int[]{2, 1, 2, 3, 4}));
        assertThrows(IllegalArgumentException.class, () -> MapPalette.decode(new int[]{1, 2}));
        assertEquals(0xffffffff, new MapPalette(0xfffafbfc, 0, 0, 0).casing());
    }
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
