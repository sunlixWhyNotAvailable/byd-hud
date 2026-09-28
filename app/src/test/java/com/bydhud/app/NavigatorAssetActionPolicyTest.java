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
                NavigatorAssetManager.READY}) {
            assertEquals(NavigatorAssetManager.READY,
                    NavigatorAssetManager.resolvedSnapshotStateForTest(false, true, stale));
            assertEquals(NavigatorAssetManager.NOT_DOWNLOADED,
                    NavigatorAssetManager.resolvedSnapshotStateForTest(false, false, stale));
        }
    }

    @Test
    public void actualFailureRemainsVisibleUntilRetryWhileInstalledStillWins() {
        assertEquals(NavigatorAssetManager.ERROR,
                NavigatorAssetManager.resolvedSnapshotStateForTest(
                        false, false, NavigatorAssetManager.ERROR));
        assertEquals(NavigatorAssetManager.ERROR,
                NavigatorAssetManager.resolvedSnapshotStateForTest(
                        false, true, NavigatorAssetManager.ERROR));
        assertEquals(NavigatorAssetManager.INSTALLED,
                NavigatorAssetManager.resolvedSnapshotStateForTest(
                        true, false, NavigatorAssetManager.ERROR));
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

        assertEquals("Waze patched version", asset.label("en"));
        assertEquals("Waze патчена версія", asset.label("uk"));
        assertEquals("Waze патченная версия", asset.label("ru"));
        assertEquals("Waze patched version", asset.label("invalid"));
    }

    @Test public void retiredAssetCannotStartNewActionsButRemainsKnownToRecovery() throws Exception {
        String id = "waze-stock-4.95.0.3";
        assertEquals(2, NavigatorAssetManager.catalog().size());
        NavigatorAssetManager.Asset retired = NavigatorAssetManager.findAsset(id);
        assertFalse(retired.offered());
        assertFalse(NavigatorAssetManager.catalog().contains(retired));
        org.junit.Assert.assertThrows(java.io.IOException.class,
                () -> NavigatorAssetManager.startDownload(null, id));
        org.junit.Assert.assertThrows(java.io.IOException.class,
                () -> NavigatorAssetManager.install(null, id, true));
        NavigatorAssetManager.AssetSnapshot recovery = new NavigatorAssetManager.AssetSnapshot(
                retired, false, NavigatorAssetManager.RECOVERY_REQUIRED, "0%", "", false, true);
        assertTrue(recovery.recoveryOnly);
        assertFalse(recovery.downloadable);
        for (NavigatorAssetManager.Asset current : NavigatorAssetManager.catalog()) {
            assertTrue(current.url.contains("/navigator-assets-v2/"));
            assertTrue(current.fileName.endsWith("-v2.apk"));
        }
    }
}
