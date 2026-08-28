package com.bydhud.gmapsdiag.patcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public final class GmapsPipManifestPatcherTest {
    @Test
    public void patchesOnlyTypedPictureInPictureBoolean() throws Exception {
        byte[] stock = manifest(1, 0x12, 0xffffffff, true);
        GmapsPipManifestPatcher.Result before = GmapsPipManifestPatcher.inspect(stock);

        assertEquals("activity=" + before.activityCount + ", attr=" + before.attributeCount
                + ", true=" + before.booleanTrueCount + ", false=" + before.booleanFalseCount,
                1, before.activityCount);
        assertEquals(1, before.attributeCount);
        assertEquals(1, before.booleanTrueCount);
        assertEquals(GmapsPipManifestPatcher.PATCHABLE, before.classification);
        byte[] patched = GmapsPipManifestPatcher.patch(stock);
        GmapsPipManifestPatcher.Result after = GmapsPipManifestPatcher.inspect(patched);

        assertEquals(GmapsPipManifestPatcher.PATCHED, after.classification);
        assertEquals(before.dataOffset, after.dataOffset);
        for (int i = 0; i < stock.length; i++) {
            if (i >= before.dataOffset && i < before.dataOffset + 4) continue;
            assertEquals("unexpected manifest byte change at " + i, stock[i], patched[i]);
        }
        assertEquals(1, after.activityCount);
        assertEquals(1, after.attributeCount);
        assertEquals(1, after.booleanFalseCount);
    }

    @Test
    public void rejectsDuplicateTargetActivities() throws Exception {
        GmapsPipManifestPatcher.Result result = GmapsPipManifestPatcher.inspect(
                manifest(2, 0x12, 0xffffffff, true));
        assertEquals(GmapsPipManifestPatcher.UNSUPPORTED, result.classification);
        assertThrows(IOException.class, () -> GmapsPipManifestPatcher.patch(
                manifest(2, 0x12, 0xffffffff, true)));
    }

    @Test
    public void rejectsMissingOrUntypedTargetAttribute() throws Exception {
        assertEquals(GmapsPipManifestPatcher.UNSUPPORTED,
                GmapsPipManifestPatcher.inspect(manifest(1, 0x03, 7, true)).classification);
        assertEquals(GmapsPipManifestPatcher.UNSUPPORTED,
                GmapsPipManifestPatcher.inspect(manifest(1, -1, 0, false)).classification);
    }

    @Test
    public void rejectsTargetActivityOutsideApplication() throws Exception {
        GmapsPipManifestPatcher.Result result = GmapsPipManifestPatcher.inspect(
                manifest(1, 0x12, 0xffffffff, true, false, false));
        assertEquals(GmapsPipManifestPatcher.UNSUPPORTED, result.classification);
        assertEquals(1, result.activityCount);
    }

    @Test
    public void rejectsMismatchedEndTag() throws Exception {
        assertThrows(IOException.class, () -> GmapsPipManifestPatcher.inspect(
                manifest(1, 0x12, 0xffffffff, true, true, true)));
    }

    @Test
    public void rejectsMalformedChunkBounds() throws Exception {
        byte[] malformed = manifest(1, 0x12, 0xffffffff, true);
        writeInt(malformed, 8 + 4, 7);
        assertThrows(IOException.class, () -> GmapsPipManifestPatcher.inspect(malformed));
    }

    @Test
    public void recognizesAlreadyDisabledTargetWithoutRewritingIt() throws Exception {
        byte[] patched = manifest(1, 0x12, 0, true);
        GmapsPipManifestPatcher.Result result = GmapsPipManifestPatcher.inspect(patched);
        assertEquals(GmapsPipManifestPatcher.PATCHED, result.classification);
        assertFalse(result.booleanTrueCount > 0);
        assertThrows(IOException.class, () -> GmapsPipManifestPatcher.patch(patched));
    }

    private static byte[] manifest(int activityCopies, int pipType, int pipData,
            boolean includePip) throws IOException {
        return manifest(activityCopies, pipType, pipData, includePip, true, false);
    }

    private static byte[] manifest(int activityCopies, int pipType, int pipData,
            boolean includePip, boolean applicationParent, boolean mismatchedEnd)
            throws IOException {
        List<String> strings = Arrays.asList(
                "http://schemas.android.com/apk/res/android",
                "manifest",
                "application",
                "activity",
                "com.google.android.maps.MapsActivity",
                "name",
                "supportsPictureInPicture",
                "resizeableActivity",
                "true");
        ByteArrayOutputStream xml = new ByteArrayOutputStream();
        writeShort(xml, 0x0003);
        writeShort(xml, 8);
        writeInt(xml, 0);
        writeChunk(xml, stringPool(strings));
        writeStartElement(xml, strings.indexOf("manifest"), new Attribute[0]);
        if (applicationParent) {
            writeStartElement(xml, strings.indexOf("application"), new Attribute[0]);
        }
        for (int i = 0; i < activityCopies; i++) {
            Attribute[] attributes = includePip
                    ? new Attribute[] {
                    new Attribute(0, strings.indexOf("name"), 3,
                            strings.indexOf("com.google.android.maps.MapsActivity")),
                    new Attribute(0, strings.indexOf("resizeableActivity"), 0x12, 0xffffffff),
                    new Attribute(0, strings.indexOf("supportsPictureInPicture"),
                            pipType, pipData)
            } : new Attribute[] {
                    new Attribute(0, strings.indexOf("name"), 3,
                            strings.indexOf("com.google.android.maps.MapsActivity")),
                    new Attribute(0, strings.indexOf("resizeableActivity"), 0x12, 0xffffffff)
            };
            writeStartElement(xml, strings.indexOf("activity"), attributes);
            writeEndElement(xml, strings.indexOf("activity"));
        }
        if (applicationParent) {
            writeEndElement(xml, strings.indexOf("application"));
        }
        writeEndElement(xml, mismatchedEnd
                ? strings.indexOf("activity") : strings.indexOf("manifest"));
        byte[] result = xml.toByteArray();
        writeInt(result, 4, result.length);
        return result;
    }

    private static byte[] stringPool(List<String> strings) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] offsets = new int[strings.size()];
        for (int i = 0; i < strings.size(); i++) {
            offsets[i] = data.size();
            byte[] value = strings.get(i).getBytes(StandardCharsets.UTF_8);
            data.write(value.length);
            data.write(value.length);
            data.write(value);
            data.write(0);
        }
        int headerSize = 28;
        int stringsStart = headerSize + offsets.length * 4;
        int size = stringsStart + data.size();
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        writeShort(result, 0x0001);
        writeShort(result, headerSize);
        writeInt(result, size);
        writeInt(result, strings.size());
        writeInt(result, 0);
        writeInt(result, 0x100);
        writeInt(result, stringsStart);
        writeInt(result, 0);
        for (int offset : offsets) writeInt(result, offset);
        result.write(data.toByteArray());
        return result.toByteArray();
    }

    private static void writeStartElement(ByteArrayOutputStream output, int name,
            Attribute[] attributes) throws IOException {
        int size = 36 + attributes.length * 20;
        writeShort(output, 0x0102);
        writeShort(output, 16);
        writeInt(output, size);
        writeInt(output, 1);
        writeInt(output, -1);
        writeInt(output, -1);
        writeInt(output, name);
        writeShort(output, 20);
        writeShort(output, 20);
        writeShort(output, attributes.length);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, 0);
        for (Attribute attribute : attributes) {
            writeInt(output, attribute.namespace);
            writeInt(output, attribute.name);
            writeInt(output, -1);
            writeShort(output, 8);
            output.write(0);
            output.write(attribute.type);
            writeInt(output, attribute.data);
        }
    }

    private static void writeEndElement(ByteArrayOutputStream output, int name)
            throws IOException {
        writeShort(output, 0x0103);
        writeShort(output, 16);
        writeInt(output, 24);
        writeInt(output, 1);
        writeInt(output, -1);
        writeInt(output, -1);
        writeInt(output, name);
    }

    private static void writeChunk(ByteArrayOutputStream output, byte[] chunk)
            throws IOException {
        output.write(chunk);
    }

    private static void writeShort(ByteArrayOutputStream output, int value) {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
    }

    private static void writeInt(ByteArrayOutputStream output, int value) {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 24) & 0xff);
    }

    private static void writeInt(byte[] output, int offset, int value) {
        output[offset] = (byte) value;
        output[offset + 1] = (byte) (value >>> 8);
        output[offset + 2] = (byte) (value >>> 16);
        output[offset + 3] = (byte) (value >>> 24);
    }

    private static final class Attribute {
        final int namespace;
        final int name;
        final int type;
        final int data;

        Attribute(int namespace, int name, int type, int data) {
            this.namespace = namespace;
            this.name = name;
            this.type = type;
            this.data = data;
        }
    }
}
