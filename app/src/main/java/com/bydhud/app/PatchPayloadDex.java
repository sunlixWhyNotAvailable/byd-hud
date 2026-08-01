package com.bydhud.app;

import android.content.Context;

import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.immutable.ImmutableDexFile;
import org.jf.dexlib2.writer.pool.DexPool;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class PatchPayloadDex {
    private PatchPayloadDex() {
    }

    static void extract(Context context, String classPrefix, File output) throws IOException {
        String sourcePath = context.getApplicationInfo().sourceDir;
        List<ClassDef> matches = new ArrayList<>();
        try (ZipFile apk = new ZipFile(sourcePath)) {
            java.util.Enumeration<? extends ZipEntry> entries = apk.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().matches("classes(\\d*)\\.dex")) continue;
                byte[] bytes;
                try (InputStream input = apk.getInputStream(entry)) {
                    bytes = readAll(input);
                }
                DexBackedDexFile dex = DexBackedDexFile.fromInputStream(
                        Opcodes.forApi(29), new ByteArrayInputStream(bytes));
                for (ClassDef classDef : dex.getClasses()) {
                    if (classDef.getType().startsWith(classPrefix)) matches.add(classDef);
                }
            }
        }
        if (matches.isEmpty()) throw new IOException("patch payload missing: " + classPrefix);
        File parent = output.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        DexPool.writeTo(output.getAbsolutePath(),
                new ImmutableDexFile(Opcodes.forApi(29), matches));
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
        return output.toByteArray();
    }
}
