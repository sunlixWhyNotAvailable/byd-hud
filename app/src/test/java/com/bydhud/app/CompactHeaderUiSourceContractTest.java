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

public final class CompactHeaderUiSourceContractTest {
    @Test
    public void allFiveMainTabsShareTheInlineHeaderWithoutChangingViewports() throws IOException {
        String source = runtimeSource();
        String header = between(source, "private fun PageSurfaceHeader(", "private data class OptionsRowSpec");
        assertContains(header,
                "Row(verticalAlignment = Alignment.CenterVertically)",
                "horizontalArrangement = Arrangement.spacedBy(16.dp)",
                "Text(title, color = palette.text, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, maxLines = 1)",
                "hint,\n                color = palette.muted",
                "fontSize = 13.sp", "overflow = TextOverflow.Ellipsis", "headerAction?.invoke()");
        assertEquals(2, occurrences(header, "maxLines = 1"));
        assertEquals(2, occurrences(header, "Modifier.weight(1f)"));
        assertEquals(2, occurrences(header, "verticalAlignment = Alignment.CenterVertically"));
        assertFalse(header.contains("Column("));
        assertFalse(header.contains("Spacer("));
        assertContains(source,
                "SidebarOptionsSurface(\n        title = copy.main,\n        hint = copy.mainHint",
                "LazyPageSurface(copy.apps, copy.appsHint, palette, scrollState, headerAction =",
                "LazyPageSurface(copy.storage, copy.storageHint, palette, scrollState)",
                "LazyPageSurface(copy.patchTab, copy.patchHint, palette, scrollState)",
                "LazyPageSurface(\n        title = copy.hudCheck,\n        hint = copy.hudCheckHint");
        assertEquals(5, occurrences(source, "LazyPageSurface("));
        assertEquals(3, occurrences(source, "PageSurfaceHeader("));
        String lazy = between(source, "private fun LazyPageSurface(", "private fun PageSurfaceHeader(");
        assertContains(lazy, "state = state", "itemSpacing: Dp = 10.dp",
                "contentPadding = PaddingValues(14.dp)",
                "item(key = \"page-header\")", "PageSurfaceHeader(title, hint, palette, headerAction)",
                "Spacer(Modifier.height(4.dp))");
        String sidebar = between(source, "private fun SidebarOptionsSurface(", "private fun SidebarOptionsCategoryItem(");
        assertContains(sidebar,
                "PageSurfaceHeader(title, hint, palette, null)\n        Spacer(Modifier.height(8.dp))",
                "state = categoryScrollState", "state = sectionScrollStates.getValue(selectedSection.key)");
    }

    @Test
    public void onlyRefreshAndHudCheckUseShorterVisualButtons() throws IOException {
        String source = runtimeSource();
        assertEquals(2, occurrences(source, ".height(36.dp)"));
        String apps = between(source, "LazyPageSurface(copy.apps,", "item(key = \"navigator-assets\")");
        assertContains(apps,
                "HudButton(copy.refreshApps, palette, primary = true, width = 178.dp, modifier = Modifier.height(36.dp))",
                "activity.composeRefreshApps()", "Arrangement.spacedBy(8.dp)",
                "Modifier.width(230.dp)", "itemSpacing = 0.dp", "Spacer(Modifier.height(10.dp))");
        String hudCheck = between(source, "private fun HudCheckTab(", "item(key = \"hud-check-mode\")");
        assertContains(hudCheck,
                "if (state.running) copy.hudCheckStop else copy.hudCheckStart",
                "width = 180.dp,\n                    modifier = Modifier.height(36.dp)",
                "activity.composeHudCheckToggleRunning()", "Arrangement.spacedBy(12.dp)");
        String button = between(source, "private fun HudButton(", "private fun HudIconButton(");
        assertContains(button,
                "val base = if (width == 0.dp) modifier.height(44.dp) else modifier.width(width).height(44.dp)",
                ".then(press.modifier)", ".clickable(", "enabled = enabled",
                "interactionSource = press.interactionSource", "onClick = visualClick");
    }

    @Test
    public void bothDropdownsUseSubtleEnabledFillAndKeepDisabledStatesAndGeometry() throws IOException {
        String source = runtimeSource();
        String tint = "val selectedBackground = palette.accent.copy(alpha = if (palette.dark) 0.20f else 0.04f)";
        String dropdown = between(source, "private fun HudDropdown(", "private fun WidgetOptionIcon(");
        assertContains(dropdown, tint,
                "val rowHeight = 40.dp",
                "val selectedContent = if (palette.dark) Color.White else palette.text",
                "val fieldBackground = if (enabled) selectedBackground else palette.panelAlt",
                "val fieldBorder = if (enabled) palette.accent else palette.borderStrong",
                "val fieldContent = if (enabled) selectedContent else palette.muted.copy(alpha = 0.62f)",
                ".border(1.dp, fieldBorder, RoundedCornerShape(6.dp))", ".background(fieldBackground)",
                ".clickable(enabled = enabled)", "if (expanded && enabled)",
                ".background(if (index == safeIndex) selectedBackground else Color.Transparent)",
                ".border(1.dp, palette.borderStrong, RoundedCornerShape(6.dp))",
                "color = fieldContent.copy(alpha = 0.78f)",
                "WidgetOptionIcon(it, fieldContent)", "PopupProperties(focusable = true)");
        assertEquals(2, occurrences(dropdown, ".height(rowHeight)"));
        String appDropdown = between(source, "private fun HudTransferAppDropdown(", "private fun TransferAppIcon(");
        assertContains(appDropdown, tint,
                "val fieldBackground = if (entries.isNotEmpty()) selectedBackground\n"
                        + "        else palette.accent.copy(alpha = if (palette.dark) 0.78f else 0.08f)",
                ".background(fieldBackground)", "if (selectedRow) selectedBackground",
                ".clickable(enabled = entries.isNotEmpty())", ".height(44.dp)", ".height(56.dp)",
                ".heightIn(max = 330.dp)", ".border(1.dp, palette.accent, RoundedCornerShape(7.dp))",
                ".border(1.dp, palette.borderStrong, RoundedCornerShape(7.dp))",
                "color = if (palette.dark) Color.White else palette.text",
                "color = palette.text.copy(alpha = 0.78f)", "TransferAppIcon(selected, palette)",
                "TransferAppIcon(entry, palette)", "items(entries.size, key = { entries[it].packageName() })",
                "InstalledTransferAppCatalog.selectionOrFallback(entries, selectedPackage)");
        String appPickerRow = between(source, "row(\"move-app\")", "row(\"move-window-profile\")");
        assertContains(appPickerRow, "HudTransferAppDropdown(", "width = 400.dp");
    }

    private static String runtimeSource() throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve("app/src/main/java/com/bydhud/app/BydHudRuntimeCompose.kt");
        if (!Files.isRegularFile(file)) {
            file = root.resolve("src/main/java/com/bydhud/app/BydHudRuntimeCompose.kt");
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex + start.length());
        assertTrue("missing source markers: " + start + " / " + end, startIndex >= 0 && endIndex >= 0);
        return source.substring(startIndex, endIndex);
    }

    private static int occurrences(String source, String marker) {
        return (source.length() - source.replace(marker, "").length()) / marker.length();
    }

    private static void assertContains(String source, String... markers) {
        for (String marker : markers) {
            assertTrue("missing source marker: " + marker, source.contains(marker));
        }
    }
}
