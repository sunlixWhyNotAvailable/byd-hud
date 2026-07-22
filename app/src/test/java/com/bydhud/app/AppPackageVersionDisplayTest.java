package com.bydhud.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class AppPackageVersionDisplayTest {
    @Test
    public void wazeUsesItsCachedVersion() {
        Map<String, String> versions = new HashMap<>();
        versions.put("com.waze", "5.20.0.1");

        List<MainActivity.ComposePackageVersion> rows =
                MainActivity.composePackageVersions("com.waze", versions);

        assertEquals(1, rows.size());
        assertEquals("com.waze", rows.get(0).packageName);
        assertEquals("5.20.0.1", rows.get(0).versionName);
    }

    @Test
    public void installedGoogleMapsVariantsStayInOneOrderedRow() {
        Map<String, String> versions = new HashMap<>();
        versions.put("app.revanced.android.apps.maps", "11.2");
        versions.put("com.google.android.apps.maps", "25.1");

        List<MainActivity.ComposePackageVersion> rows = MainActivity.composePackageVersions(
                "app.revanced.android.apps.maps",
                versions);

        assertEquals(2, rows.size());
        assertEquals("com.google.android.apps.maps", rows.get(0).packageName);
        assertEquals("25.1", rows.get(0).versionName);
        assertEquals("app.revanced.android.apps.maps", rows.get(1).packageName);
        assertEquals("11.2", rows.get(1).versionName);
    }

    @Test
    public void singleInstalledGoogleMapsVariantProducesOnePackageLine() {
        Map<String, String> versions = new HashMap<>();
        versions.put("app.revanced.android.apps.maps", "11.2");

        List<MainActivity.ComposePackageVersion> rows = MainActivity.composePackageVersions(
                "com.google.android.apps.maps",
                versions);

        assertEquals(1, rows.size());
        assertEquals("app.revanced.android.apps.maps", rows.get(0).packageName);
        assertEquals("11.2", rows.get(0).versionName);
    }

    @Test
    public void emptyCacheKeepsBothSupportedGoogleMapsPackagesVisible() {
        List<MainActivity.ComposePackageVersion> rows = MainActivity.composePackageVersions(
                "com.google.android.apps.maps",
                null);

        assertEquals(2, rows.size());
        assertEquals("com.google.android.apps.maps", rows.get(0).packageName);
        assertEquals("", rows.get(0).versionName);
        assertEquals("app.revanced.android.apps.maps", rows.get(1).packageName);
        assertEquals("", rows.get(1).versionName);
    }
}
