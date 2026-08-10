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

        assertTrue(hooks.contains("new Hook(\"nav_payload\", \"Lbrjq;\", \"a\""));
        assertTrue(hooks.contains("Collections.singletonList(\"Ldaqn;\")"));
        assertTrue(hooks.contains("new Marker(\"method\", \"Lbqoa;\", \"b\", \"V\")"));
        assertTrue(hooks.contains("new Hook(\"session_start\", \"Lbqny;\", \"d\""));
        assertTrue(hooks.contains("Collections.singletonList(\"Lbrgx;\")"));
        assertTrue(hooks.contains("new Hook(\"session_output\", \"Lbqny;\", \"c\""));
        assertTrue(hooks.contains("new Hook(\"session_stop\", \"Lbqny;\", \"sa\""));
        assertTrue(hooks.contains("new Marker(\"method\", \"Lbqoa;\", \"a\", \"V\")"));
        assertTrue(hooks.contains("Collections.singletonList(\"Lbqmm;\")"));

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
    }

    @Test
    public void detectionIsFailClosedAndProductionContractsRemainPublic() throws IOException {
        String source = sourcePath(
                "app/src/main/java/com/bydhud/gmapsdiag/patcher/GmapsDiagnosticPatcher.java");
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
        assertTrue(source.contains("public static void patchDirect("));
        assertTrue(source.contains("public static void patchNavigationAudio("));
        assertTrue(source.contains("String directClassification()"));
        assertTrue(source.contains("String audioClassification()"));
        assertTrue(source.contains("return \"PATCHABLE_STOCK\""));
        assertTrue(source.contains("return \"MESSENGER_BRIDGE_POC\""));
        assertTrue(source.contains("return \"MESSENGER_BRIDGE_NAV_AUDIO\""));
        assertTrue(source.contains("return \"NAVIGATION_AUDIO\""));
        assertTrue(source.contains("report.put(\"targetProfile\", inspection.profile.id)"));
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

        assertTrue(unit.contains("toUpperCase(java.util.Locale.US)"));
        assertTrue(unit.contains("\"US\".equals(country) || \"MM\".equals(country)"));
        assertTrue(unit.contains("|| \"LR\".equals(country) || \"GB\".equals(country)"));
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
        assertTrue(registration.contains("installClient(candidate)"));
    }

    private static String sourcePath(String relativePath) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve(relativePath);
        if (!Files.isRegularFile(file) && relativePath.startsWith("app/")) {
            file = root.resolve(relativePath.substring("app/".length()));
        }
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
