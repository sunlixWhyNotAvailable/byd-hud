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
