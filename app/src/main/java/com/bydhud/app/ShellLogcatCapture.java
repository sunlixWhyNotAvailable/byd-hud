package com.bydhud.app;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStatVfs;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;

/** The writer and logd connection belong to shell, not to the UI/service process. */
final class ShellLogcatCapture {
    static final long STORAGE_RESERVE_BYTES = 32L * 1024L * 1024L;
    private static final long SPACE_CHECK_INTERVAL_BYTES = 256L * 1024L;
    private static final String INCOMPLETE_MARKER = "\nBYD-HUD-CAPTURE-INCOMPLETE reason=";

    private String id = "";
    private Process process;
    private Thread reader;
    private volatile String error = "";
    private volatile long bytes;
    private volatile boolean stopping;

    synchronized Bundle start(String captureId, String cursor, ParcelFileDescriptor destination) {
        if (destination == null) throw new IllegalArgumentException("missing capture destination");
        if (reader != null && reader.isAlive()) {
            try { destination.close(); } catch (IOException ignored) { }
            if (!id.equals(captureId)) throw new IllegalStateException("capture already running");
            return state();
        }
        id = captureId;
        bytes = 0;
        error = "";
        stopping = false;
        try {
            long available = availableBytes(destination);
            if (shouldStopForAvailableBytes(available)) {
                error = writeIncompleteMarker(destination, "low_space", available);
                return state();
            }
            process = new ProcessBuilder("/system/bin/logcat", "-b", "all", "-v", "threadtime",
                    "-T", cursor).redirectErrorStream(true).start();
            Process owned = process;
            java.io.FileDescriptor outputDescriptor = destination.getFileDescriptor();
            reader = new Thread(() -> {
                try (InputStream input = owned.getInputStream();
                        OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(destination)) {
                    byte[] buffer = new byte[32 * 1024];
                    int count;
                    long bytesUntilSpaceCheck = 0L;
                    while ((count = input.read(buffer)) >= 0) {
                        output.write(buffer, 0, count);
                        bytes += count;
                        bytesUntilSpaceCheck += count;
                        if (bytesUntilSpaceCheck >= SPACE_CHECK_INTERVAL_BYTES) {
                            bytesUntilSpaceCheck = 0L;
                            long remaining = availableBytes(outputDescriptor);
                            if (shouldStopForAvailableBytes(remaining)) {
                                error = writeIncompleteMarker(output, outputDescriptor,
                                        "low_space", remaining);
                                stopping = true;
                                owned.destroy();
                                break;
                            }
                        }
                    }
                    if (!stopping) error = "logcat stream ended";
                } catch (Exception failure) {
                    if (!stopping) {
                        String reason = isNoSpace(failure) ? "low_space" : "write_failure";
                        error = writeIncompleteMarker(outputDescriptor, reason,
                                availableBytesQuietly(outputDescriptor), failure);
                    }
                } finally { owned.destroy(); }
            }, "BydHudShellLogcat");
            reader.start();
        } catch (IOException failure) {
            error = isNoSpace(failure)
                    ? writeIncompleteMarker(destination.getFileDescriptor(), "low_space",
                            availableBytesQuietly(destination.getFileDescriptor()), failure)
                    : failure.toString();
            try { destination.close(); } catch (IOException ignored) { }
        } catch (ErrnoException failure) {
            error = failure.errno == OsConstants.ENOSPC
                    ? writeIncompleteMarker(destination.getFileDescriptor(), "low_space",
                            availableBytesQuietly(destination.getFileDescriptor()), failure)
                    : failure.toString();
            try { destination.close(); } catch (IOException ignored) { }
        }
        return state();
    }

    static boolean shouldStopForAvailableBytes(long availableBytes) {
        return availableBytes >= 0L
                && availableBytes <= STORAGE_RESERVE_BYTES + SPACE_CHECK_INTERVAL_BYTES;
    }

    private static long availableBytes(ParcelFileDescriptor descriptor) throws ErrnoException {
        return availableBytes(descriptor.getFileDescriptor());
    }

    private static long availableBytes(java.io.FileDescriptor descriptor) throws ErrnoException {
        StructStatVfs stats = Os.fstatvfs(descriptor);
        long blocks = Math.max(0L, stats.f_bavail);
        long blockSize = Math.max(0L, stats.f_frsize);
        if (blockSize == 0L) blockSize = Math.max(0L, stats.f_bsize);
        if (blockSize != 0L && blocks > Long.MAX_VALUE / blockSize) return Long.MAX_VALUE;
        return blocks * blockSize;
    }

    private static long availableBytesQuietly(java.io.FileDescriptor descriptor) {
        try { return availableBytes(descriptor); }
        catch (Exception ignored) { return -1L; }
    }

    private static String writeIncompleteMarker(
            ParcelFileDescriptor destination, String reason, long availableBytes) {
        try (OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(destination)) {
            return writeIncompleteMarker(output, destination.getFileDescriptor(),
                    reason, availableBytes, null);
        } catch (Exception failure) {
            return reason + " availableBytes=" + availableBytes
                    + " markerWriteFailure=" + failure;
        }
    }

    private static String writeIncompleteMarker(
            OutputStream output, java.io.FileDescriptor descriptor,
            String reason, long availableBytes) {
        return writeIncompleteMarker(output, descriptor, reason, availableBytes, null);
    }

    private static String writeIncompleteMarker(
            java.io.FileDescriptor descriptor, String reason,
            long availableBytes, Throwable cause) {
        try (OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(
                ParcelFileDescriptor.dup(descriptor))) {
            return writeIncompleteMarker(output, descriptor, reason, availableBytes, cause);
        } catch (Exception failure) {
            return errorText(reason, availableBytes, cause)
                    + " markerWriteFailure=" + failure;
        }
    }

    private static String writeIncompleteMarker(
            OutputStream output, java.io.FileDescriptor descriptor, String reason,
            long availableBytes, Throwable cause) {
        String detail = errorText(reason, availableBytes, cause);
        byte[] marker = (INCOMPLETE_MARKER + reason + " availableBytes=" + availableBytes
                + " detail=" + safe(cause == null ? "" : cause.toString()) + "\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            output.write(marker);
            output.flush();
            Os.fsync(descriptor);
            return detail;
        } catch (Exception failure) {
            return detail + " markerWriteFailure=" + failure;
        }
    }

    private static String errorText(String reason, long availableBytes, Throwable cause) {
        return reason + " availableBytes=" + availableBytes
                + (cause == null ? "" : " cause=" + safe(cause.toString()));
    }

    private static boolean isNoSpace(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ErrnoException
                    && ((ErrnoException) current).errno == OsConstants.ENOSPC) return true;
            String message = current.getMessage();
            if (message != null && (message.contains("ENOSPC")
                    || message.toLowerCase(java.util.Locale.ROOT).contains("no space left"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }

    Bundle stop(String expectedId) {
        Thread worker;
        synchronized (this) {
            if (!id.equals(expectedId)) throw new IllegalArgumentException("capture identity mismatch");
            stopping = true;
            if (process != null) process.destroy();
            worker = reader;
        }
        if (worker != null) {
            try { worker.join(2_000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        return state();
    }

    synchronized Bundle state() {
        Bundle state = new Bundle();
        state.putString("id", id);
        state.putBoolean("running", reader != null && reader.isAlive());
        state.putLong("bytes", bytes);
        state.putString("error", error);
        return state;
    }
}
