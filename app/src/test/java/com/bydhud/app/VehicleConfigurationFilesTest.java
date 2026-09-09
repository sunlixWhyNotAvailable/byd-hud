package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/** Pure policy and metadata checks for the raw-file inventory. */
public final class VehicleConfigurationFilesTest {
    @Test public void metadataIncludesAcceptedCandidatesWithoutUnrelatedApps() {
        assertTrue(VehicleConfigurationFiles.inventoryPackageName("com.byd.carsettings.plugins"));
        assertTrue(VehicleConfigurationFiles.inventoryPackageName("com.byd.avc"));
        assertTrue(VehicleConfigurationFiles.inventoryPackageName("com.byd.diagnosticinfo"));
        assertFalse(VehicleConfigurationFiles.inventoryPackageName("com.thirdparty.player"));
        assertFalse(VehicleConfigurationFiles.isInventoryPath("/system/app/UnrelatedPlayer/base.apk"));
        assertFalse(VehicleConfigurationFiles.isInventoryPath("/system/app/UnrelatedPlayer/lib/arm64/libplayer.so"));
        assertTrue(VehicleConfigurationFiles.isInventoryPath("/system/priv-app/CarSettingsPlugins/CarSettingsPlugins.apk"));
        assertTrue(VehicleConfigurationFiles.isInventoryPath("/system/lib64/libbinder.so"));
        assertFalse(VehicleConfigurationFiles.isRelevantCandidate("/system/lib64/libbinder.so"));
        assertFalse(VehicleConfigurationFiles.isRelevantCandidate("/cluster/scenes/scene.json"));
    }

    @Test public void pathPolicyKeepsFirmwareAndResolvedApkShapesOnly() {
        assertTrue(VehicleConfigurationFiles.isAllowedPath("/system/framework/services.jar"));
        assertTrue(VehicleConfigurationFiles.isAllowedPath("/vendor/etc/vintf/manifest.xml"));
        assertTrue(VehicleConfigurationFiles.isAllowedPath("/cluster/config/cluster.dios_host.rc"));
        assertTrue(VehicleConfigurationFiles.isAllowedPath(
                "/data/app/~~opaque==/com.byd.naviauto-random/base.apk"));
        assertTrue(VehicleConfigurationFiles.isAllowedPath(
                "/data/app/~~opaque==/com.byd.cluster.alternate-random/split_resources.apk"));
        assertTrue(VehicleConfigurationFiles.isAllowedPath(
                "/data/app/~~opaque==/com.byd.naviauto-random/lib/arm64/libBydCluster.so"));
        assertTrue(VehicleConfigurationFiles.isAllowedPath(
                "/apex/com.android.runtime/lib64/bionic/libc.so"));
        assertFalse(VehicleConfigurationFiles.isAllowedPath(
                "/apex/com.android.runtime/etc/public-artifact.txt"));
        assertFalse(VehicleConfigurationFiles.isAllowedPath(
                "/data/app/~~opaque==/com.waze-random/base.apk"));
        for (String rejected : new String[]{
                "/data/user/0/com.byd.naviauto/shared_prefs/route.xml",
                "/data/data/com.byd.naviauto/lib/libBydCluster.so",
                "/vendor/etc/someip/../secrets.conf",
                "/vendor/etc/passwords.conf", "/system/etc/keys/private.pem",
                "/proc/1/maps", "/system//etc/init.rc", "vendor/etc/init.rc"}) {
            assertFalse(rejected, VehicleConfigurationFiles.isAllowedPath(rejected));
        }
    }

    @Test public void commandPolicyAllowsOnlyReadOnlyInventoryGrammar() {
        assertTrue(VehicleConfigurationFiles.isAllowedCommand(
                "stat -Lc '%f %s %Y %d %i %y' '/system/framework/services.jar'"));
        assertTrue(VehicleConfigurationFiles.isAllowedCommand(
                "stat -Lc '%f %s %Y %d %i %y' /system/framework/services.jar"));
        assertTrue(VehicleConfigurationFiles.isAllowedCommand(
                "readlink -f '/vendor/etc/vintf/manifest.xml'"));
        assertTrue(VehicleConfigurationFiles.isAllowedCommand(
                "readelf -d '/vendor/lib64/libsomeipimpl.so'"));
        assertTrue(VehicleConfigurationFiles.isAllowedCommand("ps -A -o pid,args"));
        assertTrue(VehicleConfigurationFiles.isAllowedCommand("cat /proc/123/maps"));
        assertTrue(VehicleConfigurationFiles.isAllowedCommand("pm path com.byd.naviauto"));
        assertTrue(VehicleConfigurationFiles.isAllowedCommand("pm path com.byd.avc"));
        assertTrue(VehicleConfigurationFiles.isAllowedCommand("pm path com.byd.sr"));
        assertTrue(VehicleConfigurationFiles.isAllowedCommand(
                "find /system/etc /vendor/etc /product/etc /odm/etc /system_ext/etc -type f"));
        assertTrue(VehicleConfigurationFiles.isAllowedCommand(
                "find /apex -type f -name '*.so'"));
        for (String rejected : new String[]{
                "cat /data/user/0/com.waze/shared_prefs/user.xml",
                "stat -Lc '%s' /system/framework/services.jar",
                "sha256sum /system/framework/services.jar",
                "pm path com.waze", "cat /proc/123/mem", "find / -type f",
                "stat -Lc '%f %s %Y %d %i %y' '/system/etc/x'; id",
                "readlink -f /vendor/etc/someip/../shadow"}) {
            assertFalse(rejected, VehicleConfigurationFiles.isAllowedCommand(rejected));
        }
    }

    @Test public void statParserRequiresRegularFileAndPreservesIdentity() throws Exception {
        VehicleConfigurationFiles.FileStat stat = VehicleConfigurationFiles.FileStat.parse(
                "81a4 4096 1720000000 42 9001 2024-07-03 09:46:40.000000000 +0000");
        assertTrue(stat.isRegularFile());
        assertEquals(4096L, stat.size);
        assertEquals(1720000000000L, stat.modifiedEpochMs);
        assertEquals("42:9001", stat.identity());
        try {
            VehicleConfigurationFiles.FileStat.parse("41ed 12 1 2 3 1970-01-01 00:00:01.000000000 +0000");
            throw new AssertionError("directory stat must be rejected");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("regular"));
        }
    }

    @Test public void subsecondStatRejectsSameSizeRewriteWithinOneMillisecond() throws Exception {
        VehicleConfigurationFiles.FileStat before = VehicleConfigurationFiles.FileStat.parse(
                "81a4 4096 1720000000 42 9001 2024-07-03 09:46:40.000000001 +0000");
        VehicleConfigurationFiles.FileStat after = VehicleConfigurationFiles.FileStat.parse(
                "81a4 4096 1720000000 42 9001 2024-07-03 09:46:40.000000002 +0000");
        assertEquals(before.modifiedEpochMs, after.modifiedEpochMs);
        VehicleConfigurationFiles.Entry entry = new VehicleConfigurationFiles.Entry(
                before, "/system/lib64/libBydCluster.so", "native");
        VehicleConfigurationZip.ensureUnchanged(entry, before);
        org.junit.Assert.assertThrows(java.io.IOException.class,
                () -> VehicleConfigurationZip.ensureUnchanged(entry, after));
        org.junit.Assert.assertThrows(java.io.IOException.class,
                () -> VehicleConfigurationFiles.FileStat.parse("81a4 4096 1720000000 42 9001 ?"));
    }

    @Test public void entriesDeduplicateByDeviceInodeAndKeepAliases() {
        VehicleConfigurationFiles.Inventory inventory = new VehicleConfigurationFiles.Inventory();
        inventory.add(new VehicleConfigurationFiles.Entry("/system/lib64/libsomeipimpl.so",
                "files/system/lib64/libsomeipimpl.so", 20L, 30L, 7L, 8L, "regular", "native"),
                "libsomeipimpl.so");
        inventory.add(new VehicleConfigurationFiles.Entry("/vendor/lib64/libsomeipimpl.so",
                "files/vendor/lib64/libsomeipimpl.so", 20L, 30L, 7L, 8L, "regular", "native"),
                "vendor-alias");
        assertEquals(1, inventory.entries.size());
        assertEquals(20L, inventory.totalBytes);
        assertTrue(inventory.entries.get(0).aliases.contains("libsomeipimpl.so"));
        assertTrue(inventory.entries.get(0).aliases.contains("vendor-alias"));
    }

    @Test public void inventoryProgressGrowsFromInspectionThroughUnavailableFiles() {
        List<String> progress = new ArrayList<>();
        VehicleConfigurationFiles.Inventory inventory = new VehicleConfigurationFiles.Inventory(
                (file, found, bytes, unavailable) -> progress.add(
                        file + "|" + found + "|" + bytes + "|" + unavailable));
        inventory.inspect("package-manager");
        inventory.add(new VehicleConfigurationFiles.Entry("/system/framework/services.jar",
                "files/system/framework/services.jar", 40L, 30L, 1L, 2L,
                "regular", "framework"), null);
        inventory.unavailable("/vendor/lib64/missing.so", "not readable");

        assertEquals("package-manager|0|0|0", progress.get(0));
        assertEquals("/system/framework/services.jar|1|40|0", progress.get(1));
        assertEquals("/vendor/lib64/missing.so|1|40|1", progress.get(2));
    }

    @Test public void candidateSelectionCoversClusterFrameworkAndDependencyFamilies() {
        assertTrue(VehicleConfigurationFiles.isRelevantCandidate(
                "/vendor/lib64/libBydDataSourceForDi5SF.so"));
        assertTrue(VehicleConfigurationFiles.isRelevantCandidate(
                "/system/lib64/libCommonAPI-SomeIP.so"));
        assertTrue(VehicleConfigurationFiles.isRelevantCandidate(
                "/system/framework/services.jar"));
        assertTrue(VehicleConfigurationFiles.isRelevantCandidate(
                "/cluster/bin/BydClusterKanzi"));
        assertTrue(VehicleConfigurationFiles.isRelevantCandidate(
                "/vendor/etc/vintf/manifest-someip.xml"));
        assertTrue(VehicleConfigurationFiles.isRelevantCandidate(
                "/system/framework/oat/arm64/services.vdex"));
        assertFalse(VehicleConfigurationFiles.isRelevantCandidate(
                "/system/lib64/libcamera_client.so"));
        assertFalse(VehicleConfigurationFiles.isRelevantCandidate(
                "/vendor/etc/location/gps.conf"));
        assertFalse(VehicleConfigurationFiles.isRelevantCandidate(
                "/data/app/~~opaque==/com.waze-random/base.apk"));
    }
}
