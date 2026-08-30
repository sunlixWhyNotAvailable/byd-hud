package com.bydhud.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class InstalledTransferAppCatalogTest {
    @Test
    public void manifestAllowsLauncherAppDiscovery() throws Exception {
        String manifest = new String(Files.readAllBytes(
                Path.of("src/main/AndroidManifest.xml")), StandardCharsets.UTF_8);

        assertTrue(manifest.contains("android.intent.action.MAIN"));
        assertTrue(manifest.contains("android.intent.category.LAUNCHER"));
    }

    @Test
    public void runtimeRefreshReusesUnchangedCatalogEntries() throws Exception {
        String catalog = new String(Files.readAllBytes(Path.of(
                "src/main/java/com/bydhud/app/InstalledTransferAppCatalog.java")),
                StandardCharsets.UTF_8);
        String activity = new String(Files.readAllBytes(Path.of(
                "src/main/java/com/bydhud/app/MainActivity.java")),
                StandardCharsets.UTF_8);

        assertTrue(catalog.contains("load(Collection<Entry> cachedEntries)"));
        assertTrue(catalog.contains("cached.label.equals(label)"));
        assertTrue(activity.contains(".load(installedTransferApps)"));
    }

    @Test
    public void filtersSelfSystemAndNonLaunchableAndSortsByLabelThenPackage() {
        List<InstalledTransferAppCatalog.Candidate> result =
                InstalledTransferAppCatalog.filterAndSortCandidates(
                        Arrays.asList(
                                new InstalledTransferAppCatalog.Candidate(
                                        "com.example.z", "Zoo", false, true),
                                new InstalledTransferAppCatalog.Candidate(
                                        "com.example.a", "Alpha", false, true),
                                new InstalledTransferAppCatalog.Candidate(
                                        "com.bydhud.app", "BYD HUD", false, true),
                                new InstalledTransferAppCatalog.Candidate(
                                        "com.system.map", "Map", true, true),
                                new InstalledTransferAppCatalog.Candidate(
                                        "com.example.hidden", "Hidden", false, false),
                                new InstalledTransferAppCatalog.Candidate(
                                        "COM.EXAMPLE.Z", "Duplicate", false, true)),
                        "com.bydhud.app");

        assertEquals(2, result.size());
        assertEquals("com.example.a", result.get(0).packageName);
        assertEquals("Alpha", result.get(0).label);
        assertEquals("com.example.z", result.get(1).packageName);
        assertEquals("Zoo", result.get(1).label);
    }

    @Test
    public void blankLabelFallsBackToPackageName() {
        List<InstalledTransferAppCatalog.Candidate> result =
                InstalledTransferAppCatalog.filterAndSortCandidates(
                        Collections.singletonList(
                                new InstalledTransferAppCatalog.Candidate(
                                        "com.example.blank", "  ", false, true)),
                        "com.bydhud.app");

        assertEquals(1, result.size());
        assertEquals("com.example.blank", result.get(0).label);
    }

    @Test
    public void missingSelectionKeepsPackageWithNeutralFallback() {
        InstalledTransferAppCatalog.Entry fallback =
                InstalledTransferAppCatalog.selectionOrFallback(
                        Collections.emptyList(), " com.example.removed ");

        assertEquals("com.example.removed", fallback.packageName());
        assertEquals("com.example.removed", fallback.label());
        assertTrue(fallback.isFallback());
        assertTrue(fallback.icon().isNeutral());
        assertEquals(1, fallback.icon().width());
        assertEquals(1, fallback.icon().height());
    }

    @Test
    public void iconPixelsAreDefensivelyCopied() {
        InstalledTransferAppCatalog.IconSnapshot icon =
                InstalledTransferAppCatalog.IconSnapshot.neutral();
        int[] pixels = icon.copyPixels();
        pixels[0] = 0;

        assertTrue(icon.isNeutral());
        assertArrayEquals(new int[]{0xFF64748B}, icon.copyPixels());
    }
}
