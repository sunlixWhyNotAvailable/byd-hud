package com.bydhud.app;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;

/** Writes one already-ordered file group with a single open/close pair. */
final class JournalTextBatch {
    private JournalTextBatch() { }

    static int byteCount(String line) {
        return line.getBytes(Charset.defaultCharset()).length;
    }

    static boolean append(File file, List<String> lines) throws IOException {
        if (file == null || lines == null || lines.isEmpty()) return false;
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("journal directory unavailable: " + parent);
        }
        try {
            try (FileWriter writer = new FileWriter(file, true)) {
                for (String line : lines) writer.write(line);
            }
        } finally {
            NavigationLogStorage.markStorageFileChanged(file);
        }
        return true;
    }
}
