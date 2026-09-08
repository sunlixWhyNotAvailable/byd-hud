package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

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
    public void StopClosesTheReaderBeforeFinalizingAndFailureStillWritesManifest() throws IOException {
        String source = source();
        String stop = section(source, "static Result stop(", "static void stopAsync(");
        String stopAsync = section(source, "static void stopAsync(",
                "static String fullLogcatCommandForTest(");
        assertTrue(stop.indexOf("session.streamControl.stop()")
                < stop.indexOf("ensureFinishLocked(session)"));
        String activeStop = stopAsync.substring(stopAsync.indexOf("activeSession = null;"));
        assertTrue(activeStop.indexOf("session.streamControl.stop()") >= 0);
        assertTrue(activeStop.indexOf("session.streamControl.stop()")
                < activeStop.indexOf("ensureFinishLocked(session)"));
        String finish = section(source, "private static void finish(",
                "private static void runStream(");
        assertTrue(finish.indexOf("await(session.readerFuture") >= 0);
        assertTrue(finish.indexOf("await(session.readerFuture")
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

    @Test
    public void BlockingReaderRunsOutsideTheStopAndFinalizationWorker() throws IOException {
        String source = source();
        assertTrue(source.contains("new Thread(runnable, \"BydHudSystemRecorder\")"));
        assertTrue(source.contains("new Thread(runnable, \"BydHudLogcatStream\")"));
        assertTrue(source.contains("startReaderBeforeSnapshot(STREAM_READER"));
        assertTrue(source.contains("STREAM_STOP_TIMEOUT_MS = 10_000L"));
        assertTrue(source.contains("Logcat reader did not stop after its stream was closed"));
        assertFalse(source.contains("future.cancel(true)"));
    }

    @Test
    public void OpenedSourceIsStopOwnedBeforeBeforeSnapshotCanFailOrBlock() throws IOException {
        String source = source();
        String begin = section(source, "private static void begin(",
                "private static void finish(");
        assertTrue(begin.indexOf("session.streamControl.install(source)")
                < begin.indexOf("captureSnapshot(session, \"before\""));
        assertTrue(begin.contains("session.streamControl.stop()"));
        assertTrue(begin.contains("closeSource(source)"));
    }

    @Test
    public void ReaderBeginsWhileBeforeSnapshotIsStillBlocked() throws Exception {
        var readerExecutor = Executors.newSingleThreadExecutor();
        var callerExecutor = Executors.newSingleThreadExecutor();
        CountDownLatch readerStarted = new CountDownLatch(1);
        CountDownLatch snapshotEntered = new CountDownLatch(1);
        CountDownLatch releaseSnapshot = new CountDownLatch(1);
        FutureTask<Void> reader = new FutureTask<>(() -> readerStarted.countDown(), null);
        try {
            var starting = callerExecutor.submit(() -> {
                LogcatRecorder.startReaderBeforeSnapshot(readerExecutor, reader, () -> {
                    snapshotEntered.countDown();
                    try {
                        if (!releaseSnapshot.await(2, TimeUnit.SECONDS)) {
                            throw new IOException("snapshot release timed out");
                        }
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IOException("snapshot interrupted", error);
                    }
                });
                return null;
            });
            assertTrue(snapshotEntered.await(1, TimeUnit.SECONDS));
            assertTrue(readerStarted.await(1, TimeUnit.SECONDS));
            assertFalse(starting.isDone());
            releaseSnapshot.countDown();
            starting.get(1, TimeUnit.SECONDS);
            reader.get(1, TimeUnit.SECONDS);
        } finally {
            releaseSnapshot.countDown();
            callerExecutor.shutdownNow();
            readerExecutor.shutdownNow();
        }
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
