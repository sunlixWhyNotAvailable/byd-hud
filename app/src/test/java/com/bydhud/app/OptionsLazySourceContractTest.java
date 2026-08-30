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
    public void dashboardArtworkMatchesApprovedPreviewGeometry() throws IOException {
        String toggle = sourcePath("app/src/main/res/drawable/ic_widget_toggle.xml");
        assertEquals(4, occurrences(toggle, "<path"));
        assertTrue(toggle.contains("android:viewportWidth=\"32\""));
        assertTrue(toggle.contains("android:strokeWidth=\"1.5\""));
        assertTrue(toggle.contains("M16.439,9.149 C20.329,9.149"));
        assertTrue(toggle.contains("M16.439,22.759 C12.909,22.759"));
        assertTrue(toggle.contains("M23,17.9 L20.75,20.15 H29"));
        assertTrue(toggle.contains("M20.75,22.75 H29 L26.75,25"));

        String car = sourcePath("app/src/main/res/drawable/ic_options_directions_car.xml");
        assertTrue(car.contains("M18.92,6.01 C18.72,5.42 18.16,5 17.5,5"));
        assertTrue(car.contains("M19,17 H5 V12 H19 V17 Z"));
        assertTrue(car.contains("M7.5,14.5 M6,14.5 A1.5,1.5"));
        assertTrue(car.contains("M16.5,14.5 M15,14.5 A1.5,1.5"));
    }

    @Test
    public void productionOptionsUseStableRowItemsAndTypedSections() throws IOException {
        String source = sourcePath("app/src/main/java/com/bydhud/app/BydHudRuntimeCompose.kt");
        String options = between(source, "private fun OptionsTab(", "private fun WidgetNumberLine(");

        assertEquals(9, occurrences(options, "optionsSection("));
        assertFalse(options.contains("\n            Section("));
        assertTrue(source.contains("contentType = \"options-header\""));
        assertTrue(source.contains("contentType = \"options-row\""));
        assertTrue(source.contains("val key: String"));
        assertTrue(source.contains("item(key = \"${section.key}:${row.key}\""));
        assertFalse(options.contains("forEachIndexed"));
        assertTrue(source.contains(".clearAndSetSemantics {}"));

        assertOrdered(options,
                "optionsSection(\"runtime-permissions\"",
                "optionsSection(\"basic-navigation\"",
                "optionsSection(\"route-eta\"",
                "optionsSection(\"speed-limit\"",
                "optionsSection(\"waze-features\"",
                "optionsSection(\"extra-navigation\"",
                "optionsSection(\"dashboard-window-profile\"",
                "\"dashboard-widget\"",
                "optionsSection(\"dashboard-move\"");
        assertTrue(options.contains("R.drawable.ic_options_build"));
        assertTrue(options.contains("R.drawable.ic_options_navigation"));
        assertTrue(options.contains("R.drawable.ic_options_schedule"));
        assertTrue(options.contains("R.drawable.ic_options_speed"));
        assertTrue(options.contains("R.drawable.waze_app_icon"));
        assertTrue(options.contains("R.drawable.ic_options_settings"));
        assertTrue(options.contains("R.drawable.ic_options_directions_car"));
        assertTrue(options.contains("R.drawable.ic_options_widgets"));
        assertTrue(options.contains("R.drawable.ic_options_open_in_new"));
        assertEquals(1, occurrences(options, "R.drawable.ic_options_build"));
        assertEquals(1, occurrences(options, "R.drawable.ic_options_navigation"));
        assertEquals(1, occurrences(options, "R.drawable.ic_options_schedule"));
        assertEquals(1, occurrences(options, "R.drawable.ic_options_speed"));
        assertEquals(1, occurrences(options, "R.drawable.waze_app_icon"));
        assertEquals(1, occurrences(options, "R.drawable.ic_options_settings"));
        assertEquals(1, occurrences(options, "R.drawable.ic_options_directions_car"));
        assertEquals(1, occurrences(options, "R.drawable.ic_options_widgets"));
        assertEquals(1, occurrences(options, "R.drawable.ic_options_open_in_new"));
        assertTrue(source.contains("SidebarOptionsSurface("));
        assertFalse(options.contains("LazyPageSurface"));
        assertTrue(source.contains("key(selectedSection.key)"));
        assertTrue(source.contains(".width(260.dp)"));
        assertTrue(source.contains("Arrangement.spacedBy(16.dp)"));
        String sidebar = between(source,
                "private fun SidebarOptionsSurface(",
                "private fun SidebarOptionsCategoryItem(");
        assertEquals(2, occurrences(sidebar, "LazyColumn("));
        assertTrue(sidebar.contains("selectedSection.preview?.let"));
        assertTrue(sidebar.contains(".width(196.dp)"));
        assertTrue(sidebar.contains("horizontalArrangement = Arrangement.spacedBy(12.dp)"));
        assertTrue(sidebar.contains("contentType = { \"options-category\" }"));
        assertTrue(sidebar.contains(".padding(horizontal = 8.dp, vertical = 6.dp)"));
        assertTrue(sidebar.contains("verticalArrangement = Arrangement.spacedBy(2.dp)"));
        String categoryItem = between(source,
                "private fun SidebarOptionsCategoryItem(",
                "private fun LazyListScope.sidebarOptionsSection(");
        assertTrue(categoryItem.contains(".padding(horizontal = 14.dp, vertical = 8.dp)"));
        String section = between(source,
                "private fun LazyListScope.sidebarOptionsSection(",
                "private fun Section(");
        assertFalse(section.contains("section.preview"));
        assertFalse(section.contains("options-preview"));
        assertFalse(options.contains("screen-capture-channel"));
        assertFalse(options.contains("roundabout-left"));
    }

    @Test
    public void previewOptionsUseStableRowItemsAndTypedSections() throws IOException {
        String source = sourcePath("../byd-hud-compose-preview/compose-preview/src/main/java/com/bydhud/preview/MainActivity.kt");
        String options = between(source, "private fun MainTab(", "private data class PreviewOptionsRowSpec");

        assertEquals(9, occurrences(options, "section("));
        assertFalse(options.contains("DashboardTile("));
        assertTrue(options.contains("buildPreviewOptionsSections"));
        assertTrue(source.contains("previewOptionsSection(selectedSection"));
        assertTrue(source.contains("key(selectedSection.key)"));
        assertTrue(source.contains("contentType = \"options-header\""));
        assertTrue(source.contains("contentType = \"options-row\""));
        assertTrue(source.contains("item(key = \"${section.key}:${row.key}\""));
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

    private static void assertOrdered(String source, String... markers) {
        int previous = -1;
        for (String marker : markers) {
            int current = source.indexOf(marker);
            assertTrue("missing marker: " + marker, current >= 0);
            assertTrue("marker out of order: " + marker, current > previous);
            previous = current;
        }
    }
}
