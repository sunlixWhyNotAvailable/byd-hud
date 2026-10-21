package com.bydhud.app;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Owns the actual recovery command until its exit has been observed. */
final class ShellRecoveryCommand implements AutoCloseable {
    private final long timeoutMs;
    private final Consumer<String> logger;
    private Attempt running;
    private long sequence;
    private boolean closed;

    ShellRecoveryCommand(long timeoutMs, Consumer<String> logger) {
        if (timeoutMs <= 0) throw new IllegalArgumentException("Invalid recovery timeout");
        this.timeoutMs = timeoutMs;
        this.logger = logger;
    }

    synchronized boolean start(ProcessBuilder builder) throws IOException {
        if (closed || running != null) return false;
        Attempt attempt = new Attempt(++sequence, builder.start());
        running = attempt;
        Thread monitor = new Thread(() -> monitor(attempt), "shell-recovery-command");
        monitor.setDaemon(true);
        monitor.start();
        log(attempt, "started timeoutMs=" + timeoutMs);
        return true;
    }

    void cancel(String reason) {
        Attempt attempt;
        synchronized (this) { attempt = running; }
        if (attempt != null) terminate(attempt, reason);
    }

    @Override public void close() {
        synchronized (this) { closed = true; }
        cancel("runtime_stopped");
    }

    private void monitor(Attempt attempt) {
        boolean interrupted = false;
        try {
            if (!attempt.process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                terminate(attempt, "timeout");
            }
        } catch (InterruptedException error) {
            interrupted = true;
            terminate(attempt, "monitor_interrupted");
        }
        // A failed kill must retain ownership, never admit another command.
        for (;;) {
            try {
                int exitCode = attempt.process.waitFor();
                synchronized (this) {
                    if (running == attempt) running = null;
                }
                log(attempt, "exited exitCode=" + exitCode + " reason=" + attempt.reason);
                break;
            } catch (InterruptedException error) {
                interrupted = true;
                terminate(attempt, "monitor_interrupted");
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void terminate(Attempt attempt, String reason) {
        boolean interrupted = false;
        synchronized (attempt) {
            if (!attempt.process.isAlive()) return;
            if (!"completed".equals(attempt.reason)) return;
            attempt.reason = reason;
            boolean exited = false;
            try {
                attempt.process.destroy();
                if (attempt.process.isAlive()) attempt.process.destroyForcibly();
                exited = attempt.process.waitFor(1_000L, TimeUnit.MILLISECONDS);
            }
            catch (InterruptedException error) { interrupted = true; }
            catch (RuntimeException error) {
                log(attempt, "termination_failed error=" + error.getClass().getSimpleName());
            }
            log(attempt, "termination reason=" + reason + " exited=" + exited);
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void log(Attempt attempt, String event) {
        try {
            logger.accept("attempt=" + attempt.id + " elapsedMs="
                    + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - attempt.started) + " " + event);
        } catch (RuntimeException ignored) {
            // A diagnostic failure must not abandon a running command.
        }
    }

    private static final class Attempt {
        final long id;
        final Process process;
        final long started = System.nanoTime();
        volatile String reason = "completed";

        Attempt(long id, Process process) {
            this.id = id;
            this.process = process;
        }
    }
}
