package com.bydhud.app;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/** Publishes a complete immutable artifact without exposing a partially written final name. */
final class AtomicArtifactFile {
    private AtomicArtifactFile() { }

    static void publish(File target, byte[] bytes) throws IOException {
        Path destination = target.toPath();
        if (Files.isRegularFile(destination) && Files.size(destination) == bytes.length
                && Arrays.equals(Files.readAllBytes(destination), bytes)) return;
        Path temporary = Files.createTempFile(destination.toAbsolutePath().getParent(), ".artifact-", ".tmp");
        Throwable primary = null;
        try {
            try (FileOutputStream output = new FileOutputStream(temporary.toFile())) {
                output.write(bytes);
                output.getFD().sync();
            }
            if (Files.size(temporary) != bytes.length || !Arrays.equals(Files.readAllBytes(temporary), bytes)) {
                throw new IOException("artifact content verification failed: " + target);
            }
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException failure) {
            primary = failure;
            throw failure;
        } finally {
            try { Files.deleteIfExists(temporary); }
            catch (IOException cleanup) {
                if (primary != null) primary.addSuppressed(cleanup);
                else throw cleanup;
            }
        }
    }
}
