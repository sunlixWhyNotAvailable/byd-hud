package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NavigatorAssetActionPolicyTest {
    @Test
    public void installedPackageKeepsInstalledActionWithOrWithoutRetainedApk() {
        assertEquals(NavigatorAssetManager.INSTALLED,
                NavigatorAssetManager.resolvedSnapshotStateForTest(
                        true, true, NavigatorAssetManager.INSTALLED));
        assertEquals(NavigatorAssetManager.INSTALLED,
                NavigatorAssetManager.resolvedSnapshotStateForTest(
                        true, false, NavigatorAssetManager.INSTALLED));
    }

    @Test
    public void removedPackageCannotRestorePersistedInstalledState() {
        assertEquals(NavigatorAssetManager.READY,
                NavigatorAssetManager.resolvedSnapshotStateForTest(
                        false, true, NavigatorAssetManager.INSTALLED));
        assertEquals(NavigatorAssetManager.NOT_DOWNLOADED,
                NavigatorAssetManager.resolvedSnapshotStateForTest(
                        false, false, NavigatorAssetManager.INSTALLED));
    }

    @Test
    public void retainedApkControlsInstallVersusDownloadForIdleStates() {
        for (String stale : new String[]{
                NavigatorAssetManager.NOT_DOWNLOADED,
                NavigatorAssetManager.READY,
                NavigatorAssetManager.ERROR}) {
            assertEquals(NavigatorAssetManager.READY,
                    NavigatorAssetManager.resolvedSnapshotStateForTest(false, true, stale));
            assertEquals(NavigatorAssetManager.NOT_DOWNLOADED,
                    NavigatorAssetManager.resolvedSnapshotStateForTest(false, false, stale));
        }
    }

    @Test
    public void activeAndRecoveryStatesRemainGuarded() {
        for (String guarded : new String[]{
                NavigatorAssetManager.DOWNLOADING,
                NavigatorAssetManager.VERIFYING,
                NavigatorAssetManager.INSTALL_REQUESTED,
                NavigatorAssetManager.UNINSTALL_REQUESTED,
                NavigatorAssetManager.RECOVERY_REQUIRED}) {
            assertEquals(guarded,
                    NavigatorAssetManager.resolvedSnapshotStateForTest(true, true, guarded));
        }
    }

    @Test
    public void snapshotExposesRetainedApkSeparatelyFromInstalledPackage() {
        NavigatorAssetManager.Asset asset = NavigatorAssetManager.catalog().get(0);
        NavigatorAssetManager.AssetSnapshot retained = new NavigatorAssetManager.AssetSnapshot(
                asset, false, NavigatorAssetManager.INSTALLED, "100%", "", true, true);
        NavigatorAssetManager.AssetSnapshot absent = new NavigatorAssetManager.AssetSnapshot(
                asset, false, NavigatorAssetManager.INSTALLED, "100%", "", true, false);

        assertTrue(retained.installed);
        assertTrue(retained.downloadReady);
        assertTrue(absent.installed);
        assertFalse(absent.downloadReady);
    }

    @Test
    public void catalogLabelsFollowAllThreeApplicationLanguages() {
        NavigatorAssetManager.Asset asset = NavigatorAssetManager.catalog().get(0);

        assertEquals("Waze original version", asset.label("en"));
        assertEquals("Waze оригінальна версія", asset.label("uk"));
        assertEquals("Waze оригинальная версия", asset.label("ru"));
        assertEquals("Waze original version", asset.label("invalid"));
    }
}
