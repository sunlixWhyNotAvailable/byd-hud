package com.bydhud.gmapsdiag.patcher;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Structural guard and minimal rewrite for the Google Maps PiP manifest flag. */
public final class GmapsPipManifestPatcher {
    public static final String PATCHABLE = "PATCHABLE_STOCK";
    public static final String PATCHED = "PICTURE_IN_PICTURE_DISABLED";
    public static final String UNSUPPORTED = "UNSUPPORTED";

    private static final int RES_XML_TYPE = 0x0003;
    private static final int RES_STRING_POOL_TYPE = 0x0001;
    private static final int RES_XML_START_NAMESPACE_TYPE = 0x0100;
    private static final int RES_XML_END_NAMESPACE_TYPE = 0x0101;
    private static final int RES_XML_START_ELEMENT_TYPE = 0x0102;
    private static final int RES_XML_END_ELEMENT_TYPE = 0x0103;
    private static final int RES_XML_CDATA_TYPE = 0x0104;
    private static final int RES_XML_RESOURCE_MAP_TYPE = 0x0180;
    private static final int TYPE_BOOLEAN = 0x12;
    private static final int ANDROID_BOOLEAN_TRUE = 0xffffffff;
    private static final String ANDROID_URI = "http://schemas.android.com/apk/res/android";
    private static final String ACTIVITY_ELEMENT = "activity";
    private static final String NAME_ATTRIBUTE = "name";
    private static final String TARGET_ACTIVITY = "com.google.android.maps.MapsActivity";
    private static final String TARGET_ATTRIBUTE = "supportsPictureInPicture";

    private GmapsPipManifestPatcher() {
    }

    public static Result inspect(byte[] manifest) throws IOException {
        if (manifest == null || manifest.length < 8) {
            throw new IOException("Binary manifest is empty");
        }
        BinaryXml xml = new BinaryXml(manifest);
        int activityCount = 0;
        int directActivityCount = 0;
        int targetNameCount = 0;
        int attributeCount = 0;
        int booleanTrueCount = 0;
        int booleanFalseCount = 0;
        int valueOffset = -1;
        for (Element element : xml.elements) {
            if (!ACTIVITY_ELEMENT.equals(element.name)) continue;
            int elementTargetNameCount = targetActivityNameCount(element.attributes);
            if (elementTargetNameCount == 0) continue;
            activityCount++;
            targetNameCount += elementTargetNameCount;
            if (!"application".equals(element.parent)) continue;
            directActivityCount++;
            for (Attribute attribute : element.attributes) {
                if (!ANDROID_URI.equals(attribute.namespace)
                        || !TARGET_ATTRIBUTE.equals(attribute.name)) continue;
                attributeCount++;
                if (attribute.type == TYPE_BOOLEAN && attribute.data == ANDROID_BOOLEAN_TRUE) {
                    booleanTrueCount++;
                    valueOffset = attribute.dataOffset;
                } else if (attribute.type == TYPE_BOOLEAN && attribute.data == 0) {
                    booleanFalseCount++;
                    valueOffset = attribute.dataOffset;
                }
            }
        }
        String classification;
        if (activityCount == 1 && directActivityCount == 1 && targetNameCount == 1
                && attributeCount == 1
                && booleanTrueCount == 1
                && booleanFalseCount == 0) {
            classification = PATCHABLE;
        } else if (activityCount == 1 && directActivityCount == 1 && targetNameCount == 1
                && attributeCount == 1
                && booleanFalseCount == 1
                && booleanTrueCount == 0) {
            classification = PATCHED;
        } else {
            classification = UNSUPPORTED;
            valueOffset = -1;
        }
        return new Result(classification, activityCount, attributeCount,
                booleanTrueCount, booleanFalseCount, valueOffset);
    }

    private static int targetActivityNameCount(List<Attribute> attributes) {
        int count = 0;
        for (Attribute attribute : attributes) {
            if (ANDROID_URI.equals(attribute.namespace)
                    && NAME_ATTRIBUTE.equals(attribute.name)
                    && TARGET_ACTIVITY.equals(attribute.value)) count++;
        }
        return count;
    }

    public static byte[] patch(byte[] manifest) throws IOException {
        Result before = inspect(manifest);
        if (!PATCHABLE.equals(before.classification)) {
            throw new IOException("PiP manifest target is " + before.classification);
        }
        if (before.dataOffset < 0 || before.dataOffset + 4 > manifest.length) {
            throw new IOException("PiP manifest boolean data offset is invalid");
        }
        byte[] output = manifest.clone();
        writeInt(output, before.dataOffset, 0);
        Result after = inspect(output);
        if (!PATCHED.equals(after.classification)) {
            throw new IOException("PiP manifest post-verification failed");
        }
        return output;
    }

    public static final class Result {
        public final String classification;
        public final int activityCount;
        public final int attributeCount;
        public final int booleanTrueCount;
        public final int booleanFalseCount;
        public final int dataOffset;

        Result(String classification, int activityCount, int attributeCount,
                int booleanTrueCount, int booleanFalseCount, int dataOffset) {
            this.classification = classification;
            this.activityCount = activityCount;
            this.attributeCount = attributeCount;
            this.booleanTrueCount = booleanTrueCount;
            this.booleanFalseCount = booleanFalseCount;
            this.dataOffset = dataOffset;
        }
    }

    private static final class Attribute {
        final String namespace;
        final String name;
        final int type;
        final int data;
        final int dataOffset;
        final String value;

        Attribute(String namespace, String name, int type, int data, int dataOffset,
                String value) {
            this.namespace = namespace;
            this.name = name;
            this.type = type;
            this.data = data;
            this.dataOffset = dataOffset;
            this.value = value;
        }
    }

    private static final class Element {
        final String name;
        final String parent;
        final List<Attribute> attributes;

        Element(String name, String parent, List<Attribute> attributes) {
            this.name = name;
            this.parent = parent;
            this.attributes = attributes;
        }
    }

    private static final class BinaryXml {
        final byte[] bytes;
        final StringPool strings;
        final List<Element> elements = new ArrayList<>();

        BinaryXml(byte[] bytes) throws IOException {
            this.bytes = bytes;
            if (u16(0) != RES_XML_TYPE || u16(2) != 8) {
                throw new IOException("Invalid binary XML header");
            }
            int totalSize = u32(4);
            if (totalSize != bytes.length || totalSize < 8) {
                throw new IOException("Invalid binary XML size");
            }
            int offset = 8;
            StringPool found = null;
            while (offset < bytes.length) {
                Chunk chunk = chunk(offset);
                if (chunk.type == RES_STRING_POOL_TYPE) {
                    if (found != null) throw new IOException("Duplicate string pool");
                    found = new StringPool(bytes, offset, chunk);
                }
                offset += chunk.size;
            }
            if (found == null) throw new IOException("Binary XML string pool is missing");
            strings = found;
            parseElements();
        }

        private void parseElements() throws IOException {
            int offset = 8;
            List<String> stack = new ArrayList<>();
            boolean rootSeen = false;
            boolean rootClosed = false;
            while (offset < bytes.length) {
                Chunk chunk = chunk(offset);
                if (chunk.type == RES_XML_START_ELEMENT_TYPE) {
                    if (chunk.headerSize < 16 || chunk.size < 36) {
                        throw new IOException("Invalid XML start element");
                    }
                    if (stack.isEmpty() && rootClosed) {
                        throw new IOException("Multiple binary XML roots");
                    }
                    int attributeStart = u16(offset + 24);
                    int attributeSize = u16(offset + 26);
                    int attributeCount = u16(offset + 28);
                    if (attributeStart < 20 || attributeSize < 20) {
                        throw new IOException("Invalid XML attribute layout");
                    }
                    long attributesOffsetLong = (long) offset + 16L + attributeStart;
                    long attributesEnd = attributesOffsetLong
                            + (long) attributeSize * attributeCount;
                    long chunkEnd = (long) offset + chunk.size;
                    if (attributesOffsetLong < (long) offset + 36L
                            || attributesEnd > chunkEnd) {
                        throw new IOException("XML attributes exceed element chunk");
                    }
                    int attributesOffset = (int) attributesOffsetLong;
                    String name = strings.get(u32(offset + 20));
                    String parent = stack.isEmpty() ? "" : stack.get(stack.size() - 1);
                    if (!rootSeen) {
                        if (!"manifest".equals(name) || !parent.isEmpty()) {
                            throw new IOException("Binary XML root is not manifest");
                        }
                        rootSeen = true;
                    } else if (stack.isEmpty()) {
                        throw new IOException("Binary XML element is outside root");
                    }
                    if ("application".equals(name) && !"manifest".equals(parent)) {
                        throw new IOException("Application is not directly under manifest");
                    }
                    List<Attribute> attributes = new ArrayList<>();
                    for (int i = 0; i < attributeCount; i++) {
                        int attributeOffset = attributesOffset + i * attributeSize;
                        int namespaceIndex = u32(attributeOffset);
                        int nameIndex = u32(attributeOffset + 4);
                        int type = bytes[attributeOffset + 15] & 0xff;
                        int dataOffset = attributeOffset + 16;
                        attributes.add(new Attribute(
                                strings.get(namespaceIndex), strings.get(nameIndex), type,
                                u32(dataOffset), dataOffset,
                                type == 3 ? strings.get(u32(dataOffset)) : ""));
                    }
                    elements.add(new Element(name, parent, attributes));
                    stack.add(name);
                } else if (chunk.type == RES_XML_END_ELEMENT_TYPE) {
                    if (chunk.headerSize < 16 || chunk.size < 24 || stack.isEmpty()) {
                        throw new IOException("Invalid XML end element");
                    }
                    String name = strings.get(u32(offset + 20));
                    String expected = stack.get(stack.size() - 1);
                    if (!expected.equals(name)) {
                        throw new IOException("Mismatched XML end element");
                    }
                    stack.remove(stack.size() - 1);
                    if (stack.isEmpty()) rootClosed = true;
                } else if (chunk.type == RES_STRING_POOL_TYPE
                        || chunk.type == RES_XML_START_NAMESPACE_TYPE
                        || chunk.type == RES_XML_END_NAMESPACE_TYPE
                        || chunk.type == RES_XML_CDATA_TYPE
                        || chunk.type == RES_XML_RESOURCE_MAP_TYPE) {
                    // Valid non-element XML chunks are structurally checked by chunk().
                } else {
                    throw new IOException("Unknown binary XML chunk type");
                }
                offset += chunk.size;
            }
            if (!rootSeen || !rootClosed || !stack.isEmpty()) {
                throw new IOException("Incomplete binary XML hierarchy");
            }
        }

        private Chunk chunk(int offset) throws IOException {
            if (offset < 0 || offset > bytes.length - 8) {
                throw new IOException("Truncated binary XML chunk");
            }
            int type = u16(offset);
            int headerSize = u16(offset + 2);
            int size = u32(offset + 4);
            long end = (long) offset + (long) size;
            if (headerSize < 8 || size < headerSize || size <= 0 || end > bytes.length) {
                throw new IOException("Invalid binary XML chunk size");
            }
            if (type == RES_XML_START_NAMESPACE_TYPE || type == RES_XML_END_NAMESPACE_TYPE) {
                if (headerSize < 16 || size < 24) {
                    throw new IOException("Invalid XML namespace chunk");
                }
            } else if (type == RES_XML_CDATA_TYPE && (headerSize < 16 || size < 28)) {
                throw new IOException("Invalid XML CDATA chunk");
            } else if (type == RES_XML_RESOURCE_MAP_TYPE
                    && (size < headerSize || ((size - headerSize) & 3) != 0)) {
                throw new IOException("Invalid XML resource map chunk");
            }
            return new Chunk(type, headerSize, size);
        }

        private int u16(int offset) {
            return ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xffff;
        }

        private int u32(int offset) {
            return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        }
    }

    private static final class Chunk {
        final int type;
        final int headerSize;
        final int size;

        Chunk(int type, int headerSize, int size) {
            this.type = type;
            this.headerSize = headerSize;
            this.size = size;
        }
    }

    private static final class StringPool {
        final byte[] bytes;
        final int offset;
        final int stringCount;
        final int stringsStart;
        final int poolEnd;
        final boolean utf8;
        final int offsetsStart;

        StringPool(byte[] bytes, int offset, Chunk chunk) throws IOException {
            if (chunk.headerSize < 28 || chunk.size < 28) {
                throw new IOException("Invalid binary XML string pool");
            }
            this.bytes = bytes;
            this.offset = offset;
            stringCount = u32(offset + 8);
            int flags = u32(offset + 16);
            stringsStart = u32(offset + 20);
            utf8 = (flags & 0x100) != 0;
            offsetsStart = offset + 28;
            long end = (long) offset + chunk.size;
            long offsetsEnd = (long) offsetsStart + (long) stringCount * 4L;
            long stringDataStart = (long) offset + stringsStart;
            poolEnd = (int) end;
            if (stringCount < 0 || offsetsEnd > end || stringsStart < 28
                    || stringDataStart < offsetsEnd || stringDataStart > end) {
                throw new IOException("Invalid binary XML string pool bounds");
            }
        }

        String get(int index) throws IOException {
            if (index == -1) return "";
            if (index < 0 || index >= stringCount) throw new IOException("Invalid string index");
            int stringOffset = u32(offsetsStart + index * 4);
            long startLong = (long) offset + stringsStart + stringOffset;
            if (stringOffset < 0 || startLong < (long) offset + stringsStart
                    || startLong >= poolEnd) {
                throw new IOException("Invalid string offset");
            }
            int start = (int) startLong;
            return utf8 ? readUtf8(start) : readUtf16(start);
        }

        private String readUtf8(int start) throws IOException {
            Length utf16Length = length8(start);
            Length byteLength = length8(start + utf16Length.bytes);
            long dataStartLong = (long) start + utf16Length.bytes + byteLength.bytes;
            long end = dataStartLong + byteLength.value;
            if (dataStartLong < start || end >= poolEnd) {
                throw new IOException("Invalid UTF-8 string bounds");
            }
            if (bytes[(int) end] != 0) throw new IOException("Missing UTF-8 terminator");
            int dataStart = (int) dataStartLong;
            return new String(bytes, dataStart, byteLength.value, StandardCharsets.UTF_8);
        }

        private String readUtf16(int start) throws IOException {
            if ((long) start + 2L > poolEnd) {
                throw new IOException("Invalid UTF-16 string length");
            }
            int length = u16(start);
            int lengthBytes = 2;
            if ((length & 0x8000) != 0) {
                if ((long) start + 4L > poolEnd) {
                    throw new IOException("Invalid UTF-16 string length");
                }
                length = ((length & 0x7fff) << 16) | u16(start + 2);
                lengthBytes = 4;
            }
            int dataStart = start + lengthBytes;
            long byteLength = (long) length * 2L;
            if (byteLength > Integer.MAX_VALUE
                    || (long) dataStart + byteLength + 2L > poolEnd) {
                throw new IOException("Invalid UTF-16 string bounds");
            }
            if (u16((int) ((long) dataStart + byteLength)) != 0) {
                throw new IOException("Missing UTF-16 terminator");
            }
            return new String(bytes, dataStart, (int) byteLength, StandardCharsets.UTF_16LE);
        }

        private Length length8(int start) throws IOException {
            if (start < offset || start >= poolEnd) throw new IOException("Invalid UTF-8 length");
            int first = bytes[start] & 0xff;
            if ((first & 0x80) == 0) return new Length(first, 1);
            if (start + 1 >= poolEnd) throw new IOException("Invalid UTF-8 length");
            return new Length(((first & 0x7f) << 8) | (bytes[start + 1] & 0xff), 2);
        }

        private int u16(int position) {
            return ByteBuffer.wrap(bytes, position, 2)
                    .order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xffff;
        }

        private int u32(int position) {
            return ByteBuffer.wrap(bytes, position, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).getInt();
        }
    }

    private static final class Length {
        final int value;
        final int bytes;

        Length(int value, int bytes) {
            this.value = value;
            this.bytes = bytes;
        }
    }

    private static void writeInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
    }
}
