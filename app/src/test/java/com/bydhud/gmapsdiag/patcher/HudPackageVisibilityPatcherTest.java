package com.bydhud.gmapsdiag.patcher;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;
import com.bydhud.gmapsdiag.patcher.GmapsPipManifestPatcher.BinaryXml;

public class HudPackageVisibilityPatcherTest {
    @Test public void appendsQueryWithoutChangingOldIndicesOrOtherElements() throws Exception {
        for (boolean utf8 : new boolean[]{false, true}) {
            for (boolean queries : new boolean[]{false, true}) {
                byte[] input = manifest(utf8, queries);
                assertFalse(HudPackageVisibilityPatcher.hasQuery(input));
                byte[] output = HudPackageVisibilityPatcher.patch(input);
                assertTrue(HudPackageVisibilityPatcher.hasQuery(output));
                assertArrayEquals(output, HudPackageVisibilityPatcher.patch(output));
                BinaryXml before = new BinaryXml(input), after = new BinaryXml(output);
                for (int i = 0; i < before.strings.stringCount; i++) {
                    assertEquals(before.strings.get(i), after.strings.get(i));
                }
                assertEquals(before.elements.size() + (queries ? 1 : 2), after.elements.size());
                assertEquals(1, after.elements.stream().filter(e -> e.name.equals("queries")).count());
                assertEquals(queries ? 2 : 1,
                        after.elements.stream().filter(e -> e.name.equals("package")).count());
                // Existing resources and all nodes remain byte-identical and in order.
                int next = 8;
                for (int offset = 8; offset < input.length;) {
                    var chunk = before.chunk(offset);
                    if (chunk.type != 1) {
                        byte[] expected = java.util.Arrays.copyOfRange(input, offset, offset + chunk.size);
                        boolean found = false;
                        while (next < output.length) {
                            var candidate = after.chunk(next);
                            byte[] actual = java.util.Arrays.copyOfRange(output, next, next + candidate.size);
                            next += candidate.size;
                            if (java.util.Arrays.equals(expected, actual)) { found = true; break; }
                        }
                        assertTrue("Original chunk changed at " + offset, found);
                    }
                    offset += chunk.size;
                }
            }
        }
    }

    @Test public void refusesMalformedOrUnanchoredManifests() throws Exception {
        assertThrows(IOException.class, () -> HudPackageVisibilityPatcher.patch(new byte[2]));
        byte[] input = manifest(true, false);
        BinaryXml xml = new BinaryXml(input);
        for (int offset = 8; offset < input.length;) {
            var chunk = xml.chunk(offset);
            if (chunk.type == 0x180) {
                ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN).putInt(offset + 8, 0);
                break;
            }
            offset += chunk.size;
        }
        assertThrows(IOException.class, () -> HudPackageVisibilityPatcher.patch(input));
    }

    private static byte[] manifest(boolean utf8, boolean queries) throws Exception {
        List<String> strings = List.of("name", "http://schemas.android.com/apk/res/android",
                "android", "manifest", "application", "queries", "package", "other.app", "Мапа");
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        ByteBuffer offsets = buffer(strings.size() * 4);
        for (String value : strings) {
            offsets.putInt(data.size());
            byte[] encoded = value.getBytes(utf8 ? StandardCharsets.UTF_8 : StandardCharsets.UTF_16LE);
            data.write(value.length()); data.write(utf8 ? encoded.length : 0);
            data.write(encoded); data.write(0); if (!utf8) data.write(0);
        }
        while (data.size() % 4 != 0) data.write(0);
        int dataStart = 28 + offsets.capacity();
        ByteBuffer pool = buffer(dataStart + data.size());
        pool.putShort((short) 1).putShort((short) 28).putInt(pool.capacity());
        pool.putInt(strings.size()).putInt(0).putInt(utf8 ? 0x100 : 0).putInt(dataStart).putInt(0);
        pool.put(offsets.array()).put(data.toByteArray());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(buffer(8).putShort((short) 3).putShort((short) 8).putInt(0).array());
        out.write(pool.array());
        out.write(buffer(12).putShort((short) 0x180).putShort((short) 8)
                .putInt(12).putInt(0x01010003).array());
        out.write(buffer(24).putShort((short) 0x100).putShort((short) 16).putInt(24)
                .putInt(1).putInt(-1).putInt(2).putInt(1).array());
        out.write(node(true, 3, false));
        if (queries) {
            out.write(node(true, 5, false)); out.write(node(true, 6, true));
            out.write(node(false, 6, false)); out.write(node(false, 5, false));
        }
        out.write(node(true, 4, false)); out.write(node(false, 4, false));
        out.write(node(false, 3, false));
        out.write(buffer(24).putShort((short) 0x101).putShort((short) 16).putInt(24)
                .putInt(1).putInt(-1).putInt(2).putInt(1).array());
        byte[] result = out.toByteArray();
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).putInt(4, result.length);
        return result;
    }

    private static byte[] node(boolean start, int name, boolean attr) {
        ByteBuffer b = buffer(start ? (attr ? 56 : 36) : 24);
        b.putShort((short) (start ? 0x102 : 0x103)).putShort((short) 16).putInt(b.capacity());
        b.putInt(1).putInt(-1).putInt(-1).putInt(name);
        if (start) {
            b.putShort((short) 20).putShort((short) 20).putShort((short) (attr ? 1 : 0));
            b.putShort((short) 0).putShort((short) 0).putShort((short) 0);
            if (attr) b.putInt(1).putInt(0).putInt(7).putShort((short) 8)
                    .put((byte) 0).put((byte) 3).putInt(7);
        }
        return b.array();
    }

    private static ByteBuffer buffer(int size) { return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN); }
}
