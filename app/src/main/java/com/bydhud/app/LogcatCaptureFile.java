package com.bydhud.app;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** One worker-owned capture file; the recorder holds the storage topology lock. */
final class LogcatCaptureFile {
    private final File part;
    private final File saved;
    private final OutputOpener opener;
    private boolean started;
    private boolean finished;
    private FileOutputStream output;

    LogcatCaptureFile(File directory) {
        this(directory, file -> new FileOutputStream(file, true));
    }

    LogcatCaptureFile(File directory, OutputOpener opener) {
        part = new File(directory, "logcat.log.part");
        saved = new File(directory, "logcat.log");
        this.opener = opener;
    }

    interface OutputOpener {
        FileOutputStream open(File file) throws IOException;
    }

    synchronized void append(byte[] bytes) throws IOException {
        append(bytes, 0, bytes == null ? 0 : bytes.length);
    }

    synchronized void append(byte[] bytes, int offset, int length) throws IOException {
        if (finished) throw new IOException("Capture log already finalized");
        if (bytes == null) throw new IllegalArgumentException("bytes are required");
        if (offset < 0 || length < 0 || offset + length > bytes.length) {
            throw new IndexOutOfBoundsException();
        }
        if (length == 0) return;
        if (!started) {
            if (saved.exists() || !part.createNewFile()) {
                throw new IOException("Capture log already exists: " + part);
            }
            try {
                output = opener.open(part);
                started = true;
            } catch (IOException | RuntimeException error) {
                // This attempt created the file; never remove existing or nonempty evidence.
                if (part.isFile() && part.length() == 0L && !part.delete()) {
                    error.addSuppressed(new IOException("Unable to remove unopened capture " + part));
                }
                throw error;
            }
        }
        output.write(bytes, offset, length);
    }

    synchronized void finish() throws IOException {
        if (finished) return;
        if (output != null) {
            FileOutputStream current = output;
            output = null;
            IOException failure = null;
            try {
                current.flush();
            } catch (IOException error) {
                failure = error;
            }
            try {
                current.close();
            } catch (IOException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
            if (failure != null) throw failure;
        }
        if (started && (saved.exists() || !part.renameTo(saved))) {
            throw new IOException("Unable to finalize " + part);
        }
        finished = true;
    }

    synchronized File file() {
        return finished ? saved : part;
    }

    synchronized long bytes() {
        return file().isFile() ? file().length() : 0L;
    }
}
