package com.bydhud.app;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Arrays;

import org.junit.Test;

public final class FailureDiagnosticsTest {
    @Test
    public void fullFormattingRetainsPrimaryCauseSuppressedAndContext() {
        IOException primary = new IOException("transport failed", new IllegalStateException("remote denied"));
        primary.addSuppressed(new SecurityException("identity mismatch"));

        String detail = FailureDiagnostics.format(primary, "register", "binder-call");

        assertTrue(detail.contains("operation=register"));
        assertTrue(detail.contains("stage=binder-call"));
        assertTrue(detail.contains("java.io.IOException: transport failed"));
        assertTrue(detail.contains("Caused by: java.lang.IllegalStateException: remote denied"));
        assertTrue(detail.contains("Suppressed: java.lang.SecurityException: identity mismatch"));
    }

    @Test
    public void boundedFormattingMarksTruncationWithinTheLimit() {
        char[] message = new char[1024];
        Arrays.fill(message, 'x');
        String detail = FailureDiagnostics.formatBounded(
                new IOException(new String(message)), "export", "dispatch", 128);

        assertTrue(detail.length() <= 128);
        assertTrue(detail.startsWith("operation=export"));
        assertTrue(detail.endsWith("...[truncated]"));
    }

    @Test
    public void formattingRedactsShellLaunchAndAuthorizationSecrets() {
        String detail = FailureDiagnostics.format(new IOException(
                "failed --nonce=nonce-value --launch-token=launch-value "
                        + "Authorization: Bearer bearer-value api_key=api-value"), "startup", "diagnostic");

        assertTrue(detail.contains("--nonce=[REDACTED]"));
        assertTrue(detail.contains("--launch-token=[REDACTED]"));
        assertTrue(detail.contains("Authorization: Bearer [REDACTED]"));
        assertTrue(detail.contains("api_key=[REDACTED]"));
        assertTrue(!detail.contains("nonce-value"));
        assertTrue(!detail.contains("launch-value"));
        assertTrue(!detail.contains("bearer-value"));
        assertTrue(!detail.contains("api-value"));
    }
}
