package com.bydhud.app;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class VehicleConfigurationFilesTest {
    @Test public void petalPackageIncludesRandomizedInstallApksAndPackageMetadataOnly() {
        String petal = "com.huawei.maps.car.app";
        assertTrue(VehicleConfigurationFiles.inventoryPackageName(petal));
        assertTrue(Arrays.asList(VehicleConfigurationDiagnostics.packageNames()).contains(petal));

        String install = "/data/app/~~install/com.huawei.maps.car.app-randomized/";
        assertTrue(VehicleConfigurationFiles.isAllowedPath(install + "base.apk"));
        assertTrue(VehicleConfigurationFiles.isAllowedPath(install + "split_config.arm64_v8a.apk"));
        assertFalse(VehicleConfigurationFiles.inventoryPackageName("com.huawei.hwid.core"));
        assertFalse(VehicleConfigurationFiles.isAllowedPath(
                "/data/app/~~install/com.huawei.hwid.core-randomized/base.apk"));
        assertFalse(VehicleConfigurationFiles.isAllowedPath(
                "/data/user/0/com.huawei.maps.car.app/files/location-history.db"));
    }

    @Test public void petalProcessAddsOnlyItsMappedNativeLibrariesToExistingClosure() {
        assertTrue(VehicleConfigurationFiles.relevantProcess("3017 com.huawei.maps.car.app"));
        assertTrue(VehicleConfigurationFiles.relevantProcess(
                "3017 com.huawei.maps.car.app:renderService"));
        assertFalse(VehicleConfigurationFiles.relevantProcess("4113 com.huawei.hwid.core"));
        assertFalse(VehicleConfigurationFiles.relevantProcess("5312 com.baidu.map.location"));
        assertFalse(VehicleConfigurationFiles.relevantProcess(
                "999 sh -c com.huawei.maps.car.app"));

        assertTrue(VehicleConfigurationFiles.isRelevantMappedLibraryPath(
                "/system/lib64/libHuaweiPetalMap.so", true));
        assertTrue(VehicleConfigurationFiles.isRelevantMappedLibraryPath(
                "/system/lib64/libc++_shared.so", true));
        assertTrue(VehicleConfigurationFiles.isRelevantMappedLibraryPath(
                "/data/app/~~install/com.huawei.maps.car.app-randomized/lib/arm64/libPetalRoute.so", true));
        assertFalse(VehicleConfigurationFiles.isRelevantMappedLibraryPath(
                "/system/lib64/libHuaweiPetalMap.so", false));
        assertFalse(VehicleConfigurationFiles.isRelevantMappedLibraryPath(
                "/system/lib64/libc++_shared.so", false));
        assertFalse(VehicleConfigurationFiles.isRelevantMappedLibraryPath(
                "/system/lib64/libc.so", true));
        assertFalse(VehicleConfigurationFiles.isRelevantMappedLibraryPath(
                "/data/user/0/com.huawei.maps.car.app/lib/libPrivate.so", true));

        String declaredSharedLibrary = "/data/app/~~shared/com.vendor.navigation-randomized/lib/arm64/libPetalPlugin.so";
        assertFalse(VehicleConfigurationFiles.isAllowedPath(declaredSharedLibrary));
        assertTrue(VehicleConfigurationFiles.isRelevantMappedLibraryPath(
                declaredSharedLibrary, true, Collections.singleton(declaredSharedLibrary)));
        assertFalse(VehicleConfigurationFiles.isRelevantMappedLibraryPath(
                declaredSharedLibrary, true, Collections.emptySet()));
    }

    @Test public void sharedDependencyPathsRequireExactPackageManagerDeclaration() {
        String declared = "/data/app/~~shared/com.vendor.navigation-randomized/base.apk";
        String undeclared = "/data/app/~~other/com.vendor.other-randomized/base.apk";
        String declaredNative = "/data/app/~~shared/com.vendor.navigation-randomized/lib/arm64/libnav.so";
        Set<String> declarations = Collections.singleton(declared);

        assertFalse(VehicleConfigurationFiles.isAllowedPath(declared));
        assertTrue(VehicleConfigurationFiles.isAllowedExportPath(declared, declarations));
        assertTrue(VehicleConfigurationFiles.isPackageDeclaredDependencyPath(declaredNative));
        assertFalse(VehicleConfigurationFiles.isAllowedExportPath(declaredNative, declarations));
        assertFalse(VehicleConfigurationFiles.isAllowedExportPath(undeclared, declarations));
        assertFalse(VehicleConfigurationFiles.isAllowedExportPath(
                "/data/app/~~shared/com.vendor.navigation-randomized/files/private.db", declarations));
        assertFalse(VehicleConfigurationFiles.isAllowedExportPath(
                "/data/user/0/com.vendor.navigation/files/location-history.db", declarations));

        String command = "readlink -f " + VehicleConfigurationFiles.quotePath(declared, declared);
        assertTrue(VehicleConfigurationFiles.isAllowedCommand(command, declared));
        assertFalse(VehicleConfigurationFiles.isAllowedCommand(
                "readlink -f " + VehicleConfigurationFiles.quotePath(undeclared, undeclared), declared));
    }

    @Test public void inventoryLineStreamRetainsCompleteOutputBeyondDiagnosticBuffer() throws Exception {
        AtomicInteger received = new AtomicInteger();
        LocalAdbBridge.StreamingLineOutput output = new LocalAdbBridge.StreamingLineOutput(line -> {
            if (line.length() != 8191) throw new java.io.IOException("unexpected streamed line");
            received.incrementAndGet();
        }, "__BYDHUD_EXIT__:");
        byte[] line = new byte[8192];
        Arrays.fill(line, 0, line.length - 1, (byte) 'x');
        line[line.length - 1] = '\n';
        for (int index = 0; index < 513; index++) output.write(line);
        output.write("__BYDHUD_EXIT__:0\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        output.finish();

        LocalAdbBridge.ShellResult result = output.result(-1, true);
        assertTrue(result.success());
        assertFalse(result.truncated);
        assertEquals(513, received.get());
    }
}
