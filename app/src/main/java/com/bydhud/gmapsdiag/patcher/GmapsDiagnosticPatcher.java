package com.bydhud.gmapsdiag.patcher;

import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.builder.BuilderInstruction;
import org.jf.dexlib2.builder.MutableMethodImplementation;
import org.jf.dexlib2.builder.instruction.BuilderInstruction11n;
import org.jf.dexlib2.builder.instruction.BuilderInstruction11x;
import org.jf.dexlib2.builder.instruction.BuilderInstruction35c;
import org.jf.dexlib2.builder.instruction.BuilderInstruction3rc;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.iface.instruction.FiveRegisterInstruction;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.NarrowLiteralInstruction;
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.instruction.TwoRegisterInstruction;
import org.jf.dexlib2.iface.reference.FieldReference;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.immutable.ImmutableMethod;
import org.jf.dexlib2.immutable.ImmutableDexFile;
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference;
import org.jf.dexlib2.rewriter.DexRewriter;
import org.jf.dexlib2.rewriter.Rewriter;
import org.jf.dexlib2.rewriter.RewriterModule;
import org.jf.dexlib2.rewriter.Rewriters;
import org.jf.dexlib2.writer.pool.DexPool;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class GmapsDiagnosticPatcher {
    private static final String LOGGER_CLASS = "Lcom/bydhud/gmapsdiag/NavInfoLogger;";
    private static final String RECEIVER_CLASS =
            "Lcom/google/android/libraries/geo/navcore/navinfo/NavigationInfoBroadcastReceiver;";
    private static final String MANEUVER_VIEW_CLASS =
            "Lcom/google/android/libraries/geo/navcore/turncard/views/"
                    + "TurnCardStepManeuverImageView;";
    private static final String AUDIO_METHOD = "g";
    private static final String AUDIO_BUILDER = "Landroid/media/AudioAttributes$Builder;";
    private static final List<Hook> HOOKS_2516 = Arrays.asList(
            new Hook("nav_payload", "Lbpix;", "b",
                    Collections.singletonList("Lclca;"), "V",
                    "log", Collections.singletonList("Ljava/lang/Object;"), 1,
                    new Marker("method", "Lbhja;", "b", "V"),
                    new Marker("method", "Ljava/util/concurrent/atomic/AtomicBoolean;",
                            "get", "Z")),
            new Hook("session_start", "Lbhiy;", "sP",
                    Collections.singletonList("Lblct;"), "V",
                    "sessionStart", Collections.singletonList("Ljava/lang/Object;"), 1,
                    new Marker("method", "Lbhiy;", "a", "Z")),
            new Hook("session_output", "Lbhiy;", "sO",
                    Collections.singletonList("Z"), "V",
                    "sessionOutputChanged", Collections.singletonList("Z"), 1,
                    new Marker("method", "Ljava/util/concurrent/atomic/AtomicBoolean;",
                            "compareAndSet", "Z")),
            new Hook("session_stop", "Lbhiy;", "t",
                    Collections.emptyList(), "V",
                    "sessionStop", Collections.emptyList(), 0,
                    new Marker("method", "Lbpix;", "b", "V")),
            new Hook("bridge_register", RECEIVER_CLASS, "onReceive",
                    Arrays.asList("Landroid/content/Context;", "Landroid/content/Intent;"), "V",
                    "registerClient", Arrays.asList(
                            "Landroid/content/Context;", "Landroid/content/Intent;"), 2,
                    new Marker("method", "Landroid/content/Intent;", "getAction",
                            "Ljava/lang/String;"),
                    new Marker("method", "Lbhja;", "a", "V")),
            new Hook("maneuver_bitmap", MANEUVER_VIEW_CLASS, "setManeuver",
                    Collections.singletonList("Lbhhf;"), "V",
                    "captureManeuverView", Arrays.asList(
                            "Ljava/lang/Object;", "Ljava/lang/Object;"), 2,
                    new Marker("method", MANEUVER_VIEW_CLASS, "a", "V"))
    );
    private static final List<Hook> HOOKS_2630 = Arrays.asList(
            new Hook("nav_payload", "Lbrjq;", "a",
                    Collections.singletonList("Ldaqn;"), "V",
                    "log", Collections.singletonList("Ljava/lang/Object;"), 1,
                    new Marker("method", "Ljava/util/concurrent/atomic/AtomicBoolean;",
                            "get", "Z"),
                    new Marker("method", "Lbqoa;", "b", "V")),
            new Hook("session_start", "Lbqny;", "d",
                    Collections.singletonList("Lbrgx;"), "V",
                    "sessionStart", Collections.singletonList("Ljava/lang/Object;"), 1,
                    new Marker("method", "Lbqny;", "e", "Z")),
            new Hook("session_output", "Lbqny;", "c",
                    Collections.singletonList("Z"), "V",
                    "sessionOutputChanged", Collections.singletonList("Z"), 1,
                    new Marker("method", "Ljava/util/concurrent/atomic/AtomicBoolean;",
                            "compareAndSet", "Z")),
            new Hook("session_stop", "Lbqny;", "sa",
                    Collections.emptyList(), "V",
                    "sessionStop", Collections.emptyList(), 0,
                    new Marker("method", "Lbrjq;", "a", "V")),
            new Hook("bridge_register", RECEIVER_CLASS, "onReceive",
                    Arrays.asList("Landroid/content/Context;", "Landroid/content/Intent;"), "V",
                    "registerClient", Arrays.asList(
                            "Landroid/content/Context;", "Landroid/content/Intent;"), 2,
                    new Marker("method", "Landroid/content/Intent;", "getAction",
                            "Ljava/lang/String;"),
                    new Marker("method", "Lbqoa;", "a", "V")),
            new Hook("maneuver_bitmap", MANEUVER_VIEW_CLASS, "setManeuver",
                    Collections.singletonList("Lbqmm;"), "V",
                    "captureManeuverView", Arrays.asList(
                            "Ljava/lang/Object;", "Ljava/lang/Object;"), 2,
                    new Marker("method", MANEUVER_VIEW_CLASS, "a", "V"))
    );
    private static final Profile PROFILE_2516 = new Profile(
            "25.16", HOOKS_2516, "Lnmv;", "a",
            Collections.singletonList("Lbhdu;"),
            "Lbhiy;", "e", "Lbpix;", "Lbpix;", "i",
            "captureSpeedLimitState", SpeedLayout.V25,
            "Lbijo;", Arrays.asList("Lbilw;", "I"),
            "Lbikc;", Arrays.asList(
                    "Landroid/media/MediaPlayer;", "Lbimd;",
                    "Ljava/util/concurrent/Executor;", "Lbiiu;", "Latqe;"));
    private static final Profile PROFILE_2630 = new Profile(
            "26.30", HOOKS_2630, "Lbimf;", "rI",
            Collections.singletonList("Lbqiy;"),
            "Lbqny;", "e", "Lbrjq;", "Lbrjq;", "k",
            "captureSpeedLimitStateV26", SpeedLayout.V26,
            "Lbrrg;", Arrays.asList("Lbrtz;", "I"),
            "Lbrsa;", Arrays.asList(
                    "Landroid/media/MediaPlayer;", "Lbruh;",
                    "Ljava/util/concurrent/Executor;", "Lbrtq;"));
    private static final List<Profile> PROFILES = Arrays.asList(PROFILE_2630, PROFILE_2516);

    private GmapsDiagnosticPatcher() {
    }

    public static String inspectClassification(File apk) throws IOException {
        requireFile(apk, "APK");
        return inspect(apk).classification();
    }

    public static String inspectDirectClassification(File apk) throws IOException {
        requireFile(apk, "APK");
        return inspect(apk).directClassification();
    }

    public static String inspectAudioClassification(File apk) throws IOException {
        requireFile(apk, "APK");
        return inspect(apk).audioClassification();
    }

    public static void patchDirect(
            File input, File output, File loggerDex, File reportFile) throws Exception {
        patch(input, output, loggerDex, reportFile);
    }

    public static void patchNavigationAudio(
            File input, File output, File reportFile) throws Exception {
        patchAudio(input, output, reportFile);
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) usage();
        if ("patch".equals(args[0]) && args.length == 5) {
            patch(new File(args[1]), new File(args[2]), new File(args[3]), new File(args[4]));
            return;
        }
        if ("verify".equals(args[0]) && args.length == 3) {
            verify(new File(args[1]), new File(args[2]));
            return;
        }
        if ("patch-audio".equals(args[0]) && args.length == 4) {
            patchAudio(new File(args[1]), new File(args[2]), new File(args[3]));
            return;
        }
        if ("verify-audio".equals(args[0]) && args.length == 3) {
            verifyAudio(new File(args[1]), new File(args[2]));
            return;
        }
        usage();
    }

    private static void usage() {
        throw new IllegalArgumentException(
                "Usage: patch <input.apk> <unsigned.apk> <logger.dex> <report.json> "
                        + "or verify <apk> <report.json> "
                        + "or patch-audio <input.apk> <unsigned.apk> <report.json> "
                        + "or verify-audio <apk> <report.json>");
    }

    private static void patch(File input, File output, File loggerDex, File reportFile)
            throws Exception {
        requireFile(input, "input APK");
        requireFile(loggerDex, "logger DEX");
        byte[] loggerBytes = Files.readAllBytes(loggerDex.toPath());
        verifyLoggerDex(loggerBytes);

        Inspection before = inspect(input);
        before.requireStock();
        String loggerEntry = nextDexEntry(before.dexEntries);
        Map<String, File> replacements = new HashMap<>();
        List<ClassDef> relocatedClasses = new ArrayList<>();
        File work = new File(output.getParentFile(), "dex-work");
        deleteTree(work);
        if (!work.mkdirs()) throw new IOException("cannot create " + work);

        try (ZipFile zip = new ZipFile(input)) {
            boolean gateRewritten = false;
            for (Map.Entry<String, List<Hook>> entry : before.hooksByDex.entrySet()) {
                byte[] source = readEntry(zip, entry.getKey());
                File rewritten = new File(work, entry.getKey());
                File parent = rewritten.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("cannot create " + parent);
                }
                boolean containsGate = entry.getKey().equals(before.gateDexEntry);
                relocatedClasses.addAll(relocateDex(
                        source, rewritten, entry.getValue(), before.profile, containsGate));
                gateRewritten |= containsGate;
                replacements.put(entry.getKey(), rewritten);
            }
            if (!gateRewritten) {
                byte[] source = readEntry(zip, before.gateDexEntry);
                File rewritten = new File(work, before.gateDexEntry);
                rewriteProducerGateDex(source, rewritten, before.profile);
                replacements.put(before.gateDexEntry, rewritten);
            }
        }

        File combinedLoggerDex = new File(work, "combined-logger.dex");
        combineLoggerDex(loggerBytes, relocatedClasses, combinedLoggerDex);
        byte[] combinedLoggerBytes = Files.readAllBytes(combinedLoggerDex.toPath());

        File parent = output.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        File temporary = new File(output.getAbsolutePath() + ".tmp");
        repack(input, temporary, replacements, loggerEntry, combinedLoggerBytes);
        Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);

        Inspection after = inspect(output);
        after.requirePatched();
        Map<String, Object> report = baseReport("patch", input, after);
        report.put("inputSha256", sha256(input));
        report.put("outputSha256", sha256(output));
        report.put("loggerDexEntry", loggerEntry);
        report.put("modifiedDexEntries", new ArrayList<>(replacements.keySet()));
        List<String> relocated = new ArrayList<>();
        for (ClassDef classDef : relocatedClasses) relocated.add(classDef.getType());
        report.put("relocatedClasses", relocated);
        report.put("outputBytes", output.length());
        writeJson(reportFile, report);
        deleteTree(work);
    }

    private static void verify(File apk, File reportFile) throws Exception {
        requireFile(apk, "APK");
        Inspection inspection = inspect(apk);
        inspection.requirePatched();
        Map<String, Object> report = baseReport("verify", apk, inspection);
        report.put("apkSha256", sha256(apk));
        report.put("apkBytes", apk.length());
        writeJson(reportFile, report);
    }

    private static void patchAudio(File input, File output, File reportFile) throws Exception {
        requireFile(input, "direct GMaps APK");
        Inspection before = inspect(input);
        before.requireAudioPatchable();
        File work = new File(output.getParentFile(), "audio-dex-work");
        deleteTree(work);
        if (!work.mkdirs()) throw new IOException("cannot create " + work);
        Map<String, File> replacements = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(input)) {
            List<String> dexEntries = new ArrayList<>();
            dexEntries.add(before.audioDexEntry);
            if (!before.playbackAudioDexEntry.equals(before.audioDexEntry)) {
                dexEntries.add(before.playbackAudioDexEntry);
            }
            for (String dexEntry : dexEntries) {
                File rewritten = new File(work, dexEntry);
                File parent = rewritten.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("cannot create " + parent);
                }
                int expectedRewrites = before.audioDexEntry.equals(dexEntry) ? 1 : 0;
                if (before.playbackAudioDexEntry.equals(dexEntry)) expectedRewrites++;
                rewriteNavigationAudioDex(
                        readEntry(zip, dexEntry), rewritten, before.profile, expectedRewrites);
                replacements.put(dexEntry, rewritten);
            }
        }
        File temporary = new File(output.getAbsolutePath() + ".tmp");
        repack(input, temporary, replacements, null, null);
        Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
        Inspection after = inspect(output);
        after.requireAudioPatched();
        Map<String, Object> report = baseReport("patch-audio", output, after);
        report.put("inputSha256", sha256(input));
        report.put("outputSha256", sha256(output));
        report.put("modifiedDexEntries", new ArrayList<>(replacements.keySet()));
        writeJson(reportFile, report);
        deleteTree(work);
    }

    private static void verifyAudio(File apk, File reportFile) throws Exception {
        requireFile(apk, "GMaps APK");
        Inspection inspection = inspect(apk);
        inspection.requireAudioPatched();
        Map<String, Object> report = baseReport("verify-audio", apk, inspection);
        report.put("apkSha256", sha256(apk));
        report.put("apkBytes", apk.length());
        writeJson(reportFile, report);
    }

    private static Map<String, Object> baseReport(
            String mode, File apk, Inspection inspection) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("mode", mode);
        report.put("apk", apk.getAbsolutePath());
        report.put("classification", inspection.classification());
        report.put("targetProfile", inspection.profile.id);
        report.put("dexCount", inspection.dexEntries.size());
        report.put("loggerClassCount", inspection.loggerClassCount);
        Map<String, Object> hooks = new LinkedHashMap<>();
        for (Hook hook : inspection.profile.hooks) {
            HookResult result = inspection.results.get(hook.id);
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("targetCount", result.targetCount);
            value.put("hookCallCount", result.hookCallCount);
            value.put("dexEntry", result.dexEntry);
            value.put("guard", result.guard);
            hooks.put(hook.id, value);
        }
        report.put("hooks", hooks);
        Map<String, Object> producerGate = new LinkedHashMap<>();
        producerGate.put("targetCount", inspection.gateTargetCount);
        producerGate.put("stockGateCount", inspection.stockGateCount);
        producerGate.put("bypassCount", inspection.gateBypassCount);
        producerGate.put("bridgeGuardCount", inspection.gateBridgeCount);
        producerGate.put("speedStateCount", inspection.speedStateCount);
        producerGate.put("speedHookCount", inspection.speedHookCount);
        producerGate.put("dexEntry", inspection.gateDexEntry);
        producerGate.put("guard", inspection.gateGuard);
        report.put("producerGate", producerGate);
        Map<String, Object> navigationAudio = new LinkedHashMap<>();
        navigationAudio.put("targetCount", inspection.audioTargetCount);
        navigationAudio.put("stockCount", inspection.stockAudioCount);
        navigationAudio.put("patchedCount", inspection.patchedAudioCount);
        navigationAudio.put("dexEntry", inspection.audioDexEntry);
        navigationAudio.put("guard", inspection.audioGuard);
        navigationAudio.put("usage", 12);
        navigationAudio.put("stockContentType", 1);
        navigationAudio.put("patchedContentType", 6);
        navigationAudio.put("playbackTargetCount", inspection.playbackAudioTargetCount);
        navigationAudio.put("stockPlaybackCount", inspection.stockPlaybackAudioCount);
        navigationAudio.put("patchedPlaybackCount", inspection.patchedPlaybackAudioCount);
        navigationAudio.put("playbackDexEntry", inspection.playbackAudioDexEntry);
        navigationAudio.put("playbackGuard", inspection.playbackAudioGuard);
        report.put("navigationAudio", navigationAudio);
        return report;
    }

    private static Inspection inspect(File apk) throws IOException {
        Profile profile = detectProfile(apk);
        Inspection inspection = new Inspection(profile);
        try (ZipFile zip = new ZipFile(apk)) {
            List<? extends ZipEntry> entries = Collections.list(zip.entries());
            entries.sort(Comparator.comparing(ZipEntry::getName));
            for (ZipEntry entry : entries) {
                if (!isDexEntry(entry.getName())) continue;
                inspection.dexEntries.add(entry.getName());
                DexBackedDexFile dex = readDex(readEntry(zip, entry.getName()));
                for (ClassDef classDef : dex.getClasses()) {
                    if (LOGGER_CLASS.equals(classDef.getType())) inspection.loggerClassCount++;
                    if (profile.gateOwner.equals(classDef.getType())) {
                        for (Method method : classDef.getMethods()) {
                            if (!matchesProducerGate(method, profile)) continue;
                            inspection.gateTargetCount++;
                            inspection.gateDexEntry = entry.getName();
                            GateScan gate = scanProducerGate(
                                    method.getImplementation(), profile);
                            inspection.stockGateCount += gate.stockCount;
                            inspection.gateBypassCount += gate.bypassCount;
                            inspection.gateBridgeCount += gate.bridgeCount;
                            inspection.gateGuard = gate.guard;
                            SpeedScan speed = scanSpeedState(
                                    method.getImplementation(), profile);
                            inspection.speedStateCount += speed.stateCount;
                            inspection.speedHookCount += speed.hookCount;
                        }
                    }
                    if (profile.audioOwner.equals(classDef.getType())) {
                        for (Method method : classDef.getMethods()) {
                            if (!matchesNavigationAudio(method, profile)) continue;
                            inspection.audioTargetCount++;
                            inspection.audioDexEntry = entry.getName();
                            AudioScan audio = scanNavigationAudio(method.getImplementation());
                            inspection.stockAudioCount += audio.stockCount;
                            inspection.patchedAudioCount += audio.patchedCount;
                            inspection.audioGuard = audio.guard;
                        }
                    }
                    if (profile.playbackAudioOwner.equals(classDef.getType())) {
                        for (Method method : classDef.getMethods()) {
                            if (!matchesPlaybackAudio(method, profile)) continue;
                            inspection.playbackAudioTargetCount++;
                            inspection.playbackAudioDexEntry = entry.getName();
                            PlaybackAudioScan audio =
                                    scanPlaybackAudio(method.getImplementation());
                            inspection.stockPlaybackAudioCount += audio.stockCount;
                            inspection.patchedPlaybackAudioCount += audio.patchedCount;
                            inspection.playbackAudioGuard = audio.guard;
                        }
                    }
                    for (Hook hook : profile.hooks) {
                        if (!hook.owner.equals(classDef.getType())) continue;
                        for (Method method : classDef.getMethods()) {
                            if (!hook.matches(method)) continue;
                            HookResult result = inspection.results.get(hook.id);
                            result.targetCount++;
                            result.dexEntry = entry.getName();
                            MethodImplementation implementation = method.getImplementation();
                            if (implementation == null) {
                                result.guard = "missing implementation";
                                continue;
                            }
                            result.hookCallCount += countLoggerCalls(implementation, hook);
                            result.guard = verifyMarkers(implementation, hook);
                            inspection.hooksByDex.computeIfAbsent(
                                    entry.getName(), unused -> new ArrayList<>()).add(hook);
                        }
                    }
                }
            }
        }
        return inspection;
    }

    private static Profile detectProfile(File apk) throws IOException {
        Map<Profile, Integer> matches = new LinkedHashMap<>();
        for (Profile profile : PROFILES) matches.put(profile, 0);
        try (ZipFile zip = new ZipFile(apk)) {
            List<? extends ZipEntry> entries = Collections.list(zip.entries());
            entries.sort(Comparator.comparing(ZipEntry::getName));
            for (ZipEntry entry : entries) {
                if (!isDexEntry(entry.getName())) continue;
                DexBackedDexFile dex = readDex(readEntry(zip, entry.getName()));
                for (ClassDef classDef : dex.getClasses()) {
                    for (Profile profile : PROFILES) {
                        if (!profile.gateOwner.equals(classDef.getType())) continue;
                        for (Method method : classDef.getMethods()) {
                            if (matchesProducerGate(method, profile)) {
                                matches.put(profile, matches.get(profile) + 1);
                            }
                        }
                    }
                }
            }
        }
        Profile selected = null;
        for (Map.Entry<Profile, Integer> entry : matches.entrySet()) {
            if (entry.getValue() != 1) continue;
            if (selected != null) {
                throw new IOException("ambiguous GMaps target profiles");
            }
            selected = entry.getKey();
        }
        if (selected == null) {
            throw new IOException("unsupported GMaps target profile: " + matches);
        }
        return selected;
    }

    private static String verifyMarkers(MethodImplementation implementation, Hook hook) {
        for (Marker marker : hook.markers) {
            int count = 0;
            for (Instruction instruction : implementation.getInstructions()) {
                if (marker.matches(instruction)) count++;
            }
            if (count < 1) return "missing marker " + marker.owner + "->" + marker.name;
        }
        return "ok";
    }

    private static int countLoggerCalls(MethodImplementation implementation, Hook hook) {
        int count = 0;
        for (Instruction instruction : implementation.getInstructions()) {
            if (!(instruction instanceof ReferenceInstruction)) continue;
            Object reference = ((ReferenceInstruction) instruction).getReference();
            if (!(reference instanceof MethodReference)) continue;
            MethodReference method = (MethodReference) reference;
            if (LOGGER_CLASS.equals(method.getDefiningClass())
                    && hook.loggerMethod.equals(method.getName())
                    && hook.loggerParameters.equals(toStrings(method.getParameterTypes()))
                    && "V".equals(method.getReturnType())) count++;
        }
        return count;
    }

    private static List<ClassDef> relocateDex(
            byte[] input, File output, List<Hook> hooks,
            Profile profile, boolean patchGate) throws IOException {
        DexBackedDexFile dex = readDex(input);
        Map<String, AtomicInteger> counts = new HashMap<>();
        for (Hook hook : hooks) counts.put(hook.id, new AtomicInteger());
        AtomicInteger gateCount = new AtomicInteger();
        DexRewriter rewriter = new DexRewriter(new RewriterModule() {
            @Override
            public Rewriter<Method> getMethodRewriter(Rewriters rewriters) {
                return method -> {
                    for (Hook hook : hooks) {
                        if (hook.matches(method)) {
                            counts.get(hook.id).incrementAndGet();
                            return inject(method, hook);
                        }
                    }
                    if (patchGate && matchesProducerGate(method, profile)) {
                        gateCount.incrementAndGet();
                        return guardProducerGate(method, profile);
                    }
                    return method;
                };
            }
        });
        DexFile rewritten = rewriter.getDexFileRewriter().rewrite(dex);
        List<ClassDef> retained = new ArrayList<>();
        List<ClassDef> relocated = new ArrayList<>();
        for (ClassDef classDef : rewritten.getClasses()) {
            boolean move = false;
            for (Hook hook : hooks) {
                if (hook.owner.equals(classDef.getType())) {
                    move = true;
                    break;
                }
            }
            if (move) relocated.add(classDef);
            else retained.add(classDef);
        }
        // DexRewriter is lazy. Materialize moved methods before validating hook counts.
        for (ClassDef classDef : relocated) {
            for (Method method : classDef.getMethods()) {
                MethodImplementation implementation = method.getImplementation();
                if (implementation != null) implementation.getInstructions().iterator().hasNext();
            }
        }
        DexPool.writeTo(output.getAbsolutePath(),
                new ImmutableDexFile(Opcodes.forApi(35), retained));
        for (Hook hook : hooks) {
            if (counts.get(hook.id).get() != 1) {
                throw new IOException(hook.id + " rewrite count=" + counts.get(hook.id).get());
            }
        }
        if (patchGate && gateCount.get() != 1) {
            throw new IOException("producer gate rewrite count=" + gateCount.get());
        }
        return relocated;
    }

    private static void rewriteProducerGateDex(
            byte[] input, File output, Profile profile) throws IOException {
        DexBackedDexFile dex = readDex(input);
        AtomicInteger count = new AtomicInteger();
        DexRewriter rewriter = new DexRewriter(new RewriterModule() {
            @Override
            public Rewriter<Method> getMethodRewriter(Rewriters rewriters) {
                return method -> {
                    if (!matchesProducerGate(method, profile)) return method;
                    count.incrementAndGet();
                    return guardProducerGate(method, profile);
                };
            }
        });
        DexFile rewritten = rewriter.getDexFileRewriter().rewrite(dex);
        List<ClassDef> classes = new ArrayList<>();
        for (ClassDef classDef : rewritten.getClasses()) classes.add(classDef);
        DexPool.writeTo(output.getAbsolutePath(),
                new ImmutableDexFile(Opcodes.forApi(35), classes));
        if (count.get() != 1) {
            throw new IOException("producer gate rewrite count=" + count.get());
        }
    }

    private static void rewriteNavigationAudioDex(
            byte[] input, File output, Profile profile, int expectedRewrites) throws IOException {
        DexBackedDexFile dex = readDex(input);
        AtomicInteger count = new AtomicInteger();
        DexRewriter rewriter = new DexRewriter(new RewriterModule() {
            @Override
            public Rewriter<Method> getMethodRewriter(Rewriters rewriters) {
                return method -> {
                    if (matchesNavigationAudio(method, profile)) {
                        count.incrementAndGet();
                        return patchNavigationAudio(method);
                    }
                    if (matchesPlaybackAudio(method, profile)) {
                        count.incrementAndGet();
                        return patchPlaybackAudio(method);
                    }
                    return method;
                };
            }
        });
        DexFile rewritten = rewriter.getDexFileRewriter().rewrite(dex);
        List<ClassDef> classes = new ArrayList<>();
        for (ClassDef classDef : rewritten.getClasses()) classes.add(classDef);
        DexPool.writeTo(output.getAbsolutePath(),
                new ImmutableDexFile(Opcodes.forApi(35), classes));
        if (count.get() != expectedRewrites) {
            throw new IOException("navigation audio rewrite count=" + count.get());
        }
    }

    private static Method patchNavigationAudio(Method method) {
        MethodImplementation source = method.getImplementation();
        AudioScan before = scanNavigationAudio(source);
        if (!"ok".equals(before.guard) || before.stockCount != 1
                || before.patchedCount != 0 || before.location == null) {
            throw new IllegalStateException("navigation audio structural guard failed: "
                    + before.guard);
        }
        AudioLocation location = before.location;
        MutableMethodImplementation mutable = new MutableMethodImplementation(source);
        mutable.replaceInstruction(location.stateIndex,
                new BuilderInstruction11n(Opcode.CONST_4, location.usageRegister, 6));
        mutable.replaceInstruction(location.contentCallIndex,
                new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 2,
                        location.builderRegister, location.usageRegister,
                        0, 0, 0, location.contentMethod));
        AudioScan after = scanNavigationAudio(mutable);
        if (!"ok".equals(after.guard) || after.stockCount != 0
                || after.patchedCount != 1) {
            throw new IllegalStateException("navigation audio post-patch verification failed");
        }
        return new ImmutableMethod(
                method.getDefiningClass(), method.getName(), method.getParameters(),
                method.getReturnType(), method.getAccessFlags(), method.getAnnotations(),
                method.getHiddenApiRestrictions(), mutable);
    }

    private static boolean matchesNavigationAudio(Method method, Profile profile) {
        return profile.audioOwner.equals(method.getDefiningClass())
                && AUDIO_METHOD.equals(method.getName())
                && profile.audioParameters.equals(toStrings(method.getParameterTypes()))
                && "Z".equals(method.getReturnType());
    }

    private static Method patchPlaybackAudio(Method method) {
        MethodImplementation source = method.getImplementation();
        PlaybackAudioScan before = scanPlaybackAudio(source);
        if (!"ok".equals(before.guard) || before.stockCount != 1
                || before.patchedCount != 0 || before.location == null) {
            throw new IllegalStateException("playback audio structural guard failed: "
                    + before.guard);
        }
        MutableMethodImplementation mutable = new MutableMethodImplementation(source);
        mutable.replaceInstruction(before.location.contentLiteralIndex,
                new BuilderInstruction11n(Opcode.CONST_4,
                        before.location.contentRegister, 6));
        PlaybackAudioScan after = scanPlaybackAudio(mutable);
        if (!"ok".equals(after.guard) || after.stockCount != 0
                || after.patchedCount != 1) {
            throw new IllegalStateException("playback audio post-patch verification failed");
        }
        return new ImmutableMethod(
                method.getDefiningClass(), method.getName(), method.getParameters(),
                method.getReturnType(), method.getAccessFlags(), method.getAnnotations(),
                method.getHiddenApiRestrictions(), mutable);
    }

    private static boolean matchesPlaybackAudio(Method method, Profile profile) {
        return profile.playbackAudioOwner.equals(method.getDefiningClass())
                && "<init>".equals(method.getName())
                && profile.playbackAudioParameters.equals(toStrings(method.getParameterTypes()))
                && "V".equals(method.getReturnType());
    }

    private static AudioScan scanNavigationAudio(MethodImplementation implementation) {
        if (implementation == null) return new AudioScan(0, 0, null,
                "missing implementation");
        List<? extends Instruction> instructions = toInstructionList(implementation);
        int stock = 0;
        int patched = 0;
        AudioLocation match = null;
        for (int i = 1; i + 4 < instructions.size(); i++) {
            AudioLocation location = audioAt(instructions, i);
            if (location == null) continue;
            if (location.stock) stock++;
            if (location.patched) patched++;
            match = location;
        }
        int total = stock + patched;
        return new AudioScan(stock, patched, total == 1 ? match : null,
                total == 1 ? "ok" : "matched navigation audio branches=" + total);
    }

    private static PlaybackAudioScan scanPlaybackAudio(MethodImplementation implementation) {
        if (implementation == null) return new PlaybackAudioScan(0, 0, null,
                "missing implementation");
        List<? extends Instruction> instructions = toInstructionList(implementation);
        int stock = 0;
        int patched = 0;
        PlaybackAudioLocation match = null;
        for (int i = 1; i + 7 < instructions.size(); i++) {
            PlaybackAudioLocation location = playbackAudioAt(instructions, i);
            if (location == null) continue;
            if (location.stock) stock++;
            if (location.patched) patched++;
            match = location;
        }
        int total = stock + patched;
        return new PlaybackAudioScan(stock, patched, total == 1 ? match : null,
                total == 1 ? "ok" : "matched playback audio branches=" + total);
    }

    private static PlaybackAudioLocation playbackAudioAt(
            List<? extends Instruction> instructions, int usageCallIndex) {
        Instruction usageLiteral = instructions.get(usageCallIndex - 1);
        if (!isLiteral(usageLiteral, 12)) return null;
        int usageRegister = ((OneRegisterInstruction) usageLiteral).getRegisterA();
        Instruction usageCall = instructions.get(usageCallIndex);
        if (!isBuilderMethod(usageCall, "setUsage", AUDIO_BUILDER)
                || !(usageCall instanceof FiveRegisterInstruction)) return null;
        FiveRegisterInstruction usageRegisters = (FiveRegisterInstruction) usageCall;
        if (usageRegisters.getRegisterCount() != 2
                || usageRegisters.getRegisterD() != usageRegister) return null;
        int builderRegister = usageRegisters.getRegisterC();
        if (!isMoveResultObject(instructions.get(usageCallIndex + 1), builderRegister)) {
            return null;
        }
        int contentLiteralIndex = usageCallIndex + 2;
        Instruction contentLiteral = instructions.get(contentLiteralIndex);
        if (!(contentLiteral instanceof OneRegisterInstruction)
                || !(contentLiteral instanceof NarrowLiteralInstruction)) return null;
        int contentRegister = ((OneRegisterInstruction) contentLiteral).getRegisterA();
        int content = ((NarrowLiteralInstruction) contentLiteral).getNarrowLiteral();
        Instruction contentCall = instructions.get(usageCallIndex + 3);
        if (!isBuilderMethod(contentCall, "setContentType", AUDIO_BUILDER)
                || !(contentCall instanceof FiveRegisterInstruction)) return null;
        FiveRegisterInstruction contentRegisters = (FiveRegisterInstruction) contentCall;
        if (contentRegisters.getRegisterCount() != 2
                || contentRegisters.getRegisterC() != builderRegister
                || contentRegisters.getRegisterD() != contentRegister) return null;
        if (!isMoveResultObject(instructions.get(usageCallIndex + 4), builderRegister)
                || !isBuilderMethod(instructions.get(usageCallIndex + 5), "build",
                "Landroid/media/AudioAttributes;")
                || !isMediaPlayerSetAudioAttributes(instructions.get(usageCallIndex + 7))) {
            return null;
        }
        boolean stock = content == 1;
        boolean patched = content == 6;
        if (!stock && !patched) return null;
        return new PlaybackAudioLocation(
                contentLiteralIndex, contentRegister, stock, patched);
    }

    private static boolean isMediaPlayerSetAudioAttributes(Instruction instruction) {
        if (!(instruction instanceof ReferenceInstruction)) return false;
        Object reference = ((ReferenceInstruction) instruction).getReference();
        if (!(reference instanceof MethodReference)) return false;
        MethodReference method = (MethodReference) reference;
        return "Landroid/media/MediaPlayer;".equals(method.getDefiningClass())
                && "setAudioAttributes".equals(method.getName())
                && Collections.singletonList("Landroid/media/AudioAttributes;")
                .equals(toStrings(method.getParameterTypes()))
                && "V".equals(method.getReturnType());
    }

    private static AudioLocation audioAt(
            List<? extends Instruction> instructions, int usageCallIndex) {
        Instruction usageLiteral = instructions.get(usageCallIndex - 1);
        if (!isLiteral(usageLiteral, 12)) return null;
        int usageRegister = ((OneRegisterInstruction) usageLiteral).getRegisterA();
        Instruction usageCall = instructions.get(usageCallIndex);
        if (!isBuilderMethod(usageCall, "setUsage", AUDIO_BUILDER)
                || !(usageCall instanceof FiveRegisterInstruction)) return null;
        FiveRegisterInstruction usageRegisters = (FiveRegisterInstruction) usageCall;
        if (usageRegisters.getRegisterCount() != 2
                || usageRegisters.getRegisterD() != usageRegister) return null;
        int builderRegister = usageRegisters.getRegisterC();
        int stateIndex = usageCallIndex + 1;
        int contentCallIndex = usageCallIndex + 2;
        Instruction contentCall = instructions.get(contentCallIndex);
        if (!isBuilderMethod(contentCall, "setContentType", AUDIO_BUILDER)
                || !(contentCall instanceof FiveRegisterInstruction)) return null;
        FiveRegisterInstruction contentRegisters = (FiveRegisterInstruction) contentCall;
        if (contentRegisters.getRegisterCount() != 2
                || contentRegisters.getRegisterC() != builderRegister) return null;
        if (!isMoveResultObject(instructions.get(contentCallIndex + 1), builderRegister)
                || !isBuilderMethod(instructions.get(contentCallIndex + 2), "build",
                "Landroid/media/AudioAttributes;")) return null;
        int contentRegister = contentRegisters.getRegisterD();
        boolean stock = isMoveResultObject(instructions.get(stateIndex), builderRegister)
                && contentRegister != usageRegister
                && hasPreviousLiteral(instructions, contentRegister, 1, usageCallIndex);
        boolean patched = isLiteral(instructions.get(stateIndex), usageRegister, 6)
                && contentRegister == usageRegister;
        if (!stock && !patched) return null;
        return new AudioLocation(stateIndex, contentCallIndex, builderRegister, usageRegister,
                stock, patched, (MethodReference) ((ReferenceInstruction) contentCall)
                .getReference());
    }

    private static boolean isBuilderMethod(
            Instruction instruction, String name, String returnType) {
        if (!(instruction instanceof ReferenceInstruction)) return false;
        Object reference = ((ReferenceInstruction) instruction).getReference();
        if (!(reference instanceof MethodReference)) return false;
        MethodReference method = (MethodReference) reference;
        return AUDIO_BUILDER.equals(method.getDefiningClass())
                && name.equals(method.getName())
                && returnType.equals(method.getReturnType());
    }

    private static boolean isMoveResultObject(Instruction instruction, int register) {
        return instruction.getOpcode() == Opcode.MOVE_RESULT_OBJECT
                && instruction instanceof OneRegisterInstruction
                && ((OneRegisterInstruction) instruction).getRegisterA() == register;
    }

    private static boolean isLiteral(Instruction instruction, int value) {
        return instruction instanceof NarrowLiteralInstruction
                && ((NarrowLiteralInstruction) instruction).getNarrowLiteral() == value;
    }

    private static boolean isLiteral(Instruction instruction, int register, int value) {
        return instruction instanceof OneRegisterInstruction
                && ((OneRegisterInstruction) instruction).getRegisterA() == register
                && isLiteral(instruction, value);
    }

    private static boolean hasPreviousLiteral(
            List<? extends Instruction> instructions, int register, int value,
            int beforeIndex) {
        for (int i = beforeIndex - 1; i >= 0; i--) {
            Instruction instruction = instructions.get(i);
            if (!(instruction instanceof OneRegisterInstruction)
                    || ((OneRegisterInstruction) instruction).getRegisterA() != register) {
                continue;
            }
            if (instruction instanceof NarrowLiteralInstruction) {
                return ((NarrowLiteralInstruction) instruction).getNarrowLiteral() == value;
            }
        }
        return false;
    }

    private static void combineLoggerDex(
            byte[] loggerBytes, List<ClassDef> relocated, File output) throws IOException {
        List<ClassDef> classes = new ArrayList<>();
        for (ClassDef classDef : readDex(loggerBytes).getClasses()) classes.add(classDef);
        classes.addAll(relocated);
        DexPool.writeTo(output.getAbsolutePath(),
                new ImmutableDexFile(Opcodes.forApi(35), classes));
    }

    private static Method inject(Method method, Hook hook) {
        MethodImplementation source = method.getImplementation();
        if (source == null) throw new IllegalStateException(hook.id + " has no implementation");
        if (!"ok".equals(verifyMarkers(source, hook))) {
            throw new IllegalStateException(hook.id + " structural guard failed");
        }
        if (countLoggerCalls(source, hook) != 0) {
            throw new IllegalStateException(hook.id + " is already hooked");
        }
        MutableMethodImplementation mutable = new MutableMethodImplementation(source);
        ImmutableMethodReference logger = new ImmutableMethodReference(
                LOGGER_CLASS, hook.loggerMethod, hook.loggerParameters, "V");
        BuilderInstruction call;
        if (hook.argumentWords == 0) {
            call = new BuilderInstruction35c(Opcode.INVOKE_STATIC, 0,
                    0, 0, 0, 0, 0, logger);
        } else {
            int firstArgument = source.getRegisterCount() - hook.argumentWords;
            if (firstArgument < 0) {
                throw new IllegalStateException(hook.id + " invalid parameter register");
            }
            call = new BuilderInstruction3rc(
                    Opcode.INVOKE_STATIC_RANGE, firstArgument, hook.argumentWords, logger);
        }
        mutable.addInstruction(0, call);
        return new ImmutableMethod(
                method.getDefiningClass(), method.getName(), method.getParameters(),
                method.getReturnType(), method.getAccessFlags(), method.getAnnotations(),
                method.getHiddenApiRestrictions(), mutable);
    }

    private static Method guardProducerGate(Method method, Profile profile) {
        MethodImplementation source = method.getImplementation();
        GateScan before = scanProducerGate(source, profile);
        SpeedScan speedBefore = scanSpeedState(source, profile);
        if (!"ok".equals(before.guard) || before.stockCount != 1
                || before.bypassCount != 0 || before.bridgeCount != 0
                || !"ok".equals(speedBefore.guard) || speedBefore.stateCount != 1
                || speedBefore.hookCount != 0) {
            throw new IllegalStateException("producer gate structural guard failed: "
                    + before.guard + "/" + speedBefore.guard);
        }
        MutableMethodImplementation mutable = new MutableMethodImplementation(source);
        GateLocation location = findStockGate(mutable.getInstructions(), profile);
        if (location == null) throw new IllegalStateException("producer gate not found");
        ImmutableMethodReference noClient = new ImmutableMethodReference(
                LOGGER_CLASS, "noClient", Collections.emptyList(), "Z");
        mutable.addInstruction(location.branchIndex,
                new BuilderInstruction35c(Opcode.INVOKE_STATIC, 0,
                        0, 0, 0, 0, 0, noClient));
        mutable.addInstruction(location.branchIndex + 1,
                new BuilderInstruction11x(Opcode.MOVE_RESULT, location.register));
        SpeedLocation speed = findSpeedState(mutable.getInstructions(), profile);
        if (speed == null || speed.hooked) {
            throw new IllegalStateException("speed-limit state hook target not found");
        }
        ImmutableMethodReference speedCapture = new ImmutableMethodReference(
                LOGGER_CLASS, profile.speedCaptureMethod,
                Collections.singletonList("Ljava/lang/Object;"), "V");
        mutable.addInstruction(speed.insertIndex, new BuilderInstruction35c(
                Opcode.INVOKE_STATIC, 1, speed.stateRegister, 0, 0, 0, 0, speedCapture));
        GateScan after = scanProducerGate(mutable, profile);
        SpeedScan speedAfter = scanSpeedState(mutable, profile);
        if (!"ok".equals(after.guard) || after.stockCount != 0
                || after.bypassCount != 0 || after.bridgeCount != 1
                || !"ok".equals(speedAfter.guard) || speedAfter.stateCount != 1
                || speedAfter.hookCount != 1) {
            throw new IllegalStateException("producer gate post-patch verification failed");
        }
        return new ImmutableMethod(
                method.getDefiningClass(), method.getName(), method.getParameters(),
                method.getReturnType(), method.getAccessFlags(), method.getAnnotations(),
                method.getHiddenApiRestrictions(), mutable);
    }

    private static boolean matchesProducerGate(Method method, Profile profile) {
        return profile.gateOwner.equals(method.getDefiningClass())
                && profile.gateMethod.equals(method.getName())
                && profile.gateParameters.equals(toStrings(method.getParameterTypes()))
                && "V".equals(method.getReturnType());
    }

    private static GateScan scanProducerGate(
            MethodImplementation implementation, Profile profile) {
        if (implementation == null) return new GateScan(0, 0, 0, "missing implementation");
        List<? extends Instruction> instructions = toInstructionList(implementation);
        int stock = 0;
        int bypass = 0;
        int bridge = 0;
        for (int i = 0; i < instructions.size(); i++) {
            GateLocation location = gateAt(instructions, i, profile);
            if (location == null) continue;
            if (location.bypassed) bypass++;
            else if (location.bridgeGuarded) bridge++;
            else stock++;
        }
        int total = stock + bypass + bridge;
        return new GateScan(stock, bypass, bridge,
                total == 1 ? "ok" : "matched producer gates=" + total);
    }

    private static GateLocation findStockGate(
            List<? extends Instruction> instructions, Profile profile) {
        for (int i = 0; i < instructions.size(); i++) {
            GateLocation location = gateAt(instructions, i, profile);
            if (location != null && !location.bypassed) return location;
        }
        return null;
    }

    private static GateLocation gateAt(
            List<? extends Instruction> instructions, int index, Profile profile) {
        if (!isEmptyCall(instructions.get(index))
                || !hasProducerFields(instructions, index, profile)) {
            return null;
        }
        int moveIndex = index + 1;
        if (moveIndex >= instructions.size()
                || instructions.get(moveIndex).getOpcode() != Opcode.MOVE_RESULT
                || !(instructions.get(moveIndex) instanceof OneRegisterInstruction)) return null;
        int register = ((OneRegisterInstruction) instructions.get(moveIndex)).getRegisterA();
        int branchIndex = moveIndex + 1;
        boolean bypassed = false;
        boolean bridgeGuarded = false;
        if (branchIndex < instructions.size()
                && isZeroConst(instructions.get(branchIndex), register)) {
            bypassed = true;
            branchIndex++;
        } else if (branchIndex + 1 < instructions.size()
                && isNoClientCall(instructions.get(branchIndex))
                && instructions.get(branchIndex + 1).getOpcode() == Opcode.MOVE_RESULT
                && instructions.get(branchIndex + 1) instanceof OneRegisterInstruction
                && ((OneRegisterInstruction) instructions.get(branchIndex + 1)).getRegisterA()
                == register) {
            bridgeGuarded = true;
            branchIndex += 2;
        }
        if (branchIndex >= instructions.size()
                || instructions.get(branchIndex).getOpcode() != Opcode.IF_NEZ
                || !(instructions.get(branchIndex) instanceof OneRegisterInstruction)
                || ((OneRegisterInstruction) instructions.get(branchIndex)).getRegisterA()
                != register) return null;
        return new GateLocation(branchIndex, register, bypassed, bridgeGuarded);
    }

    private static boolean isEmptyCall(Instruction instruction) {
        if (!(instruction instanceof ReferenceInstruction)) return false;
        Object reference = ((ReferenceInstruction) instruction).getReference();
        if (!(reference instanceof MethodReference)) return false;
        MethodReference method = (MethodReference) reference;
        return "isEmpty".equals(method.getName())
                && method.getParameterTypes().isEmpty()
                && "Z".equals(method.getReturnType());
    }

    private static boolean isNoClientCall(Instruction instruction) {
        if (!(instruction instanceof ReferenceInstruction)) return false;
        Object reference = ((ReferenceInstruction) instruction).getReference();
        if (!(reference instanceof MethodReference)) return false;
        MethodReference method = (MethodReference) reference;
        return LOGGER_CLASS.equals(method.getDefiningClass())
                && "noClient".equals(method.getName())
                && method.getParameterTypes().isEmpty()
                && "Z".equals(method.getReturnType());
    }

    private static SpeedScan scanSpeedState(
            MethodImplementation implementation, Profile profile) {
        if (implementation == null) return new SpeedScan(0, 0, "missing implementation");
        List<? extends Instruction> instructions = toInstructionList(implementation);
        int states = 0;
        int hooks = 0;
        for (int i = 0; i < instructions.size(); i++) {
            SpeedLocation location = speedStateAt(instructions, i, profile);
            if (location == null) continue;
            states++;
            if (location.hooked) hooks++;
        }
        return new SpeedScan(states, hooks,
                states == 1 ? "ok" : "matched speed states=" + states);
    }

    private static SpeedLocation findSpeedState(
            List<? extends Instruction> instructions, Profile profile) {
        SpeedLocation match = null;
        for (int i = 0; i < instructions.size(); i++) {
            SpeedLocation candidate = speedStateAt(instructions, i, profile);
            if (candidate == null) continue;
            if (match != null) return null;
            match = candidate;
        }
        return match;
    }

    private static SpeedLocation speedStateAt(
            List<? extends Instruction> instructions, int gateIndex, Profile profile) {
        GateLocation gate = gateAt(instructions, gateIndex, profile);
        if (gate == null || gate.bypassed) return null;
        if (profile.speedLayout == SpeedLayout.V26) {
            return speedStateAtV26(instructions, gateIndex, gate, profile);
        }
        int callIndex = gate.branchIndex + 1;
        if (callIndex + 2 >= instructions.size()
                || !isMethod(instructions.get(callIndex), "Lbhrz;", "c",
                Collections.emptyList(), "Lbhhw;")
                || instructions.get(callIndex + 1).getOpcode() != Opcode.MOVE_RESULT_OBJECT
                || !(instructions.get(callIndex + 1) instanceof OneRegisterInstruction)) {
            return null;
        }
        int stateRegister = ((OneRegisterInstruction) instructions.get(callIndex + 1)).getRegisterA();
        int nextIndex = callIndex + 2;
        boolean hooked = nextIndex < instructions.size()
                && isSpeedCapture(instructions.get(nextIndex), stateRegister, profile);
        if (hooked) nextIndex++;
        if (nextIndex >= instructions.size()
                || instructions.get(nextIndex).getOpcode() != Opcode.IGET_OBJECT
                || !(instructions.get(nextIndex) instanceof TwoRegisterInstruction)
                || ((TwoRegisterInstruction) instructions.get(nextIndex)).getRegisterB()
                != stateRegister
                || !isField(instructions.get(nextIndex), "Lbhhw;", "b", "Luiz;")) {
            return null;
        }
        return new SpeedLocation(callIndex + 2, stateRegister, hooked);
    }

    private static SpeedLocation speedStateAtV26(
            List<? extends Instruction> instructions, int gateIndex,
            GateLocation gate, Profile profile) {
        int stateRegister = -1;
        int stateCount = 0;
        for (int i = Math.max(0, gateIndex - 40); i + 1 < gateIndex; i++) {
            if (!isMethod(instructions.get(i), "Lcfru;", "bs",
                    Collections.singletonList("Lbqiy;"), "Lbqyt;")
                    || instructions.get(i + 1).getOpcode() != Opcode.MOVE_RESULT_OBJECT
                    || !(instructions.get(i + 1) instanceof OneRegisterInstruction)) {
                continue;
            }
            stateRegister = ((OneRegisterInstruction) instructions.get(i + 1)).getRegisterA();
            stateCount++;
        }
        if (stateCount != 1) return null;
        int insertIndex = gate.branchIndex + 1;
        boolean hooked = insertIndex < instructions.size()
                && isSpeedCapture(instructions.get(insertIndex), stateRegister, profile);
        return new SpeedLocation(insertIndex, stateRegister, hooked);
    }

    private static boolean isSpeedCapture(
            Instruction instruction, int stateRegister, Profile profile) {
        return instruction instanceof FiveRegisterInstruction
                && ((FiveRegisterInstruction) instruction).getRegisterCount() == 1
                && ((FiveRegisterInstruction) instruction).getRegisterC() == stateRegister
                && isMethod(instruction, LOGGER_CLASS, profile.speedCaptureMethod,
                Collections.singletonList("Ljava/lang/Object;"), "V");
    }

    private static boolean isMethod(Instruction instruction, String owner, String name,
            List<String> parameters, String returnType) {
        if (!(instruction instanceof ReferenceInstruction)) return false;
        Object reference = ((ReferenceInstruction) instruction).getReference();
        if (!(reference instanceof MethodReference)) return false;
        MethodReference method = (MethodReference) reference;
        return owner.equals(method.getDefiningClass()) && name.equals(method.getName())
                && parameters.equals(toStrings(method.getParameterTypes()))
                && returnType.equals(method.getReturnType());
    }

    private static boolean isField(
            Instruction instruction, String owner, String name, String type) {
        if (!(instruction instanceof ReferenceInstruction)) return false;
        Object reference = ((ReferenceInstruction) instruction).getReference();
        if (!(reference instanceof FieldReference)) return false;
        FieldReference field = (FieldReference) reference;
        return owner.equals(field.getDefiningClass()) && name.equals(field.getName())
                && type.equals(field.getType());
    }

    private static boolean hasProducerFields(
            List<? extends Instruction> instructions, int endExclusive, Profile profile) {
        boolean sessionProvider = false;
        boolean activeConsumers = false;
        for (int i = Math.max(0, endExclusive - 8); i < endExclusive; i++) {
            Instruction instruction = instructions.get(i);
            if (!(instruction instanceof ReferenceInstruction)) continue;
            Object reference = ((ReferenceInstruction) instruction).getReference();
            if (!(reference instanceof FieldReference)) continue;
            FieldReference field = (FieldReference) reference;
            sessionProvider |= profile.sessionFieldOwner.equals(field.getDefiningClass())
                    && profile.sessionFieldName.equals(field.getName())
                    && profile.sessionFieldType.equals(field.getType());
            activeConsumers |= profile.consumerFieldOwner.equals(field.getDefiningClass())
                    && profile.consumerFieldName.equals(field.getName());
        }
        return sessionProvider && activeConsumers;
    }

    private static boolean isZeroConst(Instruction instruction, int register) {
        return instruction.getOpcode() == Opcode.CONST_4
                && instruction instanceof OneRegisterInstruction
                && instruction instanceof NarrowLiteralInstruction
                && ((OneRegisterInstruction) instruction).getRegisterA() == register
                && ((NarrowLiteralInstruction) instruction).getNarrowLiteral() == 0;
    }

    private static List<? extends Instruction> toInstructionList(
            MethodImplementation implementation) {
        List<Instruction> instructions = new ArrayList<>();
        for (Instruction instruction : implementation.getInstructions()) {
            instructions.add(instruction);
        }
        return instructions;
    }

    private static void repack(
            File input, File output, Map<String, File> replacements,
            String loggerEntry, byte[] loggerBytes) throws IOException {
        try (ZipFile source = new ZipFile(input);
             ZipOutputStream target = new ZipOutputStream(new FileOutputStream(output))) {
            Enumeration<? extends ZipEntry> entries = source.entries();
            byte[] buffer = new byte[128 * 1024];
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (isSignatureEntry(entry.getName())) continue;
                File replacement = replacements.get(entry.getName());
                if (replacement != null) {
                    putStored(target, entry.getName(), Files.readAllBytes(replacement.toPath()), entry);
                    continue;
                }
                ZipEntry copy = copyMetadata(entry);
                target.putNextEntry(copy);
                if (!entry.isDirectory()) {
                    try (InputStream stream = source.getInputStream(entry)) {
                        int read;
                        while ((read = stream.read(buffer)) >= 0) target.write(buffer, 0, read);
                    }
                }
                target.closeEntry();
            }
            if (loggerEntry != null && loggerBytes != null) {
                putStored(target, loggerEntry, loggerBytes, null);
            }
        }
    }

    private static ZipEntry copyMetadata(ZipEntry source) {
        ZipEntry copy = new ZipEntry(source.getName());
        copy.setTime(source.getTime());
        if (source.getComment() != null) copy.setComment(source.getComment());
        if (source.getExtra() != null) copy.setExtra(source.getExtra());
        copy.setMethod(source.getMethod());
        if (source.getMethod() == ZipEntry.STORED) {
            copy.setSize(source.getSize());
            copy.setCompressedSize(source.getCompressedSize());
            copy.setCrc(source.getCrc());
        }
        return copy;
    }

    private static void putStored(
            ZipOutputStream output, String name, byte[] bytes, ZipEntry source) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(bytes.length);
        entry.setCompressedSize(bytes.length);
        entry.setCrc(crc.getValue());
        entry.setTime(source == null ? System.currentTimeMillis() : source.getTime());
        output.putNextEntry(entry);
        output.write(bytes);
        output.closeEntry();
    }

    private static void verifyLoggerDex(byte[] bytes) throws IOException {
        int count = 0;
        for (ClassDef classDef : readDex(bytes).getClasses()) {
            if (LOGGER_CLASS.equals(classDef.getType())) count++;
        }
        if (count != 1) throw new IOException("logger class count=" + count + ", expected=1");
    }

    private static DexBackedDexFile readDex(byte[] bytes) throws IOException {
        return DexBackedDexFile.fromInputStream(
                Opcodes.forApi(35), new ByteArrayInputStream(bytes));
    }

    private static byte[] readEntry(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) throw new IOException("missing APK entry " + name);
        try (InputStream input = zip.getInputStream(entry);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            return output.toByteArray();
        }
    }

    private static String nextDexEntry(List<String> entries) {
        int maximum = 0;
        for (String entry : entries) {
            if ("classes.dex".equals(entry)) maximum = Math.max(maximum, 1);
            else {
                String number = entry.substring("classes".length(), entry.length() - 4);
                maximum = Math.max(maximum, Integer.parseInt(number));
            }
        }
        return "classes" + (maximum + 1) + ".dex";
    }

    private static boolean isDexEntry(String name) {
        return name.matches("classes(\\d*)\\.dex");
    }

    private static boolean isSignatureEntry(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("META-INF/")) return false;
        return upper.endsWith(".RSA") || upper.endsWith(".DSA")
                || upper.endsWith(".EC") || upper.endsWith(".SF")
                || upper.equals("META-INF/MANIFEST.MF");
    }

    private static List<String> toStrings(List<? extends CharSequence> values) {
        List<String> result = new ArrayList<>(values.size());
        for (CharSequence value : values) result.add(value.toString());
        return result;
    }

    private static void requireFile(File file, String label) throws IOException {
        if (!file.isFile()) throw new IOException(label + " is missing: " + file);
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02X", value));
        return result.toString();
    }

    private static void writeJson(File output, Map<String, Object> value) throws IOException {
        File parent = output.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        Files.write(output.toPath(), json(value, 0).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String json(Object value, int indent) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof String) return "\"" + escape((String) value) + "\"";
        if (value instanceof Map) {
            StringBuilder result = new StringBuilder("{\n");
            boolean first = true;
            for (Object item : ((Map<?, ?>) value).entrySet()) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) item;
                if (!first) result.append(",\n");
                first = false;
                result.append(spaces(indent + 2)).append(json(String.valueOf(entry.getKey()), 0))
                        .append(": ").append(json(entry.getValue(), indent + 2));
            }
            return result.append('\n').append(spaces(indent)).append('}').toString();
        }
        if (value instanceof Iterable) {
            StringBuilder result = new StringBuilder("[");
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) result.append(", ");
                first = false;
                result.append(json(item, indent));
            }
            return result.append(']').toString();
        }
        return json(String.valueOf(value), indent);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private static String spaces(int count) {
        char[] value = new char[count];
        Arrays.fill(value, ' ');
        return new String(value);
    }

    private static void deleteTree(File file) throws IOException {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        if (!file.delete()) throw new IOException("cannot delete " + file);
    }

    private enum SpeedLayout {
        V25,
        V26
    }

    private static final class Profile {
        final String id;
        final List<Hook> hooks;
        final String gateOwner;
        final String gateMethod;
        final List<String> gateParameters;
        final String sessionFieldOwner;
        final String sessionFieldName;
        final String sessionFieldType;
        final String consumerFieldOwner;
        final String consumerFieldName;
        final String speedCaptureMethod;
        final SpeedLayout speedLayout;
        final String audioOwner;
        final List<String> audioParameters;
        final String playbackAudioOwner;
        final List<String> playbackAudioParameters;

        Profile(
                String id, List<Hook> hooks,
                String gateOwner, String gateMethod, List<String> gateParameters,
                String sessionFieldOwner, String sessionFieldName, String sessionFieldType,
                String consumerFieldOwner, String consumerFieldName,
                String speedCaptureMethod, SpeedLayout speedLayout,
                String audioOwner, List<String> audioParameters,
                String playbackAudioOwner, List<String> playbackAudioParameters) {
            this.id = id;
            this.hooks = hooks;
            this.gateOwner = gateOwner;
            this.gateMethod = gateMethod;
            this.gateParameters = gateParameters;
            this.sessionFieldOwner = sessionFieldOwner;
            this.sessionFieldName = sessionFieldName;
            this.sessionFieldType = sessionFieldType;
            this.consumerFieldOwner = consumerFieldOwner;
            this.consumerFieldName = consumerFieldName;
            this.speedCaptureMethod = speedCaptureMethod;
            this.speedLayout = speedLayout;
            this.audioOwner = audioOwner;
            this.audioParameters = audioParameters;
            this.playbackAudioOwner = playbackAudioOwner;
            this.playbackAudioParameters = playbackAudioParameters;
        }

        @Override
        public String toString() {
            return id;
        }
    }

    private static final class Inspection {
        final Profile profile;
        final List<String> dexEntries = new ArrayList<>();
        final Map<String, HookResult> results = new LinkedHashMap<>();
        final Map<String, List<Hook>> hooksByDex = new LinkedHashMap<>();
        int loggerClassCount;
        int gateTargetCount;
        int stockGateCount;
        int gateBypassCount;
        int gateBridgeCount;
        int speedStateCount;
        int speedHookCount;
        String gateDexEntry = "";
        String gateGuard = "not found";
        int audioTargetCount;
        int stockAudioCount;
        int patchedAudioCount;
        String audioDexEntry = "";
        String audioGuard = "not found";
        int playbackAudioTargetCount;
        int stockPlaybackAudioCount;
        int patchedPlaybackAudioCount;
        String playbackAudioDexEntry = "";
        String playbackAudioGuard = "not found";

        Inspection(Profile profile) {
            this.profile = profile;
            for (Hook hook : profile.hooks) results.put(hook.id, new HookResult());
        }

        String classification() {
            String direct = directClassification();
            String audio = audioClassification();
            if ("PATCHABLE_STOCK".equals(direct) && "PATCHABLE_STOCK".equals(audio)) {
                return "PATCHABLE_STOCK";
            }
            if ("MESSENGER_BRIDGE_POC".equals(direct)
                    && "PATCHABLE_STOCK".equals(audio)) {
                return "MESSENGER_BRIDGE_POC";
            }
            if ("MESSENGER_BRIDGE_POC".equals(direct)
                    && "NAVIGATION_AUDIO".equals(audio)) {
                return "MESSENGER_BRIDGE_NAV_AUDIO";
            }
            return "UNSUPPORTED";
        }

        String directClassification() {
            boolean stock = loggerClassCount == 0;
            boolean bridge = loggerClassCount == 1;
            for (Hook hook : profile.hooks) {
                HookResult result = results.get(hook.id);
                stock &= result.targetCount == 1 && result.hookCallCount == 0
                        && "ok".equals(result.guard);
                bridge &= result.targetCount == 1 && result.hookCallCount == 1
                        && "ok".equals(result.guard);
            }
            stock &= gateTargetCount == 1 && stockGateCount == 1
                    && gateBypassCount == 0 && gateBridgeCount == 0
                    && "ok".equals(gateGuard)
                    && speedStateCount == 1 && speedHookCount == 0;
            bridge &= gateTargetCount == 1 && stockGateCount == 0
                    && gateBypassCount == 0 && gateBridgeCount == 1
                    && "ok".equals(gateGuard)
                    && speedStateCount == 1 && speedHookCount <= 1;
            if (stock) return "PATCHABLE_STOCK";
            if (bridge) return "MESSENGER_BRIDGE_POC";
            return "UNSUPPORTED";
        }

        String audioClassification() {
            boolean stockAudio = audioTargetCount == 1
                    && stockAudioCount == 1 && patchedAudioCount == 0
                    && "ok".equals(audioGuard)
                    && playbackAudioTargetCount == 1 && stockPlaybackAudioCount == 1
                    && patchedPlaybackAudioCount == 0
                    && "ok".equals(playbackAudioGuard);
            boolean navAudio = audioTargetCount == 1
                    && stockAudioCount == 0 && patchedAudioCount == 1
                    && "ok".equals(audioGuard)
                    && playbackAudioTargetCount == 1 && stockPlaybackAudioCount == 0
                    && patchedPlaybackAudioCount == 1
                    && "ok".equals(playbackAudioGuard);
            if (stockAudio) return "PATCHABLE_STOCK";
            if (navAudio) return "NAVIGATION_AUDIO";
            return "UNSUPPORTED";
        }

        void requireStock() throws IOException {
            if (!"PATCHABLE_STOCK".equals(directClassification())) {
                throw new IOException("input direct classification=" + directClassification()
                        + "; expected PATCHABLE_STOCK: " + details());
            }
        }

        void requirePatched() throws IOException {
            if (!"MESSENGER_BRIDGE_POC".equals(directClassification())
                    || speedHookCount != 1) {
                throw new IOException("APK direct classification=" + directClassification()
                        + "; expected MESSENGER_BRIDGE_POC with speed hook: " + details());
            }
        }

        void requireAudioPatchable() throws IOException {
            if (!"MESSENGER_BRIDGE_POC".equals(directClassification())
                    || !"PATCHABLE_STOCK".equals(audioClassification())) {
                throw new IOException("input component classification="
                        + directClassification() + "/" + audioClassification()
                        + "; expected MESSENGER_BRIDGE_POC/PATCHABLE_STOCK: "
                        + details());
            }
        }

        void requireAudioPatched() throws IOException {
            if (!"MESSENGER_BRIDGE_POC".equals(directClassification())
                    || !"NAVIGATION_AUDIO".equals(audioClassification())) {
                throw new IOException("APK component classification="
                        + directClassification() + "/" + audioClassification()
                        + "; expected MESSENGER_BRIDGE_POC/NAVIGATION_AUDIO: "
                        + details());
            }
        }

        private String details() {
            StringBuilder value = new StringBuilder("logger=").append(loggerClassCount);
            for (Map.Entry<String, HookResult> entry : results.entrySet()) {
                HookResult result = entry.getValue();
                value.append(", ").append(entry.getKey()).append("=")
                        .append(result.targetCount).append('/').append(result.hookCallCount)
                        .append('/').append(result.guard);
            }
            value.append(", producerGate=").append(gateTargetCount).append('/')
                    .append(stockGateCount).append('/').append(gateBypassCount)
                    .append('/').append(gateBridgeCount)
                    .append('/').append(gateGuard);
            value.append(", speedState=").append(speedStateCount).append('/')
                    .append(speedHookCount);
            value.append(", navigationAudio=").append(audioTargetCount).append('/')
                    .append(stockAudioCount).append('/').append(patchedAudioCount)
                    .append('/').append(audioGuard);
            value.append(", playbackAudio=").append(playbackAudioTargetCount).append('/')
                    .append(stockPlaybackAudioCount).append('/')
                    .append(patchedPlaybackAudioCount).append('/')
                    .append(playbackAudioGuard);
            return value.toString();
        }
    }

    private static final class AudioScan {
        final int stockCount;
        final int patchedCount;
        final AudioLocation location;
        final String guard;

        AudioScan(int stockCount, int patchedCount, AudioLocation location, String guard) {
            this.stockCount = stockCount;
            this.patchedCount = patchedCount;
            this.location = location;
            this.guard = guard;
        }
    }

    private static final class AudioLocation {
        final int stateIndex;
        final int contentCallIndex;
        final int builderRegister;
        final int usageRegister;
        final boolean stock;
        final boolean patched;
        final MethodReference contentMethod;

        AudioLocation(
                int stateIndex, int contentCallIndex, int builderRegister, int usageRegister,
                boolean stock, boolean patched, MethodReference contentMethod) {
            this.stateIndex = stateIndex;
            this.contentCallIndex = contentCallIndex;
            this.builderRegister = builderRegister;
            this.usageRegister = usageRegister;
            this.stock = stock;
            this.patched = patched;
            this.contentMethod = contentMethod;
        }
    }

    private static final class PlaybackAudioScan {
        final int stockCount;
        final int patchedCount;
        final PlaybackAudioLocation location;
        final String guard;

        PlaybackAudioScan(
                int stockCount, int patchedCount, PlaybackAudioLocation location,
                String guard) {
            this.stockCount = stockCount;
            this.patchedCount = patchedCount;
            this.location = location;
            this.guard = guard;
        }
    }

    private static final class PlaybackAudioLocation {
        final int contentLiteralIndex;
        final int contentRegister;
        final boolean stock;
        final boolean patched;

        PlaybackAudioLocation(
                int contentLiteralIndex, int contentRegister,
                boolean stock, boolean patched) {
            this.contentLiteralIndex = contentLiteralIndex;
            this.contentRegister = contentRegister;
            this.stock = stock;
            this.patched = patched;
        }
    }

    private static final class GateScan {
        final int stockCount;
        final int bypassCount;
        final int bridgeCount;
        final String guard;

        GateScan(int stockCount, int bypassCount, int bridgeCount, String guard) {
            this.stockCount = stockCount;
            this.bypassCount = bypassCount;
            this.bridgeCount = bridgeCount;
            this.guard = guard;
        }
    }

    private static final class GateLocation {
        final int branchIndex;
        final int register;
        final boolean bypassed;
        final boolean bridgeGuarded;

        GateLocation(int branchIndex, int register, boolean bypassed,
                     boolean bridgeGuarded) {
            this.branchIndex = branchIndex;
            this.register = register;
            this.bypassed = bypassed;
            this.bridgeGuarded = bridgeGuarded;
        }
    }

    private static final class SpeedScan {
        final int stateCount;
        final int hookCount;
        final String guard;

        SpeedScan(int stateCount, int hookCount, String guard) {
            this.stateCount = stateCount;
            this.hookCount = hookCount;
            this.guard = guard;
        }
    }

    private static final class SpeedLocation {
        final int insertIndex;
        final int stateRegister;
        final boolean hooked;

        SpeedLocation(int insertIndex, int stateRegister, boolean hooked) {
            this.insertIndex = insertIndex;
            this.stateRegister = stateRegister;
            this.hooked = hooked;
        }
    }

    private static final class HookResult {
        int targetCount;
        int hookCallCount;
        String dexEntry = "";
        String guard = "not found";
    }

    private static final class Hook {
        final String id;
        final String owner;
        final String method;
        final List<String> parameters;
        final String returnType;
        final String loggerMethod;
        final List<String> loggerParameters;
        final int argumentWords;
        final List<Marker> markers;

        Hook(
                String id, String owner, String method, List<String> parameters,
                String returnType, String loggerMethod, List<String> loggerParameters,
                int argumentWords, Marker... markers) {
            this.id = id;
            this.owner = owner;
            this.method = method;
            this.parameters = parameters;
            this.returnType = returnType;
            this.loggerMethod = loggerMethod;
            this.loggerParameters = loggerParameters;
            this.argumentWords = argumentWords;
            this.markers = Arrays.asList(markers);
        }

        boolean matches(Method candidate) {
            return owner.equals(candidate.getDefiningClass())
                    && method.equals(candidate.getName())
                    && parameters.equals(toStrings(candidate.getParameterTypes()))
                    && returnType.equals(candidate.getReturnType());
        }
    }

    private static final class Marker {
        final String kind;
        final String owner;
        final String name;
        final String returnType;

        Marker(String kind, String owner, String name, String returnType) {
            this.kind = kind;
            this.owner = owner;
            this.name = name;
            this.returnType = returnType;
        }

        boolean matches(Instruction instruction) {
            if (!(instruction instanceof ReferenceInstruction)) return false;
            Object reference = ((ReferenceInstruction) instruction).getReference();
            if ("method".equals(kind) && reference instanceof MethodReference) {
                MethodReference method = (MethodReference) reference;
                return owner.equals(method.getDefiningClass())
                        && name.equals(method.getName())
                        && returnType.equals(method.getReturnType());
            }
            if ("field".equals(kind) && reference instanceof FieldReference) {
                FieldReference field = (FieldReference) reference;
                return owner.equals(field.getDefiningClass()) && name.equals(field.getName());
            }
            return false;
        }
    }
}
