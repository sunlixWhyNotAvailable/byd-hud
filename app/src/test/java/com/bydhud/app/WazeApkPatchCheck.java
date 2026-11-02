package com.bydhud.app;

import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Explicit real-APK check; not a synthetic fixture or an Android runtime test.
 * Args: version, stock APK, empty output directory; or version, verify, patched APK.
 * Map check: version, APK, real capture DEX, empty output directory, expected input state.
 */
public final class WazeApkPatchCheck {
    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 4 && args[0].equals("export-only")) {
            Path output = Path.of(args[3]); Files.createDirectory(output); int targets = 0;
            try (ZipFile zip = new ZipFile(args[2])) {
                for (ZipEntry entry : Collections.list(zip.entries())) if (entry.getName().matches("classes\\d*\\.dex")) {
                    byte[] dex = zip.getInputStream(entry).readAllBytes();
                    var before = WazePatchEngine.inspectLifecycle(dex, args[1]);
                    if (before.geometryTargetCount == 0) continue;
                    Path rewritten = output.resolve(entry.getName());
                    WazePatchEngine.patchLifecycle(dex, rewritten.toFile(), args[1], false, true);
                    var after = WazePatchEngine.inspectLifecycle(Files.readAllBytes(rewritten), args[1]);
                    require(after.geometryHookCount == before.geometryTargetCount, "export hooks incomplete");
                    require(after.applicationHookCount == before.applicationHookCount
                            && after.routeHookCount == before.routeHookCount
                            && after.speedHookCount == before.speedHookCount
                            && after.clusterEtaPatched() == before.clusterEtaPatched(), "export changed stability");
                    targets += before.geometryTargetCount;
                }
            }
            require(targets == 8, "incomplete actual export targets");
            System.out.println("Actual stock export-only upgrade passed: eight hooks; stability unchanged");
            return;
        }
        if (args.length == 4 && args[0].equals("lifecycle-only")) {
            Path output = Path.of(args[3]);Files.createDirectory(output);int targets = 0;
            try (ZipFile zip = new ZipFile(args[2])) {
                for (ZipEntry e : Collections.list(zip.entries())) if (e.getName().matches("classes\\d*\\.dex")) {
                    byte[] dex = zip.getInputStream(e).readAllBytes();
                    var before = WazePatchEngine.inspectLifecycle(dex, args[1]);
                    int count = before.applicationTargetCount + before.routeTargetCount + before.speedTargetCount + before.clusterEtaTargetCount;
                    if (count == 0 && before.geometryTargetCount == 0) continue;
                    Path rewritten = output.resolve(e.getName());
                    WazePatchEngine.patchLifecycle(dex, rewritten.toFile(), args[1], false);
                    var after = WazePatchEngine.inspectLifecycle(Files.readAllBytes(rewritten), args[1]);
                    require(after.geometryTargetCount == before.geometryTargetCount && after.geometryHookCount == before.geometryHookCount
                            && after.geometryPayloadClassCount == before.geometryPayloadClassCount, "stability changed export");
                    require(after.applicationTargetCount == 0 || after.applicationHookCount == 1, "application hook missing");
                    require(after.routeTargetCount == 0 || after.routeHookCount == 1, "route hook missing");
                    require(after.speedTargetCount == 0 || after.speedHookCount == 1, "speed hook missing");
                    require(after.clusterEtaTargetCount == 0 || after.clusterEtaPatched(), "ETA hook missing");
                    targets += count;
                }
            }
            require(targets == 4, "incomplete actual lifecycle targets");
            System.out.println("Actual stock stability upgrade passed; export hooks and payload unchanged");
            return;
        }
        if (args.length == 6 && args[0].equals("preservation")) {
            verifyDexPreservation(NavigatorHudProfile.load(args[1], args[2]), args[3], Path.of(args[4]), Path.of(args[5]));
            return;
        }
        if (args.length == 4 && args[0].equals("components")) {
            if (args[1].equals("waze")) {
                main(new String[]{"5.25.0.1", "verify", args[2]});
                main(new String[]{"5.25.0.1", "verify", args[3]});
                var p = NavigatorHudProfile.load("waze", "5.25.0.1");
                require(p.inspect(Collections.singletonList(Path.of(args[2]).toFile()), "audio").current
                        == p.inspect(Collections.singletonList(Path.of(args[3]).toFile()), "audio").current, "map changed audio state");
            } else {
                var before = com.bydhud.gmapsdiag.patcher.GmapsDiagnosticPatcher.inspectComponents(Collections.singletonList(Path.of(args[2]).toFile())).get(0);
                var after = com.bydhud.gmapsdiag.patcher.GmapsDiagnosticPatcher.inspectComponents(Collections.singletonList(Path.of(args[3]).toFile())).get(0);
                require(before.direct.equals(after.direct) && before.gmsCore.equals(after.gmsCore)
                        && before.audio.equals(after.audio) && before.pip.equals(after.pip), "map changed navigation components");
                System.out.println("GMaps navigation components preserved: " + after.direct + ", " + after.gmsCore + ", " + after.audio + ", " + after.pip);
            }
            return;
        }
        if (args.length == 6 && args[0].equals("prepare")) {
            prepareDirect(args[1], args[2], Path.of(args[3]), Path.of(args[4]), Path.of(args[5]));
            return;
        }
        if (args.length == 7 && args[0].equals("hud")) {
            checkHud(args[1], args[2], args[3], Path.of(args[4]), Path.of(args[5]), args[6]);
            return;
        }
        if (args.length == 5) {
            String version = args[0];
            var sources = Collections.singletonList(Path.of(args[1]).toFile());
            var before = NavigatorMapPatcher.inspect(sources, "waze", version);
            require(before.state.equals(args[4]), "map input: " + before.state + " " + before.reason);
            var patched = NavigatorMapPatcher.patch(sources, Path.of(args[3]).toFile(),
                    Path.of(args[2]).toFile(), "waze", version);
            require(NavigatorMapPatcher.PATCHED.equals(patched.state),
                    "map output: " + patched.state + " " + patched.reason);
            var repeated = NavigatorMapPatcher.inspect(Collections.singletonList(
                    Path.of(args[3], sources.get(0).getName()).toFile()), "waze", version);
            require(NavigatorMapPatcher.PATCHED.equals(repeated.state), repeated.reason);
            if (NavigatorMapPatcher.PATCHED.equals(before.state)) {
                require(Arrays.equals(Files.readAllBytes(sources.get(0).toPath()),
                        Files.readAllBytes(Path.of(args[3], sources.get(0).getName()))),
                        "supported capture APK was rewritten");
            }
            require(NavigatorMapPatcher.UNSUPPORTED.equals(
                    NavigatorMapPatcher.inspect(sources, "waze", "5.26.0.1").state),
                    "unknown version accepted");
            if (NavigatorMapPatcher.PATCHABLE.equals(before.state)) {
                verifyMissingCompanions(sources, Path.of(args[2]), Path.of(args[3]), version);
            }
            System.out.println("Map patch and reopen verified: " + version);
            return;
        }
        if (args.length != 3) throw new IllegalArgumentException("version, APK, output directory (or version, verify, APK)");
        String version = args[0];
        args = Arrays.copyOfRange(args, 1, args.length);
        boolean verify = args[0].equals("verify");
        Path input = Path.of(args[verify ? 1 : 0]);
        Path output = verify ? null : Path.of(args[1]);
        if (output != null) Files.createDirectory(output);
        int hosts=0, lanes=0, alerts=0, once=0, app=0, route=0, speed=0, eta=0, bridge=0;
        try (ZipFile apk = new ZipFile(input.toFile())) {
            for (ZipEntry entry : Collections.list(apk.entries())) {
                if (!entry.getName().matches("classes\\d*\\.dex")) continue;
                byte[] dex = apk.getInputStream(entry).readAllBytes();
                Path target = verify ? null : output.resolve(entry.getName());
                boolean changed = false;
                var host = WazePatchEngine.inspectWaze(dex,version);
                if (host.targetCount != 0) {
                    hosts += host.targetCount;
                    require((verify ? WazePatchEngine.ALREADY_PATCHED : WazePatchEngine.PATCHABLE_STOCK)
                            .equals(host.classification), "host: " + host.reason);
                    if (!verify) {
                        WazePatchEngine.patchWazeAllowlist(dex,target.toFile(),version);
                        dex=Files.readAllBytes(target); changed=true;
                    }
                }
                var lane = WazePatchEngine.inspectLane(dex,version);
                if (lane.frameClassCount != 0) {
                    lanes += lane.frameClassCount;
                    require(verify ? lane.patchedTargets() : lane.stockTargets(), "lanes: " + lane.summary());
                    if (!verify) {
                        WazePatchEngine.patchLanes(dex,target.toFile(),version);
                        dex=Files.readAllBytes(target); changed=true;
                    }
                }
                var alert = WazePatchEngine.inspectAlertHook(dex,version);
                alerts+=alert.classCount; once+=alert.alertOnceClassCount;
                if (alert.classCount+alert.alertOnceClassCount != 0) {
                    require(alert.classCount==0 || (verify ? alert.patchedTargets() : alert.stockTargets()),
                            "alerts: " + alert.summary());
                    require(alert.alertOnceClassCount==0 || (verify ? alert.alertOncePatchedTargets() : alert.alertOnceStockTargets()),
                            "alert once: " + alert.summary());
                    if (!verify) {
                        WazePatchEngine.patchAlertComponents(dex,target.toFile(),alert.classCount!=0,alert.alertOnceClassCount!=0,version);
                        dex=Files.readAllBytes(target); changed=true;
                    }
                }
                var life = WazePatchEngine.inspectLifecycle(dex,version);
                app+=life.applicationTargetCount; route+=life.routeTargetCount;
                speed+=life.speedTargetCount; eta+=life.clusterEtaTargetCount; bridge+=life.bridgeClassCount;
                if (life.applicationTargetCount+life.routeTargetCount+life.speedTargetCount+life.clusterEtaTargetCount != 0) {
                    if (!verify) {
                        WazePatchEngine.patchLifecycle(dex,target.toFile(),version);
                        dex=Files.readAllBytes(target); changed=true;
                        life=WazePatchEngine.inspectLifecycle(dex,version);
                    }
                    require(life.applicationTargetCount==0 || life.applicationHookCount==1, "application init");
                    require(life.routeTargetCount==0 || life.routeHookCount==1 && life.routeGuard.equals("ok"), "route: "+life.routeGuard);
                    require(life.speedTargetCount==0 || life.speedHookCount==1 && life.speedGuard.equals("ok"), "speed: "+life.speedGuard);
                    require(life.clusterEtaTargetCount==0 || life.clusterEtaPatched(), "ETA: "+life.clusterEtaGuard);
                }
                if (changed) System.out.println("Direct patched and verified: " + entry.getName());
            }
        }
        require(hosts==1 && lanes==1 && alerts==1 && once==1 && app==1 && route==1 && speed==1 && eta==1,
                "Incomplete Direct set: "+Arrays.asList(hosts,lanes,alerts,once,app,route,speed,eta));
        require(bridge==(verify ? 1 : 0), "Direct payload class count="+bridge);
        System.out.println("Direct: host, lifecycle, speed, ETA, lanes, alerts and deduplication verified.");
    }

    private static void prepareDirect(String family, String version, Path input, Path hudApk, Path work) throws Exception {
        Files.createDirectory(work);
        List<org.jf.dexlib2.iface.ClassDef> payload = new ArrayList<>();
        String[] roots = family.equals("waze") ? new String[]{"Lcom/waze/bydhud/RouteStateBridgeV2", "Lcom/bydhud/navexport/WazeRouteExport",
                "Lcom/bydhud/navexport/RouteExport", "Lcom/bydhud/navexport/RouteData", "Lcom/bydhud/navexport/WazeManeuverRecovery"}
                : new String[]{"Lcom/bydhud/gmapsdiag/NavInfoLogger", "Lcom/bydhud/navexport/GoogleRouteExport",
                "Lcom/bydhud/navexport/RouteExport", "Lcom/bydhud/navexport/RouteData"};
        try (ZipFile zip = new ZipFile(hudApk.toFile())) {
            for (ZipEntry e : Collections.list(zip.entries())) if (e.getName().matches("classes\\d*\\.dex"))
                for (var c : new org.jf.dexlib2.dexbacked.DexBackedDexFile(org.jf.dexlib2.Opcodes.forApi(35), zip.getInputStream(e).readAllBytes()).getClasses())
                    for (String root : roots) if (c.getType().startsWith(root)) { payload.add(c); break; }
        }
        require(!payload.isEmpty(), "real HUD Direct payload absent");
        Path bridge = work.resolve("bridge.dex");Files.write(bridge, NavigatorHudProfile.dex(payload));
        Path candidate = work.resolve("direct.apk");
        if (family.equals("gmaps")) {
            com.bydhud.gmapsdiag.patcher.GmapsDiagnosticPatcher.patchDirect(input.toFile(), candidate.toFile(), bridge.toFile(), work.resolve("direct-report.json").toFile());
        } else {
            Path dexDir = work.resolve("direct-dex");main(new String[]{version, input.toString(), dexDir.toString()});
            int maximum = 1;
            try (ZipFile source = new ZipFile(input.toFile()); ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(candidate))) {
                for (ZipEntry e : Collections.list(source.entries())) {
                    String name = e.getName(); if (name.matches("(?i)META-INF/(MANIFEST\\.MF|[^/]+\\.(SF|RSA|DSA|EC))")) continue;
                    if (name.matches("classes\\d*\\.dex")) { String digits = name.substring(7,name.length()-4); maximum = Math.max(maximum,digits.isEmpty()?1:Integer.parseInt(digits)); }
                    byte[] bytes = Files.exists(dexDir.resolve(name)) && !Files.isDirectory(dexDir.resolve(name))
                            ? Files.readAllBytes(dexDir.resolve(name)) : source.getInputStream(e).readAllBytes();
                    out.putNextEntry(new ZipEntry(name));out.write(bytes);out.closeEntry();
                }
                out.putNextEntry(new ZipEntry("classes"+(maximum+1)+".dex"));out.write(Files.readAllBytes(bridge));out.closeEntry();
            }
        }
        System.out.println("Prepared real Direct engine output: " + candidate);
    }

    /** Uses actual navigator APKs and the exact packaged profile; no Android substitute. */
    private static void checkHud(String family, String version, String component,
            Path input, Path output, String expected) throws Exception {
        NavigatorHudProfile profile = NavigatorHudProfile.load(family, version);
        var sources = Collections.singletonList(input.toFile());
        var before = profile.inspect(sources, component);
        require((before.current ? "PATCHED" : "PATCHABLE").equals(expected), "unexpected input state");
        profile.patch(sources, output.toFile(), component);
        Path candidate = output.resolve(input.getFileName());
        var outputs = Collections.singletonList(candidate.toFile());
        require(profile.inspect(outputs, component).current, "candidate is not current");
        if (before.current) require(Arrays.equals(Files.readAllBytes(input), Files.readAllBytes(candidate)), "current APK was rewritten");
        Path repeated = output.resolve("repeated");
        profile.patch(outputs, repeated.toFile(), component);
        require(Arrays.equals(Files.readAllBytes(candidate), Files.readAllBytes(repeated.resolve(input.getFileName()))), "second patch was not byte-identical");
        try (ZipFile source = new ZipFile(input.toFile()); ZipFile after = new ZipFile(candidate.toFile())) {
            for (ZipEntry e : Collections.list(source.entries())) {
                String name = e.getName();
                if (name.matches("classes\\d*\\.dex") || name.matches("(?i)META-INF/(MANIFEST\\.MF|[^/]+\\.(SF|RSA|DSA|EC))")
                        || component.equals("map") && profile.resources.containsKey(name)) continue;
                require(after.getEntry(name) != null && Arrays.equals(source.getInputStream(e).readAllBytes(), after.getInputStream(after.getEntry(name)).readAllBytes()), "unrelated APK member changed: " + name);
            }
        }
        System.out.println("HUD " + family + " " + component + " " + expected + " -> PATCHED; repeated output byte-identical; unrelated non-DEX members preserved");
    }

    private static Map<String, org.jf.dexlib2.iface.ClassDef> apkClasses(Path apk) throws Exception {
        Map<String, org.jf.dexlib2.iface.ClassDef> classes = new TreeMap<>();
        try (ZipFile zip = new ZipFile(apk.toFile())) {
            for (ZipEntry e : Collections.list(zip.entries())) if (e.getName().matches("classes\\d*\\.dex"))
                for (var c : new org.jf.dexlib2.dexbacked.DexBackedDexFile(org.jf.dexlib2.Opcodes.forApi(35), zip.getInputStream(e).readAllBytes()).getClasses())
                    require(classes.put(c.getType(), c) == null, "duplicate output class " + c.getType());
        }
        return classes;
    }

    private static byte[] unselected(NavigatorHudProfile profile, String component, org.jf.dexlib2.iface.ClassDef cls) throws Exception {
        List<org.jf.dexlib2.iface.Method> methods = new ArrayList<>();
        for (var method : cls.getMethods()) {
            var rule = profile.methods.get(NavigatorHudProfile.key(method));
            if (rule == null || !component.equals(rule.component)) methods.add(method);
        }
        return NavigatorHudProfile.dex(Collections.singletonList(new org.jf.dexlib2.immutable.ImmutableClassDef(
                cls.getType(), cls.getAccessFlags(), cls.getSuperclass(), cls.getInterfaces(), cls.getSourceFile(), cls.getAnnotations(), cls.getFields(), methods)));
    }

    private static void verifyDexPreservation(NavigatorHudProfile profile, String component, Path before, Path after) throws Exception {
        var original = apkClasses(before); var patched = apkClasses(after); int checked = 0;
        for (var cls : original.values()) {
            if (component.equals("map") && NavigatorHudProfile.owned(cls.getType())) continue;
            var result = patched.remove(cls.getType());
            require(result != null, "unrelated class removed " + cls.getType());
            require(Arrays.equals(unselected(profile, component, cls), unselected(profile, component, result)),
                    "unselected methods, fields or class metadata changed " + cls.getType());
            checked++;
        }
        for (String added : patched.keySet()) require(component.equals("map") && NavigatorHudProfile.owned(added), "unexpected new class " + added);
        System.out.println("DEX preservation: " + checked + " non-HUD classes; all unselected methods, fields and class metadata unchanged");
    }

    private static void verifyMissingCompanions(List<java.io.File> sources, Path capture,
            Path output, String version) throws Exception {
        var opcodes = org.jf.dexlib2.Opcodes.forApi(35);
        var dex = new org.jf.dexlib2.dexbacked.DexBackedDexFile(opcodes, Files.readAllBytes(capture));
        for (String name : Arrays.asList("LifecycleJournalSpool-IA", "WazeSurfaceCopy-IA")) {
            String missing = "Lcom/bydhud/mapcapture/" + name + ";";
            List<org.jf.dexlib2.iface.ClassDef> retained = new ArrayList<>();
            for (var definition : dex.getClasses()) {
                if (!missing.equals(definition.getType())) retained.add(definition);
            }
            if (retained.size() == dex.getClasses().size()) continue;
            Path incomplete = output.resolve("missing-" + name + ".dex");
            var pool = new org.jf.dexlib2.writer.pool.DexPool(opcodes);
            for (var definition : retained) pool.internClass(definition);
            var data = new org.jf.dexlib2.writer.io.FileDataStore(incomplete.toFile());
            try {
                pool.writeTo(data);
            } finally {
                data.close();
            }
            Path rejectedOutput = output.resolve("rejected-" + name);
            var rejected = NavigatorMapPatcher.patch(sources, rejectedOutput.toFile(),
                    incomplete.toFile(), "waze", version);
            require(NavigatorMapPatcher.UNSUPPORTED.equals(rejected.state)
                    && rejected.reason.contains(missing), "missing companion accepted: " + rejected.reason);
            require(!Files.exists(rejectedOutput), "incomplete capture produced patch output");
            System.out.println("Missing real-payload companion rejected: " + name);
        }
    }
}
