package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Assume;
import org.junit.Test;
import org.jf.dexlib2.AccessFlags;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.builder.MethodImplementationBuilder;
import org.jf.dexlib2.builder.instruction.BuilderInstruction10x;
import org.jf.dexlib2.builder.instruction.BuilderInstruction11x;
import org.jf.dexlib2.builder.instruction.BuilderInstruction21t;
import org.jf.dexlib2.builder.instruction.BuilderInstruction35c;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction;
import org.jf.dexlib2.iface.instruction.TwoRegisterInstruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.immutable.ImmutableMethod;
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class WazePatchEngineAlertHookTest {
    @Test
    public void compositeAlertUpgradesEachMissingComponent() throws Exception {
        Path stock = fixture(
                "references/navigation/waze/patched/5.20.0.1/waze_mod.apk");
        Assume.assumeTrue(Files.isRegularFile(stock));
        byte[] stockDex = alertDex(stock);
        File hookOnly = File.createTempFile("waze-alert-hook-only-", ".dex");
        File qOnly = File.createTempFile("waze-alert-q-only-", ".dex");
        File hookUpgrade = File.createTempFile("waze-alert-hook-upgrade-", ".dex");
        File qUpgrade = File.createTempFile("waze-alert-q-upgrade-", ".dex");
        try {
            WazePatchEngine.patchAlertComponents(stockDex, hookOnly, true, false);
            WazePatchEngine.AlertInspection hook = WazePatchEngine.inspectAlertHook(
                    Files.readAllBytes(hookOnly.toPath()));
            assertTrue(hook.patchedTargets());
            assertTrue(hook.alertOnceStockTargets());
            assertTrue(hook.patchableComponents());
            WazePatchEngine.patchAlertHook(Files.readAllBytes(hookOnly.toPath()), hookUpgrade);
            WazePatchEngine.AlertInspection hookOutput = WazePatchEngine.inspectAlertHook(
                    Files.readAllBytes(hookUpgrade.toPath()));
            assertTrue(hookOutput.patchedTargets() && hookOutput.alertOncePatchedTargets());

            WazePatchEngine.patchAlertComponents(stockDex, qOnly, false, true);
            WazePatchEngine.AlertInspection q = WazePatchEngine.inspectAlertHook(
                    Files.readAllBytes(qOnly.toPath()));
            assertTrue(q.stockTargets());
            assertTrue(q.alertOncePatchedTargets());
            assertTrue(q.patchableComponents());
            WazePatchEngine.patchAlertHook(Files.readAllBytes(qOnly.toPath()), qUpgrade);
            WazePatchEngine.AlertInspection qOutput = WazePatchEngine.inspectAlertHook(
                    Files.readAllBytes(qUpgrade.toPath()));
            assertTrue(qOutput.patchedTargets() && qOutput.alertOncePatchedTargets());
        } finally {
            hookOnly.delete();
            qOnly.delete();
            hookUpgrade.delete();
            qUpgrade.delete();
        }
    }

    @Test
    public void partialAlertOnceGuardIsRejectedByCompositeClassifier() throws Exception {
        Path stock = fixture(
                "references/navigation/waze/patched/5.20.0.1/waze_mod.apk");
        Assume.assumeTrue(Files.isRegularFile(stock));
        WazePatchEngine.AlertInspection partial = WazePatchEngine.inspectAlertHook(
                alertDex(stock));
        partial.alertOnceModeReadCount = 1;
        assertFalse(partial.alertOnceStockTargets());
        assertFalse(partial.alertOncePatchedTargets());
        assertFalse(partial.patchableComponents());

        partial = WazePatchEngine.inspectAlertHook(alertDex(stock));
        partial.alertOnceStockShapeCount = 0;
        assertFalse(partial.alertOnceStockTargets());
        assertFalse(partial.patchableComponents());
    }

    @Test
    public void realStockPatchesToBeta4AndRejectsRepatch() throws Exception {
        Path stock = fixture(
                "references/navigation/waze/patched/5.20.0.1/waze_mod.apk");
        Path alerts = fixture(
                "references/navigation/waze/patched/5.20.0.1/"
                        + "waze-5.20.0.1-cluster-alerts.apk");
        Path integrated = fixture(
                "arhud/runtime/build_outputs/"
                        + "waze-5.20.0.1-hud-bridge-alert-once.apk");
        Assume.assumeTrue(Files.isRegularFile(stock));

        byte[] stockDex = alertDex(stock);
        assertEquals(WazePatchEngine.ALREADY_PATCHED, allowlistClassification(stock));
        WazePatchEngine.LifecycleInspection stockLifecycle = lifecycle(stock);
        assertTrue(stockLifecycle.stockTargets()
                && stockLifecycle.clusterEtaStock()
                && stockLifecycle.legacyApplicationHookCount == 0
                && stockLifecycle.legacyRouteHookCount == 0
                && stockLifecycle.legacyBridgeClassCount == 0);
        assertTrue(WazePatchEngine.inspectAlertHook(stockDex).stockTargets());
        WazePatchEngine.AlertInspection stockAlert =
                WazePatchEngine.inspectAlertHook(stockDex);
        assertTrue(stockAlert.alertOnceStockTargets());
        assertTrue(stockAlert.patchableComponents());
        String stockAlertOnceI = alertOnceMethod(stockDex, "i");
        String stockAlertOnceJ = alertOnceMethod(stockDex, "j");
        byte[] integratedDex = null;
        if (Files.isRegularFile(integrated)) {
            integratedDex = alertDex(integrated);
            WazePatchEngine.AlertInspection integratedAlert =
                    WazePatchEngine.inspectAlertHook(integratedDex);
            assertTrue(integratedAlert.patchedTargets());
            assertTrue(integratedAlert.alertOncePatchedTargets());
        }
        if (Files.isRegularFile(alerts)) {
            WazePatchEngine.AlertInspection oldAlert =
                    WazePatchEngine.inspectAlertHook(alertDex(alerts));
            assertEquals(0, oldAlert.tripPublisherCallCount);
            assertFalse(oldAlert.patchedTargets());
            assertFalse(oldAlert.patchableComponents());
        }

        File lifecycleOutput = File.createTempFile("waze-lifecycle-", ".dex");
        File clusterEtaOutput = File.createTempFile("waze-cluster-eta-", ".dex");
        File clusterEtaDuplicate = File.createTempFile("waze-cluster-eta-duplicate-", ".dex");
        try {
            WazePatchEngine.patchLifecycle(lifecycleDex(stock), lifecycleOutput);
            WazePatchEngine.LifecycleInspection patchedLifecycle =
                    WazePatchEngine.inspectLifecycle(Files.readAllBytes(lifecycleOutput.toPath()));
            assertEquals(1, patchedLifecycle.applicationHookCount);
            assertEquals(1, patchedLifecycle.routeHookCount);
            assertEquals(1, patchedLifecycle.speedHookCount);
            assertEquals("ok", patchedLifecycle.speedGuard);
            WazePatchEngine.patchLifecycle(clusterEtaDex(stock), clusterEtaOutput);
            WazePatchEngine.LifecycleInspection patchedEta =
                    WazePatchEngine.inspectLifecycle(
                            Files.readAllBytes(clusterEtaOutput.toPath()));
            assertTrue(patchedEta.clusterEtaPatched());
            try {
                WazePatchEngine.patchLifecycle(
                        Files.readAllBytes(clusterEtaOutput.toPath()), clusterEtaDuplicate);
                fail("A beta.4 cluster ETA patch must not be applied twice");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("already patched"));
            }
            assertEquals(0L, clusterEtaDuplicate.length());
        } finally {
            lifecycleOutput.delete();
            clusterEtaOutput.delete();
            clusterEtaDuplicate.delete();
        }

        File output = File.createTempFile("waze-alert-hook-", ".dex");
        File duplicate = File.createTempFile("waze-alert-hook-duplicate-", ".dex");
        try {
            WazePatchEngine.patchAlertHook(stockDex, output);
            byte[] outputDex = Files.readAllBytes(output.toPath());
            WazePatchEngine.AlertInspection patched =
                    WazePatchEngine.inspectAlertHook(outputDex);
            assertTrue(patched.patchedTargets());
            assertTrue(patched.alertOncePatchedTargets());
            assertEquals(stockAlertOnceI, alertOnceMethod(outputDex, "i"));
            assertEquals(stockAlertOnceJ, alertOnceMethod(outputDex, "j"));
            if (integratedDex != null) {
                assertEquals(alertOnceMethod(integratedDex, "k"),
                        alertOnceMethod(outputDex, "k"));
            }
            assertEquals(1, patched.tripPublisherCallCount);
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

    @Test
    public void legacyNopAndAmbiguousProducerAreNotBeta4Ready() {
        assertEquals("legacy NOP patch", WazePatchEngine.inspectClusterEtaGuard(
                clusterEtaMethod(1, true)));
        String ambiguous = WazePatchEngine.inspectClusterEtaGuard(
                clusterEtaMethod(2, false));
        assertTrue(ambiguous.contains("producer mismatch stock=2, empty=0"));
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

    private static byte[] lifecycleDex(Path apk) throws Exception {
        try (ZipFile zip = new ZipFile(apk.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().matches("classes(\\d*)\\.dex")) continue;
                byte[] dex = read(zip, entry);
                WazePatchEngine.LifecycleInspection value =
                        WazePatchEngine.inspectLifecycle(dex);
                if (value.applicationTargetCount == 1 && value.routeTargetCount == 1
                        && value.speedTargetCount == 1) return dex;
            }
        }
        throw new IOException("Waze lifecycle DEX missing");
    }

    private static byte[] clusterEtaDex(Path apk) throws Exception {
        try (ZipFile zip = new ZipFile(apk.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().matches("classes(\\d*)\\.dex")) continue;
                byte[] dex = read(zip, entry);
                WazePatchEngine.LifecycleInspection value =
                        WazePatchEngine.inspectLifecycle(dex);
                if (value.clusterEtaTargetCount == 1) return dex;
            }
        }
        throw new IOException("Waze cluster ETA DEX missing");
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
                total.v1RouteHookCount += value.v1RouteHookCount;
                total.legacyRouteHookCount += value.legacyRouteHookCount;
                total.speedTargetCount += value.speedTargetCount;
                total.speedHookCount += value.speedHookCount;
                total.legacySpeedHookCount += value.legacySpeedHookCount;
                total.clusterEtaTargetCount += value.clusterEtaTargetCount;
                total.clusterEtaPatchCount += value.clusterEtaPatchCount;
                total.bridgeClassCount += value.bridgeClassCount;
                total.legacyBridgeClassCount += value.legacyBridgeClassCount;
                if (value.applicationTargetCount > 0) total.applicationGuard = value.applicationGuard;
                if (value.routeTargetCount > 0) total.routeGuard = value.routeGuard;
                if (value.speedTargetCount > 0) total.speedGuard = value.speedGuard;
                if (value.clusterEtaTargetCount > 0) {
                    total.clusterEtaGuard = value.clusterEtaGuard;
                }
            }
        }
        return total;
    }

    private static Method clusterEtaMethod(int stockProducerCount, boolean legacyNop) {
        ImmutableMethodReference producer = new ImmutableMethodReference(
                "Lcom/waze/car_lib/e/a;", "a", Collections.emptyList(),
                "Ljava/util/List;");
        ImmutableMethodReference exclusion = new ImmutableMethodReference(
                "Lcom/waze/car_lib/r/g;", "b",
                Arrays.asList("Ljava/util/List;", "Lh/c/e;"),
                "Ljava/lang/Object;");
        ImmutableMethodReference booleanValue = new ImmutableMethodReference(
                "Ljava/lang/Boolean;", "booleanValue", Collections.emptyList(), "Z");
        ImmutableMethodReference addDestination = new ImmutableMethodReference(
                "Landroidx/car/app/navigation/model/Trip$Builder;", "addDestination",
                Arrays.asList("Landroidx/car/app/navigation/model/Destination;",
                        "Landroidx/car/app/navigation/model/TravelEstimate;"),
                "Landroidx/car/app/navigation/model/Trip$Builder;");
        ImmutableMethodReference tripBuild = new ImmutableMethodReference(
                "Landroidx/car/app/navigation/model/Trip$Builder;", "build",
                Collections.emptyList(), "Landroidx/car/app/navigation/model/Trip;");
        ImmutableMethodReference updateTrip = new ImmutableMethodReference(
                "Landroidx/car/app/navigation/NavigationManager;", "updateTrip",
                Collections.singletonList("Landroidx/car/app/navigation/model/Trip;"), "V");

        MethodImplementationBuilder code = new MethodImplementationBuilder(8);
        for (int index = 0; index < stockProducerCount; index++) {
            code.addInstruction(new BuilderInstruction35c(
                    Opcode.INVOKE_INTERFACE, 1, 5, 0, 0, 0, 0, producer));
            code.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 5));
        }
        code.addInstruction(new BuilderInstruction35c(
                Opcode.INVOKE_VIRTUAL, 3, 2, 5, 7, 0, 0, exclusion));
        code.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
        code.addInstruction(new BuilderInstruction35c(
                Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0, booleanValue));
        code.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT, 0));
        if (legacyNop) {
            code.addInstruction(new BuilderInstruction10x(Opcode.NOP));
            code.addInstruction(new BuilderInstruction10x(Opcode.NOP));
        } else {
            code.addInstruction(new BuilderInstruction21t(
                    Opcode.IF_NEZ, 0, code.getLabel("build")));
        }
        code.addInstruction(new BuilderInstruction35c(
                Opcode.INVOKE_VIRTUAL, 3, 1, 3, 4, 0, 0, addDestination));
        code.addLabel("build");
        code.addInstruction(new BuilderInstruction35c(
                Opcode.INVOKE_VIRTUAL, 1, 2, 0, 0, 0, 0, tripBuild));
        code.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
        code.addInstruction(new BuilderInstruction35c(
                Opcode.INVOKE_VIRTUAL, 2, 6, 0, 0, 0, 0, updateTrip));
        code.addInstruction(new BuilderInstruction11x(Opcode.RETURN_OBJECT, 0));
        return new ImmutableMethod(
                "Lcom/waze/car_lib/j/l;", "invokeSuspend", Collections.emptyList(),
                "Ljava/lang/Object;", AccessFlags.PUBLIC.getValue(),
                Collections.emptySet(), Collections.emptySet(), code.getMethodImplementation());
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

    private static String alertOnceMethod(byte[] dex, String name) throws IOException {
        DexBackedDexFile file = DexBackedDexFile.fromInputStream(
                Opcodes.forApi(29), new java.io.ByteArrayInputStream(dex));
        for (ClassDef classDef : file.getClasses()) {
            if (!"Lcom/waze/alerters/a/q;".equals(classDef.getType())) continue;
            for (Method method : classDef.getMethods()) {
                if (!name.equals(method.getName()) || method.getImplementation() == null) continue;
                StringBuilder body = new StringBuilder();
                for (Instruction instruction : method.getImplementation().getInstructions()) {
                    body.append(instruction.getOpcode());
                    if (instruction instanceof OneRegisterInstruction) {
                        body.append(':').append(((OneRegisterInstruction) instruction).getRegisterA());
                    }
                    if (instruction instanceof TwoRegisterInstruction) {
                        body.append(':').append(((TwoRegisterInstruction) instruction).getRegisterB());
                    }
                    if (instruction instanceof ReferenceInstruction) {
                        body.append(':').append(((ReferenceInstruction) instruction).getReference());
                    }
                    body.append('\n');
                }
                return body.toString();
            }
        }
        throw new IOException("Waze alert-once method missing: " + name);
    }

}
