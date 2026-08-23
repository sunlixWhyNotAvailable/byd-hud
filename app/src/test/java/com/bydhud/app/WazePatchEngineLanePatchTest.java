package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Assume;
import org.junit.Test;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.iface.instruction.FiveRegisterInstruction;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.NarrowLiteralInstruction;
import org.jf.dexlib2.iface.instruction.OffsetInstruction;
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction;
import org.jf.dexlib2.iface.instruction.RegisterRangeInstruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.instruction.TwoRegisterInstruction;
import org.jf.dexlib2.iface.reference.FieldReference;
import org.jf.dexlib2.iface.reference.MethodReference;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class WazePatchEngineLanePatchTest {
    private static final String FIXTURE =
            "references/navigation/waze/patched/5.20.0.1/waze_mod.apk";
    private static final String FRAME = "Lcom/waze/car_lib/s/cf;";
    private static final String PRODUCER = "Lcom/waze/car_lib/s/br;";
    private static final String ADAPTER = "Lcom/waze/i/a;";

    @Test
    public void realStockPatchesToStructuredLanesAndRejectsRepatch() throws Exception {
        Path apk = fixture(FIXTURE);
        Assume.assumeTrue(Files.isRegularFile(apk));
        byte[] stockDex = laneDex(apk);
        WazePatchEngine.LaneInspection stock = WazePatchEngine.inspectLane(stockDex);
        assertEquals(WazePatchEngine.PATCHABLE_STOCK, stock.classification);
        assertTrue(stock.stockShape());

        File output = File.createTempFile("waze-lanes-", ".dex");
        File duplicate = File.createTempFile("waze-lanes-duplicate-", ".dex");
        try {
            WazePatchEngine.patchLanes(stockDex, output);
            byte[] patchedDex = Files.readAllBytes(output.toPath());
            WazePatchEngine.LaneInspection patched = WazePatchEngine.inspectLane(patchedDex);
            assertEquals(WazePatchEngine.ALREADY_PATCHED, patched.classification);
            assertTrue(patched.patchedShape());
            assertEquals(2, patched.frameEqualsFieldCount);
            assertEquals(1, patched.frameHashFieldCount);
            assertProducerFallback(patchedDex);
            assertAdapterLoop(patchedDex);
            assertAngleMapping(patchedDex);

            try {
                WazePatchEngine.patchLanes(patchedDex, duplicate);
                fail("A structured-lane patch must not be applied twice");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("not compatible stock"));
            }
            assertEquals(0L, duplicate.length());
        } finally {
            output.delete();
            duplicate.delete();
        }
    }

    private static void assertProducerFallback(byte[] dex) throws Exception {
        Method producer = method(dex, PRODUCER, "e", 6);
        int emptyToV18 = 0;
        int laneCtor = 0;
        for (Instruction instruction : producer.getImplementation().getInstructions()) {
            if (instruction.getOpcode() == Opcode.SGET_OBJECT
                    && instruction instanceof OneRegisterInstruction
                    && instruction instanceof ReferenceInstruction
                    && ((OneRegisterInstruction) instruction).getRegisterA() == 18
                    && "Ljava/util/Collections;->EMPTY_LIST:Ljava/util/List;".equals(
                    ((ReferenceInstruction) instruction).getReference().toString())) {
                emptyToV18++;
            }
            if (instruction.getOpcode() == Opcode.INVOKE_DIRECT_RANGE
                    && instruction instanceof RegisterRangeInstruction
                    && instruction instanceof ReferenceInstruction
                    && ((ReferenceInstruction) instruction).getReference().toString().contains(
                    FRAME + "-><init>(ILcom/waze/t/b/bw;Landroid/text/SpannableStringBuilder;"
                            + "Ljava/lang/String;Landroid/graphics/Bitmap;Ljava/lang/Integer;"
                            + "Ljava/util/Collection;Ljava/lang/Long;Ljava/util/List;)V")) {
                RegisterRangeInstruction range = (RegisterRangeInstruction) instruction;
                assertEquals(9, range.getStartRegister());
                assertEquals(10, range.getRegisterCount());
                laneCtor++;
            }
        }
        assertEquals(1, emptyToV18);
        assertEquals(1, laneCtor);
    }

    private static void assertAdapterLoop(byte[] dex) throws Exception {
        Method adapter = method(dex, ADAPTER, "d", 2);
        List<? extends Instruction> instructions = list(adapter.getImplementation());
        int frameSave = 0;
        int helper = -1;
        for (int index = 0; index < instructions.size(); index++) {
            Instruction instruction = instructions.get(index);
            if (isCall(instruction, FRAME + "->b()Landroid/graphics/Bitmap;") && index > 0) {
                Instruction previous = instructions.get(index - 1);
                if (previous.getOpcode() == Opcode.MOVE_OBJECT
                        && previous instanceof TwoRegisterInstruction
                        && ((TwoRegisterInstruction) previous).getRegisterA() == 7
                        && ((TwoRegisterInstruction) previous).getRegisterB()
                        == adapter.getImplementation().getRegisterCount() - 2) {
                    frameSave++;
                }
            }
            if (isCall(instruction,
                    ADAPTER + "->h(" + FRAME + ")Ljava/util/List;")) helper = index;
        }
        assertEquals(1, frameSave);
        assertTrue(helper >= 0);
        FiveRegisterInstruction helperCall = (FiveRegisterInstruction) instructions.get(helper);
        assertEquals(1, helperCall.getRegisterCount());
        assertEquals(7, helperCall.getRegisterC());

        Opcode[] loop = {
                Opcode.MOVE_RESULT_OBJECT, Opcode.INVOKE_INTERFACE, Opcode.MOVE_RESULT_OBJECT,
                Opcode.NOP, Opcode.INVOKE_INTERFACE, Opcode.MOVE_RESULT, Opcode.IF_EQZ,
                Opcode.INVOKE_INTERFACE, Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST,
                Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.GOTO
        };
        for (int index = 0; index < loop.length; index++) {
            assertEquals(loop[index], instructions.get(helper + index + 1).getOpcode());
        }
        Instruction back = instructions.get(helper + loop.length);
        assertTrue(back instanceof OffsetInstruction);
        assertTrue(((OffsetInstruction) back).getCodeOffset() < 0);
    }

    private static void assertAngleMapping(byte[] dex) throws Exception {
        Method mapper = method(dex, ADAPTER, "i", 1);
        int[][] cases = {
                {Integer.MIN_VALUE, 1}, {-181, 1}, {-180, 9}, {-179, 7}, {-91, 7},
                {-90, 5}, {-89, 3}, {-1, 3}, {0, 2}, {1, 4}, {89, 4}, {90, 6},
                {91, 8}, {179, 8}, {180, 10}, {181, 1}, {Integer.MAX_VALUE, 1}
        };
        for (int[] value : cases) {
            assertEquals("angle=" + value[0], value[1], runMapper(mapper, value[0]));
        }
    }

    private static int runMapper(Method mapper, int angle) {
        List<? extends Instruction> instructions = list(mapper.getImplementation());
        int[] addresses = new int[instructions.size()];
        Map<Integer, Integer> indices = new HashMap<>();
        int address = 0;
        for (int index = 0; index < instructions.size(); index++) {
            addresses[index] = address;
            indices.put(address, index);
            address += instructions.get(index).getCodeUnits();
        }
        int[] registers = {0, angle};
        for (int pc = 0, steps = 0; steps++ < 80;) {
            Instruction instruction = instructions.get(pc);
            Opcode opcode = instruction.getOpcode();
            if (opcode == Opcode.CONST_16) {
                registers[((OneRegisterInstruction) instruction).getRegisterA()] =
                        ((NarrowLiteralInstruction) instruction).getNarrowLiteral();
                pc++;
            } else if (opcode == Opcode.RETURN) {
                return registers[((OneRegisterInstruction) instruction).getRegisterA()];
            } else {
                boolean branch;
                if (opcode == Opcode.GOTO || opcode == Opcode.GOTO_16
                        || opcode == Opcode.GOTO_32) {
                    branch = true;
                } else if (opcode == Opcode.IF_LTZ || opcode == Opcode.IF_EQZ) {
                    int value = registers[((OneRegisterInstruction) instruction).getRegisterA()];
                    branch = opcode == Opcode.IF_LTZ ? value < 0 : value == 0;
                } else if (opcode == Opcode.IF_LE || opcode == Opcode.IF_EQ
                        || opcode == Opcode.IF_LT || opcode == Opcode.IF_GE) {
                    TwoRegisterInstruction compare = (TwoRegisterInstruction) instruction;
                    int left = registers[compare.getRegisterA()];
                    int right = registers[compare.getRegisterB()];
                    branch = opcode == Opcode.IF_LE ? left <= right
                            : opcode == Opcode.IF_EQ ? left == right
                            : opcode == Opcode.IF_LT ? left < right : left >= right;
                } else {
                    throw new AssertionError("Unexpected mapper opcode " + opcode);
                }
                if (!branch) {
                    pc++;
                    continue;
                }
                Integer target = indices.get(addresses[pc]
                        + ((OffsetInstruction) instruction).getCodeOffset());
                if (target == null) throw new AssertionError("Mapper branch target missing");
                pc = target;
            }
        }
        throw new AssertionError("Mapper did not return for angle=" + angle);
    }

    private static Method method(byte[] dex, String owner, String name, int parameterCount)
            throws IOException {
        DexBackedDexFile file = DexBackedDexFile.fromInputStream(
                Opcodes.forApi(29), new ByteArrayInputStream(dex));
        Method found = null;
        for (ClassDef classDef : file.getClasses()) {
            if (!owner.equals(classDef.getType())) continue;
            for (Method candidate : classDef.getMethods()) {
                if (!name.equals(candidate.getName())
                        || candidate.getParameterTypes().size() != parameterCount) continue;
                if (found != null) throw new IOException("Method is ambiguous: " + owner + name);
                found = candidate;
            }
        }
        if (found == null || found.getImplementation() == null) {
            throw new IOException("Method is missing: " + owner + name);
        }
        return found;
    }

    private static byte[] laneDex(Path apk) throws Exception {
        byte[] found = null;
        try (ZipFile zip = new ZipFile(apk.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().matches("classes(\\d*)\\.dex")) continue;
                byte[] dex = read(zip, entry);
                WazePatchEngine.LaneInspection lane = WazePatchEngine.inspectLane(dex);
                if (lane.frameClassCount == 0 && lane.producerTargetCount == 0
                        && lane.adapterTargetCount == 0) continue;
                if (found != null) throw new IOException("Waze lane DEX is ambiguous");
                found = dex;
            }
        }
        if (found == null) throw new IOException("Waze lane DEX is missing");
        return found;
    }

    private static List<? extends Instruction> list(MethodImplementation implementation) {
        List<Instruction> result = new ArrayList<>();
        implementation.getInstructions().forEach(result::add);
        return result;
    }

    private static boolean isCall(Instruction instruction, String expected) {
        return instruction instanceof ReferenceInstruction
                && ((ReferenceInstruction) instruction).getReference() instanceof MethodReference
                && expected.equals(((ReferenceInstruction) instruction).getReference().toString());
    }

    private static Path fixture(String relative) {
        Path cursor = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (cursor != null) {
            Path candidate = cursor.resolve(relative);
            if (Files.isRegularFile(candidate)) return candidate;
            cursor = cursor.getParent();
        }
        return Paths.get(relative);
    }

    private static byte[] read(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream input = zip.getInputStream(entry);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[128 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }
}
