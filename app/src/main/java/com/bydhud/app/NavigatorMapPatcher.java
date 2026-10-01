package com.bydhud.app;

import com.android.zipflinger.BytesSource;
import com.android.zipflinger.ZipArchive;

import org.jf.dexlib2.AccessFlags;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.builder.Label;
import org.jf.dexlib2.builder.MutableMethodImplementation;
import org.jf.dexlib2.builder.instruction.BuilderInstruction10x;
import org.jf.dexlib2.builder.instruction.BuilderInstruction11n;
import org.jf.dexlib2.builder.instruction.BuilderInstruction11x;
import org.jf.dexlib2.builder.instruction.BuilderInstruction12x;
import org.jf.dexlib2.builder.instruction.BuilderInstruction21t;
import org.jf.dexlib2.builder.instruction.BuilderInstruction3rc;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Field;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.instruction.FiveRegisterInstruction;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.NarrowLiteralInstruction;
import org.jf.dexlib2.iface.instruction.OffsetInstruction;
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.instruction.RegisterRangeInstruction;
import org.jf.dexlib2.iface.instruction.TwoRegisterInstruction;
import org.jf.dexlib2.iface.reference.FieldReference;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.iface.reference.StringReference;
import org.jf.dexlib2.iface.reference.TypeReference;
import org.jf.dexlib2.immutable.ImmutableMethod;
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference;
import org.jf.dexlib2.rewriter.DexRewriter;
import org.jf.dexlib2.rewriter.Rewriter;
import org.jf.dexlib2.rewriter.RewriterModule;
import org.jf.dexlib2.rewriter.Rewriters;
import org.jf.dexlib2.writer.pool.DexPool;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * On-device port of navigator-map-probe/tools/PatchCapture.java.
 * The source DEX and payload contracts are verified against Maps r11 and Waze r8.
 */
public final class NavigatorMapPatcher {
    public static final String PATCHABLE = "PATCHABLE";
    public static final String PATCHED = "PATCHED";
    public static final String UNSUPPORTED = "UNSUPPORTED";
    public static final String FAILED = "FAILED";
    public static final String PAYLOAD_PREFIX = "Lcom/bydhud/mapcapture/";
    public static final String REVISION = "navigator-map-capture-r1";

    private static final String BRIDGE = "Lcom/bydhud/mapcapture/CaptureBridge;";
    private static final String BACKGROUND = "Lcom/bydhud/mapcapture/BackgroundCapture;";
    private static final String BACKGROUND_TARGET = "Lcom/bydhud/mapcapture/BackgroundCapture$Target;";
    private static final String BACKGROUND_D8_ACCESS = "Lcom/bydhud/mapcapture/BackgroundCapture-IA;";
    private static final int MAX_DEX_BYTES = 64 * 1024 * 1024;
    private static final int MAX_METHOD_IDS = 65536;
    private static final Set<String> PAYLOAD_ROOTS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("CaptureBridge", "BackgroundCapture", "CaptureLease",
                    "CapturePollPolicy", "CarCapturePolicy", "MapsFollowing",
                    "MapsNavigationCamera", "PixelMath")));
    private static final Set<String> REQUIRED_PAYLOAD_CLASSES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(BRIDGE, BACKGROUND,
                    "Lcom/bydhud/mapcapture/CaptureLease;",
                    "Lcom/bydhud/mapcapture/CapturePollPolicy;",
                    "Lcom/bydhud/mapcapture/CarCapturePolicy;",
                    "Lcom/bydhud/mapcapture/MapsFollowing;",
                    "Lcom/bydhud/mapcapture/MapsNavigationCamera;",
                    "Lcom/bydhud/mapcapture/PixelMath;")));

    public static final class Inspection {
        public final String state;
        public final String reason;
        public final String revision;

        private Inspection(String state, String reason) {
            this.state = state;
            this.reason = reason;
            this.revision = REVISION;
        }
    }

    private static final class Hook {
        final String name;
        final boolean guard;
        final boolean end;
        final List<String> args;

        Hook(String name, boolean guard, boolean end, String... parameters) {
            this.name = name;
            this.guard = guard;
            this.end = end;
            this.args = new ArrayList<>();
            this.args.add("Ljava/lang/Object;");
            this.args.addAll(Arrays.asList(parameters));
        }
    }

    private static final class DexUnit {
        final File apk;
        final String name;
        final DexBackedDexFile dex;
        final Set<String> classes;
        final boolean hasTargets;
        final boolean hasPayload;
        final boolean hasPayloadReferences;

        DexUnit(File apk, String name, DexBackedDexFile dex, Set<String> classes,
                boolean hasTargets, boolean hasPayload, boolean hasPayloadReferences) {
            this.apk = apk;
            this.name = name;
            this.dex = dex;
            this.classes = classes;
            this.hasTargets = hasTargets;
            this.hasPayload = hasPayload;
            this.hasPayloadReferences = hasPayloadReferences;
        }
    }

    private static final class PayloadInfo {
        final Set<String> classes = new HashSet<>();
        final Map<String, ClassDef> definitions = new HashMap<>();
        final Map<String, Method> methods = new HashMap<>();
        final Set<String> bridgeStrings = new HashSet<>();
        final Set<String> unshippedReferences = new HashSet<>();
        int implementedMethods;
        int instructionCount;
    }

    private static final class Analysis {
        final List<File> files = new ArrayList<>();
        final List<DexUnit> dexUnits = new ArrayList<>();
        final List<DexUnit> targetDex = new ArrayList<>();
        final List<DexUnit> payloadDex = new ArrayList<>();
        final Set<String> targetOwners = new HashSet<>();
        final Map<String, Map<String, Integer>> callsByTargetMethod = new HashMap<>();
        final Map<String, Integer> actualCalls = new TreeMap<>();
        final Set<File> targetApks = new HashSet<>();
        boolean hasAnyPayloadClass;
        boolean hasAnyPayloadReference;
        boolean unexpectedPayloadCallOwner;
        boolean duplicateRelevantClass;
        int payloadClassCount;
        int maxPayloadMethods;
        PayloadInfo payload;
    }

    private static final class PatchPlan {
        final String profile;
        final Map<String, Hook> lifecycle;
        final Map<String, Integer> anchorCounts = new TreeMap<>();
        final Map<String, Integer> calls = new TreeMap<>();

        PatchPlan(String profile) {
            this.profile = profile;
            this.lifecycle = lifecycle(profile);
        }

        void count(String key) {
            anchorCounts.put(key, anchorCounts.getOrDefault(key, 0) + 1);
        }

        void call(MethodReference reference) {
            String key = key(reference);
            calls.put(key, calls.getOrDefault(key, 0) + 1);
        }
    }

    private NavigatorMapPatcher() {
    }

    public static Inspection inspect(List<File> apks, String profile, String version) {
        try {
            return inspectInternal(apks, profile, version);
        } catch (UnsupportedPatchException error) {
            return result(UNSUPPORTED, message(error));
        } catch (IOException | RuntimeException error) {
            return result(FAILED, message(error));
        }
    }

    /** Writes a complete candidate set, preserving untouched APK members byte-for-byte. */
    public static Inspection patch(List<File> sourceApks, File outputDir, File payloadDex,
            String profile, String version) {
        try {
            Inspection input = inspectInternal(sourceApks, profile, version);
            if (PATCHED.equals(input.state)) {
                copyMembers(sourceApks, outputDir);
                Inspection copied = inspectInternal(outputMembers(sourceApks, outputDir),
                        profile, version);
                return PATCHED.equals(copied.state) ? copied
                        : result(FAILED, "copied capture set failed verification: " + copied.reason);
            }
            if (!PATCHABLE.equals(input.state)) return input;

            String family = normalizeProfile(profile);
            Analysis source = analyze(sourceApks, family);
            PayloadInfo suppliedPayload = inspectPayloadFile(payloadDex);
            verifyPayload(suppliedPayload, family);
            requireSupportedTargets(source, family);

            File parent = outputDir.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("cannot create candidate parent");
            }
            if (!outputDir.exists() && !outputDir.mkdirs()) {
                throw new IOException("cannot create candidate directory");
            }
            File work = new File(outputDir, ".map-capture-" + UUID.randomUUID());
            if (!work.mkdir()) throw new IOException("cannot create map-capture staging directory");
            try {
                Map<File, Map<String, File>> replacements = new HashMap<>();
                PatchPlan total = new PatchPlan(family);
                int targetDexCount = 0;
                java.util.Iterator<DexUnit> targetDexIterator = source.targetDex.iterator();
                while (targetDexIterator.hasNext()) {
                    DexUnit unit = targetDexIterator.next();
                    PatchPlan one = new PatchPlan(family);
                    DexFile changed = rewrite(unit.dex, one);
                    verifyDexMethodLimit(unit.dex, one.calls);
                    merge(total.anchorCounts, one.anchorCounts);
                    merge(total.calls, one.calls);

                    File dexFile = new File(work, UUID.randomUUID() + ".dex");
                    DexPool.writeTo(dexFile.getAbsolutePath(), changed);
                    replacements.computeIfAbsent(unit.apk.getCanonicalFile(), ignored -> new HashMap<>())
                            .put(unit.name, dexFile);
                    targetDexCount++;
                    targetDexIterator.remove();
                    source.dexUnits.remove(unit);
                }
                if (targetDexCount == 0 || !total.anchorCounts.equals(expectedAnchorCounts(family))) {
                    throw new UnsupportedPatchException("source did not match the complete map-capture target");
                }
                if (!total.calls.equals(expectedHookRefs(family))) {
                    throw new UnsupportedPatchException("generated hook inventory differs from the verified profile");
                }

                File targetApk = onlyTargetApk(source);
                int dexNumber = maxDexNumber(targetApk) + 1;
                if (dexNumber < 2) throw new IOException("invalid payload DEX number");
                String payloadName = "classes" + dexNumber + ".dex";
                if (containsEntry(targetApk, payloadName)) {
                    throw new IOException("payload DEX entry already exists");
                }
                Map<String, File> additions = Collections.singletonMap(payloadName, payloadDex);

                List<File> candidates = new ArrayList<>();
                for (File sourceApk : sourceApks) {
                    File candidate = new File(work, sourceApk.getName());
                    File canonicalSource = sourceApk.getCanonicalFile();
                    Map<String, File> dexChanges = replacements.get(canonicalSource);
                    if (canonicalSource.equals(targetApk)) {
                        repack(sourceApk, candidate, dexChanges, additions);
                    } else if (dexChanges != null) {
                        repack(sourceApk, candidate, dexChanges, Collections.emptyMap());
                    } else {
                        Files.copy(sourceApk.toPath(), candidate.toPath(),
                                StandardCopyOption.REPLACE_EXISTING);
                    }
                    candidates.add(candidate);
                }

                Inspection post = inspectInternal(candidates, family, version);
                if (!PATCHED.equals(post.state)) {
                    throw new IOException("post-patch structural verification failed: " + post.reason);
                }
                for (File candidate : candidates) {
                    Files.copy(candidate.toPath(), new File(outputDir, candidate.getName()).toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                Inspection output = inspectInternal(outputMembers(sourceApks, outputDir), family, version);
                if (!PATCHED.equals(output.state)) {
                    throw new IOException("published candidate verification failed: " + output.reason);
                }
                return output;
            } finally {
                deleteTree(work);
            }
        } catch (UnsupportedPatchException error) {
            return result(UNSUPPORTED, message(error));
        } catch (IOException | RuntimeException error) {
            return result(FAILED, message(error));
        }
    }

    private static Inspection inspectInternal(List<File> apks, String profile, String version)
            throws IOException {
        String family = normalizeProfile(profile);
        if (family == null) return result(UNSUPPORTED, "unsupported navigator profile");
        if (!supportedVersion(family, version)) {
            return result(UNSUPPORTED, "unsupported " + family + " map-capture version: " + safe(version));
        }
        Analysis analysis = analyze(apks, family);
        if (analysis.duplicateRelevantClass) {
            return result(UNSUPPORTED, "duplicate map-capture or target class across APK members");
        }
        if (!analysis.hasAnyPayloadClass && !analysis.hasAnyPayloadReference) {
            try {
                requireSupportedTargets(analysis, family);
                PatchPlan total = new PatchPlan(family);
                for (DexUnit unit : analysis.targetDex) {
                    PatchPlan one = new PatchPlan(family);
                    rewrite(unit.dex, one);
                    verifyDexMethodLimit(unit.dex, one.calls);
                    merge(total.anchorCounts, one.anchorCounts);
                    merge(total.calls, one.calls);
                }
                if (!total.anchorCounts.equals(expectedAnchorCounts(family))
                        || !total.calls.equals(expectedHookRefs(family))) {
                    return result(UNSUPPORTED, "Direct DEX hook inventory mismatch; anchors="
                            + total.anchorCounts + " refs=" + total.calls);
                }
                if (analysis.targetApks.size() != 1) {
                    return result(UNSUPPORTED, "map target methods are split across APK members");
                }
                return result(PATCHABLE, "supported Direct-only " + family + " " + version);
            } catch (UnsupportedPatchException error) {
                return result(UNSUPPORTED, message(error));
            }
        }

        try {
            verifyCaptured(analysis, family);
            return result(PATCHED, "complete " + family + " map-capture payload and hooks verified");
        } catch (UnsupportedPatchException error) {
            return result(UNSUPPORTED, message(error));
        }
    }

    private static Analysis analyze(List<File> apks, String family) throws IOException {
        if (apks == null || apks.isEmpty()) throw new UnsupportedPatchException("no APK members supplied");
        Analysis result = new Analysis();
        Set<String> canonicalFiles = new HashSet<>();
        Set<String> relevantClasses = new HashSet<>();
        Set<String> targetOwners = targetOwners(family);
        for (File file : apks) {
            if (file == null || !file.isFile() || !file.canRead()) {
                throw new IOException("APK member is unreadable");
            }
            String canonical = file.getCanonicalPath();
            if (!canonicalFiles.add(canonical)) throw new UnsupportedPatchException("duplicate APK member supplied");
            result.files.add(file);
            try (ZipFile zip = new ZipFile(file)) {
                Set<String> names = new HashSet<>();
                java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (!names.add(entry.getName())) {
                        throw new UnsupportedPatchException("duplicate ZIP entry in " + file.getName());
                    }
                    if (!entry.getName().matches("classes(\\d*)\\.dex")) continue;
                    byte[] bytes = readEntry(zip, entry, MAX_DEX_BYTES);
                    DexBackedDexFile dex = DexBackedDexFile.fromInputStream(Opcodes.forApi(35),
                            new ByteArrayInputStream(bytes));
                    Set<String> classes = new HashSet<>();
                    boolean hasTargets = false;
                    boolean hasPayload = false;
                    for (ClassDef classDef : dex.getClasses()) {
                        String type = classDef.getType();
                        classes.add(type);
                        if (targetOwners.contains(type)) {
                            hasTargets = true;
                            result.targetOwners.add(type);
                            if (!relevantClasses.add(type)) result.duplicateRelevantClass = true;
                        }
                        if (type.startsWith(PAYLOAD_PREFIX)) {
                            hasPayload = true;
                            result.hasAnyPayloadClass = true;
                            result.payloadClassCount++;
                            if (!relevantClasses.add(type)) result.duplicateRelevantClass = true;
                        }
                    }
                    if (hasTargets && hasPayload) {
                        throw new UnsupportedPatchException("target code and payload share one DEX");
                    }
                    boolean hasPayloadReferences = false;
                    for (int index = 0; index < dex.getMethodSection().size(); index++) {
                        MethodReference method = dex.getMethodSection().get(index);
                        if (method.getDefiningClass().startsWith(PAYLOAD_PREFIX)) {
                            hasPayloadReferences = true;
                            result.hasAnyPayloadReference = true;
                        }
                    }
                    if (hasTargets) result.targetApks.add(file.getCanonicalFile());
                    if (hasPayloadReferences && !hasPayload && !hasTargets) {
                        result.unexpectedPayloadCallOwner = true;
                    }
                    if (hasTargets || hasPayload || hasPayloadReferences) {
                        DexUnit unit = new DexUnit(file, entry.getName(), dex, classes,
                                hasTargets, hasPayload, hasPayloadReferences);
                        result.dexUnits.add(unit);
                        if (hasTargets) result.targetDex.add(unit);
                        if (hasPayload) result.payloadDex.add(unit);
                    }
                }
            }
        }
        for (DexUnit unit : result.targetDex) collectCalls(unit, family, result);
        if (!result.payloadDex.isEmpty()) {
            if (result.payloadDex.size() != 1) {
                throw new UnsupportedPatchException("map-capture payload spans multiple DEX files");
            }
            result.payload = inspectPayload(result.payloadDex.get(0).dex);
        }
        return result;
    }

    private static void collectCalls(DexUnit unit, String family, Analysis analysis) {
        Set<String> allowed = targetOwners(family);
        for (ClassDef classDef : unit.dex.getClasses()) {
            for (Method method : classDef.getMethods()) {
                if (method.getImplementation() == null) continue;
                String methodKey = key(method);
                for (Instruction instruction : method.getImplementation().getInstructions()) {
                    if (!(instruction instanceof ReferenceInstruction)) continue;
                    Object reference = ((ReferenceInstruction) instruction).getReference();
                    if (!(reference instanceof MethodReference)) continue;
                    MethodReference called = (MethodReference) reference;
                    if (!called.getDefiningClass().startsWith(PAYLOAD_PREFIX)) continue;
                    analysis.hasAnyPayloadReference = true;
                    if (!allowed.contains(method.getDefiningClass())) {
                        analysis.unexpectedPayloadCallOwner = true;
                        continue;
                    }
                    String calledKey = key(called);
                    Map<String, Integer> byCall = analysis.callsByTargetMethod.computeIfAbsent(
                            methodKey, ignored -> new TreeMap<>());
                    byCall.put(calledKey, byCall.getOrDefault(calledKey, 0) + 1);
                    analysis.actualCalls.put(calledKey,
                            analysis.actualCalls.getOrDefault(calledKey, 0) + 1);
                }
            }
        }
    }

    private static void requireSupportedTargets(Analysis analysis, String family)
            throws UnsupportedPatchException {
        Set<String> required = targetOwners(family);
        if (!analysis.targetOwners.containsAll(required)) {
            Set<String> missing = new HashSet<>(required);
            missing.removeAll(analysis.targetOwners);
            throw new UnsupportedPatchException("missing " + family + " target classes: " + missing);
        }
        if (analysis.targetApks.size() != 1) {
            throw new UnsupportedPatchException("map target methods are split across APK members");
        }
    }

    private static void verifyCaptured(Analysis analysis, String family)
            throws UnsupportedPatchException {
        requireSupportedTargets(analysis, family);
        if (analysis.hasAnyPayloadClass != analysis.hasAnyPayloadReference
                || analysis.unexpectedPayloadCallOwner) {
            throw new UnsupportedPatchException("partial or misplaced map-capture hooks detected");
        }
        if (!analysis.actualCalls.equals(expectedHookRefs(family))) {
            Map<String, Integer> expected = expectedHookRefs(family);
            throw new UnsupportedPatchException("map-capture hook references differ; missing="
                    + deficits(expected, analysis.actualCalls) + " extra="
                    + deficits(analysis.actualCalls, expected));
        }
        verifyHookPlacement(analysis, family);
        if (analysis.payload == null) throw new UnsupportedPatchException("map-capture payload DEX is missing");
        verifyPayload(analysis.payload, family);
        for (DexUnit unit : analysis.dexUnits) {
            if (unit.dex.getMethodSection().size() > MAX_METHOD_IDS) {
                throw new UnsupportedPatchException("DEX method reference limit exceeded: " + unit.name);
            }
        }
    }

    private static void verifyHookPlacement(Analysis analysis, String family)
            throws UnsupportedPatchException {
        Map<String, Map<String, Integer>> expected = expectedCallsByAnchor(family);
        Map<String, Map<String, Integer>> actual = analysis.callsByTargetMethod;
        Map<String, Integer> constructors = new TreeMap<>();
        List<String> constructorKeys = new ArrayList<>();
        for (Map.Entry<String, Map<String, Integer>> entry : actual.entrySet()) {
            if (entry.getKey().startsWith("Lbpey;-><init>(")) {
                constructorKeys.add(entry.getKey());
                merge(constructors, entry.getValue());
            }
        }
        if ("gmaps".equals(family)) {
            if (constructorKeys.size() != 1) {
                throw new UnsupportedPatchException("Maps capture constructor hook count changed");
            }
            Map<String, Integer> expectedConstructor = new TreeMap<>();
            add(expectedConstructor, methodKey(BRIDGE, "trackMaps",
                    Collections.singletonList("Ljava/lang/Object;"), "V"), 2);
            add(expectedConstructor, methodKey(BRIDGE, "init",
                    Collections.singletonList("Landroid/content/Context;"), "V"), 1);
            if (!constructors.equals(expectedConstructor)) {
                throw new UnsupportedPatchException("Maps constructor hook shape changed");
            }
        } else if (!constructorKeys.isEmpty()) {
            throw new UnsupportedPatchException("unexpected Maps hooks in Waze APK");
        }
        for (String constructor : constructorKeys) actual.remove(constructor);
        if ("waze".equals(family)) {
            addDynamicWazeAnchor(expected, actual,
                    "Lcom/waze/map/opengl/w;->l(", "Waze swap hook target method count changed");
            addDynamicWazeAnchor(expected, actual,
                    "Lcom/waze/map/NativeCanvasRenderer;->onDrawFrame(",
                    "Waze canvas hook target method count changed");
        }
        if (!actual.equals(expected)) {
            throw new UnsupportedPatchException("map-capture hooks are attached to unexpected target methods");
        }
        Map<String, Hook> lifecycle = lifecycle(family);
        for (Map.Entry<String, Hook> entry : lifecycle.entrySet()) {
            if (!"carRendererReady".equals(entry.getValue().name)) {
                verifyLifecyclePlacement(findTargetMethod(analysis, entry.getKey()), entry.getKey(), entry.getValue());
            }
        }
        if ("gmaps".equals(family)) {
            verifyMapsConstructor(findTargetMethod(analysis, constructorKeys.get(0)));
            verifyMapsCameraPlacement(findTargetMethod(analysis,
                    methodKey("Lbrer;", "pF", Arrays.asList("Lbrha;", "Lbrha;"), "V")));
        } else {
            verifyEntryPlacement(findTargetMethod(analysis,
                    methodKey("Lcom/waze/mobile/WazeMobileApplication;", "onCreate",
                            Collections.emptyList(), "V")),
                    methodKey(BRIDGE, "init", Collections.singletonList("Landroid/content/Context;"), "V"));
            verifyWazeSwapPlacement(findTargetMethod(analysis,
                    methodKey("Lcom/waze/map/opengl/w;", "l", Collections.emptyList(), "Z")));
        }
        for (DexUnit unit : analysis.targetDex) {
            for (ClassDef classDef : unit.dex.getClasses()) {
                for (Method method : classDef.getMethods()) {
                    verifyCarRendererHook(method);
                    if ("waze".equals(family)) verifyWazeCanvasHook(method);
                }
            }
        }
    }

    private static Method findTargetMethod(Analysis analysis, String methodKey)
            throws UnsupportedPatchException {
        Method found = null;
        for (DexUnit unit : analysis.targetDex) {
            for (ClassDef classDef : unit.dex.getClasses()) {
                for (Method method : classDef.getMethods()) {
                    if (!methodKey.equals(key(method))) continue;
                    if (found != null) throw new UnsupportedPatchException("duplicate target method: " + methodKey);
                    found = method;
                }
            }
        }
        if (found == null || found.getImplementation() == null) {
            throw new UnsupportedPatchException("hook anchor method missing: " + methodKey);
        }
        return found;
    }

    private static void verifyLifecyclePlacement(Method method, String methodKey, Hook hook)
            throws UnsupportedPatchException {
        List<Instruction> instructions = instructions(method);
        int receiver = parameterStart(method);
        String returnType = hook.guard ? "Z" : "V";
        String callKey = "mapsFollowing".equals(hook.name)
                ? methodKey(BACKGROUND, "mapsFollowing", Arrays.asList(
                        "Ljava/lang/Object;", "I", "Ljava/lang/Object;"), "Z")
                : methodKey(BACKGROUND, hook.name, hook.args, returnType);
        if ("mapsFollowing".equals(hook.name)) {
            verifyMapsFollowingPrefix(method, methodKey, callKey, receiver, hook.guard);
            return;
        }
        if (hook.guard) {
            if (instructions.size() < 4
                    || !isRangeCall(instructions.get(0), callKey, receiver, hook.args.size())
                    || instructions.get(1).getOpcode() != Opcode.MOVE_RESULT
                    || !isRegister(instructions.get(1), 0)
                    || instructions.get(2).getOpcode() != Opcode.IF_EQZ
                    || !isRegister(instructions.get(2), 0)
                    || instructions.get(3).getOpcode() != Opcode.RETURN_VOID
                    || !branchTargets(instructions, 2, 4)) {
                throw new UnsupportedPatchException("guard hook prefix changed: " + methodKey);
            }
            requireCallCount(instructions, callKey, 1, methodKey);
        } else if (hook.end) {
            int returns = 0;
            int calls = 0;
            for (int i = 0; i < instructions.size(); i++) {
                if (instructions.get(i).getOpcode() == Opcode.RETURN_VOID) {
                    returns++;
                    if (i == 0 || !isRangeCall(instructions.get(i - 1), callKey,
                            receiver, hook.args.size())) {
                        throw new UnsupportedPatchException("end hook is not before every return: " + methodKey);
                    }
                }
                if (isMethodCall(instructions.get(i), callKey)) {
                    calls++;
                    if (i == 0 || instructions.get(i - 1).getOpcode() == Opcode.RETURN_VOID) {
                        throw new UnsupportedPatchException("end hook moved from return: " + methodKey);
                    }
                }
            }
            if (returns == 0 || calls != returns) {
                throw new UnsupportedPatchException("end hook return inventory changed: " + methodKey);
            }
        } else {
            if (instructions.isEmpty()
                    || !isRangeCall(instructions.get(0), callKey, receiver, hook.args.size())) {
                throw new UnsupportedPatchException("entry hook is not at method entry: " + methodKey);
            }
            requireCallCount(instructions, callKey, 1, methodKey);
        }
    }

    private static void verifyMapsFollowingPrefix(Method method, String methodKey,
            String callKey, int receiver, boolean guard) throws UnsupportedPatchException {
        List<Instruction> instructions = instructions(method);
        String name = method.getName();
        int phase = name.equals("g") ? 0 : name.equals("h") ? 1 : name.equals("d") ? 2 : 3;
        int invokeIndex = phase == 3 ? 5 : 3;
        if (instructions.size() <= invokeIndex
                || !isMoveObject(instructions.get(0), 0, receiver)
                || !isConst4(instructions.get(1), 1, phase)
                || !isMoveObject(instructions.get(2), 2, phase == 3 ? receiver + 2 : receiver)) {
            throw new UnsupportedPatchException("Maps following register prefix changed: " + methodKey);
        }
        if (phase == 3 && (!isRegister(instructions.get(3), 2)
                || instructions.get(3).getOpcode() != Opcode.IF_NEZ
                || !branchTargets(instructions, 3, invokeIndex)
                || !isMoveObject(instructions.get(4), 2, receiver + 1))) {
            throw new UnsupportedPatchException("Maps following request branch changed: " + methodKey);
        }
        if (!isRangeCall(instructions.get(invokeIndex), callKey, 0, 3)) {
            throw new UnsupportedPatchException("Maps following hook anchor changed: " + methodKey);
        }
        if (guard) {
            int resultIndex = invokeIndex + 1;
            if (instructions.size() <= invokeIndex + 4
                    || instructions.get(resultIndex).getOpcode() != Opcode.MOVE_RESULT
                    || !isRegister(instructions.get(resultIndex), 0)
                    || instructions.get(resultIndex + 1).getOpcode() != Opcode.IF_EQZ
                    || !isRegister(instructions.get(resultIndex + 1), 0)
                    || instructions.get(resultIndex + 2).getOpcode() != Opcode.RETURN_VOID
                    || !branchTargets(instructions, resultIndex + 1, invokeIndex + 4)) {
                throw new UnsupportedPatchException("Maps following guard target changed: " + methodKey);
            }
        }
        requireCallCount(instructions, callKey, 1, methodKey);
    }

    private static void verifyMapsConstructor(Method constructor) throws UnsupportedPatchException {
        List<Instruction> instructions = instructions(constructor);
        int receiver = parameterStart(constructor);
        String initKey = methodKey(BRIDGE, "init", Collections.singletonList("Landroid/content/Context;"), "V");
        String trackKey = methodKey(BRIDGE, "trackMaps", Collections.singletonList("Ljava/lang/Object;"), "V");
        int superCalls = 0;
        int initCalls = 0;
        int returns = 0;
        int trackCalls = 0;
        for (int i = 0; i < instructions.size(); i++) {
            Instruction instruction = instructions.get(i);
            if (instruction.getOpcode() == Opcode.INVOKE_DIRECT && isMethodCall(instruction,
                    methodKey("Ljava/lang/Object;", "<init>", Collections.emptyList(), "V"))
                    && invocationReceiver(instruction) == receiver) {
                superCalls++;
                if (i + 1 >= instructions.size()
                        || !isRangeCall(instructions.get(i + 1), initKey, receiver + 1, 1)) {
                    throw new UnsupportedPatchException("Maps init hook is not after Object.<init>");
                }
            }
            if (isMethodCall(instruction, initKey)) initCalls++;
            if (instruction.getOpcode() == Opcode.RETURN_VOID) {
                returns++;
                if (i == 0 || !isRangeCall(instructions.get(i - 1), trackKey, receiver, 1)) {
                    throw new UnsupportedPatchException("Maps track hook is missing before constructor return");
                }
            }
            if (isMethodCall(instruction, trackKey)) trackCalls++;
        }
        if (superCalls != 1 || initCalls != 1 || returns != 2 || trackCalls != returns) {
            throw new UnsupportedPatchException("Maps constructor hook anchor inventory changed");
        }
    }

    private static void verifyMapsCameraPlacement(Method method) throws UnsupportedPatchException {
        List<Instruction> instructions = instructions(method);
        String anchor = methodKey("Lbrfd;", "f", Arrays.asList("Lcksk;", "Lbrfe;"), "V");
        String hook = methodKey(BACKGROUND, "mapsNavigationApplied",
                Collections.singletonList("Ljava/lang/Object;"), "V");
        int anchors = 0;
        int calls = 0;
        for (int i = 0; i < instructions.size(); i++) {
            if (!isMethodCall(instructions.get(i), anchor)) continue;
            anchors++;
            int receiver = invocationReceiver(instructions.get(i));
            if (i + 1 >= instructions.size() || !isRangeCall(instructions.get(i + 1), hook, receiver, 1)) {
                throw new UnsupportedPatchException("Maps camera hook is not after its state application call");
            }
        }
        for (Instruction instruction : instructions) if (isMethodCall(instruction, hook)) calls++;
        if (anchors != 1 || calls != 1) {
            throw new UnsupportedPatchException("Maps camera hook anchor inventory changed");
        }
    }

    private static void verifyEntryPlacement(Method method, String hookKey)
            throws UnsupportedPatchException {
        List<Instruction> instructions = instructions(method);
        if (instructions.isEmpty() || !isRangeCall(instructions.get(0), hookKey,
                parameterStart(method), 1)) {
            throw new UnsupportedPatchException("entry hook is not at method entry: " + key(method));
        }
        requireCallCount(instructions, hookKey, 1, key(method));
    }

    private static void verifyWazeSwapPlacement(Method method) throws UnsupportedPatchException {
        List<Instruction> instructions = instructions(method);
        String anchor = methodKey("Ljavax/microedition/khronos/egl/EGL10;", "eglSwapBuffers",
                Arrays.asList("Ljavax/microedition/khronos/egl/EGLDisplay;",
                        "Ljavax/microedition/khronos/egl/EGLSurface;"), "Z");
        int receiver = parameterStart(method);
        int anchors = 0;
        int hooks = 0;
        for (int i = 0; i < instructions.size(); i++) {
            if (isMethodCall(instructions.get(i), anchor)) {
                anchors++;
                if (i == 0 || !isRangeCall(instructions.get(i - 1),
                        methodKey(BRIDGE, "wazeFrame", Collections.singletonList("Ljava/lang/Object;"), "V"),
                        receiver, 1)) {
                    throw new UnsupportedPatchException("Waze frame hook is not before eglSwapBuffers");
                }
            }
            if (isWazeFrameHook(instructions.get(i))) hooks++;
        }
        if (anchors != 1 || hooks != anchors) {
            throw new UnsupportedPatchException("Waze swap hook anchor inventory changed");
        }
    }

    private static List<Instruction> instructions(Method method) throws UnsupportedPatchException {
        if (method.getImplementation() == null) {
            throw new UnsupportedPatchException("hook anchor has no implementation: " + key(method));
        }
        List<Instruction> result = new ArrayList<>();
        method.getImplementation().getInstructions().forEach(result::add);
        return result;
    }

    private static int parameterStart(Method method) {
        int words = 1;
        for (CharSequence type : method.getParameterTypes()) {
            String descriptor = type.toString();
            words += descriptor.equals("J") || descriptor.equals("D") ? 2 : 1;
        }
        return method.getImplementation().getRegisterCount() - words;
    }

    private static boolean isRangeCall(Instruction instruction, String methodKey,
            int firstRegister, int registerCount) {
        if (instruction.getOpcode() != Opcode.INVOKE_STATIC_RANGE
                || !(instruction instanceof RegisterRangeInstruction)
                || !(instruction instanceof ReferenceInstruction)
                || !(((ReferenceInstruction) instruction).getReference() instanceof MethodReference)) return false;
        RegisterRangeInstruction range = (RegisterRangeInstruction) instruction;
        return range.getStartRegister() == firstRegister && range.getRegisterCount() == registerCount
                && methodKey.equals(key((MethodReference) ((ReferenceInstruction) instruction).getReference()));
    }

    private static boolean isMethodCall(Instruction instruction, String methodKey) {
        return instruction instanceof ReferenceInstruction
                && ((ReferenceInstruction) instruction).getReference() instanceof MethodReference
                && methodKey.equals(key((MethodReference) ((ReferenceInstruction) instruction).getReference()));
    }

    private static void requireCallCount(List<Instruction> instructions, String methodKey,
            int expected, String target) throws UnsupportedPatchException {
        int count = 0;
        for (Instruction instruction : instructions) if (isMethodCall(instruction, methodKey)) count++;
        if (count != expected) throw new UnsupportedPatchException("hook count changed at " + target);
    }

    private static boolean isMoveObject(Instruction instruction, int destination, int source) {
        return instruction.getOpcode() == Opcode.MOVE_OBJECT
                && instruction instanceof TwoRegisterInstruction
                && ((TwoRegisterInstruction) instruction).getRegisterA() == destination
                && ((TwoRegisterInstruction) instruction).getRegisterB() == source;
    }

    private static boolean isConst4(Instruction instruction, int register, int literal) {
        return instruction.getOpcode() == Opcode.CONST_4
                && instruction instanceof OneRegisterInstruction
                && instruction instanceof NarrowLiteralInstruction
                && ((OneRegisterInstruction) instruction).getRegisterA() == register
                && ((NarrowLiteralInstruction) instruction).getNarrowLiteral() == literal;
    }

    private static boolean isRegister(Instruction instruction, int register) {
        return instruction instanceof OneRegisterInstruction
                && ((OneRegisterInstruction) instruction).getRegisterA() == register;
    }

    private static boolean branchTargets(List<Instruction> instructions, int branchIndex,
            int targetIndex) {
        if (branchIndex < 0 || targetIndex < 0 || branchIndex >= instructions.size()
                || targetIndex >= instructions.size()
                || !(instructions.get(branchIndex) instanceof OffsetInstruction)) return false;
        long branchAddress = addressAt(instructions, branchIndex);
        long targetAddress = branchAddress
                + ((OffsetInstruction) instructions.get(branchIndex)).getCodeOffset();
        return targetAddress == addressAt(instructions, targetIndex);
    }

    private static long addressAt(List<Instruction> instructions, int index) {
        long address = 0;
        for (int i = 0; i < index; i++) address += instructions.get(i).getCodeUnits();
        return address;
    }

    private static int invocationReceiver(Instruction instruction) {
        if (instruction instanceof FiveRegisterInstruction) {
            return ((FiveRegisterInstruction) instruction).getRegisterC();
        }
        if (instruction instanceof RegisterRangeInstruction) {
            return ((RegisterRangeInstruction) instruction).getStartRegister();
        }
        return -1;
    }

    private static Map<String, Map<String, Integer>> expectedCallsByAnchor(String family) {
        Map<String, Map<String, Integer>> result = new TreeMap<>();
        Map<String, Hook> hooks = lifecycle(family);
        for (Map.Entry<String, Hook> entry : hooks.entrySet()) {
            Hook hook = entry.getValue();
            String callKey;
            if ("mapsFollowing".equals(hook.name)) {
                callKey = methodKey(BACKGROUND, "mapsFollowing", Arrays.asList(
                        "Ljava/lang/Object;", "I", "Ljava/lang/Object;"), "Z");
            } else {
                callKey = methodKey(BACKGROUND, hook.name, hook.args, hook.guard ? "Z" : "V");
            }
            Map<String, Integer> byCall = result.computeIfAbsent(entry.getKey(), ignored -> new TreeMap<>());
            add(byCall, callKey, expectedHookCount(entry.getKey()));
        }
        if ("gmaps".equals(family)) {
            add(result, "Lbrer;->pF(Lbrha;Lbrha;)V", methodKey(BACKGROUND,
                    "mapsNavigationApplied", Collections.singletonList("Ljava/lang/Object;"), "V"), 1);
        } else {
            add(result, "Lcom/waze/mobile/WazeMobileApplication;->onCreate()V",
                    methodKey(BRIDGE, "init", Collections.singletonList("Landroid/content/Context;"), "V"), 1);
        }
        return result;
    }

    private static void addDynamicWazeAnchor(Map<String, Map<String, Integer>> expected,
            Map<String, Map<String, Integer>> actual, String prefix, String error)
            throws UnsupportedPatchException {
        List<String> anchors = new ArrayList<>();
        for (String anchor : actual.keySet()) if (anchor.startsWith(prefix)) anchors.add(anchor);
        if (anchors.size() != 1) throw new UnsupportedPatchException(error);
        add(expected, anchors.get(0), methodKey(BRIDGE, "wazeFrame",
                Collections.singletonList("Ljava/lang/Object;"), "V"), 1);
    }

    private static int expectedHookCount(String key) {
        return "Lcom/waze/map/opengl/z;->s(Lcom/waze/bj/e;Lcom/waze/bj/h;)V".equals(key) ? 2 : 1;
    }

    private static void verifyPayload(PayloadInfo payload, String family)
            throws UnsupportedPatchException {
        Set<String> requiredClasses = new HashSet<>(REQUIRED_PAYLOAD_CLASSES);
        if ("waze".equals(family)) {
            requiredClasses.remove("Lcom/bydhud/mapcapture/MapsNavigationCamera;");
        }
        if (!payload.classes.containsAll(requiredClasses)
                || payload.classes.size() < 30 || payload.classes.size() > 48
                || payload.implementedMethods < 80 || payload.instructionCount < 2500) {
            Set<String> missing = new HashSet<>(requiredClasses);
            missing.removeAll(payload.classes);
            throw new UnsupportedPatchException("map-capture payload incomplete: classes="
                    + payload.classes.size() + " methods=" + payload.implementedMethods
                    + " instructions=" + payload.instructionCount + " missing=" + missing);
        }
        for (String type : payload.classes) {
            if (BACKGROUND_D8_ACCESS.equals(type)) continue;
            String name = type.substring(PAYLOAD_PREFIX.length(), type.length() - 1);
            int nested = name.indexOf('$');
            String root = nested < 0 ? name : name.substring(0, nested);
            if (!PAYLOAD_ROOTS.contains(root)) {
                throw new UnsupportedPatchException("unknown map-capture payload class: " + type);
            }
        }
        verifyKnownD8Companion(payload);
        if (!payload.unshippedReferences.isEmpty()) {
            throw new UnsupportedPatchException("map-capture payload links to unshipped HUD/helper class: "
                    + payload.unshippedReferences.iterator().next());
        }
        requireStaticImplemented(payload, methodKey(BRIDGE, "init",
                Collections.singletonList("Landroid/content/Context;"), "V"));
        requireStaticImplemented(payload, methodKey(BRIDGE, "trackMaps",
                Collections.singletonList("Ljava/lang/Object;"), "V"));
        requireStaticImplemented(payload, methodKey(BRIDGE, "wazeFrame",
                Collections.singletonList("Ljava/lang/Object;"), "V"));
        requireStaticImplemented(payload, methodKey(BRIDGE, "poll", Collections.emptyList(), "V"));
        requireStaticImplemented(payload, methodKey(BRIDGE, "captureMaps",
                Collections.singletonList("Landroid/os/Bundle;"), "V"));
        requireStaticImplemented(payload, methodKey(BRIDGE, "finish", Arrays.asList(
                "Landroid/os/Bundle;", "Landroid/graphics/Bitmap;", "Ljava/lang/String;",
                "Ljava/lang/String;", "I", "I"), "V"));
        requireStaticImplemented(payload, methodKey(BACKGROUND, "mapsFollowing", Arrays.asList(
                "Ljava/lang/Object;", "I", "Ljava/lang/Object;"), "Z"));

        String endpoint = "content://com.bydhud.app.mapframes";
        String mapsBuild = "map-probe-r11-navigation-state-hud-consumer-v1";
        String wazeBuild = "map-probe-r8-adaptive-poll-hud-consumer-v1";
        if (!payload.bridgeStrings.contains(endpoint)
                || payload.bridgeStrings.contains("content://com.bydhud.mapcaptureprobe.frames")
                || !payload.bridgeStrings.contains("gmaps".equals(family) ? mapsBuild : wazeBuild)
                || !payload.bridgeStrings.contains("pollIntervalMs")
                || !payload.bridgeStrings.contains("minPollIntervalMs")) {
            throw new UnsupportedPatchException("map-capture payload protocol markers or provider authority are incomplete");
        }
        for (String reference : expectedHookRefs(family).keySet()) {
            requireStaticImplemented(payload, reference);
        }
    }

    private static void requireStaticImplemented(PayloadInfo payload, String key)
            throws UnsupportedPatchException {
        Method method = payload.methods.get(key);
        if (method == null || method.getImplementation() == null
                || (method.getAccessFlags() & AccessFlags.STATIC.getValue()) == 0) {
            throw new UnsupportedPatchException("map-capture payload method missing: " + key);
        }
    }

    private static PayloadInfo inspectPayloadFile(File file) throws IOException {
        if (file == null || !file.isFile() || !file.canRead()) throw new IOException("map-capture payload DEX is unreadable");
        byte[] bytes;
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            bytes = readLimited(input, MAX_DEX_BYTES, "map-capture payload DEX");
        }
        return inspectPayload(DexBackedDexFile.fromInputStream(Opcodes.forApi(35),
                new ByteArrayInputStream(bytes)));
    }

    private static PayloadInfo inspectPayload(DexBackedDexFile dex) {
        PayloadInfo result = new PayloadInfo();
        for (ClassDef classDef : dex.getClasses()) {
            if (!classDef.getType().startsWith(PAYLOAD_PREFIX)) continue;
            result.classes.add(classDef.getType());
            result.definitions.put(classDef.getType(), classDef);
            recordUnshippedType(result, classDef.getSuperclass());
            for (String iface : classDef.getInterfaces()) recordUnshippedType(result, iface);
            for (Method method : classDef.getMethods()) {
                result.methods.put(key(method), method);
                recordUnshippedType(result, method.getReturnType());
                for (CharSequence parameter : method.getParameterTypes()) {
                    recordUnshippedType(result, parameter.toString());
                }
                if (method.getImplementation() == null) continue;
                result.implementedMethods++;
                for (Instruction instruction : method.getImplementation().getInstructions()) {
                    result.instructionCount++;
                    if (!(instruction instanceof ReferenceInstruction)) continue;
                    Object reference = ((ReferenceInstruction) instruction).getReference();
                    if (reference instanceof MethodReference) {
                        MethodReference methodReference = (MethodReference) reference;
                        recordUnshippedType(result, methodReference.getDefiningClass());
                        recordUnshippedType(result, methodReference.getReturnType());
                        for (CharSequence parameter : methodReference.getParameterTypes()) {
                            recordUnshippedType(result, parameter.toString());
                        }
                    } else if (reference instanceof FieldReference) {
                        FieldReference fieldReference = (FieldReference) reference;
                        recordUnshippedType(result, fieldReference.getDefiningClass());
                        recordUnshippedType(result, fieldReference.getType());
                    } else if (reference instanceof TypeReference) {
                        recordUnshippedType(result, ((TypeReference) reference).getType());
                    } else if (BRIDGE.equals(classDef.getType()) && reference instanceof StringReference) {
                        result.bridgeStrings.add(((StringReference) reference).getString());
                    }
                }
            }
            for (Field field : classDef.getFields()) recordUnshippedType(result, field.getType());
        }
        return result;
    }

    private static void recordUnshippedType(PayloadInfo payload, String type) {
        if (type == null) return;
        if (type.startsWith("Lcom/bydhud/app/")
                || type.startsWith("Lcom/bydhud/mapcaptureprobe/")
                || (!type.startsWith(PAYLOAD_PREFIX)
                && (type.contains("$$ExternalSyntheticBackport")
                || type.contains("$$ExternalSyntheticLambda")))) {
            payload.unshippedReferences.add(type);
        }
    }

    private static void verifyKnownD8Companion(PayloadInfo payload)
            throws UnsupportedPatchException {
        if (!payload.classes.contains(BACKGROUND_D8_ACCESS)) return;
        ClassDef companion = payload.definitions.get(BACKGROUND_D8_ACCESS);
        int expectedClassFlags = AccessFlags.PUBLIC.getValue() | AccessFlags.FINAL.getValue()
                | AccessFlags.SYNTHETIC.getValue();
        if (companion == null || companion.getAccessFlags() != expectedClassFlags
                || !"Ljava/lang/Object;".equals(companion.getSuperclass())
                || !companion.getInterfaces().isEmpty()
                || !"D8$$SyntheticClass".equals(companion.getSourceFile())
                || companion.getAnnotations().iterator().hasNext()
                || companion.getFields().iterator().hasNext()
                || companion.getMethods().iterator().hasNext()) {
            throw new UnsupportedPatchException("known D8 map-capture companion has an unexpected shape");
        }

        String markerConstructor = methodKey(BACKGROUND_TARGET, "<init>",
                Collections.singletonList(BACKGROUND_D8_ACCESS), "V");
        Method constructor = payload.methods.get(markerConstructor);
        int expectedMethodFlags = AccessFlags.SYNTHETIC.getValue() | AccessFlags.CONSTRUCTOR.getValue();
        if (constructor == null || constructor.getAccessFlags() != expectedMethodFlags
                || constructor.getImplementation() == null) {
            throw new UnsupportedPatchException("known D8 map-capture constructor bridge is missing");
        }
        List<Instruction> body = new ArrayList<>();
        constructor.getImplementation().getInstructions().forEach(body::add);
        String noArgConstructor = methodKey(BACKGROUND_TARGET, "<init>", Collections.emptyList(), "V");
        if (body.size() != 2 || !(body.get(0) instanceof ReferenceInstruction)
                || body.get(0).getOpcode() != Opcode.INVOKE_DIRECT
                || !(((ReferenceInstruction) body.get(0)).getReference() instanceof MethodReference)
                || !noArgConstructor.equals(key((MethodReference)
                ((ReferenceInstruction) body.get(0)).getReference()))
                || invocationReceiver(body.get(0)) != parameterStart(constructor)
                || body.get(1).getOpcode() != Opcode.RETURN_VOID) {
            throw new UnsupportedPatchException("known D8 map-capture constructor bridge is not a no-op delegate");
        }

        Set<String> expectedSites = new HashSet<>(Arrays.asList(
                methodKey(BACKGROUND, "startCar", Collections.singletonList("Ljava/lang/Object;"), "V"),
                methodKey(BACKGROUND, "startMaps", Collections.singletonList("Ljava/lang/Object;"), "V"),
                methodKey(BACKGROUND, "startWaze", Collections.singletonList("Ljava/lang/Object;"), "V")));
        Set<String> actualSites = new HashSet<>();
        int calls = 0;
        for (ClassDef definition : payload.definitions.values()) {
            for (Method method : definition.getMethods()) {
                if (method.getImplementation() == null) continue;
                for (Instruction instruction : method.getImplementation().getInstructions()) {
                    if (!(instruction instanceof ReferenceInstruction)
                            || !(((ReferenceInstruction) instruction).getReference() instanceof MethodReference)) continue;
                    MethodReference reference = (MethodReference) ((ReferenceInstruction) instruction).getReference();
                    if (!markerConstructor.equals(key(reference))) continue;
                    calls++;
                    actualSites.add(key(method));
                }
            }
        }
        if (calls != expectedSites.size() || !actualSites.equals(expectedSites)) {
            throw new UnsupportedPatchException("known D8 map-capture companion is referenced from unexpected methods");
        }
    }

    private static Map<String, Hook> lifecycle(String profile) {
        Map<String, Hook> hooks = new LinkedHashMap<>();
        addHook(hooks, "Lbpey;", "D", "mapsStarted", false, false);
        addHook(hooks, "Lbpey;", "E", "mapsStopping", false, false);
        addHook(hooks, "Lbpey;", "B", "mapsGone", false, false);
        observe(hooks, "Laorq;", "g", "mapsFollowing", "Z");
        addHook(hooks, "Laorq;", "h", "mapsFollowing", true, false);
        addHook(hooks, "Laorq;", "d", "mapsFollowing", false, false);
        addHook(hooks, "Laorq;", "j", "mapsFollowing", true, false,
                "Lbprv;", "Lbouy;", "Z");
        observe(hooks, "Lbrer;", "e", "mapsNavigationStarted");
        addHook(hooks, "Lbrer;", "f", "mapsNavigationStopping", true, false);
        hooks.put("Lbrer;->pF(Lbrha;Lbrha;)V", new Hook("mapsNavigationState", false, false,
                "Ljava/lang/Object;", "Ljava/lang/Object;"));
        observe(hooks, "Lbrgs;", "e", "mapsNavigationUiStarted");
        addHook(hooks, "Lbrgs;", "f", "mapsNavigationUiStopping", true, false);
        hooks.put("Lapzr;->b(Lbrha;Lbrha;)V", new Hook("mapsNavigationUiDispatch", true, false,
                "Ljava/lang/Object;", "Ljava/lang/Object;"));

        addHook(hooks, "Lcom/waze/map/isolated/w;", "o", "wazeStopped", false, true);
        addHook(hooks, "Lcom/waze/map/isolated/w;", "p", "wazeStarted", false, false);
        addHook(hooks, "Lcom/waze/map/isolated/w;", "i", "wazeGone", false, false);
        addHook(hooks, "Lcom/waze/map/isolated/w;", "onDetachedFromWindow", "wazeGone", false, false);
        addHook(hooks, "Lcom/waze/map/opengl/z;", "t", "wazeReleased", false, true);
        observeEnd(hooks, "Lcom/waze/map/opengl/z;", "s", "wazeRendererReady",
                "Lcom/waze/bj/e;", "Lcom/waze/bj/h;");
        hooks.put("Lcom/waze/car_lib/i/a/s;->d(Lcom/waze/car_lib/i/a/h;Lcom/waze/car_lib/i/a/f;Lcom/waze/map/ed;Lcom/waze/bj/e;Lcom/waze/map/opengl/w;)V",
                new Hook("carRendererReady", false, false));
        addHook(hooks, "Lcom/waze/bj/q;", "c", "wazeLost", true, false);
        addHook(hooks, "Lcom/waze/bj/q;", "b", "wazeSurface", true, false,
                "Landroid/view/Surface;", "I", "I", "I");
        addHook(hooks, "Lcom/waze/bj/q;", "a", "wazeSize", true, false, "I", "I", "I");
        observe(hooks, "Lcom/waze/car_lib/i/a/a;", "onPause", "carPaused",
                "Landroidx/lifecycle/LifecycleOwner;");
        observe(hooks, "Lcom/waze/car_lib/i/a/a;", "onResume", "carResumed",
                "Landroidx/lifecycle/LifecycleOwner;");
        observe(hooks, "Lcom/waze/car_lib/i/a/a;", "onDestroy", "carGone",
                "Landroidx/lifecycle/LifecycleOwner;");
        observe(hooks, "Lcom/waze/car_lib/i/a/a;", "h", "carReleasing");
        hooks.put("Lcom/waze/car_lib/i/a/g;->onSurfaceAvailable(Landroidx/car/app/SurfaceContainer;)V",
                new Hook("carSurfaceAvailable", false, false, "Ljava/lang/Object;"));
        hooks.put("Lcom/waze/car_lib/i/a/g;->onSurfaceDestroyed(Landroidx/car/app/SurfaceContainer;)V",
                new Hook("carSurfaceLost", false, false, "Ljava/lang/Object;"));
        addHook(hooks, "Lbqeh;", "m", "mapsLost", true, false);
        addHook(hooks, "Lbqeh;", "l", "mapsSurface", true, false, "Ljava/lang/Object;");
        addHook(hooks, "Lbqeh;", "n", "mapsSize", true, false, "I", "I");
        addHook(hooks, "Lbqeh;", "g", "mapsPause", true, false);
        addHook(hooks, "Lboso;", "d", "mapsDisable", true, false, "Z");
        addHook(hooks, "Lbjac;", "d", "mapsLost", true, false);
        addHook(hooks, "Lbjac;", "c", "mapsNativeSurface", true, false, "Landroid/view/Surface;");
        addHook(hooks, "Lcbop;", "b", "mapsSize", true, false, "I", "I");
        addHook(hooks, "Lcboh;", "f", "mapsPause", true, false);

        Map<String, Hook> selected = new LinkedHashMap<>();
        for (Map.Entry<String, Hook> entry : hooks.entrySet()) {
            boolean waze = entry.getKey().startsWith("Lcom/waze/");
            if ("waze".equals(profile) == waze) selected.put(entry.getKey(), entry.getValue());
        }
        return selected;
    }

    private static void addHook(Map<String, Hook> hooks, String owner, String name,
            String hook, boolean guard, boolean end, String... parameters) {
        hooks.put(owner + "->" + name + "(" + String.join("", parameters) + ")V",
                new Hook(hook, guard, end, parameters));
    }

    private static void observe(Map<String, Hook> hooks, String owner, String name,
            String hook, String... ignored) {
        hooks.put(owner + "->" + name + "(" + String.join("", ignored) + ")V",
                new Hook(hook, false, false));
    }

    private static void observeEnd(Map<String, Hook> hooks, String owner, String name,
            String hook, String... parameters) {
        hooks.put(owner + "->" + name + "(" + String.join("", parameters) + ")V",
                new Hook(hook, false, true));
    }

    private static Set<String> targetOwners(String family) {
        Set<String> result = new HashSet<>();
        for (String key : lifecycle(family).keySet()) result.add(key.substring(0, key.indexOf("->")));
        if ("gmaps".equals(family)) {
            result.add("Lbpey;");
        } else {
            result.add("Lcom/waze/mobile/WazeMobileApplication;");
            result.add("Lcom/waze/map/opengl/w;");
            result.add("Lcom/waze/map/NativeCanvasRenderer;");
        }
        return result;
    }

    private static Map<String, Integer> expectedAnchorCounts(String family) {
        Map<String, Integer> expected = new TreeMap<>();
        for (String key : lifecycle(family).keySet()) expected.put(key, expectedHookCount(key));
        if ("gmaps".equals(family)) {
            expected.put("maps_init", 1);
            expected.put("maps_track", 2);
            expected.put("maps_camera_applied", 1);
        } else {
            expected.put("waze_init", 1);
            expected.put("waze_swap", 1);
            expected.put("waze_canvas", 1);
        }
        return expected;
    }

    private static Map<String, Integer> expectedHookRefs(String family) {
        Map<String, Integer> expected = new TreeMap<>();
        for (Map.Entry<String, Hook> entry : lifecycle(family).entrySet()) {
            Hook hook = entry.getValue();
            String key;
            if ("mapsFollowing".equals(hook.name)) {
                key = methodKey(BACKGROUND, "mapsFollowing", Arrays.asList(
                        "Ljava/lang/Object;", "I", "Ljava/lang/Object;"), "Z");
            } else {
                key = methodKey(BACKGROUND, hook.name, hook.args, hook.guard ? "Z" : "V");
            }
            add(expected, key, expectedHookCount(entry.getKey()));
        }
        if ("gmaps".equals(family)) {
            add(expected, methodKey(BRIDGE, "init",
                    Collections.singletonList("Landroid/content/Context;"), "V"), 1);
            add(expected, methodKey(BRIDGE, "trackMaps",
                    Collections.singletonList("Ljava/lang/Object;"), "V"), 2);
            add(expected, methodKey(BACKGROUND, "mapsNavigationApplied",
                    Collections.singletonList("Ljava/lang/Object;"), "V"), 1);
        } else {
            add(expected, methodKey(BRIDGE, "init",
                    Collections.singletonList("Landroid/content/Context;"), "V"), 1);
            add(expected, methodKey(BRIDGE, "wazeFrame",
                    Collections.singletonList("Ljava/lang/Object;"), "V"), 2);
        }
        return expected;
    }

    private static DexFile rewrite(DexBackedDexFile dex, PatchPlan plan)
            throws UnsupportedPatchException {
        Map<String, Method> changedMethods = new HashMap<>();
        int matchingMethods = 0;
        for (ClassDef classDef : dex.getClasses()) {
            for (Method method : classDef.getMethods()) {
                Method changed = patchMethod(method, plan);
                if (changed != method) {
                    changedMethods.put(key(method), changed);
                    matchingMethods++;
                }
            }
        }
        if (matchingMethods == 0 && hasTargetClass(dex, plan.profile)) {
            throw new UnsupportedPatchException("target DEX has no guarded hook methods");
        }
        DexRewriter rewriter = new DexRewriter(new RewriterModule() {
            @Override
            public Rewriter<Method> getMethodRewriter(Rewriters rewriters) {
                return method -> changedMethods.getOrDefault(key(method), method);
            }
        });
        return rewriter.getDexFileRewriter().rewrite(dex);
    }

    private static boolean hasTargetClass(DexBackedDexFile dex, String family) {
        Set<String> targets = targetOwners(family);
        for (ClassDef classDef : dex.getClasses()) if (targets.contains(classDef.getType())) return true;
        return false;
    }

    private static Method patchMethod(Method method, PatchPlan plan) {
        String owner = method.getDefiningClass();
        String name = method.getName();
        if (method.getImplementation() == null) return method;
        boolean mapsConstructor = "gmaps".equals(plan.profile)
                && owner.equals("Lbpey;") && name.equals("<init>");
        boolean startup = "waze".equals(plan.profile)
                && owner.equals("Lcom/waze/mobile/WazeMobileApplication;")
                && name.equals("onCreate") && method.getParameterTypes().isEmpty();
        boolean swap = "waze".equals(plan.profile)
                && owner.equals("Lcom/waze/map/opengl/w;") && name.equals("l")
                && method.getParameterTypes().isEmpty();
        boolean canvas = "waze".equals(plan.profile)
                && owner.equals("Lcom/waze/map/NativeCanvasRenderer;") && name.equals("onDrawFrame");
        Hook background = plan.lifecycle.get(key(method));
        if (!mapsConstructor && !startup && !swap && !canvas && background == null) return method;

        int words = 1;
        for (CharSequence type : method.getParameterTypes()) {
            String parameter = type.toString();
            words += parameter.equals("J") || parameter.equals("D") ? 2 : 1;
        }
        int p0 = method.getImplementation().getRegisterCount() - words;
        MutableMethodImplementation body = new MutableMethodImplementation(method.getImplementation());
        int hooks = 0;
        for (int i = body.getInstructions().size() - 1; i >= 0; i--) {
            Instruction instruction = body.getInstructions().get(i);
            MethodReference call = instruction instanceof ReferenceInstruction
                    && ((ReferenceInstruction) instruction).getReference() instanceof MethodReference
                    ? (MethodReference) ((ReferenceInstruction) instruction).getReference() : null;
            if (call != null && call.getDefiningClass().startsWith(PAYLOAD_PREFIX)) {
                throw new IllegalStateException("Already patched: " + owner);
            }
            if (mapsConstructor && instruction.getOpcode() == Opcode.RETURN_VOID) {
                add(body, i, p0, plan, reference(BRIDGE, "trackMaps",
                        Collections.singletonList("Ljava/lang/Object;"), "V"));
                plan.count("maps_track");
                hooks++;
            }
            int receiver = instruction instanceof FiveRegisterInstruction
                    ? ((FiveRegisterInstruction) instruction).getRegisterC()
                    : instruction instanceof RegisterRangeInstruction
                    ? ((RegisterRangeInstruction) instruction).getStartRegister() : -1;
            if ("gmaps".equals(plan.profile) && owner.equals("Lbrer;") && name.equals("pF")
                    && call != null && key(call).equals("Lbrfd;->f(Lcksk;Lbrfe;)V")) {
                add(body, i + 1, receiver, plan, reference(BACKGROUND, "mapsNavigationApplied",
                        Collections.singletonList("Ljava/lang/Object;"), "V"));
                plan.count("maps_camera_applied");
                hooks++;
            }
            if (mapsConstructor && call != null && call.getDefiningClass().equals("Ljava/lang/Object;")
                    && call.getName().equals("<init>") && receiver == p0) {
                add(body, i + 1, p0 + 1, plan, reference(BRIDGE, "init",
                        Collections.singletonList("Landroid/content/Context;"), "V"));
                plan.count("maps_init");
                hooks++;
            }
            if (swap && call != null && call.getName().equals("eglSwapBuffers")
                    && call.getDefiningClass().equals("Ljavax/microedition/khronos/egl/EGL10;")) {
                add(body, i, p0, plan, reference(BRIDGE, "wazeFrame",
                        Collections.singletonList("Ljava/lang/Object;"), "V"));
                plan.count("waze_swap");
                hooks++;
            }
            if (canvas && call != null && call.getName().equals("RenderNTV")
                    && call.getDefiningClass().equals(owner)) {
                add(body, i + 1, p0, plan, reference(BRIDGE, "wazeFrame",
                        Collections.singletonList("Ljava/lang/Object;"), "V"));
                plan.count("waze_canvas");
                hooks++;
            }
        }
        if (startup) {
            add(body, 0, p0, plan, reference(BRIDGE, "init",
                    Collections.singletonList("Landroid/content/Context;"), "V"));
            plan.count("waze_init");
            hooks++;
        }
        if (background != null) {
            if (background.name.equals("mapsFollowing")) {
                int phase = name.equals("g") ? 0 : name.equals("h") ? 1 : name.equals("d") ? 2 : 3;
                if (p0 < 2 || p0 > (phase == 3 ? 13 : 15)) {
                    throw new IllegalStateException("Following hook register layout changed: " + key(method));
                }
                Label original = body.newLabelForIndex(0);
                body.addInstruction(0, new BuilderInstruction12x(Opcode.MOVE_OBJECT, 0, p0));
                body.addInstruction(1, new BuilderInstruction11n(Opcode.CONST_4, 1, phase));
                body.addInstruction(2, new BuilderInstruction12x(Opcode.MOVE_OBJECT, 2,
                        phase == 3 ? p0 + 2 : p0));
                int invokeIndex = 3;
                Label branch = null;
                if (phase == 3) {
                    branch = body.newLabelForIndex(3);
                    body.addInstruction(3, new BuilderInstruction21t(Opcode.IF_NEZ, 2, branch));
                    body.addInstruction(4, new BuilderInstruction12x(Opcode.MOVE_OBJECT, 2, p0 + 1));
                    invokeIndex = 5;
                }
                MethodReference reference = reference(BACKGROUND, "mapsFollowing", Arrays.asList(
                        "Ljava/lang/Object;", "I", "Ljava/lang/Object;"), "Z");
                body.addInstruction(invokeIndex, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,
                        0, 3, reference));
                plan.call(reference);
                if (branch != null) {
                    branch.getLocation().getLabels().remove(branch);
                    body.getInstructions().get(invokeIndex).getLocation().getLabels().add(branch);
                    if (branch.getLocation() != body.getInstructions().get(invokeIndex).getLocation()) {
                        throw new IllegalStateException("Following request branch changed");
                    }
                }
                if (background.guard) {
                    body.addInstruction(invokeIndex + 1, new BuilderInstruction11x(Opcode.MOVE_RESULT, 0));
                    body.addInstruction(invokeIndex + 2, new BuilderInstruction21t(Opcode.IF_EQZ, 0, original));
                    body.addInstruction(invokeIndex + 3, new BuilderInstruction10x(Opcode.RETURN_VOID));
                    if (original.getLocation() != body.getInstructions().get(invokeIndex + 4).getLocation()) {
                        throw new IllegalStateException("Following guard branch changed");
                    }
                }
                plan.count(key(method));
            } else if (background.name.equals("carRendererReady")) {
                int anchor = -1;
                for (int i = 0; i < body.getInstructions().size(); i++) {
                    if (isCarManagerAssignment(body.getInstructions().get(i), owner, p0)) {
                        if (anchor >= 0) {
                            throw new IllegalStateException("Multiple car manager assignments");
                        }
                        anchor = i;
                    }
                }
                if (anchor < 0) {
                    throw new IllegalStateException("Car manager assignment missing");
                }
                requireUnchangedReceiver(body.getInstructions(), anchor, p0);
                MethodReference reference = reference(BACKGROUND, background.name,
                        background.args, "V");
                body.addInstruction(anchor + 1, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,
                        p0, background.args.size(), reference));
                plan.call(reference);
                plan.count(key(method));
            } else if (background.guard) {
                if (p0 < 1) throw new IllegalStateException("Guard requires an unused local: " + key(method));
                Label original = body.newLabelForIndex(0);
                MethodReference reference = reference(BACKGROUND, background.name, background.args, "Z");
                body.addInstruction(0, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,
                        p0, background.args.size(), reference));
                body.addInstruction(1, new BuilderInstruction11x(Opcode.MOVE_RESULT, 0));
                body.addInstruction(2, new BuilderInstruction21t(Opcode.IF_EQZ, 0, original));
                body.addInstruction(3, new BuilderInstruction10x(Opcode.RETURN_VOID));
                if (original.getLocation() != body.getInstructions().get(4).getLocation()) {
                    throw new IllegalStateException("Guard branch changed");
                }
                plan.call(reference);
                plan.count(key(method));
            } else if (background.end) {
                for (int i = body.getInstructions().size() - 1; i >= 0; i--) {
                    if (body.getInstructions().get(i).getOpcode() == Opcode.RETURN_VOID) {
                        add(body, i, p0, plan, reference(BACKGROUND, background.name,
                                background.args, "V"));
                        plan.count(key(method));
                    }
                }
            } else {
                add(body, 0, p0, plan, reference(BACKGROUND, background.name,
                        background.args, "V"));
                plan.count(key(method));
            }
            hooks++;
        }
        if (hooks == 0) return method;
        return new ImmutableMethod(owner, name, method.getParameters(), method.getReturnType(),
                method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(), body);
    }

    private static void add(MutableMethodImplementation body, int index, int register,
            PatchPlan plan, MethodReference reference) {
        var original = body.getInstructions().get(index);
        List<Label> labels = new ArrayList<>(original.getLocation().getLabels());
        BuilderInstruction3rc hook = new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,
                register, reference.getParameterTypes().size(), reference);
        body.addInstruction(index, hook);
        for (Label label : labels) {
            original.getLocation().getLabels().remove(label);
            hook.getLocation().getLabels().add(label);
            if (label.getLocation() != hook.getLocation()) throw new IllegalStateException("Hook branch bypass");
        }
        plan.call(reference);
    }

    private static boolean isCarManagerAssignment(Instruction instruction, String owner, int receiver) {
        if (instruction.getOpcode() != Opcode.IPUT_OBJECT || !(instruction instanceof ReferenceInstruction)) return false;
        Object reference = ((ReferenceInstruction) instruction).getReference();
        return reference instanceof FieldReference
                && ((FieldReference) reference).getDefiningClass().equals(owner)
                && ((FieldReference) reference).getName().equals("f")
                && ((FieldReference) reference).getType().equals("Lcom/waze/bj/r;")
                && ((TwoRegisterInstruction) instruction).getRegisterB() == receiver;
    }

    private static void requireUnchangedReceiver(List<? extends Instruction> instructions,
            int before, int receiver) {
        for (int i = 0; i < before; i++) {
            Instruction instruction = instructions.get(i);
            if (instruction instanceof OffsetInstruction) {
                throw new IllegalStateException("Car receiver path is no longer linear");
            }
            if (instruction.getOpcode().setsRegister() && instruction instanceof OneRegisterInstruction) {
                int written = ((OneRegisterInstruction) instruction).getRegisterA();
                if (written == receiver || (instruction.getOpcode().setsWideRegister()
                        && written + 1 == receiver)) {
                    throw new IllegalStateException("Car presenter register overwritten before hook");
                }
            }
        }
    }

    private static void verifyCarRendererHook(Method method) throws UnsupportedPatchException {
        if (!method.getDefiningClass().equals("Lcom/waze/car_lib/i/a/s;")
                || !method.getName().equals("d") || method.getImplementation() == null) return;
        List<Instruction> instructions = new ArrayList<>();
        method.getImplementation().getInstructions().forEach(instructions::add);
        int found = 0;
        for (int i = 0; i < instructions.size(); i++) {
            Instruction instruction = instructions.get(i);
            if (!(instruction instanceof ReferenceInstruction)
                    || !(((ReferenceInstruction) instruction).getReference() instanceof MethodReference)) continue;
            MethodReference called = (MethodReference) ((ReferenceInstruction) instruction).getReference();
            if (!called.getDefiningClass().equals(BACKGROUND)
                    || !called.getName().equals("carRendererReady")) continue;
            found++;
            if (!(instruction instanceof RegisterRangeInstruction)) {
                throw new UnsupportedPatchException("Car renderer hook is not a range invocation");
            }
            int receiver = ((RegisterRangeInstruction) instruction).getStartRegister();
            if (receiver != method.getImplementation().getRegisterCount() - 6 || i == 0
                    || !isCarManagerAssignment(instructions.get(i - 1), method.getDefiningClass(), receiver)) {
                throw new UnsupportedPatchException("Car renderer hook is not after its receiver's manager assignment");
            }
            try {
                requireUnchangedReceiver(instructions, i - 1, receiver);
            } catch (IllegalStateException error) {
                throw new UnsupportedPatchException(message(error));
            }
        }
        if (found != 1) throw new UnsupportedPatchException("Car renderer hook count changed");
    }

    private static void verifyWazeCanvasHook(Method method) throws UnsupportedPatchException {
        if (!method.getDefiningClass().equals("Lcom/waze/map/NativeCanvasRenderer;")
                || !method.getName().equals("onDrawFrame") || method.getImplementation() == null) return;
        List<Instruction> instructions = new ArrayList<>();
        method.getImplementation().getInstructions().forEach(instructions::add);
        int renderCalls = 0;
        int frameHooks = 0;
        for (int i = 0; i < instructions.size(); i++) {
            Instruction instruction = instructions.get(i);
            if (!(instruction instanceof ReferenceInstruction)
                    || !(((ReferenceInstruction) instruction).getReference() instanceof MethodReference)) continue;
            MethodReference called = (MethodReference) ((ReferenceInstruction) instruction).getReference();
            if (called.getDefiningClass().equals(method.getDefiningClass())
                    && called.getName().equals("RenderNTV")) {
                renderCalls++;
                if (i + 1 >= instructions.size() || !isWazeFrameHook(instructions.get(i + 1))) {
                    throw new UnsupportedPatchException("Waze canvas hook is not immediately after RenderNTV");
                }
            }
            if (isWazeFrameHook(instruction)) frameHooks++;
        }
        if (renderCalls != 1 || frameHooks != 1) {
            throw new UnsupportedPatchException("Waze canvas hook structure changed");
        }
    }

    private static boolean isWazeFrameHook(Instruction instruction) {
        if (!(instruction instanceof ReferenceInstruction)
                || !(((ReferenceInstruction) instruction).getReference() instanceof MethodReference)) return false;
        MethodReference method = (MethodReference) ((ReferenceInstruction) instruction).getReference();
        return method.getDefiningClass().equals(BRIDGE) && method.getName().equals("wazeFrame")
                && method.getParameterTypes().size() == 1
                && method.getParameterTypes().get(0).toString().equals("Ljava/lang/Object;")
                && method.getReturnType().equals("V");
    }

    private static void verifyDexMethodLimit(DexBackedDexFile original,
            Map<String, Integer> insertedCalls) throws UnsupportedPatchException {
        Set<String> existing = new HashSet<>();
        for (int index = 0; index < original.getMethodSection().size(); index++) {
            existing.add(key(original.getMethodSection().get(index)));
        }
        int newIds = 0;
        for (String call : insertedCalls.keySet()) if (!existing.contains(call)) newIds++;
        if (original.getMethodSection().size() + newIds > MAX_METHOD_IDS) {
            throw new UnsupportedPatchException("DEX method reference ceiling would be exceeded");
        }
    }

    private static void repack(File input, File output, Map<String, File> replacements,
            Map<String, File> additions) throws IOException {
        Files.copy(input.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
        try (ZipArchive archive = new ZipArchive(output)) {
            for (String name : new ArrayList<>(archive.listEntries())) {
                String upper = name.toUpperCase(Locale.ROOT);
                if (replacements.containsKey(name) || additions.containsKey(name)
                        || (upper.startsWith("META-INF/") && (upper.endsWith(".RSA")
                        || upper.endsWith(".DSA") || upper.endsWith(".EC")
                        || upper.endsWith(".SF") || upper.endsWith("MANIFEST.MF")))) {
                    archive.delete(name);
                }
            }
            Map<String, File> changed = new HashMap<>(replacements);
            changed.putAll(additions);
            for (Map.Entry<String, File> entry : changed.entrySet()) {
                BytesSource source = new BytesSource(entry.getValue(), entry.getKey(), Deflater.BEST_SPEED);
                source.align(4);
                archive.add(source);
            }
        }
    }

    private static void copyMembers(List<File> sources, File outputDir) throws IOException {
        if (sources == null || outputDir == null) throw new IOException("invalid output members");
        if (!outputDir.exists() && !outputDir.mkdirs()) throw new IOException("cannot create candidate directory");
        Set<String> names = new HashSet<>();
        for (File source : sources) {
            if (source == null || !source.isFile() || !names.add(source.getName())) {
                throw new IOException("invalid or duplicate APK member");
            }
            File output = new File(outputDir, source.getName());
            if (source.getCanonicalFile().equals(output.getCanonicalFile())) continue;
            Files.copy(source.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static List<File> outputMembers(List<File> sources, File outputDir) throws IOException {
        List<File> result = new ArrayList<>();
        for (File source : sources) result.add(new File(outputDir, source.getName()).getCanonicalFile());
        return result;
    }

    private static File onlyTargetApk(Analysis analysis) throws UnsupportedPatchException {
        if (analysis.targetApks.size() != 1) throw new UnsupportedPatchException("target is not one APK member");
        try {
            return analysis.targetApks.iterator().next().getCanonicalFile();
        } catch (IOException error) {
            throw new UnsupportedPatchException(message(error));
        }
    }

    private static int maxDexNumber(File apk) throws IOException {
        int maximum = 1;
        try (ZipFile zip = new ZipFile(apk)) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.matches("classes(\\d*)\\.dex")) continue;
                String number = name.substring("classes".length(), name.length() - ".dex".length());
                int current = number.isEmpty() ? 1 : Integer.parseInt(number);
                maximum = Math.max(maximum, current);
            }
        }
        return maximum;
    }

    private static boolean containsEntry(File apk, String name) throws IOException {
        try (ZipFile zip = new ZipFile(apk)) { return zip.getEntry(name) != null; }
    }

    private static void deleteTree(File root) {
        if (root == null || !root.exists()) return;
        File[] children = root.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        root.delete();
    }

    private static byte[] readEntry(ZipFile zip, ZipEntry entry, int limit) throws IOException {
        try (InputStream input = new BufferedInputStream(zip.getInputStream(entry))) {
            return readLimited(input, limit, entry.getName());
        }
    }

    private static byte[] readLimited(InputStream input, int limit, String label) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if ((long) output.size() + read > limit) throw new IOException("APK DEX exceeds limit: " + label);
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void merge(Map<String, Integer> into, Map<String, Integer> from) {
        for (Map.Entry<String, Integer> entry : from.entrySet()) add(into, entry.getKey(), entry.getValue());
    }

    private static Map<String, Integer> deficits(Map<String, Integer> expected,
            Map<String, Integer> actual) {
        Map<String, Integer> result = new TreeMap<>();
        for (Map.Entry<String, Integer> entry : expected.entrySet()) {
            int difference = entry.getValue() - actual.getOrDefault(entry.getKey(), 0);
            if (difference > 0) result.put(entry.getKey(), difference);
        }
        return result;
    }

    private static void add(Map<String, Integer> map, String key, int count) {
        map.put(key, map.getOrDefault(key, 0) + count);
    }

    private static void add(Map<String, Map<String, Integer>> map, String anchor,
            String call, int count) {
        add(map.computeIfAbsent(anchor, ignored -> new TreeMap<>()), call, count);
    }

    private static MethodReference reference(String owner, String name, List<String> parameters,
            String returnType) {
        return new ImmutableMethodReference(owner, name, parameters, returnType);
    }

    private static String methodKey(String owner, String name, List<String> parameters,
            String returnType) {
        StringBuilder result = new StringBuilder(owner).append("->").append(name).append('(');
        for (String parameter : parameters) result.append(parameter);
        return result.append(')').append(returnType).toString();
    }

    private static String key(Method method) { return key((MethodReference) method); }

    private static String key(MethodReference method) {
        StringBuilder result = new StringBuilder(method.getDefiningClass()).append("->")
                .append(method.getName()).append('(');
        for (CharSequence parameter : method.getParameterTypes()) result.append(parameter);
        return result.append(')').append(method.getReturnType()).toString();
    }

    private static String normalizeProfile(String profile) {
        if (profile == null) return null;
        String value = profile.trim().toLowerCase(Locale.ROOT);
        return "gmaps".equals(value) ? value : "waze".equals(value) ? value : null;
    }

    private static boolean supportedVersion(String profile, String version) {
        if (version == null) return false;
        String prefix = "gmaps".equals(profile) ? "26.30." : "5.20.";
        return version.trim().startsWith(prefix);
    }

    private static Inspection result(String state, String reason) {
        return new Inspection(state, reason == null || reason.trim().isEmpty() ? state : reason.trim());
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }

    private static String message(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return error.getClass().getSimpleName();
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private static final class UnsupportedPatchException extends IOException {
        UnsupportedPatchException(String message) { super(message); }
    }
}
