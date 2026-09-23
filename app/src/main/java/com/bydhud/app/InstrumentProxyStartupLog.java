package com.bydhud.app;

import android.os.Process;
import android.os.RemoteException;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Flushed startup markers written directly to descriptor 2. */
final class InstrumentProxyStartupLog {
    private static final long STARTED_NANOS = System.nanoTime();
    private static final int MAX_LINE_LENGTH = 240;
    private static final Object WRITE_LOCK = new Object();
    private static FileOutputStream stderr;

    enum Stage {
        ENTRY_VALIDATED,
        ENTRY_REJECTED,
        CONTEXT_CREATE,
        CONTEXT_READY,
        SERVICE_CREATE,
        SERVICE_READY,
        BINDER_HANDOFF,
        LOOPER_PREPARE,
        LOOPER,
        CONNECT_REQUESTED,
        OEM_INSTRUMENT,
        OEM_BODYWORK,
        OEM_NATIVE_SPEED,
        CONNECT_REJECTED,
        CALLBACK_FAILED,
        READY,
        STOP_REQUESTED,
        STOPPED
    }

    enum Outcome {
        STARTED,
        OK,
        AVAILABLE,
        UNAVAILABLE,
        INVALID_ARGUMENTS,
        WRONG_UID,
        IDENTITY_MISMATCH,
        MISSING_CLIENT,
        CAPABILITIES_UNAVAILABLE,
        HANDOFF_REJECTED,
        HANDOFF_TIMEOUT,
        CLIENT_DIED,
        APP_SHUTDOWN,
        PING_CALLBACK_FAILED,
        SECURITY_EXCEPTION,
        OEM_CLASS_MISSING,
        OEM_MEMBER_MISSING,
        REFLECTION_ACCESS_DENIED,
        BINDER_REMOTE_EXCEPTION,
        UNKNOWN_EXCEPTION,
        UNKNOWN_STOP
    }

    private InstrumentProxyStartupLog() {
    }

    static void record(long generation, Stage stage, Outcome outcome) {
        try {
            writeLine(format(elapsedMillis(), generation,
                    Process.myPid(), Process.myUid(), stage, outcome));
        } catch (IOException | RuntimeException ignored) {
            // Diagnostics must not change helper startup behavior.
        }
    }

    static void recordException(long generation, Stage stage, Throwable error) {
        try {
            writeLine(formatException(elapsedMillis(), generation,
                    Process.myPid(), Process.myUid(), stage, error));
        } catch (IOException | RuntimeException ignored) {
            // Diagnostics must not change helper startup behavior.
        }
    }

    static Outcome exceptionOutcome(Throwable error) {
        Throwable cause = error;
        if (cause instanceof InvocationTargetException
                && ((InvocationTargetException) cause).getTargetException() != null) {
            cause = ((InvocationTargetException) cause).getTargetException();
        }
        if (cause instanceof SecurityException) return Outcome.SECURITY_EXCEPTION;
        if (cause instanceof ClassNotFoundException || cause instanceof NoClassDefFoundError) {
            return Outcome.OEM_CLASS_MISSING;
        }
        if (cause instanceof NoSuchMethodException || cause instanceof NoSuchFieldException) {
            return Outcome.OEM_MEMBER_MISSING;
        }
        if (cause instanceof IllegalAccessException) return Outcome.REFLECTION_ACCESS_DENIED;
        if (cause instanceof RemoteException) return Outcome.BINDER_REMOTE_EXCEPTION;
        return Outcome.UNKNOWN_EXCEPTION;
    }

    static Outcome stopOutcome(String reason) {
        if ("missing client".equals(reason)) return Outcome.MISSING_CLIENT;
        if ("handoff rejected".equals(reason)) return Outcome.HANDOFF_REJECTED;
        if ("handoff timeout".equals(reason)) return Outcome.HANDOFF_TIMEOUT;
        if ("client died".equals(reason)) return Outcome.CLIENT_DIED;
        if ("app shutdown".equals(reason)) return Outcome.APP_SHUTDOWN;
        if ("ping callback failed".equals(reason)) return Outcome.PING_CALLBACK_FAILED;
        return Outcome.UNKNOWN_STOP;
    }

    static String format(long elapsedMs, long generation, int pid, int uid,
            Stage stage, Outcome outcome) {
        return bounded("instrument_proxy_startup elapsedMs=" + elapsedMs
                + " generation=" + generation + " pid=" + pid + " uid=" + uid
                + " stage=" + stage.name().toLowerCase(Locale.US)
                + " outcome=" + outcome.name().toLowerCase(Locale.US));
    }

    static String formatException(long elapsedMs, long generation, int pid, int uid,
            Stage stage, Throwable error) {
        return bounded(format(elapsedMs, generation, pid, uid, stage,
                exceptionOutcome(error)) + " exception=" + exceptionType(error));
    }

    static void writeDiagnostic(OutputStream output, String line) throws IOException {
        output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static void writeLine(String line) throws IOException {
        synchronized (WRITE_LOCK) {
            if (stderr == null) stderr = new FileOutputStream(FileDescriptor.err);
            writeDiagnostic(stderr, line);
        }
    }

    private static String exceptionType(Throwable error) {
        Throwable cause = error;
        if (cause instanceof InvocationTargetException
                && ((InvocationTargetException) cause).getTargetException() != null) {
            cause = ((InvocationTargetException) cause).getTargetException();
        }
        if (cause == null) return "unknown";
        String type = cause.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_]", "_");
        if (type.isEmpty()) return "unknown";
        return type.length() <= 40 ? type : type.substring(0, 40);
    }

    private static String bounded(String line) {
        return line.length() <= MAX_LINE_LENGTH
                ? line : line.substring(0, MAX_LINE_LENGTH);
    }

    private static long elapsedMillis() {
        return Math.max(0L, (System.nanoTime() - STARTED_NANOS) / 1_000_000L);
    }
}
