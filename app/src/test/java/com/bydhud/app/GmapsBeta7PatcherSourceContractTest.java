package com.bydhud.app;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class GmapsBeta7PatcherSourceContractTest {
    @Test
    public void profile2630IsExactAndPrecedes2516() throws IOException {
        String source = sourcePath(
                "app/src/main/java/com/bydhud/gmapsdiag/patcher/GmapsDiagnosticPatcher.java");
        String hooks = between(source,
                "private static final List<Hook> HOOKS_2630",
                "private static final Profile PROFILE_2516");
        String profile2516 = between(source,
                "private static final Profile PROFILE_2516",
                "private static final Profile PROFILE_2630");
        String profile = between(source,
                "private static final Profile PROFILE_2630",
                "private static final List<Profile> PROFILES");
        String speed = between(source,
                "private static SpeedLocation speedStateAtV26(",
                "private static boolean isSpeedCapture(");
        String pipeline = sourcePath(
                "app/src/main/java/com/bydhud/app/NavigatorPatchPipeline.java");

        assertTrue(hooks.contains("new Hook(\"nav_payload\", \"Lbrjq;\", \"a\""));
        assertTrue(hooks.contains("Collections.singletonList(\"Ldaqn;\")"));
        assertTrue(hooks.contains("new Marker(\"method\", \"Lbqoa;\", \"b\", \"V\")"));
        assertTrue(hooks.contains("new Hook(\"session_start\", \"Lbqny;\", \"d\""));
        assertTrue(hooks.contains("Collections.singletonList(\"Lbrgx;\")"));
        assertTrue(hooks.contains("new Hook(\"session_output\", \"Lbqny;\", \"c\""));
        assertTrue(source.contains("new Hook(\"session_output\", \"Lbhiy;\", \"sO\""));
        assertTrue(hooks.contains("false,\n                    HookPlacement.PRE_BODY"));
        assertTrue(hooks.contains("new Hook(\"session_stop\", \"Lbqny;\", \"sa\""));
        assertTrue(hooks.contains("new Marker(\"method\", \"Lbqoa;\", \"a\", \"V\")"));
        assertTrue(hooks.contains("Collections.singletonList(\"Lbqmm;\")"));
        assertTrue(hooks.contains("new Hook(\"maneuver_model\", \"Lbqkz;\", \"b\""));
        assertTrue(hooks.contains("captureManeuverModelV26"));
        assertTrue(hooks.contains("HookPlacement.PRE_BODY"));
        assertTrue(hooks.contains("\"captureManeuverViewV26\""));
        assertTrue(hooks.contains("\"captureManeuverView\",\n                    new Marker"));
        assertTrue(hooks.contains("HookPlacement.POST_BODY_BEFORE_RETURN_VOID"));

        assertTrue(profile.contains("\"26.30\", HOOKS_2630, \"Lbimf;\", \"rI\""));
        assertTrue(profile.contains("Collections.singletonList(\"Lbqiy;\")"));
        assertTrue(profile.contains("\"Lbqny;\", \"e\", \"Lbrjq;\", \"Lbrjq;\", \"k\""));
        assertTrue(profile.contains("\"captureSpeedLimitStateV26\", SpeedLayout.V26"));
        assertTrue(profile.contains("\"Lbrrg;\", Arrays.asList(\"Lbrtz;\", \"I\")"));
        assertTrue(profile.contains("\"Lbrsa;\", Arrays.asList("));
        assertTrue(profile.contains("\"Landroid/media/MediaPlayer;\", \"Lbruh;\""));
        assertTrue(profile.contains("\"Ljava/util/concurrent/Executor;\", \"Lbrtq;\""));
        assertTrue(speed.contains("Math.max(0, gateIndex - 40)"));
        assertTrue(speed.contains(
                "isMethod(instructions.get(i), \"Lcfru;\", \"bs\""));
        assertTrue(speed.contains("Collections.singletonList(\"Lbqiy;\"), \"Lbqyt;\""));
        assertTrue(speed.contains("int insertIndex = gate.branchIndex + 1"));

        assertTrue(profile2516.contains("\"25.16\", HOOKS_2516, \"Lnmv;\", \"a\""));
        assertTrue(profile2516.contains("Collections.singletonList(\"Lbhdu;\")"));
        assertTrue(profile2516.contains(
                "\"Lbhiy;\", \"e\", \"Lbpix;\", \"Lbpix;\", \"i\""));
        assertTrue(profile2516.contains("\"captureSpeedLimitState\", SpeedLayout.V25"));
        assertTrue(profile2516.contains("\"Lbijo;\", Arrays.asList(\"Lbilw;\", \"I\")"));
        assertTrue(profile2516.contains("\"Lbikc;\", Arrays.asList("));
        assertTrue(source.contains(
                "PROFILES = Arrays.asList(PROFILE_2630, PROFILE_2516)"));
        assertTrue(pipeline.contains("private static String inspectGmapsPip("));
        assertTrue(pipeline.contains("PIP_INSPECTION_FAILED"));
    }

    @Test
    public void detectionIsFailClosedAndProductionContractsRemainPublic() throws IOException {
        String source = sourcePath(
                "app/src/main/java/com/bydhud/gmapsdiag/patcher/GmapsDiagnosticPatcher.java");
        String pipeline = sourcePath(
                "app/src/main/java/com/bydhud/app/NavigatorPatchPipeline.java");
        String detection = between(source,
                "private static Profile detectProfile(File apk)",
                "private static String verifyMarkers(");

        assertTrue(detection.contains("for (Profile profile : PROFILES)"));
        assertTrue(detection.contains("if (entry.getValue() != 1) continue"));
        assertTrue(detection.contains(
                "throw new IOException(\"ambiguous GMaps target profiles\")"));
        assertTrue(detection.contains(
                "throw new IOException(\"unsupported GMaps target profile: \" + matches)"));

        assertTrue(source.contains("public static String inspectClassification(File apk)"));
        assertTrue(source.contains("public static String inspectDirectClassification(File apk)"));
        assertTrue(source.contains("public static String inspectAudioClassification(File apk)"));
        assertTrue(source.contains("detectProfile(apk);"));
        String pipInspection = between(source,
                "public static String inspectPipClassification(File apk)",
                "public static void patchDirect(");
        assertTrue(pipInspection.indexOf("detectProfile(apk);")
                < pipInspection.indexOf("inspectPipManifest(apk)"));
        assertTrue(source.contains("public static void patchDirect("));
        assertTrue(source.contains("public static void patchNavigationAudio("));
        assertTrue(pipeline.contains("boolean hasCode = hasDexEntries(member.file);"));
        assertTrue(pipeline.contains("private static boolean hasDexEntries(File apk)"));
        assertTrue(source.contains("String directClassification()"));
        assertTrue(source.contains("String audioClassification()"));
        assertTrue(source.contains("return \"PATCHABLE_STOCK\""));
        assertTrue(source.contains("return \"MESSENGER_BRIDGE_POC\""));
        assertTrue(source.contains("return \"MESSENGER_BRIDGE_UPGRADEABLE\""));
        assertTrue(source.contains("CAP_ROUTE_GENERATION"));
        assertTrue(source.contains("rewriteExistingBridgeDex"));
        assertTrue(source.contains("legacy maneuver hook count is not exactly one"));
        assertTrue(source.contains("post-body normal return-void is missing or ambiguous"));
        assertTrue(source.contains("loggerIndex != returnIndex - 1"));
        assertTrue(source.contains("rewriteGmsCoreDialogDex"));
        assertTrue(source.contains("inspectGmsCoreClassification(File apk)"));
        assertTrue(source.contains("patchGmsCore("));
        assertTrue(source.contains("verifyGmsCore(File apk)"));
        assertTrue(source.contains("ALREADY_SUPPRESSED"));
        assertTrue(source.contains("UI_SUPPRESSED") || source.contains("ACTIVE"));
        assertTrue(source.contains("return \"MESSENGER_BRIDGE_NAV_AUDIO\""));
        assertTrue(source.contains("return \"NAVIGATION_AUDIO\""));
        assertTrue(source.contains("report.put(\"targetProfile\", inspection.profile.id)"));
        assertTrue(source.contains("components.put(\"direct\", direct)"));
        assertTrue(source.contains("report.put(\"releaseReady\", directReady && qolReady)"));
        assertTrue(source.contains("boolean directReady = \"MESSENGER_BRIDGE_POC\".equals(direct)"));
        assertTrue(source.contains("GmapsPipManifestPatcher.PATCHED.equals(pip)"));
    }

    @Test
    public void audioPatchSupportsFocusAndPlaybackInDifferentDexFiles() throws IOException {
        String source = sourcePath(
                "app/src/main/java/com/bydhud/gmapsdiag/patcher/GmapsDiagnosticPatcher.java");
        String patchAudio = between(source,
                "private static void patchAudio(",
                "private static void verifyAudio(");

        assertTrue(patchAudio.contains("dexEntries.add(before.audioDexEntry)"));
        assertTrue(patchAudio.contains(
                "if (!before.playbackAudioDexEntry.equals(before.audioDexEntry))"));
        assertTrue(patchAudio.contains("dexEntries.add(before.playbackAudioDexEntry)"));
        assertTrue(patchAudio.contains(
                "int expectedRewrites = before.audioDexEntry.equals(dexEntry) ? 1 : 0"));
        assertTrue(patchAudio.contains(
                "if (before.playbackAudioDexEntry.equals(dexEntry)) expectedRewrites++"));
        assertTrue(patchAudio.contains(
                "rewriteNavigationAudioDex("));
        assertTrue(patchAudio.contains(
                "report.put(\"modifiedDexEntries\", new ArrayList<>(replacements.keySet()))"));
    }

    @Test
    public void reviewFixesKeepPlacementUpgradeGateOrderAndAtomicOutputs() throws IOException {
        String source = sourcePath(
                "app/src/main/java/com/bydhud/gmapsdiag/patcher/GmapsDiagnosticPatcher.java");
        assertTrue(source.contains("hook.placement\n                                    == HookPlacement.POST_BODY_BEFORE_RETURN_VOID"));
        assertTrue(source.contains("private static boolean placementSatisfied(HookResult result, Hook hook)"));
        assertTrue(source.contains("legacy.targetCount == 1"));
        assertTrue(source.contains("legacy.hookCallCount + legacy.legacyHookCallCount == 1"));
        assertTrue(source.contains("report.put(\"inputLegacyManeuverView\""));
        assertTrue(source.contains("return \"25.16\".equals(profile.id) ? inject(stripped, targetHook) : stripped"));
        assertTrue(source.contains("location = findStockGate(mutable.getInstructions(), profile)"));
        assertTrue(source.contains("producer gate moved after frame capture"));
        assertTrue(source.contains("hasCanonicalFrameGate(mutable.getInstructions(), profile)"));
        assertTrue(source.contains("beginFrameCaptureV26"));
        assertTrue(source.contains("Inspection after = inspect(temporary)"));
        assertTrue(source.contains("GmsCoreInspection after = inspectGmsCoreOnly(temporary"));
        assertTrue(source.contains("deleteTree(temporary)"));

        String build = sourcePath("../gmaps-direct/build_gmaps_prod.ps1");
        assertTrue(build.contains("\"-PinputApk=$signingInput\" \"-PoutputApk=$audioUnsigned\""));
        assertTrue(build.contains("verifyGmapsGmsCore"));
        assertTrue(build.contains("$gmsCoreInputState -eq \"ACTIVE\""));
        assertTrue(build.contains("GmsCore dialog is already suppressed."));
        assertTrue(build.contains("$components.direct -ne \"MESSENGER_BRIDGE_POC\""));
        assertTrue(build.contains("PICTURE_IN_PICTURE_DISABLED"));
        assertTrue(build.contains("PSObject.Properties[\"maneuver_model\"]"));
        String beta = sourcePath("../gmaps-direct/build_gmaps_2630_prod.ps1");
        assertTrue(beta.contains("pip-off-beta7-r4"));
        assertTrue(beta.contains("background model producer"));
        assertTrue(beta.contains("$builtReport.releaseReady -ne $true"));
        assertTrue(beta.contains("MapsActivity supportsPictureInPicture=false"));
    }

    @Test
    public void loggerContainsExact2630SpeedLayout() throws IOException {
        String source = sourcePath(
                "app/src/main/java/com/bydhud/gmapsdiag/NavInfoLogger.java");
        String capture = between(source,
                "public static void captureSpeedLimitStateV26(",
                "private static void installClient(");
        String unit = between(source,
                "private static String speedUnitV26(",
                "private static void unlinkCurrentClientLocked(");

        assertTrue(capture.contains("invokeObject(aggregateState, \"d\")"));
        assertTrue(capture.contains("requiredField(state, \"b\")"));
        assertTrue(capture.contains("booleanField(state, \"p\")"));
        assertTrue(capture.contains("invokeBoolean(route, \"ay\")"));
        assertTrue(capture.contains("requiredField(state, \"c\")"));
        assertTrue(capture.contains("requiredField(step, \"U\")"));
        assertTrue(capture.contains("intField(step, \"l\") - intField(state, \"h\")"));
        assertTrue(capture.contains("requiredField(speedStep, \"N\")"));
        assertTrue(capture.contains("requiredField(route, \"Z\")"));
        assertTrue(capture.contains("speedUnitV26(intField(change, \"d\"), countries)"));
        assertTrue(capture.contains("SPEED_LIMIT_FAILED|profile=26.30"));
        assertTrue(source.contains(
                "private static Object invokeObject(Object target, String name)"));

        String loggerSource = sourcePath(
                "app/src/main/java/com/bydhud/gmapsdiag/NavInfoLogger.java");
        assertTrue(loggerSource.contains(
                "PROCESS_EPOCH = SystemClock.elapsedRealtimeNanos()"));
        assertTrue(loggerSource.contains(
                "data.putLong(\"producerEpoch\", PROCESS_EPOCH)"));
        assertTrue(loggerSource.contains("CAP_ROUTE_GENERATION"));
        assertTrue(loggerSource.contains("data.putLong(\"routeGeneration\", epoch)"));
        assertTrue(loggerSource.contains("data.putLong(\"stateEpoch\", epoch)"));
        assertTrue(!loggerSource.contains(
                "data.putLong(\"producerEpoch\", STATE_EPOCH.get())"));

        assertTrue(unit.contains("toUpperCase(java.util.Locale.US)"));
        assertTrue(unit.contains("\"US\".equals(country) || \"MM\".equals(country)"));
        assertTrue(unit.contains("|| \"LR\".equals(country) || \"GB\".equals(country)"));
    }

    @Test
    public void loggerUsesCanonical2630ManeuverExtractor() throws IOException {
        String source = sourcePath(
                "app/src/main/java/com/bydhud/gmapsdiag/NavInfoLogger.java");
        String capture = between(source,
                "public static void captureManeuverViewV26(",
                "private static void captureManeuverViewValue(");
        assertTrue(capture.contains("readField(optionalValue, \"a\")"));
        assertTrue(capture.contains("readField(maneuverValue, \"a\")"));
        assertTrue(source.contains("((Enum<?>) value).name()"));
        String patcher = sourcePath(
                "app/src/main/java/com/bydhud/gmapsdiag/patcher/GmapsDiagnosticPatcher.java");
        assertTrue(patcher.contains(
                "Optional.a -> Lbqmm.a -> enum.name"));
        assertTrue(patcher.contains("countLegacyLoggerCalls"));
        assertTrue(patcher.contains("legacy maneuver hook count is not exactly one"));
        assertTrue(patcher.contains("value.put(\"extractor\""));
    }

    @Test
    public void backgroundModelCaptureIsOrderedBeforeFrame() throws IOException {
        String logger = sourcePath(
                "app/src/main/java/com/bydhud/gmapsdiag/NavInfoLogger.java");
        assertTrue(logger.contains("public static void beginFrameCaptureV26()"));
        assertTrue(logger.contains("public static void captureManeuverModelV26(Object value)"));
        assertTrue(logger.contains("Lbqmu.b[0] -> bqmn.c -> bqmn.b[index] -> bqmq.i"));
        assertTrue(logger.contains("Class.forName(\"bqyl\""));
        assertTrue(logger.contains("context.getResources(), model.value, -1"));
        assertTrue(logger.contains("model.renderGeneration, event.epoch, event.sequence)"));
        assertTrue(logger.contains("private static void requeueForRetry(Event event)"));
        assertTrue(logger.contains("lastManeuverSourceSequence < 0L"));
        assertTrue(logger.contains("if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle()"));
        assertTrue(logger.contains("synchronized (STATE_LOCK) {\n"
                + "            if (!isCurrentEvent(event)) return;\n"
                + "            Messenger target = client;"));
        assertTrue(logger.contains("LAST_FRAME_FOR_REPLAY.set(event);\n"
                + "            PENDING_FRAME_FOR_REPLAY.compareAndSet(event, null);"));
        assertTrue(logger.indexOf("renderModelBitmap(event)")
                < logger.indexOf("sendMatchingBitmap(event, target, bitmapRequired)"));
        assertTrue(logger.indexOf("sendMatchingBitmap(event, target, bitmapRequired)")
                < logger.indexOf("sendFrameEvent(event, target)"));
        int replayStart = logger.indexOf("start.putString(\"event\", \"replay-start\")");
        int replayReadyGuard = logger.indexOf(
                "if (replayEvent == null || replayEvent.epoch != epoch) return true;");
        assertTrue(replayStart >= 0 && replayReadyGuard > replayStart);
    }

    @Test
    public void legacyViewBitmapUsesOneClientSnapshot() throws IOException {
        String logger = sourcePath(
                "app/src/main/java/com/bydhud/gmapsdiag/NavInfoLogger.java");
        String capture = between(logger,
                "private static void captureRenderedManeuver(",
                "private static void rememberBitmap(");

        assertTrue(capture.indexOf("rememberBitmap(maneuver, viewId")
                < capture.indexOf("synchronized (STATE_LOCK)"));
        assertTrue(capture.indexOf("synchronized (STATE_LOCK)")
                < capture.indexOf("sendTo(client, MESSAGE_MANEUVER_BITMAP, data)"));
        assertTrue(logger.contains(
                "boolean bitmapRequired = modelReady && event.model != null;"));
        assertTrue(logger.contains(
                "sendMatchingBitmap(event, target, bitmapRequired)"));
    }

    @Test
    public void loggerRegistrationFailsClosedOnMalformedExtras() throws IOException {
        String source = sourcePath(
                "app/src/main/java/com/bydhud/gmapsdiag/NavInfoLogger.java");
        String registration = between(source,
                "public static void registerClient(",
                "public static boolean noClient()");

        assertTrue(registration.contains("catch (RuntimeException error)"));
        assertTrue(registration.contains(
                "CLIENT_REJECTED|reason=malformed_extras|type="));
        assertTrue(registration.contains("if (!isTrustedSender(identity))"));
        assertTrue(registration.contains("EXTRA_CHANNEL_ID"));
        assertTrue(registration.contains(
                "installClient(candidate, channelId == null ? \"\" : channelId.trim())"));
    }

    private static String sourcePath(String relativePath) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = null;
        for (Path base = root; base != null && file == null; base = base.getParent()) {
            Path candidate = base.resolve(relativePath).normalize();
            if (Files.isRegularFile(candidate)) file = candidate;
            if (file == null && relativePath.startsWith("app/")) {
                candidate = base.resolve(relativePath.substring("app/".length())).normalize();
                if (Files.isRegularFile(candidate)) file = candidate;
            }
        }
        if (file == null) throw new IOException("Source file not found: " + relativePath);
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue("missing start marker " + start, from >= 0);
        assertTrue("missing end marker " + end, to > from);
        return source.substring(from, to);
    }
}
