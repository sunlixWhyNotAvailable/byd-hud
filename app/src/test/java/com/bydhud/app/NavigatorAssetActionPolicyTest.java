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

    @Test
    public void replacementAssetsKeepStableIdentityAndPermitSameVersionUpdates() {
        NavigatorAssetManager.Asset waze = NavigatorAssetManager.findAsset("waze-direct-5.20.0.1");
        assertEquals("979090937DB76796FA6C1DECD21E86890D660EF87349DBF8963E808C867CF123",
                waze.sha256);
        assertEquals("5.20.0.1", waze.versionName);
        assertEquals(1030706L, waze.versionCode);
        assertEquals("com.waze", waze.packageName);
        assertEquals(NavigatorAssetSignerCatalog.WAZE_PROJECT_SIGNER, waze.signerSha256);
        assertEquals("https://github.com/sunlixWhyNotAvailable/byd-hud/releases/download/"
                + "navigator-assets-v2/waze-5.20.0.1-direct-v2.apk", waze.url);
        assertEquals("waze-5.20.0.1-direct-v2.apk", waze.fileName);
        assertTrue(NavigatorAssetManager.installIdentityChanged(
                "B29E876C5F1CD7A15BD50CEEC536D15BF81F9B61CE9D2B5BB8BAE20DCC9F81EE|same-package",
                waze.sha256 + "|same-package"));

        NavigatorAssetManager.Asset maps = NavigatorAssetManager.findAsset(
                "gmaps-direct-26.30.09.950492155");
        assertEquals("A1A9028E6AB0A171DA18F3E48325DFF2741E25476985DCE1D577E998945A86B4",
                maps.sha256);
        assertEquals("26.30.09.950492155", maps.versionName);
        assertEquals(1068694917L, maps.versionCode);
        assertEquals("app.revanced.android.apps.maps", maps.packageName);
        assertEquals(NavigatorAssetSignerCatalog.GMAPS_PROJECT_SIGNER, maps.signerSha256);
        assertEquals("https://github.com/sunlixWhyNotAvailable/byd-hud/releases/download/"
                + "navigator-assets-v2/google-maps-revanced-26.30.09.950492155-direct-v2.apk",
                maps.url);
        assertEquals("google-maps-revanced-26.30.09.950492155-direct-v2.apk", maps.fileName);

        assertFalse(NavigatorAssetManager.requiresDestructiveConfirmationForIdentity(
                true, false, true, waze.versionCode, waze.versionCode));
        assertTrue(NavigatorAssetManager.requiresDestructiveConfirmationForIdentity(
                true, false, false, waze.versionCode, waze.versionCode));
        assertTrue(NavigatorAssetManager.requiresDestructiveConfirmationForIdentity(
                true, false, true, waze.versionCode + 1, waze.versionCode));
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
