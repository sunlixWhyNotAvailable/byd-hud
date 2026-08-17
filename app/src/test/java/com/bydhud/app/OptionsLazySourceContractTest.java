package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public final class OptionsLazySourceContractTest {
    @Test
    public void productionOptionsUseStableRowItemsAndTypedSections() throws IOException {
        String source = sourcePath("app/src/main/java/com/bydhud/app/BydHudRuntimeCompose.kt");
        String options = between(source, "private fun OptionsTab(", "private fun SetupReminderOverlay(");

        assertEquals(7, occurrences(options, "optionsSection("));
        assertFalse(options.contains("\n            Section("));
        assertTrue(source.contains("contentType = \"options-header\""));
        assertTrue(source.contains("contentType = \"options-row\""));
        assertTrue(source.contains("val key: String"));
        assertTrue(source.contains("item(key = \"$sectionKey:${row.key}\""));
        assertFalse(options.contains("forEachIndexed"));
        assertTrue(source.contains(".clearAndSetSemantics {}"));
    }

    @Test
    public void previewOptionsUseStableRowItemsAndTypedSections() throws IOException {
        String source = sourcePath("../byd-hud-compose-preview/compose-preview/src/main/java/com/bydhud/preview/MainActivity.kt");
        String options = between(source, "private fun MainTab(", "private data class PreviewOptionsRowSpec");

        assertEquals(7, occurrences(options, "previewOptionsSection("));
        assertFalse(options.contains("DashboardTile("));
        assertTrue(source.contains("contentType = \"options-header\""));
        assertTrue(source.contains("contentType = \"options-row\""));
        assertTrue(source.contains("item(key = \"$sectionKey:${row.key}\""));
        String compactSwitch = between(source, "private fun CompactSwitchLine(", "private fun PreviewDropdown(");
        String hudSwitch = between(source, "private fun HudSwitch(", "private fun Segmented(");
        assertTrue(compactSwitch.contains("role = Role.Switch"));
        assertTrue(compactSwitch.contains(".semantics(mergeDescendants = true) {}"));
        assertTrue(compactSwitch.contains("clearSemantics = true"));
        assertTrue(hudSwitch.contains("role = Role.Switch"));
        assertTrue(hudSwitch.contains("clearAndSetSemantics"));
    }

    private static String sourcePath(String relativePath) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve(relativePath).normalize();
        if (!Files.isRegularFile(file) && relativePath.startsWith("app/")) {
            file = root.resolve(relativePath.substring("app/".length()));
        }
        if (!Files.isRegularFile(file) && root.getFileName() != null
                && "app".equals(root.getFileName().toString())) {
            file = root.getParent().resolve(relativePath).normalize();
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }

    private static String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex + start.length());
        assertTrue("missing source markers", startIndex >= 0 && endIndex >= 0);
        return source.substring(startIndex, endIndex);
    }

    private static int occurrences(String source, String marker) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(marker, offset)) >= 0) {
            count++;
            offset += marker.length();
        }
        return count;
    }
}
