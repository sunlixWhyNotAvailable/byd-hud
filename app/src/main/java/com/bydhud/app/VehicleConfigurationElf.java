package com.bydhud.app;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

//Reads ELF DT_NEEDED names without loading an entire native library into memory.
final class VehicleConfigurationElf {
    private static final int PT_LOAD = 1;
    private static final int PT_DYNAMIC = 2;
    private static final long DT_NULL = 0L;
    private static final long DT_NEEDED = 1L;
    private static final long DT_STRTAB = 5L;
    private static final long DT_STRSZ = 10L;
    private static final int MAX_NEEDED_NAME_BYTES = 4096;

    private VehicleConfigurationElf() { }

    static List<String> needed(File file) throws IOException {
        if (file == null || !file.isFile()) throw new IOException("ELF file unavailable");
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long fileLength = input.length();
            if (fileLength < 16L) throw new IOException("ELF header truncated");
            byte[] ident = new byte[16];
            input.seek(0L);
            input.readFully(ident);
            if ((ident[0] & 0xff) != 0x7f || ident[1] != 'E' || ident[2] != 'L'
                    || ident[3] != 'F') throw new IOException("ELF magic missing");
            int elfClass = ident[4] & 0xff;
            int data = ident[5] & 0xff;
            if (elfClass != 1 && elfClass != 2) throw new IOException("ELF class unsupported");
            if (data != 1 && data != 2) throw new IOException("ELF byte order unsupported");
            ByteOrder order = data == 1 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
            boolean elf64 = elfClass == 2;
            int headerSize = elf64 ? 64 : 52;
            int phdrSize = elf64 ? 56 : 32;
            int dynamicSize = elf64 ? 16 : 8;
            if (fileLength < headerSize) throw new IOException("ELF header truncated");
            ByteBuffer header = read(input, 0L, headerSize, order);
            long programOffset = elf64 ? unsignedLong(header.getLong(32)) : uint(header.getInt(28));
            int programEntrySize = u16(header.getShort(elf64 ? 54 : 42));
            int programCount = u16(header.getShort(elf64 ? 56 : 44));
            if (programCount == 0) return new ArrayList<>();
            if (programEntrySize < phdrSize) throw new IOException("ELF program header too small");
            long programBytes = multiply(programEntrySize, programCount);
            requireRange(programOffset, programBytes, fileLength, "program headers");

            List<LoadSegment> loads = new ArrayList<>();
            List<DynamicSegment> dynamics = new ArrayList<>();
            for (int i = 0; i < programCount; i++) {
                long entryOffset = programOffset + (long) i * programEntrySize;
                ByteBuffer entry = read(input, entryOffset, phdrSize, order);
                long type = uint(entry.getInt(0));
                long offset;
                long virtualAddress;
                long fileSize;
                if (elf64) {
                    offset = unsignedLong(entry.getLong(8));
                    virtualAddress = unsignedLong(entry.getLong(16));
                    fileSize = unsignedLong(entry.getLong(32));
                } else {
                    offset = uint(entry.getInt(4));
                    virtualAddress = uint(entry.getInt(8));
                    fileSize = uint(entry.getInt(16));
                }
                requireRange(offset, fileSize, fileLength, "program segment");
                if (type == PT_LOAD && fileSize > 0L) {
                    loads.add(new LoadSegment(virtualAddress, offset, fileSize));
                } else if (type == PT_DYNAMIC && fileSize > 0L) {
                    dynamics.add(new DynamicSegment(offset, fileSize));
                }
            }
            if (dynamics.isEmpty()) return new ArrayList<>();

            Set<Long> neededOffsets = new LinkedHashSet<>();
            Long stringAddress = null;
            Long stringSize = null;
            for (DynamicSegment dynamic : dynamics) {
                if (dynamic.fileSize % dynamicSize != 0L) {
                    throw new IOException("ELF dynamic segment truncated");
                }
                long entryCount = dynamic.fileSize / dynamicSize;
                if (entryCount == 0L) throw new IOException("ELF dynamic segment empty");
                boolean terminated = false;
                for (long i = 0L; i < entryCount; i++) {
                    long entryOffset = dynamic.fileOffset + i * dynamicSize;
                    ByteBuffer entry = read(input, entryOffset, dynamicSize, order);
                    long tag;
                    long value;
                    if (elf64) {
                        tag = signedLong(entry.getLong(0));
                        value = unsignedLong(entry.getLong(8));
                    } else {
                        tag = signedInt(entry.getInt(0));
                        value = uint(entry.getInt(4));
                    }
                    if (tag == DT_NULL) {
                        terminated = true;
                        break;
                    }
                    if (tag == DT_NEEDED) {
                        neededOffsets.add(value);
                    } else if (tag == DT_STRTAB) {
                        if (stringAddress != null && stringAddress.longValue() != value) {
                            throw new IOException("ELF DT_STRTAB differs");
                        }
                        stringAddress = value;
                    } else if (tag == DT_STRSZ) {
                        if (stringSize != null && stringSize.longValue() != value) {
                            throw new IOException("ELF DT_STRSZ differs");
                        }
                        stringSize = value;
                    }
                }
                if (!terminated) throw new IOException("ELF dynamic table unterminated");
            }
            if (neededOffsets.isEmpty()) return new ArrayList<>();
            if (stringAddress == null || stringSize == null || stringSize <= 0L) {
                throw new IOException("ELF dynamic string table missing");
            }
            long stringOffset = virtualToFile(loads, stringAddress, fileLength);
            requireRange(stringOffset, stringSize, fileLength, "ELF string table");

            Set<String> names = new LinkedHashSet<>();
            for (long neededOffset : neededOffsets) {
                if (neededOffset < 0L || neededOffset >= stringSize) {
                    throw new IOException("ELF DT_NEEDED offset outside string table");
                }
                String name = readName(input, stringOffset + neededOffset,
                        stringSize - neededOffset, fileLength);
                if (!isSafeDependencyName(name)) {
                    throw new IOException("unsafe ELF dependency name");
                }
                names.add(name);
            }
            return new ArrayList<>(names);
        }
    }

    private static String readName(RandomAccessFile input, long offset, long remaining,
            long fileLength) throws IOException {
        long limit = Math.min(remaining, MAX_NEEDED_NAME_BYTES);
        byte[] bytes = new byte[(int) limit];
        int count = 0;
        while (count < bytes.length) {
            input.seek(offset + count);
            int value = input.read();
            if (value < 0) throw new IOException("ELF dependency string truncated");
            if (value == 0) {
                return new String(bytes, 0, count, StandardCharsets.UTF_8);
            }
            bytes[count++] = (byte) value;
        }
        if (remaining > MAX_NEEDED_NAME_BYTES) {
            throw new IOException("ELF dependency name too long");
        }
        throw new IOException("ELF dependency string missing NUL");
    }

    private static boolean isSafeDependencyName(String name) {
        if (name == null || name.isEmpty() || name.contains("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || name.indexOf('\u0000') >= 0 || name.indexOf('\ufffd') >= 0) return false;
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) return false;
        }
        return true;
    }

    private static long virtualToFile(List<LoadSegment> loads, long address, long fileLength)
            throws IOException {
        for (LoadSegment load : loads) {
            if (address < load.virtualAddress) continue;
            long delta = address - load.virtualAddress;
            if (delta < load.fileSize && delta <= fileLength - load.fileOffset) {
                long offset = load.fileOffset + delta;
                requireRange(offset, 1L, fileLength, "ELF string table address");
                return offset;
            }
        }
        throw new IOException("ELF DT_STRTAB is not file-backed");
    }

    private static ByteBuffer read(RandomAccessFile input, long offset, int length,
            ByteOrder order) throws IOException {
        byte[] bytes = new byte[length];
        input.seek(offset);
        input.readFully(bytes);
        return ByteBuffer.wrap(bytes).order(order);
    }

    private static long multiply(long left, long right) throws IOException {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new IOException("ELF program header range overflow", overflow);
        }
    }

    private static void requireRange(long offset, long length, long fileLength, String label)
            throws IOException {
        if (offset < 0L || length < 0L || offset > fileLength || length > fileLength - offset) {
            throw new IOException("ELF " + label + " outside file");
        }
    }

    private static int u16(short value) { return value & 0xffff; }
    private static long uint(int value) { return value & 0xffffffffL; }
    private static long signedInt(int value) { return value; }

    private static long unsignedLong(long value) throws IOException {
        if (value < 0L) throw new IOException("ELF unsigned value out of range");
        return value;
    }

    private static long signedLong(long value) { return value; }

    private static final class LoadSegment {
        final long virtualAddress;
        final long fileOffset;
        final long fileSize;

        LoadSegment(long virtualAddress, long fileOffset, long fileSize) {
            this.virtualAddress = virtualAddress;
            this.fileOffset = fileOffset;
            this.fileSize = fileSize;
        }
    }

    private static final class DynamicSegment {
        final long fileOffset;
        final long fileSize;

        DynamicSegment(long fileOffset, long fileSize) {
            this.fileOffset = fileOffset;
            this.fileSize = fileSize;
        }
    }
}
