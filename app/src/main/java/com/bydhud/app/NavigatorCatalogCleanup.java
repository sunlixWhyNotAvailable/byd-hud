package com.bydhud.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/** Persists and applies exact catalog-file cleanup identities. */
final class NavigatorCatalogCleanup {
    static final class Entry {
        final String fileName;
        final String sha256;

        Entry(String fileName, String sha256) {
            this.fileName = fileName;
            this.sha256 = sha256;
        }
    }

    private NavigatorCatalogCleanup() {
    }

    static String enqueue(String pending, String fileName, String sha256) {
        if (!valid(fileName, sha256)) return pending == null ? "" : pending;
        List<Entry> entries = entries(pending);
        for (Entry entry : entries) {
            if (entry.fileName.equals(fileName) && entry.sha256.equalsIgnoreCase(sha256)) {
                return encode(entries);
            }
        }
        entries.add(new Entry(fileName, sha256.toLowerCase(java.util.Locale.US)));
        return encode(entries);
    }

    static List<Entry> entries(String pending) {
        List<Entry> result = new ArrayList<>();
        if (pending == null || pending.isEmpty()) return result;
        for (String line : pending.split("\\n")) {
            int separator = line.indexOf('\t');
            if (separator < 0 || line.indexOf('\t', separator + 1) >= 0) continue;
            String fileName = line.substring(0, separator);
            String sha256 = line.substring(separator + 1);
            if (!valid(fileName, sha256)) continue;
            boolean duplicate = false;
            for (Entry entry : result) {
                if (entry.fileName.equals(fileName)
                        && entry.sha256.equalsIgnoreCase(sha256)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) result.add(new Entry(fileName, sha256.toLowerCase(
                    java.util.Locale.US)));
        }
        return result;
    }

    static String remove(String pending, Entry removed) {
        List<Entry> entries = entries(pending);
        entries.removeIf(entry -> entry.fileName.equals(removed.fileName)
                && entry.sha256.equalsIgnoreCase(removed.sha256));
        return encode(entries);
    }

    static void deleteOwnedCopies(File downloadsDirectory, File updatesDirectory, Entry entry)
            throws IOException {
        deleteOwned(downloadsDirectory, entry);
        deleteOwned(updatesDirectory, entry);
    }

    private static void deleteOwned(File directory, Entry entry) throws IOException {
        if (directory == null || entry == null || !valid(entry.fileName, entry.sha256)
                || !directory.exists()) return;
        if (!directory.isDirectory()) {
            throw new IOException("catalog cleanup parent is not a directory: " + directory);
        }
        File canonicalDirectory = directory.getCanonicalFile();
        File namedFile = new File(canonicalDirectory, entry.fileName);
        File file = namedFile.getCanonicalFile();
        if (!canonicalDirectory.equals(file.getParentFile())
                || !entry.fileName.equals(file.getName()) || !file.isFile()
                || !entry.sha256.equalsIgnoreCase(sha256(file))) return;
        if (!file.delete()) throw new IOException("could not delete owned catalog file "
                + entry.fileName);
    }

    private static String encode(List<Entry> entries) {
        StringBuilder result = new StringBuilder();
        for (Entry entry : entries) {
            if (result.length() > 0) result.append('\n');
            result.append(entry.fileName).append('\t').append(entry.sha256);
        }
        return result.toString();
    }

    private static boolean valid(String fileName, String sha256) {
        return fileName != null && fileName.matches("[A-Za-z0-9._-]{1,255}")
                && !".".equals(fileName) && !"..".equals(fileName)
                && sha256 != null && sha256.matches("(?i)[0-9a-f]{64}");
    }

    private static String sha256(File file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 unavailable", error);
        }
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) {
            result.append(String.format(java.util.Locale.US, "%02x", value & 0xff));
        }
        return result.toString();
    }
}
