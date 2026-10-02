package com.bydhud.app;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;

/** The writer and logd connection belong to shell, not to the UI/service process. */
final class ShellLogcatCapture {
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
            process = new ProcessBuilder("/system/bin/logcat", "-b", "all", "-v", "threadtime",
                    "-T", cursor).redirectErrorStream(true).start();
            Process owned = process;
            reader = new Thread(() -> {
                try (InputStream input = owned.getInputStream();
                        OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(destination)) {
                    byte[] buffer = new byte[32 * 1024];
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        output.write(buffer, 0, count);
                        bytes += count;
                    }
                    if (!stopping) error = "logcat stream ended";
                } catch (Exception failure) {
                    if (!stopping) error = failure.toString();
                } finally { owned.destroy(); }
            }, "BydHudShellLogcat");
            reader.start();
        } catch (IOException failure) {
            error = failure.toString();
            try { destination.close(); } catch (IOException ignored) { }
        }
        return state();
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
