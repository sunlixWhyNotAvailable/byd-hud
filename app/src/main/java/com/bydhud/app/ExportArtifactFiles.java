package com.bydhud.app;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Set;

/** File deletion stays inside the selected workspace and never follows links. */
final class ExportArtifactFiles {
    static final long LOG_RETENTION_MS = 10 * 60_000L;

    static final class Removed {
        int files;
        long bytes;
    }

    static File child(File root, String relative) throws IOException {
        Path base = root.toPath().toAbsolutePath().normalize();
        Path target = base.resolve(relative).normalize();
        if (target.equals(base) || !target.startsWith(base))
            throw new IOException("outside_workspace");
        // Check ancestors too: canonical containment alone permits an in-root link.
        for (Path part = target; part != null; part = part.getParent()) {
            if (Files.isSymbolicLink(part)) throw new IOException("symbolic_path");
        }
        return target.toFile();
    }

    static void delete(File root, File target, Removed removed) throws IOException {
        Path base = root.toPath().toAbsolutePath().normalize();
        File checked = child(root, base.relativize(target.toPath().toAbsolutePath().normalize()).toString());
        Path path = checked.toPath();
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            File[] children = checked.listFiles();
            if (children == null) throw new IOException("directory_unreadable");
            for (File member : children) delete(root, member, removed);
            remove(path);
        } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            long size = Files.size(path);
            remove(path);
            removed.files++;
            removed.bytes += size;
        } else throw new IOException("unexpected_file_type");
    }

    private static void remove(Path path) throws IOException {
        try { Files.delete(path); }
        catch (IOException error) { throw new IOException("delete_failed", error); }
    }

    static void logArchives(File directory, Set<String> retained, long now, Removed removed)
            throws IOException {
        if (retained.contains("*")) return;
        File checked = child(directory.getParentFile(), directory.getName());
        File[] files = checked.listFiles();
        if (files == null) {
            if (checked.exists()) throw new IOException("directory_unreadable");
            return;
        }
        for (File file : files) {
            if (!file.getName().matches("BYD-HUD-logs-[A-Za-z0-9_-]+\\.zip(?:\\.part|\\.staging|\\.source\\.part)?")
                    || retained.contains(file.getAbsolutePath())) continue;
            File owned = child(checked, file.getName());
            boolean partial = owned.getName().endsWith(".part") || owned.isDirectory();
            if (partial || (owned.lastModified() > 0 && now - owned.lastModified() >= LOG_RETENTION_MS))
                delete(checked, owned, removed);
        }
    }

    private ExportArtifactFiles() { }
}
