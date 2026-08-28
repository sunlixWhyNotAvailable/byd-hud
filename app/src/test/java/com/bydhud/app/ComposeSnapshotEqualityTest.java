package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;

public final class ComposeSnapshotEqualityTest {
    @Test
    public void independentlyAllocatedNestedValuesAreEqualAndShareHashCodes() {
        assertValue(new MainActivity.ComposeStorageDay(
                        "20260810", "2026-08-10", 2, 42L, true, true, false),
                new MainActivity.ComposeStorageDay(
                        "20260810", "2026-08-10", 2, 42L, true, true, false));
        assertValue(packageVersion("1.0"), packageVersion("1.0"));
        assertValue(appRow("1.0"), appRow("1.0"));
        assertValue(patchRow(), patchRow());
        assertValue(patchOperation(), patchOperation());
        assertValue(assetSnapshot(), assetSnapshot());
    }

    @Test
    public void independentlyAllocatedEquivalentSnapshotsAreEqualAndShareHashCodes()
            throws Exception {
        MainActivity.ComposeSnapshot first = snapshot("1.0");
        MainActivity.ComposeSnapshot second = snapshot("1.0");

        assertNotSame(first, second);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    public void nestedValueChangeMakesSnapshotsUnequal() throws Exception {
        assertFalse(snapshot("1.0").equals(snapshot("2.0")));
    }

    private static void assertValue(Object first, Object second) {
        assertNotSame(first, second);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    private static MainActivity.ComposePackageVersion packageVersion(String version) {
        return new MainActivity.ComposePackageVersion("com.example.nav", version);
    }

    private static MainActivity.ComposeAppRow appRow(String version) {
        return new MainActivity.ComposeAppRow(
                "Example", "com.example.nav",
                Collections.singletonList(packageVersion(version)),
                true, true, false, true, true, true, false,
                false, true, false, "com.example.nav", 100);
    }

    private static MainActivity.ComposeNavigatorPatchRow patchRow() {
        return new MainActivity.ComposeNavigatorPatchRow(
                "waze", "Waze", "com.waze", "5.20.0.1", true, false,
                "installed", "5.20.0.1", NavigatorPatchStore.PATCHED,
                NavigatorPatchStore.PATCHABLE, "Stable session",
                NavigatorPatchStore.NOT_CHECKED, "Waze alerts", "", true);
    }

    private static MainActivity.ComposePatchOperation patchOperation() {
        return new MainActivity.ComposePatchOperation(
                "waze", NavigatorPatchStore.OP_RECOVERY,
                NavigatorPatchStore.RECOVERY_REQUIRED, "restore", "token", 42L,
                75, "", 0L, true, false, true, false, true);
    }

    private static NavigatorAssetManager.AssetSnapshot assetSnapshot() {
        NavigatorAssetManager.Asset asset = new NavigatorAssetManager.Asset(
                "asset", "Asset", "Ресурс", "1.0", 1L, "com.example.nav",
                "signer", "sha", "https://example.invalid/asset.apk", "asset.apk",
                NavigatorPatchStore.Profile.WAZE);
        return new NavigatorAssetManager.AssetSnapshot(
                asset, false, NavigatorAssetManager.READY, "100%", "", false);
    }

    private static MainActivity.ComposeSnapshot snapshot(String nestedVersion) throws Exception {
        Constructor<?> constructor = MainActivity.ComposeSnapshot.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        Type[] genericTypes = constructor.getGenericParameterTypes();
        Object[] arguments = new Object[parameterTypes.length];
        for (int index = 0; index < parameterTypes.length; index++) {
            Class<?> type = parameterTypes[index];
            String genericName = genericTypes[index].getTypeName();
            if (type == boolean.class) {
                arguments[index] = true;
            } else if (type == int.class) {
                arguments[index] = 1;
            } else if (type == long.class) {
                arguments[index] = 1L;
            } else if (type == String.class) {
                arguments[index] = "value";
            } else if (type == HudCheckState.class) {
                arguments[index] = new HudCheckState();
            } else if (type == DashboardWidgetState.class) {
                arguments[index] = new DashboardWidgetState();
            } else if (genericName.contains("ComposeStorageDay")) {
                arguments[index] = Collections.singletonList(new MainActivity.ComposeStorageDay(
                        "20260810", "2026-08-10", 2, 42L, true, true, false));
            } else if (genericName.contains("ComposeAppRow")) {
                arguments[index] = Collections.singletonList(appRow(nestedVersion));
            } else if (genericName.contains("ComposeNavigatorPatchRow")) {
                arguments[index] = Collections.singletonList(patchRow());
            } else if (genericName.contains("AssetSnapshot")) {
                arguments[index] = Collections.singletonList(assetSnapshot());
            } else if (genericName.contains("ComposePatchOperation")) {
                arguments[index] = Collections.singletonList(patchOperation());
            } else if (List.class.isAssignableFrom(type)) {
                arguments[index] = Collections.singletonList("path");
            } else {
                throw new AssertionError("Unhandled snapshot parameter: " + genericName);
            }
        }
        return (MainActivity.ComposeSnapshot) constructor.newInstance(arguments);
    }
}
