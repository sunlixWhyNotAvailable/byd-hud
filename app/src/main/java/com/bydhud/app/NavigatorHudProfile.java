package com.bydhud.app;

import com.android.zipflinger.BytesSource;
import com.android.zipflinger.ZipArchive;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.iface.reference.FieldReference;
import org.jf.dexlib2.iface.reference.TypeReference;
import org.jf.dexlib2.immutable.ImmutableClassDef;
import org.jf.dexlib2.immutable.ImmutableDexFile;
import org.jf.dexlib2.writer.io.MemoryDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.*;

/** Exact-version guards plus source-built extensions; original method bodies come only from the input APK. */
final class NavigatorHudProfile {
    static final String REVISION = "navigator-hud-v3-transforms-r5";
    private static final int LIMIT = 64 * 1024 * 1024;
    private static final Map<String, NavigatorHudProfile> CACHE = new HashMap<>();
    final Properties metadata;
    final Map<String, Rule> methods = new TreeMap<>(), classes = new TreeMap<>(), resources = new TreeMap<>();
    final Map<String, byte[]> content;
    private final String family;

    static final class InputMismatch extends IOException {
        InputMismatch(String message) { super(message); }
    }

    static final class Rule {
        final String key, component, current;
        final Set<String> before, equivalent;
        final boolean absent;
        final int resource;
        Rule(Properties p, String prefix, String keyName, String component, int resource) {
            key = p.getProperty(prefix + keyName);
            this.component = p.getProperty(prefix + "component", component);
            current = p.getProperty(prefix + "current");
            before = new HashSet<>(Arrays.asList(p.getProperty(prefix + "before", "").split(",")));
            equivalent = new HashSet<>(Arrays.asList(p.getProperty(prefix + "equivalent", current).split(",")));
            absent = Boolean.parseBoolean(p.getProperty(prefix + "absent"));
            this.resource = resource;
        }
        boolean check(String actual) throws IOException {
            if (current.equals(actual) || equivalent.contains(actual)) return true;
            if (actual.isEmpty() ? absent : before.contains(actual)) return false;
            throw new InputMismatch("Unrecognized " + component + " input: " + key + " sha256=" + actual);
        }
    }

    static final class Result {
        final boolean current;
        final Map<String, ClassDef> definitions = new TreeMap<>();
        final Map<String, File> owners = new HashMap<>();
        final Set<String> dependencies = new HashSet<>();
        File codeApk, nativeApk;
        Result(boolean current) { this.current = current; }
    }

    static boolean supports(String profile, String version) {
        return "waze".equals(profile) && "5.25.0.1".equals(version)
                || "gmaps".equals(profile) && "26.30.09.950492155".equals(version);
    }

    static synchronized NavigatorHudProfile load(String profile, String version) throws IOException {
        if (!supports(profile, version)) throw new IOException("Unsupported HUD profile " + profile + " " + version);
        NavigatorHudProfile cached = CACHE.get(profile);
        if (cached != null) return cached;
        String name = "/navigator-hud/" + profile + ".zip";
        Map<String, byte[]> content = new HashMap<>();
        try (InputStream in = NavigatorHudProfile.class.getResourceAsStream(name)) {
            if (in == null) throw new IOException("Missing packaged HUD profile " + name);
            try (ZipInputStream zip = new ZipInputStream(in)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (content.put(entry.getName(), read(zip, LIMIT)) != null) throw new IOException("Duplicate HUD profile entry " + entry.getName());
                }
            }
        }
        Properties p = new Properties();
        byte[] props = content.get("profile.properties");
        if (props == null) throw new IOException("HUD profile metadata missing");
        p.load(new ByteArrayInputStream(props));
        if (!profile.equals(p.getProperty("family")) || !version.equals(p.getProperty("version"))) throw new IOException("HUD profile identity differs");
        if (!sha(required(content, "payload.dex")).equals(p.getProperty("payload.sha256"))) throw new IOException("HUD payload digest differs");
        NavigatorHudProfile result = new NavigatorHudProfile(profile, p, content);
        for (ClassDef cls : new DexBackedDexFile(Opcodes.forApi(35), required(content, "payload.dex")).getClasses())
            if (!owned(cls.getType())) throw new IOException("Extension contains a navigator class: " + cls.getType());
        CACHE.put(profile, result);
        return result;
    }

    private NavigatorHudProfile(String family, Properties p, Map<String, byte[]> content) throws IOException {
        this.family = family; this.metadata = p; this.content = content;
        addRules(p, "method", "key", "map", methods);
        addRules(p, "class", "type", "map", classes);
        addRules(p, "resource", "entry", "map", resources);
        for (Rule r : resources.values()) {
            String prefix = "resource." + r.resource + ".";
            if (!"transform".equals(p.getProperty(prefix + "format")) && !r.current.equals(sha(required(content, "resource-" + r.resource))))
                throw new IOException("HUD resource digest differs: " + r.key);
        }
    }

    private static void addRules(Properties p, String kind, String key, String component, Map<String, Rule> out) throws IOException {
        int count;
        try { count = Integer.parseInt(p.getProperty(kind + ".count")); }
        catch (RuntimeException e) { throw new IOException("Invalid HUD " + kind + " count", e); }
        if (count < 0 || count > 2048) throw new IOException("HUD rule count exceeds bound");
        for (int i = 0; i < count; i++) {
            Rule r = new Rule(p, kind + "." + i + ".", key, component, i);
            if (r.key == null || r.current == null || out.put(r.key, r) != null) throw new IOException("Invalid/duplicate HUD " + kind + " rule");
        }
    }

    Result inspect(List<File> apks, String component) throws IOException {
        if (apks == null || apks.isEmpty()) throw new IOException("No APK members supplied");
        Result scan = new Result(false);
        Map<String, String> foundMethods = new HashMap<>(), foundClasses = new HashMap<>(), foundResources = new HashMap<>();
        Set<String> paths = new HashSet<>(), fileNames = new HashSet<>();
        boolean engine = false;
        for (File input : apks) {
            File file = input.getCanonicalFile();
            if (!paths.add(file.getPath()) || !fileNames.add(file.getName())) throw new IOException("Duplicate APK member " + file.getName());
            try (ZipFile zip = new ZipFile(file)) {
                Set<String> entries = new HashSet<>();
                for (ZipEntry entry : Collections.list(zip.entries())) {
                    String name = entry.getName();
                    if (!entries.add(name)) throw new IOException("Duplicate ZIP entry " + name);
                    if (name.equals(metadata.getProperty("engine.entry"))) {
                        if (engine || !metadata.getProperty("engine.sha256").equals(streamSha(zip.getInputStream(entry)))) throw new InputMismatch("Navigator native engine differs from the HUD ABI");
                        engine = true; scan.nativeApk = file;
                    }
                    if (component.equals("map") && resources.containsKey(name)) {
                        if (foundResources.put(name, streamSha(zip.getInputStream(entry))) != null) throw new IOException("Duplicate HUD resource " + name);
                        scan.owners.put(name, file);
                    }
                    if (!name.matches("classes\\d*\\.dex")) continue;
                    DexBackedDexFile dex = new DexBackedDexFile(Opcodes.forApi(35), read(zip.getInputStream(entry), LIMIT));
                    for (ClassDef cls : dex.getClasses()) {
                        interrupted();
                        if (firstParty(cls.getType()) && !owned(cls.getType())) {
                            scan.dependencies.add(cls.getType());
                            for (org.jf.dexlib2.iface.Field f : cls.getFields())
                                scan.dependencies.add(f.getDefiningClass() + "->" + f.getName() + ":" + f.getType());
                            for (Method m : cls.getMethods()) scan.dependencies.add(key(m));
                        }
                        boolean own = component.equals("map") && owned(cls.getType());
                        if (own) {
                            if (!classes.containsKey(cls.getType())) throw new InputMismatch("Unknown HUD payload class " + cls.getType());
                            if (foundClasses.put(cls.getType(), sha(dex(Collections.singletonList(cls)))) != null) throw new IOException("Duplicate HUD payload class " + cls.getType());
                            scan.definitions.put(cls.getType(), cls); scan.owners.put(cls.getType(), file);
                        }
                        for (Method m : cls.getMethods()) {
                            Rule rule = methods.get(key(m));
                            if (rule != null && component.equals(rule.component)) {
                                if (foundMethods.put(rule.key, sha(method(m))) != null) throw new IOException("Duplicate HUD method " + rule.key);
                                scan.definitions.put(cls.getType(), cls); scan.owners.put(cls.getType(), file);
                                if (scan.codeApk == null) scan.codeApk = file;
                            } else if (component.equals("map") && !own && callsPayload(m)) {
                                throw new InputMismatch("Unexpected HUD hook owner " + key(m));
                            }
                        }
                    }
                }
            }
        }
        if (!engine) throw new IOException("Navigator native engine missing");
        boolean current = true; int targets = 0;
        for (Rule r : methods.values()) if (component.equals(r.component)) {
            current &= r.check(foundMethods.getOrDefault(r.key, "")); targets++;
        }
        if (targets == 0 || scan.codeApk == null) throw new IOException("Missing " + component + " method targets");
        if (component.equals("map")) {
            for (Rule r : classes.values()) current &= r.check(foundClasses.getOrDefault(r.key, ""));
            for (Rule r : resources.values()) current &= r.check(foundResources.getOrDefault(r.key, ""));
            if (current) {
                Map<String,String> bundle = new TreeMap<>();
                for (Map.Entry<String,String> entry : foundClasses.entrySet()) bundle.put("class:"+entry.getKey(),entry.getValue());
                for (Map.Entry<String,String> entry : foundResources.entrySet()) bundle.put("resource:"+entry.getKey(),entry.getValue());
                String hash = bundleHash(bundle);
                current = hash.equals(metadata.getProperty("bundle.accepted")) || hash.equals(metadata.getProperty("bundle.compiled"));
            }
        }
        Result result = new Result(current);result.definitions.putAll(scan.definitions);result.owners.putAll(scan.owners);
        result.dependencies.addAll(scan.dependencies);
        result.codeApk = scan.codeApk; result.nativeApk = scan.nativeApk;
        if (current) verifyDependencies(result, component);
        return result;
    }

    void patch(List<File> inputs, File outputDir, String component) throws IOException {
        Result before = inspect(inputs, component);
        verifyDependencies(before, component);
        if (outputDir.exists() && Objects.requireNonNull(outputDir.list()).length != 0) throw new IOException("HUD candidate directory must be empty");
        if (!outputDir.isDirectory() && !outputDir.mkdirs()) throw new IOException("Cannot create HUD candidate directory");
        if (before.current) { for (File file : inputs) Files.copy(file.toPath(), new File(outputDir, file.getName()).toPath()); return; }
        Map<String, ClassDef> changed = new TreeMap<>();
        for (ClassDef cls : before.definitions.values()) {
            if (component.equals("map") && owned(cls.getType())) continue;
            ClassDef result = transform(cls, component);
            if (result != cls) changed.put(cls.getType(), result);
        }
        List<ClassDef> payload = new ArrayList<>(changed.values());
        if (component.equals("map")) payload.addAll(new DexBackedDexFile(Opcodes.forApi(35), required(content, "payload.dex")).getClasses());
        byte[] fresh = dex(payload);
        DexBackedDexFile freshDex = new DexBackedDexFile(Opcodes.forApi(35), fresh);
        if (freshDex.getMethodSection().size() > 65536 || freshDex.getTypeSection().size() > 65536) throw new IOException("HUD delta exceeds DEX reference bound");
        for (File input : inputs) {
            interrupted();
            File canonical = input.getCanonicalFile(), output = new File(outputDir, input.getName());
            Map<String, byte[]> replacements = new LinkedHashMap<>(); int maximum = 1;
            try (ZipFile zip = new ZipFile(input)) {
                for (ZipEntry e : Collections.list(zip.entries())) if (e.getName().matches("classes\\d*\\.dex")) {
                    String digits = e.getName().substring(7, e.getName().length()-4); maximum = Math.max(maximum, digits.isEmpty() ? 1 : Integer.parseInt(digits));
                    byte[] bytes = read(zip.getInputStream(e), LIMIT);
                    List<ClassDef> keep = new ArrayList<>(); boolean removed = false;
                    for (ClassDef c : new DexBackedDexFile(Opcodes.forApi(35), bytes).getClasses()) {
                        if (changed.containsKey(c.getType()) || component.equals("map") && owned(c.getType())) removed = true; else keep.add(c);
                    }
                    if (removed) replacements.put(e.getName(), dex(keep));
                }
            }
            if (canonical.equals(before.codeApk)) replacements.put("classes" + (maximum + 1) + ".dex", fresh);
            if (component.equals("map")) for (Rule r : resources.values()) {
                File owner = before.owners.get(r.key);
                if (owner == null && r.key.equals(WazeScenePatch.MARKER_ASSET)) owner = before.owners.get(WazeScenePatch.PACK);
                if (owner == null) owner = r.key.startsWith("lib/") ? before.nativeApk : before.codeApk;
                if (canonical.equals(owner)) replacements.put(r.key, resourceBytes(r, input));
            }
            Files.copy(input.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (replacements.isEmpty()) continue;
            try (ZipArchive archive = new ZipArchive(output)) {
                for (String n : new ArrayList<>(archive.listEntries())) if (replacements.containsKey(n) || n.matches("(?i)META-INF/(MANIFEST\\.MF|[^/]+\\.(SF|RSA|DSA|EC))")) archive.delete(n);
                for (Map.Entry<String, byte[]> r : replacements.entrySet()) {
                    boolean nativeLib = r.getKey().startsWith("lib/");
                    BytesSource source = new BytesSource(r.getValue(), r.getKey(), nativeLib ? Deflater.NO_COMPRESSION : Deflater.BEST_SPEED);
                    source.align(nativeLib ? 16384 : 4); archive.add(source);
                }
            }
        }
        List<File> outputs = new ArrayList<>(); for (File f : inputs) outputs.add(new File(outputDir, f.getName()));
        if (!inspect(outputs, component).current) throw new IOException("HUD candidate reinspection is not current");
    }

    private ClassDef transform(ClassDef cls, String component) throws IOException {
        Map<String, Method> existing = new LinkedHashMap<>(), edits = new LinkedHashMap<>();
        for (Method method : cls.getMethods()) existing.put(key(method), method);
        for (Rule rule : methods.values()) {
            if (!component.equals(rule.component) || !rule.key.startsWith(cls.getType() + "->")) continue;
            Method method = existing.get(rule.key);
            if (rule.check(method == null ? "" : sha(method(method)))) continue;
            if (method == null || method.getName().startsWith("bydHudOriginal_")) continue;
            Method retained = existing.get(rule.key.replace("->", "->bydHudOriginal_"));
            if (retained != null && GmapsHudTransform.wrapped.containsKey(rule.key))
                method = GmapsHudTransform.method(retained, method.getName(), retained.getImplementation());
            edits.put(rule.key, method);
        }
        if (edits.isEmpty()) return cls;
        Map<String, Method> replacements = new LinkedHashMap<>();
        if (family.equals("waze")) {
            for (Method method : edits.values()) {
                Method result = WazeHudTransform.patch(method, component);
                replacements.put(key(result), result);
            }
        } else {
            ClassDef selected = new ImmutableClassDef(cls.getType(), cls.getAccessFlags(), cls.getSuperclass(),
                    cls.getInterfaces(), cls.getSourceFile(), cls.getAnnotations(), cls.getFields(), edits.values());
            GmapsHudTransform transform = new GmapsHudTransform();
            for (Method method : transform.transform(selected).getMethods()) {
                Method result = NavigatorMapPatcher.patchCaptureMethod(method, family);
                replacements.put(key(result), result);
            }
        }
        for (Map.Entry<String, Method> entry : replacements.entrySet()) {
            Rule rule = methods.get(entry.getKey());
            if (rule == null || !component.equals(rule.component)
                    || !rule.current.equals(sha(method(entry.getValue()))))
                throw new IOException("HUD transform output differs: " + entry.getKey()
                        + " sha256=" + sha(method(entry.getValue())));
            existing.put(entry.getKey(), entry.getValue());
        }
        return new ImmutableClassDef(cls.getType(), cls.getAccessFlags(), cls.getSuperclass(), cls.getInterfaces(),
                cls.getSourceFile(), cls.getAnnotations(), cls.getFields(), existing.values());
    }

    static boolean owned(String type) { return type.startsWith("Lcom/bydhud/mapcapture/") || type.startsWith("Lcom/bydhud/mapshud/"); }
    private static boolean firstParty(String type) { return type.startsWith("Lcom/bydhud/") || type.startsWith("Lcom/waze/bydhud/"); }
    private void verifyDependencies(Result input, String component) throws IOException {
        for (ClassDef cls : new DexBackedDexFile(Opcodes.forApi(35), required(content, "payload.dex")).getClasses())
            for (Method m : cls.getMethods()) {
                Rule rule = methods.get(key(m));
                if (!(component.equals("map") && owned(cls.getType())) && (rule == null || !component.equals(rule.component))) continue;
                if (m.getImplementation() == null) continue;
                for (Instruction op : m.getImplementation().getInstructions()) {
                    if (!(op instanceof ReferenceInstruction)) continue;
                    Object ref = ((ReferenceInstruction)op).getReference(); String type = "", signature = "";
                    if (ref instanceof MethodReference) { MethodReference r = (MethodReference)ref; type = r.getDefiningClass(); signature = key(r); }
                    else if (ref instanceof FieldReference) { FieldReference r = (FieldReference)ref; type = r.getDefiningClass(); signature = type + "->" + r.getName() + ":" + r.getType(); }
                    else if (ref instanceof TypeReference) { type = ((TypeReference)ref).getType(); signature = type; }
                    if (firstParty(type) && !owned(type) && !input.dependencies.contains(signature))
                        throw new InputMismatch("HUD requires the verified Direct dependency " + signature);
                }
            }
    }
    private byte[] resourceBytes(Rule rule, File input) throws IOException {
        String prefix = "resource." + rule.resource + ".";
        if (!"transform".equals(metadata.getProperty(prefix + "format"))) return required(content, "resource-" + rule.resource);
        byte[] original;
        try (ZipFile zip = new ZipFile(input)) {
            ZipEntry entry = zip.getEntry(WazeScenePatch.PACK);
            if (entry == null) throw new InputMismatch("Missing shader pack " + rule.key);
            original = read(zip.getInputStream(entry), LIMIT);
        }
        resources.get(WazeScenePatch.PACK).check(sha(original));
        byte[] result = WazeScenePatch.patch(original);
        if (rule.key.equals(WazeScenePatch.MARKER_ASSET)) result = WazeScenePatch.marker(result);
        if (!rule.current.equals(sha(result))) throw new IOException("Transformed shader digest differs: " + rule.key);
        return result;
    }
    static String key(MethodReference m) { return m.getDefiningClass()+"->"+m.getName()+"("+String.join("",m.getParameterTypes())+")"+m.getReturnType(); }
    static String bundleHash(Map<String,String> values) throws IOException {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String,String> entry : new TreeMap<>(values).entrySet())
            result.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        return sha(result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static boolean callsPayload(Method m) {
        if (m.getImplementation() != null) for (Instruction i : m.getImplementation().getInstructions())
            if (i instanceof ReferenceInstruction && ((ReferenceInstruction)i).getReference() instanceof MethodReference
                    && owned(((MethodReference)((ReferenceInstruction)i).getReference()).getDefiningClass())) return true;
        return false;
    }
    static byte[] dex(Collection<? extends ClassDef> classes) throws IOException {
        MemoryDataStore out = new MemoryDataStore(); DexPool.writeTo(out, new ImmutableDexFile(Opcodes.forApi(35), classes)); return out.getData();
    }
    static byte[] method(Method m) throws IOException {
        return dex(Collections.singletonList(new ImmutableClassDef(m.getDefiningClass(), 1, "Ljava/lang/Object;", Collections.emptyList(), null,
                Collections.emptySet(), Collections.emptyList(), Collections.singletonList(m))));
    }
    private static byte[] required(Map<String, byte[]> content, String name) throws IOException {
        byte[] value = content.get(name); if (value == null) throw new IOException("Missing HUD profile entry " + name); return value;
    }
    private static byte[] read(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[65536];int n;
        while ((n = in.read(b)) != -1) { interrupted(); if ((long)out.size()+n > limit) throw new IOException("HUD input exceeds byte bound"); out.write(b,0,n); } return out.toByteArray();
    }
    static String sha(byte[] data) throws IOException {
        try { byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);StringBuilder s = new StringBuilder();
            for (byte b : hash) s.append(String.format(Locale.ROOT,"%02x",b & 255));return s.toString();
        } catch (NoSuchAlgorithmException e) { throw new IOException("SHA-256 unavailable", e); }
    }

    private static String streamSha(InputStream input) throws IOException {
        try (InputStream in = input) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536]; long size = 0; int count;
            while ((count = in.read(buffer)) != -1) {
                interrupted();
                size += count; if (size > 256L * 1024 * 1024) throw new IOException("Native engine exceeds byte bound");
                digest.update(buffer, 0, count);
            }
            StringBuilder result = new StringBuilder();
            for (byte b : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException e) { throw new IOException("SHA-256 unavailable", e); }
    }

    private static void interrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Navigator HUD patch interrupted");
    }
}
