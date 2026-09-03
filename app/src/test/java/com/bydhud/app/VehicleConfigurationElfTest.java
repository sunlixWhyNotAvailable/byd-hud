package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.Test;

public class VehicleConfigurationElfTest {
    @Test
    public void readsDistinctNeededNamesFromElf32LittleEndian() throws Exception {
        File file = writeTemp(elf32(ByteOrder.LITTLE_ENDIAN));
        try {
            assertEquals(Arrays.asList("libfoo.so", "libbar.so"),
                    VehicleConfigurationElf.needed(file));
        } finally {
            file.delete();
        }
    }

    @Test
    public void readsNeededNamesFromElf64BigEndian() throws Exception {
        File file = writeTemp(elf64(ByteOrder.BIG_ENDIAN));
        try {
            assertEquals(Arrays.asList("libalpha.so", "libbeta.so"),
                    VehicleConfigurationElf.needed(file));
        } finally {
            file.delete();
        }
    }

    @Test
    public void rejectsNeededOffsetOutsideStringTable() throws Exception {
        byte[] bytes = elf32(ByteOrder.LITTLE_ENDIAN);
        put32(bytes, 0x180 + 16 + 4, 0x7fffffffL, ByteOrder.LITTLE_ENDIAN);
        File file = writeTemp(bytes);
        try {
            assertThrows(IOException.class, () -> VehicleConfigurationElf.needed(file));
        } finally {
            file.delete();
        }
    }

    @Test
    public void rejectsDependencyStringWithoutTerminatingNul() throws Exception {
        byte[] bytes = elf32(ByteOrder.LITTLE_ENDIAN);
        bytes[0x120 + 30] = 'x';
        File file = writeTemp(bytes);
        try {
            assertThrows(IOException.class, () -> VehicleConfigurationElf.needed(file));
        } finally {
            file.delete();
        }
    }

    private static byte[] elf32(ByteOrder order) {
        byte[] bytes = new byte[0x240];
        ident(bytes, 1, order);
        put16(bytes, 16, 2, order);
        put32(bytes, 28, 52, order);
        put16(bytes, 42, 32, order);
        put16(bytes, 44, 2, order);
        put32(bytes, 52, 1, order);
        put32(bytes, 56, 0, order);
        put32(bytes, 60, 0x400000, order);
        put32(bytes, 68, bytes.length, order);
        put32(bytes, 52 + 32, 2, order);
        put32(bytes, 52 + 36, 0x180, order);
        put32(bytes, 52 + 40, 0x400180, order);
        put32(bytes, 52 + 48, 6 * 8, order);
        byte[] strings = "\0libfoo.so\0libbar.so\0libfoo.so\0"
                .getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(strings, 0, bytes, 0x120, strings.length);
        long stringAddress = 0x400120L;
        put32(bytes, 0x180, 5, order);
        put32(bytes, 0x184, stringAddress, order);
        put32(bytes, 0x188, 10, order);
        put32(bytes, 0x18c, strings.length, order);
        put32(bytes, 0x190, 1, order);
        put32(bytes, 0x194, 1, order);
        put32(bytes, 0x198, 1, order);
        put32(bytes, 0x19c, 11, order);
        put32(bytes, 0x1a0, 1, order);
        put32(bytes, 0x1a4, 21, order);
        put32(bytes, 0x1a8, 0, order);
        put32(bytes, 0x1ac, 0, order);
        return bytes;
    }

    private static byte[] elf64(ByteOrder order) {
        byte[] bytes = new byte[0x280];
        ident(bytes, 2, order);
        put16(bytes, 16, 2, order);
        put64(bytes, 32, 64, order);
        put16(bytes, 54, 56, order);
        put16(bytes, 56, 2, order);
        put32(bytes, 64, 1, order);
        put64(bytes, 72, 0, order);
        put64(bytes, 80, 0x500000, order);
        put64(bytes, 96, bytes.length, order);
        put32(bytes, 64 + 56, 2, order);
        put64(bytes, 64 + 56 + 8, 0x1c0, order);
        put64(bytes, 64 + 56 + 16, 0x5001c0, order);
        put64(bytes, 64 + 56 + 32, 6 * 16, order);
        byte[] strings = "\0libalpha.so\0libbeta.so\0libalpha.so\0"
                .getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(strings, 0, bytes, 0x140, strings.length);
        long stringAddress = 0x500140L;
        put64(bytes, 0x1c0, 5, order);
        put64(bytes, 0x1c8, stringAddress, order);
        put64(bytes, 0x1d0, 10, order);
        put64(bytes, 0x1d8, strings.length, order);
        put64(bytes, 0x1e0, 1, order);
        put64(bytes, 0x1e8, 1, order);
        put64(bytes, 0x1f0, 1, order);
        put64(bytes, 0x1f8, 13, order);
        put64(bytes, 0x200, 1, order);
        put64(bytes, 0x208, 24, order);
        put64(bytes, 0x210, 0, order);
        put64(bytes, 0x218, 0, order);
        return bytes;
    }

    private static void ident(byte[] bytes, int elfClass, ByteOrder order) {
        bytes[0] = 0x7f;
        bytes[1] = 'E';
        bytes[2] = 'L';
        bytes[3] = 'F';
        bytes[4] = (byte) elfClass;
        bytes[5] = (byte) (order == ByteOrder.LITTLE_ENDIAN ? 1 : 2);
        bytes[6] = 1;
    }

    private static File writeTemp(byte[] bytes) throws IOException {
        File file = File.createTempFile("bydhud-elf-", ".bin");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
        }
        return file;
    }

    private static void put16(byte[] bytes, int offset, int value, ByteOrder order) {
        ByteBuffer.wrap(bytes, offset, 2).order(order).putShort((short) value);
    }

    private static void put32(byte[] bytes, int offset, long value, ByteOrder order) {
        ByteBuffer.wrap(bytes, offset, 4).order(order).putInt((int) value);
    }

    private static void put64(byte[] bytes, int offset, long value, ByteOrder order) {
        ByteBuffer.wrap(bytes, offset, 8).order(order).putLong(value);
    }
}
