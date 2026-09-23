package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;

public final class InstrumentProxyStartupLogTest {
    @Test
    public void formatterIncludesBoundedStageIdentityAndMonotonicElapsedTime() {
        String line = InstrumentProxyStartupLog.format(23L, 412L, 3101, 2000,
                InstrumentProxyStartupLog.Stage.CONTEXT_READY,
                InstrumentProxyStartupLog.Outcome.OK);

        assertEquals("instrument_proxy_startup elapsedMs=23 generation=412 pid=3101 uid=2000"
                + " stage=context_ready outcome=ok", line);
        assertTrue(line.length() <= 240);
    }

    @Test
    public void exceptionAndUnknownStopDiagnosticsOmitArgumentsAndMessages() {
        String nonce = "0123456789abcdef0123456789abcdef";
        String launchToken = "fedcba9876543210";
        Throwable cause = new InvocationTargetException(
                new RuntimeException("--nonce=" + nonce + " --launch-token=" + launchToken
                        + " --app-uid=2000"));
        String exceptionLine = InstrumentProxyStartupLog.formatException(9L, 412L,
                3101, 2000, InstrumentProxyStartupLog.Stage.CONTEXT_CREATE, cause);
        String stopLine = InstrumentProxyStartupLog.format(10L, 412L, 3101, 2000,
                InstrumentProxyStartupLog.Stage.STOPPED,
                InstrumentProxyStartupLog.stopOutcome("unexpected " + nonce));

        assertTrue(exceptionLine.contains("outcome=unknown_exception exception=RuntimeException"));
        assertEquals(InstrumentProxyStartupLog.Outcome.UNKNOWN_STOP,
                InstrumentProxyStartupLog.stopOutcome("unexpected " + nonce));
        assertEquals(InstrumentProxyStartupLog.Outcome.APP_SHUTDOWN,
                InstrumentProxyStartupLog.stopOutcome("app shutdown"));
        assertTrue(exceptionLine.length() <= 240);
        assertFalse(exceptionLine.contains(nonce));
        assertFalse(exceptionLine.contains(launchToken));
        assertFalse(exceptionLine.contains("--app-uid"));
        assertFalse(stopLine.contains(nonce));
    }

    @Test
    public void writerFlushesOneLineWithoutClosingTheSharedDescriptor() throws IOException {
        TestOutput output = new TestOutput();
        InstrumentProxyStartupLog.writeDiagnostic(output, "startup marker");

        assertEquals("startup marker\n", new String(
                output.toByteArray(), StandardCharsets.UTF_8));
        assertTrue(output.flushed);
        assertFalse(output.closed);
    }

    private static final class TestOutput extends ByteArrayOutputStream {
        boolean flushed;
        boolean closed;

        @Override
        public void flush() throws IOException {
            flushed = true;
            super.flush();
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
