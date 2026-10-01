package com.bydhud.app;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.lang.reflect.Method;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
public final class NavigatorAssetMigrationTest {
    @Test public void retiredTransactionRemainsProtectedAndRecoverableUntilRestoreCompletes() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = context.getSharedPreferences("navigator_asset_manager", 0);
        prefs.edit().clear().commit();
        NavigatorAssetManager.Asset legacy = NavigatorAssetManager.findAsset("waze-stock-4.95.0.3");
        String key = legacy.id + "_";
        prefs.edit().putString(key + "catalog_sha", legacy.sha256)
                .putString(key + "transaction", "legacy-backup")
                .putString(key + "backup_fingerprint", "fingerprint")
                .putString(key + "phase", "RECOVERY")
                .putString(key + "state", NavigatorAssetManager.RECOVERY_REQUIRED).commit();

        NavigatorAssetManager.AssetSnapshot row = NavigatorAssetManager.snapshots(context, false)
                .stream().filter(it -> it.id.equals(legacy.id)).findFirst().get();
        assertTrue(row.recoveryOnly);
        assertFalse(row.downloadable);
        assertEquals(NavigatorAssetManager.RECOVERY_REQUIRED, row.state);
        Method protectedNames = NavigatorAssetManager.class.getDeclaredMethod(
                "protectedTransactionNames", Context.class, File.class);
        protectedNames.setAccessible(true);
        assertTrue(((Set<?>) protectedNames.invoke(null, context, context.getFilesDir()))
                .contains("legacy-backup"));
        assertTrue(NavigatorAssetManager.recordAuthoritativeRestoreVerified(context, legacy.profile,
                legacy.id, "legacy-backup", "fingerprint"));
        assertTrue(NavigatorAssetManager.finishAuthoritativeRestoreVerified(context, legacy.profile,
                legacy.id, "legacy-backup", "fingerprint"));
        assertFalse(NavigatorAssetManager.snapshots(context, false).stream()
                .anyMatch(it -> it.id.equals(legacy.id)));
    }

    @Test public void newCatalogHashInvalidatesOldReadinessWithoutLosingRecovery() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = context.getSharedPreferences("navigator_asset_manager", 0);
        prefs.edit().clear().commit();
        for (NavigatorAssetManager.Asset asset : NavigatorAssetManager.catalog()) {
            String key = asset.id + "_";
            prefs.edit().putString(key + "catalog_sha", "old-v1")
                    .putBoolean(key + "download_ready", true)
                    .putBoolean(key + "installed_matches", true)
                    .putString(key + "installed_identity", "old-installed")
                    .putString(key + "phase", "RECOVERY")
                    .putString(key + "state", NavigatorAssetManager.RECOVERY_REQUIRED)
                    .putString(key + "transaction", "retained-backup")
                    .putString(key + "backup_fingerprint", "retained-fingerprint").commit();
            NavigatorAssetManager.AssetSnapshot row = NavigatorAssetManager.snapshot(context, asset, false);
            assertFalse(row.downloadReady);
            assertFalse(row.installed);
            assertEquals(asset.sha256, prefs.getString(key + "catalog_sha", ""));
            assertEquals("RECOVERY", prefs.getString(key + "phase", ""));
            assertEquals("retained-backup", prefs.getString(key + "transaction", ""));
            assertEquals("retained-fingerprint", prefs.getString(key + "backup_fingerprint", ""));
        }
    }

    @Test public void revisedCatalogOffersRedownloadInsteadOfStaleReadyApk() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = context.getSharedPreferences("navigator_asset_manager", 0);
        prefs.edit().clear().commit();
        for (NavigatorAssetManager.Asset asset : NavigatorAssetManager.catalog()) {
            String key = asset.id + "_";
            prefs.edit().putString(key + "catalog_sha", "previous-digest")
                    .putBoolean(key + "download_ready", true)
                    .putString(key + "state", NavigatorAssetManager.READY)
                    .putString(key + "phase", "NONE").commit();

            NavigatorAssetManager.AssetSnapshot row =
                    NavigatorAssetManager.snapshot(context, asset, false);

            assertEquals(asset.id, row.id);
            assertFalse(row.downloadReady);
            assertTrue(row.downloadable);
            assertEquals(NavigatorAssetManager.NOT_DOWNLOADED, row.state);
            assertEquals(asset.sha256, prefs.getString(key + "catalog_sha", ""));
        }
    }
}
