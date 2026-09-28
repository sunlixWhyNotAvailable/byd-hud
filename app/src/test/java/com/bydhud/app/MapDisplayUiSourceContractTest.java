package com.bydhud.app;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Checks the production map editor stays connected to persisted settings and the live runtime. */
public final class MapDisplayUiSourceContractTest {
    @Test
    public void mapEditorFollowsSpeedLimitAndBindsAllSixControls() throws Exception {
        String compose = source("BydHudRuntimeCompose.kt");
        int speedLimit = compose.indexOf("optionsSection(\"speed-limit\"");
        int mapDisplay = compose.indexOf("optionsSection(\"map-display\"");
        int wazeFeatures = compose.indexOf("optionsSection(\"waze-features\"");
        assertTrue(speedLimit >= 0 && speedLimit < mapDisplay && mapDisplay < wazeFeatures);

        String map = compose.substring(mapDisplay, wazeFeatures);
        assertTrue(map.contains("HudHelpTopicId.MapOutputMode"));
        assertTrue(map.contains("mapSettings.mode == HudMapSettings.EXPERIMENTAL"));
        assertTrue(map.contains("onClick = { runAction { activity.composeStartMapLive() } }"));
        assertTrue(map.contains("onClick = { runAction { activity.composeStopMapLive() } }"));
        assertTrue(map.contains("snapshot.mapLive?.running == true"));
        assertTrue(map.contains("copy.hudCheckRunning else copy.hudCheckStopped"));
        assertTrue(map.contains("Вивід використовує ваші налаштування HUD."));

        for (String control : new String[] {"MAP_X", "MAP_Y", "MAP_SCALE", "LANE_X", "LANE_Y", "LANE_SCALE"}) {
            assertTrue(control, map.contains("HudMapSettings.CONTROL_" + control));
        }
        assertTrue(map.contains("enabled = mapEditorEnabled, showTicks = false"));
        assertTrue(map.contains("enabled = mapEditorEnabled, showTicks = true"));
        assertTrue(map.contains("− makes smaller, + makes larger"));
    }

    @Test
    public void numberLineUsesExistingImmediateNumericAndSliderEditor() throws Exception {
        String compose = source("BydHudRuntimeCompose.kt");
        String mapRow = between(compose, "private fun MapGeometryRow(", "private fun StorageDayRow(");
        assertTrue(mapRow.contains("WidgetNumberLine(title, hint, value, minimum..maximum, suffix"));
        assertTrue(mapRow.contains("showTicks)"));
        assertTrue(mapRow.contains("onValueChange(it)"));
    }

    @Test
    public void mapChangesUseTheSharedPersistedPreferenceApi() throws Exception {
        String activity = source("MainActivity.java");
        String prefs = source("HudPrefs.java");
        assertTrue(activity.contains("HudPrefs.setMapSettings(this, settings)"));
        assertTrue(activity.contains("NavHudLiveSender.refreshMapLiveSettings(reason)"));
        assertTrue(prefs.contains("static HudMapSettings mapSettings(Context context)"));
        assertTrue(prefs.contains("static void setMapSettings(Context context, HudMapSettings settings)"));
        assertTrue(prefs.contains("markOutputOptionChanged(\"map_settings\")"));
    }

    private static String source(String name) throws Exception {
        return new String(Files.readAllBytes(projectRoot().resolve(
                "app/src/main/java/com/bydhud/app/" + name)), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
    }

    private static Path projectRoot() {
        Path root = Paths.get(System.getProperty("user.dir"));
        return Files.isDirectory(root.resolve("app")) ? root : root.getParent();
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        if (from < 0 || to <= from) throw new AssertionError("missing source section: " + start);
        return source.substring(from, to);
    }
}
