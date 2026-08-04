package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Assume;
import org.junit.Test;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.iface.instruction.FiveRegisterInstruction;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.NarrowLiteralInstruction;
import org.jf.dexlib2.iface.instruction.OffsetInstruction;
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.instruction.RegisterRangeInstruction;
import org.jf.dexlib2.iface.instruction.TwoRegisterInstruction;
import org.jf.dexlib2.util.ReferenceUtil;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class WazePatchEngineAlertHookTest {
    @Test
    public void realFixturesClassifyAndPatchWithoutDuplicateHook() throws Exception {
        Path stock = fixture(
                "references/navigation/waze/patched/5.20.0.1/waze_mod.apk");
        Path alerts = fixture(
                "references/navigation/waze/patched/5.20.0.1/"
                        + "waze-5.20.0.1-cluster-alerts.apk");
        Path bridge = fixture("arhud/runtime/build_outputs/waze-5.20.0.1-hud-bridge.apk");
        Assume.assumeTrue(Files.isRegularFile(stock)
                && Files.isRegularFile(alerts) && Files.isRegularFile(bridge));

        byte[] stockDex = alertDex(stock);
        assertEquals(WazePatchEngine.ALREADY_PATCHED, allowlistClassification(stock));
        WazePatchEngine.LifecycleInspection stockLifecycle = lifecycle(stock);
        assertTrue(stockLifecycle.stockTargets()
                && stockLifecycle.legacyApplicationHookCount == 0
                && stockLifecycle.legacyRouteHookCount == 0
                && stockLifecycle.legacyBridgeClassCount == 0);
        assertTrue(WazePatchEngine.inspectAlertHook(stockDex).stockTargets());
        assertEquals(WazePatchEngine.ALREADY_PATCHED, allowlistClassification(alerts));
        assertTrue(WazePatchEngine.inspectAlertHook(alertDex(alerts)).patchedTargets());
        assertEquals(WazePatchEngine.ALREADY_PATCHED, allowlistClassification(bridge));
        assertTrue(WazePatchEngine.inspectAlertHook(alertDex(bridge)).patchedTargets());
        assertTrue(lifecyclePatched(lifecycle(bridge)));

        File output = File.createTempFile("waze-alert-hook-", ".dex");
        File duplicate = File.createTempFile("waze-alert-hook-duplicate-", ".dex");
        try {
            WazePatchEngine.patchAlertHook(stockDex, output);
            byte[] outputDex = Files.readAllBytes(output.toPath());
            assertTrue(WazePatchEngine.inspectAlertHook(outputDex).patchedTargets());
            byte[] canonicalDex = alertDex(alerts);
            assertEquals(methodSignature(canonicalDex, "c"), methodSignature(outputDex, "c"));
            assertEquals(methodSignature(canonicalDex, "onCreateScreen"),
                    methodSignature(outputDex, "onCreateScreen"));
            try {
                WazePatchEngine.patchAlertHook(Files.readAllBytes(output.toPath()), duplicate);
                fail("A patched alert hook must not be patched twice");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("not compatible stock"));
            }
            assertEquals(0L, duplicate.length());
        } finally {
            output.delete();
            duplicate.delete();
        }
    }

    private static Path fixture(String relative) {
        Path cursor = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (cursor != null) {
            Path candidate = cursor.resolve(relative);
            if (Files.isRegularFile(candidate)) return candidate;
            cursor = cursor.getParent();
        }
        return Paths.get(relative);
    }

    private static byte[] alertDex(Path apk) throws Exception {
        try (ZipFile zip = new ZipFile(apk.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().matches("classes(\\d*)\\.dex")) continue;
                byte[] dex = read(zip, entry);
                if (WazePatchEngine.inspectAlertHook(dex).classCount == 1) return dex;
            }
        }
        throw new IOException("Waze alert session DEX not found in " + apk);
    }

    private static String allowlistClassification(Path apk) throws Exception {
        String classification = WazePatchEngine.UNSUPPORTED;
        int targets = 0;
        try (ZipFile zip = new ZipFile(apk.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().matches("classes(\\d*)\\.dex")) continue;
                WazePatchEngine.WazeInspection value = WazePatchEngine.inspectWaze(read(zip, entry));
                if (value.targetCount <= 0) continue;
                targets += value.targetCount;
                classification = value.classification;
            }
        }
        if (targets != 1) throw new IOException("Waze allowlist target count=" + targets);
        return classification;
    }

    private static WazePatchEngine.LifecycleInspection lifecycle(Path apk) throws Exception {
        WazePatchEngine.LifecycleInspection total = new WazePatchEngine.LifecycleInspection();
        try (ZipFile zip = new ZipFile(apk.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().matches("classes(\\d*)\\.dex")) continue;
                WazePatchEngine.LifecycleInspection value =
                        WazePatchEngine.inspectLifecycle(read(zip, entry));
                total.applicationTargetCount += value.applicationTargetCount;
                total.applicationHookCount += value.applicationHookCount;
                total.legacyApplicationHookCount += value.legacyApplicationHookCount;
                total.routeTargetCount += value.routeTargetCount;
                total.routeHookCount += value.routeHookCount;
                total.legacyRouteHookCount += value.legacyRouteHookCount;
                total.bridgeClassCount += value.bridgeClassCount;
                total.legacyBridgeClassCount += value.legacyBridgeClassCount;
                if (value.applicationTargetCount > 0) total.applicationGuard = value.applicationGuard;
                if (value.routeTargetCount > 0) total.routeGuard = value.routeGuard;
            }
        }
        return total;
    }

    private static boolean lifecyclePatched(WazePatchEngine.LifecycleInspection total) {
        return total.patchedTargets()
                || total.applicationTargetCount == 1 && total.applicationHookCount == 0
                && total.legacyApplicationHookCount == 1
                && total.routeTargetCount == 1 && total.routeHookCount == 0
                && total.legacyRouteHookCount == 1
                && total.bridgeClassCount == 0 && total.legacyBridgeClassCount == 1
                && "ok".equals(total.applicationGuard) && "ok".equals(total.routeGuard);
    }

    private static byte[] read(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream input = zip.getInputStream(entry);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[128 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static List<String> methodSignature(byte[] dex, String methodName)
            throws IOException {
        DexBackedDexFile file = DexBackedDexFile.fromInputStream(
                Opcodes.forApi(29), new ByteArrayInputStream(dex));
        for (ClassDef classDef : file.getClasses()) {
            if (!"Lcom/waze/car_lib/e/q;".equals(classDef.getType())) continue;
            for (Method method : classDef.getMethods()) {
                if (!methodName.equals(method.getName())) continue;
                MethodImplementation implementation = method.getImplementation();
                if (implementation == null) throw new IOException("Alert helper has no body");
                List<String> signature = new ArrayList<>();
                signature.add("registers=" + implementation.getRegisterCount());
                for (Instruction instruction : implementation.getInstructions()) {
                    StringBuilder value = new StringBuilder(instruction.getOpcode().name());
                    if (instruction instanceof FiveRegisterInstruction) {
                        FiveRegisterInstruction registers = (FiveRegisterInstruction) instruction;
                        value.append('|').append(registers.getRegisterCount())
                                .append(':').append(registers.getRegisterC())
                                .append(':').append(registers.getRegisterD())
                                .append(':').append(registers.getRegisterE())
                                .append(':').append(registers.getRegisterF())
                                .append(':').append(registers.getRegisterG());
                    } else if (instruction instanceof RegisterRangeInstruction) {
                        RegisterRangeInstruction registers = (RegisterRangeInstruction) instruction;
                        value.append('|').append(registers.getStartRegister())
                                .append(':').append(registers.getRegisterCount());
                    } else if (instruction instanceof TwoRegisterInstruction) {
                        TwoRegisterInstruction registers = (TwoRegisterInstruction) instruction;
                        value.append('|').append(registers.getRegisterA())
                                .append(':').append(registers.getRegisterB());
                    } else if (instruction instanceof OneRegisterInstruction) {
                        value.append('|').append(
                                ((OneRegisterInstruction) instruction).getRegisterA());
                    }
                    if (instruction instanceof NarrowLiteralInstruction) {
                        value.append("|lit=").append(
                                ((NarrowLiteralInstruction) instruction).getNarrowLiteral());
                    }
                    if (instruction instanceof OffsetInstruction) {
                        value.append("|off=").append(
                                ((OffsetInstruction) instruction).getCodeOffset());
                    }
                    if (instruction instanceof ReferenceInstruction) {
                        value.append("|ref=").append(ReferenceUtil.getReferenceString(
                                ((ReferenceInstruction) instruction).getReference()));
                    }
                    signature.add(value.toString());
                }
                return signature;
            }
        }
        throw new IOException("Waze method missing: " + methodName);
    }
}
