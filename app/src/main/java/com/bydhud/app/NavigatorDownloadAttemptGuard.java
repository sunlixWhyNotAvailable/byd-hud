package com.bydhud.app;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** Filesystem boundary that prevents one navigator download attempt touching another. */
final class NavigatorDownloadAttemptGuard {
    interface IoOperation<T> {
        T run() throws IOException;
    }

    private NavigatorDownloadAttemptGuard() {
    }

    static boolean shouldRediscover(String state, boolean recordedReady, File stableFile) {
        return !recordedReady && stableFile.isFile()
                && !NavigatorAssetManager.DOWNLOADING.equals(state)
                && !NavigatorAssetManager.VERIFYING.equals(state)
                && !NavigatorAssetManager.ERROR.equals(state);
    }

    static File validationCandidate(String state, File requestedAttempt, File stableFile) {
        return NavigatorAssetManager.VERIFYING.equals(state)
                && !requestedAttempt.isFile() && stableFile.isFile()
                ? stableFile : requestedAttempt;
    }

    static String newAttemptFileName(String stableFileName, long generation) {
        return stableFileName + ".attempt-" + generation + "-" + UUID.randomUUID();
    }

    static boolean settleValidated(File candidate, File stable, boolean ownsAttempt)
            throws IOException {
        boolean isStable = candidate.getCanonicalFile().equals(stable.getCanonicalFile());
        if (!ownsAttempt) {
            if (!isStable) Files.deleteIfExists(candidate.toPath());
            return false;
        }
        if (!isStable) {
            try {
                Files.move(candidate.toPath(), stable.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                throw new IOException("Atomic navigator cache promotion is unavailable", error);
            }
        }
        return true;
    }

    static void discardFailed(File candidate) throws IOException {
        Files.deleteIfExists(candidate.toPath());
    }

    static <T> T serialized(Object lock, IoOperation<T> operation) throws IOException {
        synchronized (lock) {
            return operation.run();
        }
    }
}
