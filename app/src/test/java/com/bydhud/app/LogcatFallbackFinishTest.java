package com.bydhud.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class LogcatFallbackFinishTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private static final class Source implements LogcatRecorder.CaptureSource {
        int closes;
        @Override public void readTo(OutputStream output) { }
        @Override public void close() { closes++; }
    }

    @Test public void stopWithoutReconcilerPreservesCompleteUtf8TailExactlyOnce() throws Exception {
        LogcatRecorder.Session session = session(temporaryFolder.newFolder());
        LogcatRecorder.CaptureOutput capture = new LogcatRecorder.CaptureOutput(session);
        Source source = attach(session);
        byte[] complete = utf8("first line\nостанній фрагмент €");
        for (byte value : complete) capture.write(value);
        session.stopRequested = true;

        LogcatRecorder.finishFallbackStream(session, capture, null, source);
        session.logFile.finish();
        assertArrayEquals(complete, Files.readAllBytes(session.logFile.file().toPath()));
        assertFalse(session.knownLoss);
        assertEquals(1, source.closes);
        session.streamControl.stop();
        assertEquals(1, source.closes);
        assertEquals(1, session.interruptions.size());
    }

    @Test public void stopWithoutReconcilerPreservesOnlyCompleteUtf8AndRecordsLoss() throws Exception {
        LogcatRecorder.Session session = session(temporaryFolder.newFolder());
        LogcatRecorder.CaptureOutput capture = new LogcatRecorder.CaptureOutput(session);
        Source source = attach(session);
        byte[] complete = utf8("кінець €");
        capture.write(complete, 0, complete.length - 1);

        LogcatRecorder.finishFallbackStream(session, capture, null, source);
        session.logFile.finish();
        assertArrayEquals(utf8("кінець "), Files.readAllBytes(session.logFile.file().toPath()));
        assertTrue(session.knownLoss);
        assertTrue(session.interruptions.get(0).getString("reason").contains("discardedUtf8Tail=2"));
        assertEquals(1, source.closes);
    }

    @Test public void reconciledFallbackStillFlushesTheTailWithoutDuplicatingOldLines() throws Exception {
        LogcatRecorder.Session session = session(temporaryFolder.newFolder());
        LogcatRecorder.CaptureOutput capture = new LogcatRecorder.CaptureOutput(session);
        Source source = attach(session);
        byte[] previous = utf8("09-15 10:00:00.001  1  2 I Tag: previous\n");
        byte[] tail = utf8("09-15 10:00:00.002  1  2 I Tag: кінець");
        capture.write(previous);
        LogcatStreamBoundary.Reconciler reconciler = new LogcatStreamBoundary.Reconciler(
                session.boundary.snapshot(), capture);
        reconciler.write(previous);
        reconciler.write(tail);

        LogcatRecorder.finishFallbackStream(session, capture, reconciler, source);
        session.logFile.finish();
        byte[] expected = Arrays.copyOf(previous, previous.length + tail.length);
        System.arraycopy(tail, 0, expected, previous.length, tail.length);
        assertArrayEquals(expected, Files.readAllBytes(session.logFile.file().toPath()));
        assertEquals(1, source.closes);
    }

    @Test public void failedTailWriteStillDetachesAndClosesTheSource() throws Exception {
        LogcatRecorder.Session session = session(temporaryFolder.newFile("blocked-directory"));
        LogcatRecorder.CaptureOutput capture = new LogcatRecorder.CaptureOutput(session);
        Source source = attach(session);
        capture.write(utf8("unwritable tail"));

        assertThrows(IOException.class,
                () -> LogcatRecorder.finishFallbackStream(session, capture, null, source));
        assertEquals(1, source.closes);
        session.streamControl.stop();
        assertEquals(1, source.closes);
        assertEquals(0L, session.logFile.bytes());
    }

    @Test public void failedReconcilerFinishStillClosesTheSource() throws Exception {
        LogcatRecorder.Session session = session(temporaryFolder.newFolder());
        LogcatRecorder.CaptureOutput capture = new LogcatRecorder.CaptureOutput(session);
        Source source = attach(session);
        OutputStream failing = new OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("write failed"); }
        };
        LogcatStreamBoundary.Reconciler reconciler = new LogcatStreamBoundary.Reconciler(
                session.boundary.snapshot(), failing, utf8("pending prefix"));

        assertThrows(IOException.class,
                () -> LogcatRecorder.finishFallbackStream(session, capture, reconciler, source));
        assertEquals(1, source.closes);
        session.streamControl.stop();
        assertEquals(1, source.closes);
    }

    private static LogcatRecorder.Session session(File directory) {
        return new LogcatRecorder.Session(null, "20260915", "test", directory);
    }

    private static Source attach(LogcatRecorder.Session session) {
        Source source = new Source();
        assertTrue(session.streamControl.install(source));
        return source;
    }

    private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }
}
