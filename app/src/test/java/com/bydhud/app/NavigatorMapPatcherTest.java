package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.jf.dexlib2.AccessFlags;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.immutable.ImmutableClassDef;
import org.jf.dexlib2.immutable.ImmutableDexFile;
import org.jf.dexlib2.rewriter.DexRewriter;
import org.jf.dexlib2.rewriter.Rewriter;
import org.jf.dexlib2.rewriter.RewriterModule;
import org.jf.dexlib2.rewriter.Rewriters;
import org.jf.dexlib2.writer.pool.DexPool;
import org.junit.Assume;
import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class NavigatorMapPatcherTest {
    private static final String MAPS_VERSION = "26.30.09.950492155";
    private static final String WAZE_VERSION = "5.20.0.1";

    @Test
    public void rejectsUnsupportedFutureVersionsBeforeOpeningApkMembers() {
        assertEquals(NavigatorMapPatcher.UNSUPPORTED,
                NavigatorMapPatcher.inspect(Collections.emptyList(), "gmaps", "26.31.0").state);
        assertEquals(NavigatorMapPatcher.UNSUPPORTED,
                NavigatorMapPatcher.inspect(Collections.emptyList(), "waze", "5.21.0.0").state);
    }

    @Test
    public void rejectsPartialPayloadWithoutTreatingItAsAnUpgrade() throws Exception {
        File temp = Files.createTempDirectory("navigator-map-partial-test-").toFile();
        try {
            File dex = new File(temp, "partial.dex");
            File apk = new File(temp, "partial-capture.apk");
            ImmutableClassDef bridgeWithoutImplementation = new ImmutableClassDef(
                    "Lcom/bydhud/mapcapture/CaptureBridge;", AccessFlags.PUBLIC.getValue(),
                    "Ljava/lang/Object;", Collections.emptyList(), null, Collections.emptySet(),
                    Collections.emptyList(), Collections.emptyList());
            DexPool.writeTo(dex.getAbsolutePath(), new ImmutableDexFile(Opcodes.forApi(29),
                    Collections.singletonList(bridgeWithoutImplementation)));
            try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(apk))) {
                output.putNextEntry(new ZipEntry("classes.dex"));
                Files.copy(dex.toPath(), output);
                output.closeEntry();
            }

            assertEquals(NavigatorMapPatcher.UNSUPPORTED,
                    NavigatorMapPatcher.inspect(Collections.singletonList(apk), "gmaps", MAPS_VERSION).state);
        } finally {
            deleteTree(temp.toPath());
        }
    }

    @Test
    public void verifiesReleasedAndDirectFixturesThenPatchesWithoutChangingNativeLibraries()
            throws Exception {
        File workspace = findWorkspaceRoot();
        Assume.assumeTrue("workspace APK fixtures are unavailable", workspace != null);
        File direct = new File(workspace, "direct-apks/release-assets/navigator-assets-v2");
        File consumer = new File(workspace, "navigator-map-probe/dist/production-consumer");
        File directMaps = new File(direct,
                "google-maps-revanced-26.30.09.950492155-direct-v2.apk");
        File directWaze = new File(direct, "waze-5.20.0.1-direct-v2.apk");
        File capturedMaps = new File(consumer, "maps-map-capture-hud-consumer.apk");
        File capturedWaze = new File(consumer, "waze-map-capture-hud-consumer.apk");
        Assume.assumeTrue("pinned Direct and released map-capture fixtures are unavailable",
                directMaps.isFile() && directWaze.isFile()
                        && capturedMaps.isFile() && capturedWaze.isFile());

        assertEquals(NavigatorMapPatcher.PATCHABLE,
                NavigatorMapPatcher.inspect(Collections.singletonList(directMaps), "gmaps", MAPS_VERSION).state);
        assertEquals(NavigatorMapPatcher.PATCHABLE,
                NavigatorMapPatcher.inspect(Collections.singletonList(directWaze), "waze", WAZE_VERSION).state);
        assertEquals(NavigatorMapPatcher.PATCHED,
                NavigatorMapPatcher.inspect(Collections.singletonList(capturedMaps), "gmaps", MAPS_VERSION).state);
        assertEquals(NavigatorMapPatcher.PATCHED,
                NavigatorMapPatcher.inspect(Collections.singletonList(capturedWaze), "waze", WAZE_VERSION).state);

        File outputRoot = Files.createTempDirectory("navigator-map-patcher-test-").toFile();
        try {
            File assembledHud = new File(workspace,
                    "byd-hud-production-oss/app/build/outputs/apk/performance/app-performance.apk");
            File mapsPayload;
            File wazePayload;
            if (assembledHud.isFile()) {
                mapsPayload = extractPayload(assembledHud, new File(outputRoot, "hud-payload.dex"));
                wazePayload = mapsPayload;
            } else {
                mapsPayload = extractPayload(capturedMaps, new File(outputRoot, "maps-payload.dex"));
                wazePayload = extractPayload(capturedWaze, new File(outputRoot, "waze-payload.dex"));
            }
            File mapsOutput = new File(outputRoot, "maps-members");
            File wazeOutput = new File(outputRoot, "waze-members");

            NavigatorMapPatcher.Inspection mapsResult = NavigatorMapPatcher.patch(
                    Collections.singletonList(directMaps), mapsOutput, mapsPayload, "gmaps", MAPS_VERSION);
            NavigatorMapPatcher.Inspection wazeResult = NavigatorMapPatcher.patch(
                    Collections.singletonList(directWaze), wazeOutput, wazePayload, "waze", WAZE_VERSION);
            assertEquals(mapsResult.reason, NavigatorMapPatcher.PATCHED, mapsResult.state);
            assertEquals(wazeResult.reason, NavigatorMapPatcher.PATCHED, wazeResult.state);

            assertOutputRetainsMembersAndNativeLibraries(directMaps,
                    new File(mapsOutput, directMaps.getName()));
            assertOutputRetainsMembersAndNativeLibraries(directWaze,
                    new File(wazeOutput, directWaze.getName()));

            File mutatedMaps = new File(outputRoot, "mutated-maps.apk");
            mutateMapsFollowingRegister(new File(mapsOutput, directMaps.getName()), mutatedMaps);
            assertEquals("moved MapsFollowing hook must no longer verify as current",
                    NavigatorMapPatcher.UNSUPPORTED,
                    NavigatorMapPatcher.inspect(Collections.singletonList(mutatedMaps),
                            "gmaps", MAPS_VERSION).state);
        } finally {
            deleteTree(outputRoot.toPath());
        }
    }

    private static File findWorkspaceRoot() throws Exception {
        File current = new File(System.getProperty("user.dir")).getCanonicalFile();
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getParentFile()) {
            File fixture = new File(current,
                    "navigator-map-probe/dist/production-consumer/maps-map-capture-hud-consumer.apk");
            if (fixture.isFile()) return current;
        }
        return null;
    }

    private static File extractPayload(File apk, File output) throws Exception {
        List<ClassDef> payloadClasses = new ArrayList<>();
        Set<String> types = new HashSet<>();
        try (ZipFile zip = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().matches("classes(\\d*)\\.dex")) continue;
                try (InputStream input = new BufferedInputStream(zip.getInputStream(entry))) {
                    DexBackedDexFile dex = DexBackedDexFile.fromInputStream(Opcodes.forApi(35), input);
                    for (ClassDef classDef : dex.getClasses()) {
                        if (!classDef.getType().startsWith(NavigatorMapPatcher.PAYLOAD_PREFIX)) continue;
                        assertTrue("duplicate map-capture class: " + classDef.getType(),
                                types.add(classDef.getType()));
                        payloadClasses.add(ImmutableClassDef.of(classDef));
                    }
                }
            }
        }
        assertTrue("No map-capture payload DEX in " + apk, !payloadClasses.isEmpty());
        DexPool.writeTo(output.getAbsolutePath(), new ImmutableDexFile(Opcodes.forApi(35), payloadClasses));
        return output;
    }

    private static void mutateMapsFollowingRegister(File input, File output) throws Exception {
        boolean[] changed = {false};
        try (ZipFile before = new ZipFile(input); ZipOutputStream after = new ZipOutputStream(
                new FileOutputStream(output))) {
            Enumeration<? extends ZipEntry> entries = before.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                after.putNextEntry(new ZipEntry(entry.getName()));
                if (entry.getName().matches("classes(\\d*)\\.dex")) {
                    DexBackedDexFile dex;
                    try (InputStream in = new BufferedInputStream(before.getInputStream(entry))) {
                        dex = DexBackedDexFile.fromInputStream(Opcodes.forApi(35), in);
                    }
                    boolean hasTarget = false;
                    for (ClassDef classDef : dex.getClasses()) {
                        if ("Laorq;".equals(classDef.getType())) hasTarget = true;
                    }
                    if (hasTarget) {
                        DexRewriter rewriter = new DexRewriter(new RewriterModule() {
                            @Override
                            public Rewriter<Method> getMethodRewriter(Rewriters rewriters) {
                                return method -> {
                                    if (!"Laorq;".equals(method.getDefiningClass())
                                            || !"h".equals(method.getName())
                                            || !method.getParameterTypes().isEmpty()
                                            || method.getImplementation() == null) return method;
                                    org.jf.dexlib2.builder.MutableMethodImplementation body =
                                            new org.jf.dexlib2.builder.MutableMethodImplementation(method.getImplementation());
                                    if (body.getInstructions().size() < 4
                                            || body.getInstructions().get(0).getOpcode()
                                            != org.jf.dexlib2.Opcode.MOVE_OBJECT) {
                                        throw new AssertionError("expected MapsFollowing hook prefix missing");
                                    }
                                    org.jf.dexlib2.iface.instruction.TwoRegisterInstruction original =
                                            (org.jf.dexlib2.iface.instruction.TwoRegisterInstruction)
                                                    body.getInstructions().get(0);
                                    if (original.getRegisterB() == 0) {
                                        throw new AssertionError("fixture receiver unexpectedly uses v0");
                                    }
                                    body.replaceInstruction(0,
                                            new org.jf.dexlib2.builder.instruction.BuilderInstruction12x(
                                                    org.jf.dexlib2.Opcode.MOVE_OBJECT,
                                                    original.getRegisterA(), 0));
                                    changed[0] = true;
                                    return new org.jf.dexlib2.immutable.ImmutableMethod(
                                            method.getDefiningClass(), method.getName(), method.getParameters(),
                                            method.getReturnType(), method.getAccessFlags(), method.getAnnotations(),
                                            method.getHiddenApiRestrictions(), body);
                                };
                            }
                        });
                        File tempDex = File.createTempFile("navigator-map-mutated-", ".dex",
                                output.getAbsoluteFile().getParentFile());
                        try {
                            DexPool.writeTo(tempDex.getAbsolutePath(),
                                    rewriter.getDexFileRewriter().rewrite(dex));
                            assertTrue("MapsFollowing target method was not rewritten", changed[0]);
                            Files.copy(tempDex.toPath(), after);
                        } finally {
                            Files.deleteIfExists(tempDex.toPath());
                        }
                    } else {
                        try (InputStream in = new BufferedInputStream(before.getInputStream(entry))) {
                            in.transferTo(after);
                        }
                    }
                } else {
                    try (InputStream in = new BufferedInputStream(before.getInputStream(entry))) {
                        in.transferTo(after);
                    }
                }
                after.closeEntry();
            }
        }
    }

    private static void assertOutputRetainsMembersAndNativeLibraries(File input, File output)
            throws Exception {
        assertTrue("patched APK missing: " + output, output.isFile());
        try (ZipFile before = new ZipFile(input); ZipFile after = new ZipFile(output)) {
            Set<String> outputNames = new HashSet<>();
            Enumeration<? extends ZipEntry> outputEntries = after.entries();
            while (outputEntries.hasMoreElements()) outputNames.add(outputEntries.nextElement().getName());

            List<String> missing = new ArrayList<>();
            Enumeration<? extends ZipEntry> originalEntries = before.entries();
            while (originalEntries.hasMoreElements()) {
                ZipEntry entry = originalEntries.nextElement();
                if (!isV1Signature(entry.getName()) && !outputNames.contains(entry.getName())) {
                    missing.add(entry.getName());
                }
                if (entry.getName().startsWith("lib/") && entry.getName().endsWith(".so")) {
                    ZipEntry patchedNative = after.getEntry(entry.getName());
                    assertTrue("native library missing: " + entry.getName(), patchedNative != null);
                    assertEquals("native library changed: " + entry.getName(),
                            sha256(before.getInputStream(entry)), sha256(after.getInputStream(patchedNative)));
                }
            }
            assertTrue("original APK members missing from patched output: " + missing, missing.isEmpty());
        }
    }

    private static boolean isV1Signature(String name) {
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        return upper.startsWith("META-INF/") && (upper.endsWith(".RSA") || upper.endsWith(".DSA")
                || upper.endsWith(".EC") || upper.endsWith(".SF") || upper.endsWith("MANIFEST.MF"));
    }

    private static String sha256(InputStream input) throws Exception {
        try (InputStream stream = input) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count);
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) result.append(String.format("%02x", value & 0xff));
            return result.toString();
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        List<Path> pathsInTree = new ArrayList<>();
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            paths.forEach(pathsInTree::add);
        }
        pathsInTree.sort(Comparator.reverseOrder());
        for (Path path : pathsInTree) Files.deleteIfExists(path);
    }
}
