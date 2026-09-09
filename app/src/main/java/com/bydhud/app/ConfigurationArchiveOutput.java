package com.bydhud.app;

import java.io.*;
import java.util.*;

/** One ZIP byte stream, split without rereading it or retaining a second complete archive. */
final class ConfigurationArchiveOutput extends OutputStream {
    static final long VOLUME_BYTES = 1_000_000_000L;
    private final File target;
    private final long limit;
    private final List<File> partials = new ArrayList<>();
    private final List<File> published = new ArrayList<>();
    private FileOutputStream file;
    private OutputStream buffered;
    private long volumeBytes;
    private boolean closed;

    ConfigurationArchiveOutput(File target, long limit) {
        if (limit < 1) throw new IllegalArgumentException("volume limit");
        this.target = target;
        this.limit = limit;
    }

    @Override public void write(int value) throws IOException {
        write(new byte[]{(byte) value}, 0, 1);
    }

    @Override public void write(byte[] bytes, int offset, int length) throws IOException {
        if (closed) throw new IOException("archive closed");
        while (length > 0) {
            if (buffered == null || volumeBytes == limit) {
                closeVolume();
                File next = new File(target.getPath() + String.format(Locale.ROOT,
                        ".%03d.part", partials.size() + 1));
                if (!next.createNewFile()) throw new IOException("archive volume already exists");
                partials.add(next);
                file = new FileOutputStream(next);
                buffered = new BufferedOutputStream(file, 64 * 1024);
                volumeBytes = 0;
            }
            int count = (int) Math.min(length, limit - volumeBytes);
            buffered.write(bytes, offset, count);
            volumeBytes += count;
            offset += count;
            length -= count;
        }
    }

    private void closeVolume() throws IOException {
        if (buffered == null) return;
        try {
            buffered.flush();
            file.getFD().sync();
        } finally {
            try { buffered.close(); } finally { buffered = null; file = null; }
        }
    }

    @Override public void close() throws IOException {
        closed = true;
        closeVolume();
    }

    List<File> publish() throws IOException {
        close();
        if (partials.isEmpty()) throw new IOException("empty archive stream");
        for (int index = 0; index < partials.size(); index++) {
            File destination = partials.size() == 1 ? target : new File(target.getPath()
                    + String.format(Locale.ROOT, ".%03d", index + 1));
            if (destination.exists() || !partials.get(index).renameTo(destination)) {
                throw new IOException("archive volume finalization failed");
            }
            published.add(destination);
        }
        return Collections.unmodifiableList(new ArrayList<>(published));
    }

    void abort() {
        try { close(); } catch (IOException ignored) { }
        for (File path : partials) LogShareZip.deleteArtifact(path);
        for (File path : published) LogShareZip.deleteArtifact(path);
    }
}
