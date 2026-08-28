package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Focused source contracts for the terminal guidance ordering barrier. */
public final class InstrumentProxyBarrierContractTest {
    @Test
    public void normalTerminalAndNextNormalUseOneOrderedDrain() throws IOException {
        String source = managerSource();
        int normal = source.indexOf("void sendGuidance(");
        int terminal = source.indexOf("void sendTerminalGuidanceClear(");
        int clear = source.indexOf("executeTerminalGuidanceClear(");
        int finish = source.indexOf("finishTerminalGuidanceClear(");
        int drain = source.indexOf("private void drainGuidance(");

        assertTrue(normal >= 0 && terminal > normal);
        assertTrue(clear > terminal && finish > clear && drain > finish);
        assertTrue(source.contains("if (guidanceBarrierActive)"));
        assertTrue(source.contains("deferredGuidance = next"));
        assertTrue(source.contains("pendingGuidance = deferred"));
    }

    @Test
    public void clearFailureReleasesBarrierAndStillAttemptsDeferredFrame() throws IOException {
        String source = managerSource();
        assertTrue(source.contains(
                "finishTerminalGuidanceClear(requestToken,\n                    Result.unavailable"));
        assertTrue(source.contains("guidanceBarrierActive = false"));
        assertTrue(source.contains("if (deferred != null && !enqueueCall"));
        assertTrue(source.contains("current.sendGuidance(\n"
                + "                        currentGeneration, 0, -1, \"\", "
                + "new int[0], new int[0])"));
    }

    @Test
    public void resetInvalidatesOldBarrierAndCompletesCallbacks() throws IOException {
        String source = managerSource();
        assertTrue(source.contains("resetTerminalCallbacks = new ArrayList"));
        assertTrue(source.contains("guidanceBarrierToken++"));
        assertTrue(source.contains("proxy reset: \" + safe(reason)"));
        assertTrue(source.contains("activeOperationTerminal && activeCallback != null"));
    }

    @Test
    public void duplicateTerminalCallsJoinCallbackWithoutAnotherClearTask() throws IOException {
        String source = managerSource();
        int terminal = source.indexOf("void sendTerminalGuidanceClear(");
        int callbackJoin = source.indexOf("terminalClearCallbacks.add(callback)", terminal);
        int enqueue = source.indexOf("executeTerminalGuidanceClear(requestToken, requestEpoch)",
                terminal);
        assertTrue(terminal >= 0 && callbackJoin > terminal && enqueue > callbackJoin);
        assertTrue(source.contains("if (guidanceBarrierActive)"));
        assertTrue(source.contains("if (callback != null) terminalClearCallbacks.add(callback)"));
        assertTrue(source.contains("deferredSuperseded = deferredGuidance"));
        assertTrue(source.contains("superseded by duplicate terminal clear"));
    }

    @Test
    public void duplicateTerminalDropsDeferredFrameButJoinsActiveBarrier() {
        assertTrue(InstrumentProxyManager.shouldDropDeferredGuidanceForDuplicateTerminalForTest(
                true, true));
        assertFalse(InstrumentProxyManager.shouldDropDeferredGuidanceForDuplicateTerminalForTest(
                true, false));
        assertFalse(InstrumentProxyManager.shouldDropDeferredGuidanceForDuplicateTerminalForTest(
                false, true));
    }

    private static String managerSource() throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve("app/src/main/java/com/bydhud/app/InstrumentProxyManager.java");
        if (!Files.isRegularFile(file)) {
            file = root.resolve("src/main/java/com/bydhud/app/InstrumentProxyManager.java");
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }
}
