package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class TextTransliterationUiSourceContractTest {
    @Test
    public void preferenceAndComposeDropdownUseApprovedModesAndCopy() throws IOException {
        String prefs = sourcePath("app/src/main/java/com/bydhud/app/HudPrefs.java");
        String activity = sourcePath("app/src/main/java/com/bydhud/app/MainActivity.java");
        String compose = sourcePath("app/src/main/java/com/bydhud/app/BydHudRuntimeCompose.kt");

        assertTrue(prefs.contains("KEY_TEXT_TRANSLITERATION = \"text_transliteration\""));
        assertEquals(0, HudPrefs.TRANSLITERATION_OFF);
        assertEquals(1, HudPrefs.TRANSLITERATION_UKRAINIAN);
        assertEquals(2, HudPrefs.TRANSLITERATION_UNIVERSAL);
        assertTrue(prefs.contains("prefs(context).getInt(KEY_TEXT_TRANSLITERATION, TRANSLITERATION_OFF)"));
        assertTrue(prefs.contains("markOutputOptionChanged(KEY_TEXT_TRANSLITERATION)"));
        assertTrue(activity.contains("HudPrefs.transliterationMode(this)"));
        assertTrue(activity.contains("composeSetTransliterationMode(int mode)"));
        assertTrue(activity.contains("HudPrefs.setTransliterationMode(this, mode)"));
        assertTrue(compose.contains("textTransliteration = \"Text transliteration\""));
        assertTrue(compose.contains("textTransliteration = \"Транслітерація тексту\""));
        assertTrue(compose.contains("listOf(\"Off\", \"Ukrainian\", \"Universal\")"));
        assertTrue(compose.contains("listOf(\"Вимкнено\", \"Українська\", \"Універсальна\")"));
        assertTrue(compose.contains("selectedIndex = snapshot.transliterationMode"));
        assertTrue(compose.contains("activity.composeSetTransliterationMode(mode)"));
        assertTrue(indexOf(compose, "copy.streetOutput") < indexOf(compose,
                "copy.textTransliteration"));
        assertTrue(indexOf(compose, "copy.textTransliteration") < indexOf(compose,
                "copy.distanceOutput"));
    }

    private static String sourcePath(String relativePath) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve(relativePath);
        if (!Files.isRegularFile(file) && relativePath.startsWith("app/")) {
            file = root.resolve(relativePath.substring("app/".length()));
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }

    private static int indexOf(String source, String marker) {
        int index = source.indexOf(marker);
        assertTrue("missing marker " + marker, index >= 0);
        return index;
    }
}
