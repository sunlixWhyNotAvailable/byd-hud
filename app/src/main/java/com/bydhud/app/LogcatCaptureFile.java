package com.bydhud.app;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** One worker-owned capture file; the recorder holds the storage topology lock. */
final class LogcatCaptureFile {
    private final File part;
    private final File saved;
    private boolean started;
    private boolean finished;

    LogcatCaptureFile(File directory) {
        part = new File(directory, "logcat.log.part");
        saved = new File(directory, "logcat.log");
    }

    void append(byte[] bytes) throws IOException {
        if (finished) throw new IOException("Capture log already finalized");
        if (!started) {
            if (saved.exists() || !part.createNewFile()) {
                throw new IOException("Capture log already exists: " + part);
            }
            started = true;
        }
        // Close each bounded poll so even a failed write leaves no open capture handle.
        try (FileOutputStream output = new FileOutputStream(part, true)) {
            output.write(bytes);
        }
    }

    void finish() throws IOException {
        if (finished) return;
        if (started && (saved.exists() || !part.renameTo(saved))) {
            throw new IOException("Unable to finalize " + part);
        }
        finished = true;
    }

    File file() {
        return finished ? saved : part;
    }

    long bytes() {
        return file().isFile() ? file().length() : 0L;
    }
}
