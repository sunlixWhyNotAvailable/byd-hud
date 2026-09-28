package com.bydhud.gmapsdiag.patcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.bydhud.gmapsdiag.patcher.GmapsPipManifestPatcher.BinaryXml;
import com.bydhud.gmapsdiag.patcher.GmapsPipManifestPatcher.Chunk;
import com.bydhud.gmapsdiag.patcher.GmapsPipManifestPatcher.Element;
import com.bydhud.gmapsdiag.patcher.GmapsPipManifestPatcher.Attribute;

/** Adds one explicit package query, retaining all existing string/resource indices. */
public final class HudPackageVisibilityPatcher {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String HUD = "com.bydhud.app";

    private HudPackageVisibilityPatcher() { }

    public static boolean hasQuery(byte[] manifest) throws IOException {
        if (manifest == null || manifest.length < 8 || manifest.length > 8 * 1024 * 1024) {
            throw new IOException("Invalid manifest size for HUD package query");
        }
        BinaryXml xml = new BinaryXml(manifest);
        int groups = 0;
        boolean found = false;
        for (Element element : xml.elements) {
            if ("queries".equals(element.name)) {
                if (!"manifest".equals(element.parent) || ++groups > 1) {
                    throw new IOException("Ambiguous manifest queries hierarchy");
                }
            }
            if ("package".equals(element.name) && "queries".equals(element.parent)) {
                for (Attribute attribute : element.attributes) {
                    if (ANDROID.equals(attribute.namespace) && "name".equals(attribute.name)
                            && HUD.equals(attribute.value)) found = true;
                }
            }
        }
        return found;
    }

    public static byte[] patch(byte[] manifest) throws IOException {
        if (hasQuery(manifest)) return manifest;
        BinaryXml xml = new BinaryXml(manifest);
        List<String> strings = new ArrayList<>();
        for (int i = 0; i < xml.strings.stringCount; i++) strings.add(xml.strings.get(i));
        int namespace = strings.indexOf(ANDROID);
        int name = -1;
        int insertion = -1;
        boolean queries = false;
        boolean androidNamespace = false;
        for (int offset = 8; offset < manifest.length;) {
            Chunk chunk = xml.chunk(offset);
            if (chunk.type == 0x0180) {
                for (int i = chunk.headerSize; i < chunk.size; i += 4) {
                    if (xml.u32(offset + i) == 0x01010003) {
                        name = (i - chunk.headerSize) / 4;
                    }
                }
            } else if (chunk.type == 0x0100 && xml.u32(offset + 20) == namespace) {
                androidNamespace = true;
            } else if (chunk.type == 0x0103) {
                String element = xml.strings.get(xml.u32(offset + 20));
                if ("queries".equals(element)) { insertion = offset; queries = true; }
                if ("manifest".equals(element) && !queries) insertion = offset;
            }
            offset += chunk.size;
        }
        if (namespace < 0 || !androidNamespace || name < 0
                || !"name".equals(xml.strings.get(name)) || insertion < 0) {
            throw new IOException("Missing Android name/namespace anchors for HUD query");
        }
        int queryName = index(strings, "queries");
        int packageName = index(strings, "package");
        int value = index(strings, HUD);
        ByteArrayOutputStream nodes = new ByteArrayOutputStream();
        if (!queries) nodes.write(element(true, queryName, -1, -1, -1));
        nodes.write(element(true, packageName, namespace, name, value));
        nodes.write(element(false, packageName, -1, -1, -1));
        if (!queries) nodes.write(element(false, queryName, -1, -1, -1));
        byte[] pool = appendStrings(xml, strings);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(manifest, 0, 8);
        for (int offset = 8; offset < manifest.length;) {
            Chunk chunk = xml.chunk(offset);
            if (offset == insertion) output.write(nodes.toByteArray());
            if (offset == xml.strings.offset) output.write(pool);
            else output.write(manifest, offset, chunk.size);
            offset += chunk.size;
        }
        byte[] result = output.toByteArray();
        putInt(result, 4, result.length);
        if (!hasQuery(result)) throw new IOException("HUD package query verification failed");
        return result;
    }

    private static int index(List<String> strings, String value) {
        int existing = strings.indexOf(value);
        if (existing >= 0) return existing;
        strings.add(value);
        return strings.size() - 1;
    }

    private static byte[] appendStrings(BinaryXml xml, List<String> strings) throws IOException {
        int start = xml.strings.offset;
        Chunk chunk = xml.chunk(start);
        int oldCount = xml.strings.stringCount;
        int added = strings.size() - oldCount;
        int dataStart = xml.strings.stringsStart;
        int styleCount = xml.u32(start + 12);
        int stylesStart = xml.u32(start + 24);
        int dataEnd = stylesStart == 0 ? chunk.size : stylesStart;
        if (chunk.headerSize != 28 || styleCount < 0
                || 28L + (oldCount + (long) styleCount) * 4 > dataStart
                || dataEnd < dataStart || dataEnd > chunk.size
                || (styleCount > 0 && stylesStart == 0)) {
            throw new IOException("Unsupported string/style pool layout");
        }
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ByteArrayOutputStream offsets = new ByteArrayOutputStream();
        for (int i = oldCount; i < strings.size(); i++) {
            offsets.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(dataEnd - dataStart + encoded.size()).array());
            // Only the three fixed, short ASCII query strings can be appended here.
            String value = strings.get(i);
            encoded.write(value.length());
            encoded.write(xml.strings.utf8 ? value.length() : 0);
            encoded.write(value.getBytes(xml.strings.utf8
                    ? StandardCharsets.UTF_8 : StandardCharsets.UTF_16LE));
            encoded.write(0);
            if (!xml.strings.utf8) encoded.write(0);
        }
        while ((dataEnd + encoded.size()) % 4 != 0) encoded.write(0);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int tableEnd = 28 + oldCount * 4;
        output.write(xml.bytes, start, tableEnd);
        output.write(offsets.toByteArray());
        output.write(xml.bytes, start + tableEnd, dataEnd - tableEnd);
        output.write(encoded.toByteArray());
        output.write(xml.bytes, start + dataEnd, chunk.size - dataEnd);
        byte[] result = output.toByteArray();
        putInt(result, 4, result.length);
        putInt(result, 8, strings.size());
        putInt(result, 16, xml.u32(start + 16) & ~1); // Appending invalidates SORTED.
        putInt(result, 20, dataStart + added * 4);
        if (stylesStart != 0) putInt(result, 24, stylesStart + added * 4 + encoded.size());
        return result;
    }

    private static byte[] element(boolean start, int name, int namespace, int attr, int value) {
        boolean attribute = start && attr >= 0;
        int size = start ? (attribute ? 56 : 36) : 24;
        ByteBuffer out = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        out.putShort((short) (start ? 0x0102 : 0x0103)).putShort((short) 16).putInt(size);
        out.putInt(0).putInt(-1).putInt(-1).putInt(name);
        if (start) {
            out.putShort((short) 20).putShort((short) 20).putShort((short) (attribute ? 1 : 0));
            out.putShort((short) 0).putShort((short) 0).putShort((short) 0);
            if (attribute) {
                out.putInt(namespace).putInt(attr).putInt(value);
                out.putShort((short) 8).put((byte) 0).put((byte) 3).putInt(value);
            }
        }
        return out.array();
    }

    private static void putInt(byte[] bytes, int offset, int value) {
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value);
    }
}
