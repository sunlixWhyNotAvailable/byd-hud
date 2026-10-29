package com.bydhud.app;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.List;

/** Writes one already-ordered file group with a single open/close pair. */
final class JournalTextBatch {
    private JournalTextBatch() { }

    static int byteCount(String line) {
        return line.getBytes(Charset.defaultCharset()).length;
    }

    static boolean append(File file, List<String> lines) throws IOException {
        return append(file, lines, false);
    }

    static boolean append(File file, List<String> lines, boolean durable) throws IOException {
        if (file == null || lines == null || lines.isEmpty()) return false;
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("journal directory unavailable: " + parent);
        }
        try {
            try (FileOutputStream output = new FileOutputStream(file, true);
                    OutputStreamWriter writer = new OutputStreamWriter(output, Charset.defaultCharset())) {
                for (String line : lines) writer.write(line);
                writer.flush();
                if (durable) output.getFD().sync();
            }
        } finally {
            NavigationLogStorage.markStorageFileChanged(file);
        }
        return true;
    }
}
