package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class LogcatRecorderConcurrencyTest {
    @Test
    public void StopLeavesFinalizationAndFailureOwnershipOnTheWorker() throws IOException {
        String source = source();
        String start = section(source, "static Result start(", "static Result restartAfterRebase(");
        String stop = section(source, "static Result stop(", "static void stopAsync(");
        assertTrue(start.contains("if (session.finalized)"));
        assertTrue(start.contains("session.stopRequested || activeSession != session"));
        assertTrue(stop.contains("finalizingSession = session"));
        assertTrue(stop.contains("ensureFinishLocked(session)"));
        assertTrue(stop.contains("Result.pending"));
        assertFalse(stop.contains("future.cancel(true)"));
        assertFalse(stop.contains("fail(session"));
        assertTrue(source.contains("if (session.finalized || session.failed) return;"));
    }

    @Test
    public void AsyncStopPostsCompletionAndSessionQueryCoversFinalizingState()
            throws IOException {
        String source = source();
        assertTrue(source.contains("static void stopAsync(Context context, Runnable completion)"));
        assertTrue(source.contains("postCompletions(completions)"));
        assertTrue(source.contains("static synchronized boolean hasSessionForDay(String day)"));
        assertTrue(source.contains("activeSession != null || finalizingSession != null"));
    }

    @Test
    public void StopPollsBeforeFinalizingAndFailureStillWritesManifest() throws IOException {
        String source = source();
        String finish = section(source, "private static void finish(",
                "private static void pollSafely(");
        assertTrue(finish.indexOf("poll(session)") >= 0);
        assertTrue(finish.indexOf("poll(session)")
                < finish.indexOf("captureSnapshot(session, \"after\""));
        assertTrue(finish.indexOf("captureSnapshot(session, \"after\"")
                < finish.indexOf("finalizeLog(session)"));
        String failure = section(source, "private static void fail(",
                "private static Throwable await(");
        assertTrue(failure.contains("log finalization failed:"));
        assertTrue(failure.indexOf("catch (Exception error)")
                < failure.indexOf("session.manifest.put(\"status\", \"failed\")"));
        assertTrue(failure.contains("writeManifest(session)"));
    }

    private static String source() throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve("app/src/main/java/com/bydhud/app/LogcatRecorder.java");
        if (!Files.isRegularFile(file)) {
            file = root.resolve("src/main/java/com/bydhud/app/LogcatRecorder.java");
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static String section(String source, String start, String end) {
        int begin = source.indexOf(start);
        int finish = source.indexOf(end, begin + start.length());
        return begin < 0 || finish < 0 ? "" : source.substring(begin, finish);
    }
}
