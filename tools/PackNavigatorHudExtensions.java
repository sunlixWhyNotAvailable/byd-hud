package com.bydhud.app;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.builder.*;
import org.jf.dexlib2.builder.instruction.*;
import org.jf.dexlib2.dexbacked.*;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.*;
import org.jf.dexlib2.immutable.*;

/** Packages only compiled first-party classes. Never accepts or reads a navigator APK. */
public final class PackNavigatorHudExtensions {
    private static String bundle(Properties props, boolean accepted) throws IOException {
        Map<String,String> values = new TreeMap<>();
        for (String kind : Arrays.asList("class", "resource")) {
            int count = Integer.parseInt(props.getProperty(kind + ".count"));
            for (int index=0;index<count;index++) {
                String prefix=kind+"."+index+".";
                String value=props.getProperty(prefix+(accepted?"equivalent":"current"),props.getProperty(prefix+"current"));
                if(!value.isEmpty())values.put(kind+":"+props.getProperty(prefix+(kind.equals("class")?"type":"entry")),value);
            }
        }
        return NavigatorHudProfile.bundleHash(values);
    }
    private static ClassDef mapsCapture(ClassDef cls) {
        List<Method> methods = new ArrayList<>();
        for (Method method : cls.getMethods()) {
            String key = NavigatorHudProfile.key(method);
            String replacement = GmapsHudTransform.replaced.get(key);
            if (replacement != null) {
                MutableMethodImplementation body = new MutableMethodImplementation(GmapsHudTransform.words(method));
                body.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, 0,
                        GmapsHudTransform.words(method), GmapsHudTransform.ref(GmapsHudTransform.HELPER,
                        replacement, method.getParameterTypes(), "V")));
                body.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
                methods.add(GmapsHudTransform.method(method, method.getName(), body));
            } else if (key.equals(GmapsHudTransform.CAP + "->poll()V")) {
                MutableMethodImplementation body = new MutableMethodImplementation(method.getImplementation());
                int count = 0;
                for (int index = 0; index < body.getInstructions().size(); index++) {
                    Instruction instruction = body.getInstructions().get(index);
                    if (!(instruction instanceof ReferenceInstruction)) continue;
                    Object ref = ((ReferenceInstruction) instruction).getReference();
                    if (!(ref instanceof MethodReference) || !NavigatorHudProfile.key((MethodReference) ref).equals(
                            "Landroid/content/ContentResolver;->call(Landroid/net/Uri;Ljava/lang/String;Ljava/lang/String;Landroid/os/Bundle;)Landroid/os/Bundle;")) continue;
                    List<CharSequence> params = new ArrayList<>();
                    params.add("Landroid/content/ContentResolver;"); params.addAll(((MethodReference) ref).getParameterTypes());
                    body.replaceInstruction(index, GmapsHudTransform.replaceInvoke(instruction, GmapsHudTransform.ref(
                            GmapsHudTransform.JOURNAL, "poll", params, "Landroid/os/Bundle;")));
                    count++;
                }
                if (count != 1) throw new IllegalStateException("Source-built capture poll ABI changed");
                methods.add(GmapsHudTransform.method(method, method.getName(), body));
            } else methods.add(method);
        }
        return new ImmutableClassDef(cls.getType(), cls.getAccessFlags(), cls.getSuperclass(), cls.getInterfaces(),
                cls.getSourceFile(), cls.getAnnotations(), cls.getFields(), methods);
    }

    private static void entry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(name); entry.setTime(0); zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry();
    }

    private static byte[] metadata(Properties props) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        props.store(encoded, null);
        // Keep Properties escaping, but omit its timestamp and stabilize key ordering.
        return encoded.toString(StandardCharsets.ISO_8859_1).lines()
                .filter(line -> !line.startsWith("#")).sorted()
                .collect(Collectors.joining("\n", "", "\n"))
                .getBytes(StandardCharsets.ISO_8859_1);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4 || args.length > 5) throw new IllegalArgumentException("family guards.properties compiled.dex output.zip [compiled-native.so]");
        String family = args[0]; Properties props = new Properties();
        try (InputStream in = Files.newInputStream(Path.of(args[1]))) { props.load(in); }
        Map<String, ClassDef> compiled = new TreeMap<>();
        for (ClassDef cls : new DexBackedDexFile(Opcodes.forApi(35), Files.readAllBytes(Path.of(args[2]))).getClasses()) {
            if (!NavigatorHudProfile.owned(cls.getType())) throw new IOException("Non-owned extension class " + cls.getType());
            compiled.put(cls.getType(), family.equals("gmaps") ? mapsCapture(cls) : cls);
        }
        int count = Integer.parseInt(props.getProperty("class.count"));
        Set<String> known = new HashSet<>();
        for (int index = 0; index < count; index++) {
            String prefix = "class." + index + ".", type = props.getProperty(prefix + "type"); known.add(type);
            String previous = props.getProperty(prefix + "current");
            props.setProperty(prefix + "equivalent", previous);
            ClassDef cls = compiled.get(type);
            props.setProperty(prefix + "current", cls == null ? "" : NavigatorHudProfile.sha(NavigatorHudProfile.dex(Collections.singletonList(cls))));
        }
        for (ClassDef cls : compiled.values()) if (!known.contains(cls.getType())) {
            String prefix = "class." + count++ + ".";
            props.setProperty(prefix + "type", cls.getType()); props.setProperty(prefix + "absent", "true");
            props.setProperty(prefix + "equivalent", "");
            props.setProperty(prefix + "current", NavigatorHudProfile.sha(NavigatorHudProfile.dex(Collections.singletonList(cls))));
        }
        props.setProperty("class.count", Integer.toString(count));
        byte[] dex = NavigatorHudProfile.dex(compiled.values());
        props.setProperty("payload.sha256", NavigatorHudProfile.sha(dex));
        byte[] nativeCode = null;
        if (family.equals("waze")) {
            nativeCode = Files.readAllBytes(Path.of(args[4]));
            props.setProperty("resource.0.equivalent", props.getProperty("resource.0.current"));
            props.setProperty("resource.0.current", NavigatorHudProfile.sha(nativeCode));
        }
        props.setProperty("bundle.accepted", bundle(props, true));
        props.setProperty("bundle.compiled", bundle(props, false));
        Path output = Path.of(args[3]); Files.createDirectories(output.toAbsolutePath().getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output))) {
            entry(zip, "payload.dex", dex);
            if (nativeCode != null) entry(zip, "resource-0", nativeCode);
            entry(zip, "profile.properties", metadata(props));
        }
        System.out.println(family + ": compiled first-party classes=" + compiled.size() + ", dexBytes=" + dex.length);
    }
}
