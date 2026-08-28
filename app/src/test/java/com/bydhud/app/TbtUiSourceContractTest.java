package com.bydhud.app;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class TbtUiSourceContractTest {
    @Test
    public void productionPortsBothPreviewTbtOptionsAndDefaults() throws IOException {
        String compose = source("app/src/main/java/com/bydhud/app/BydHudRuntimeCompose.kt");
        String activity = source("app/src/main/java/com/bydhud/app/MainActivity.java");
        String prefs = source("app/src/main/java/com/bydhud/app/HudPrefs.java");

        assertTrue(compose.contains("tbtWithoutHudOutput"));
        assertTrue(compose.contains("switchToTbtOnHudStart"));
        assertTrue(compose.contains("Create a TBT card even for an active navigator session without HUD output"));
        assertTrue(compose.contains("Формувати TBT-картку навіть для активної сесії навігатора без виводу на HUD"));
        assertTrue(activity.contains("composeSetTbtWithoutHudOutputEnabled"));
        assertTrue(activity.contains("composeSetSwitchToTbtOnHudStartEnabled"));
        assertTrue(prefs.contains("getBoolean(KEY_TBT_WITHOUT_HUD_OUTPUT, true)"));
        assertTrue(prefs.contains("getBoolean(KEY_SWITCH_TO_TBT_ON_HUD_START, true)"));
    }

    @Test
    public void roadInfoUsesAcceptedF2Constant() throws IOException {
        String road = source("app/src/main/java/com/bydhud/app/HudRoadPayload.java");
        assertTrue(road.contains("writeInt32(road, 2, 2)"));
    }

    @Test
    public void uiHudSwitchLetsTheRuntimeSerializeOwnerReplacement() throws IOException {
        String activity = source("app/src/main/java/com/bydhud/app/MainActivity.java");
        assertTrue(activity.contains("returnPreviousHudAppToMain(previousHudPackage, normalized)"));
        assertTrue(!activity.contains(
                "stop(previousHudPackage, \"ui-switch\", true)"));
    }

    private static String source(String relativePath) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve(relativePath);
        if (!Files.isRegularFile(file) && relativePath.startsWith("app/")) {
            file = root.resolve(relativePath.substring("app/".length()));
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }
}
