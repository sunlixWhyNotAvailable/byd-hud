package com.bydhud.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;

/** Streams the raw parts of one archive into the user-selected document. */
final class ArchiveDocumentCopy {
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final long PROGRESS_BYTES = 1024 * 1024;

    static final class Plan {
        final List<File> sources;
        final long[] lengths;
        final long totalBytes;

        Plan(List<File> sources, long[] lengths, long totalBytes) {
            this.sources = sources;
            this.lengths = lengths;
            this.totalBytes = totalBytes;
        }
    }

    static Plan prepare(List<File> sources) throws IOException {
        if (sources == null || sources.isEmpty()) throw new IOException("archive sources missing");
        List<File> files = new ArrayList<>(sources.size());
        long[] lengths = new long[sources.size()];
        long total = 0;
        for (int index = 0; index < sources.size(); index++) {
            File source = sources.get(index);
            if (source == null || Files.isSymbolicLink(source.toPath()))
                throw new IOException("archive source is not a regular file");
            File canonical = source.getCanonicalFile();
            if (!canonical.isFile() || !canonical.canRead())
                throw new IOException("archive source is not readable");
            long length = canonical.length();
            if (length <= 0) throw new IOException("archive source is empty");
            try { total = Math.addExact(total, length); }
            catch (ArithmeticException overflow) { throw new IOException("archive size overflow", overflow); }
            files.add(canonical);
            lengths[index] = length;
        }
        return new Plan(files, lengths, total);
    }

    /** Revalidates every part before the destination is opened for writing. */
    static void validate(Plan plan) throws IOException {
        if (plan == null || plan.sources.isEmpty() || plan.sources.size() != plan.lengths.length)
            throw new IOException("archive sources missing");
        for (int index = 0; index < plan.sources.size(); index++) {
            File source = plan.sources.get(index);
            if (source == null || Files.isSymbolicLink(source.toPath())
                    || !source.isFile() || !source.canRead())
                throw new IOException("archive source is not readable");
            if (source.length() != plan.lengths[index])
                throw new IOException("archive source size changed");
        }
    }

    static long copy(Plan plan, OutputStream destination,
            AtomicBoolean cancelled, LongConsumer progress) throws IOException {
        if (plan == null || destination == null || cancelled == null)
            throw new IOException("archive copy is not ready");
        validate(plan);
        byte[] buffer = new byte[BUFFER_BYTES];
        long copied = 0;
        long nextProgress = PROGRESS_BYTES;
        for (int index = 0; index < plan.sources.size(); index++) {
            checkCancelled(cancelled);
            File source = plan.sources.get(index);
            long expected = plan.lengths[index];
            if (Files.isSymbolicLink(source.toPath()) || !source.isFile() || source.length() != expected)
                throw new IOException("archive source size changed");
            long sourceBytes = 0;
            try (FileInputStream input = new FileInputStream(source)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    checkCancelled(cancelled);
                    sourceBytes += read;
                    if (sourceBytes > expected) throw new IOException("archive source size changed");
                    destination.write(buffer, 0, read);
                    copied += read;
                    if (progress != null && (copied >= nextProgress || copied == plan.totalBytes)) {
                        progress.accept(copied);
                        nextProgress = copied + PROGRESS_BYTES;
                    }
                }
            }
            if (sourceBytes != expected || source.length() != expected)
                throw new IOException("archive source size changed");
        }
        if (copied != plan.totalBytes) throw new IOException("archive size changed");
        destination.flush();
        checkCancelled(cancelled);
        return copied;
    }

    private static void checkCancelled(AtomicBoolean cancelled) throws InterruptedIOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("archive save cancelled");
    }

    private ArchiveDocumentCopy() { }
}
