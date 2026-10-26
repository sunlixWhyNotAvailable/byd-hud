package com.bydhud.app;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.regex.Pattern;

/** Formats a failure without replacing its primary exception, causes, or suppressed failures. */
final class FailureDiagnostics {
    private static final Pattern LAUNCH_SECRET = Pattern.compile(
            "(?i)(--(?:nonce|launch-token)=)[^\\s,;]+");
    private static final Pattern AUTH_SECRET = Pattern.compile(
            "(?i)((?:proxy-)?authorization\\s*[:=]\\s*(?:bearer|basic)\\s+)[^\\s,;]+");
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)((?:password|passwd|passphrase|(?:access|refresh|auth)[_-]?token|token|secret|"
                    + "api[_-]?(?:key|secret)|private[_-]?key|client[_-]?secret|cookie|credential)"
                    + "\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)");
    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "(?is)-----BEGIN [^-\\r\\n]*PRIVATE KEY-----.*?-----END [^-\\r\\n]*PRIVATE KEY-----");

    private FailureDiagnostics() { }

    static String format(Throwable failure, String operation, String stage) {
        StringWriter text = new StringWriter();
        PrintWriter writer = new PrintWriter(text);
        if (operation != null && !operation.isEmpty()) writer.println("operation=" + operation);
        if (stage != null && !stage.isEmpty()) writer.println("stage=" + stage);
        if (failure == null) writer.println("cause=unknown");
        else failure.printStackTrace(writer);
        writer.flush();
        return redact(text.toString().trim());
    }

    static String formatBounded(Throwable failure, String operation, String stage, int maxChars) {
        String marker = "...[truncated]";
        if (maxChars < marker.length())
            throw new IllegalArgumentException("maxChars must fit the truncation marker");
        String detail = format(failure, operation, stage);
        if (detail.length() <= maxChars) return detail;
        if (maxChars == marker.length()) return marker;
        return detail.substring(0, maxChars - marker.length()) + marker;
    }

    private static String redact(String value) {
        String redacted = LAUNCH_SECRET.matcher(value).replaceAll("$1[REDACTED]");
        redacted = AUTH_SECRET.matcher(redacted).replaceAll("$1[REDACTED]");
        redacted = CREDENTIAL.matcher(redacted).replaceAll("$1[REDACTED]");
        return PRIVATE_KEY.matcher(redacted).replaceAll("[REDACTED PRIVATE KEY]");
    }
}
