package dev.moon.hotwire.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.moon.hotwire.concurrent.Parallel;
import dev.moon.hotwire.jar.JarNames;
import dev.moon.hotwire.jar.ZipBytes;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

public final class JarRelocation {
    private static final String ROOT = "hotwire_live/";
    private static final String MANIFEST = "fabric.mod.json";
    private static final String CLASS_SUFFIX = ".class";
    private static final String SERVICES = "META-INF/services/";
    private static final String META_INF = "META-INF/";
    private static final String LIVE_NAMES = LiveNames.class.getName().replace('.', '/');

    private final Set<String> classes = new HashSet<>();
    private final Set<String> packages = new HashSet<>();
    private final Set<String> configs = new HashSet<>();

    private final Map<String, String> liveIds = new HashMap<>();
    private final BoundNatives bound = new BoundNatives();
    private static final int MOST_ID_LENGTH = 64;
    private static final int MOST_NESTED = 8;
    private final String tag;
    private final String prefix;
    private final String filePrefix;
    private final BiPredicate<String, String> takesCarried;
    private final Function<String, byte @Nullable []> running;
    private final List<OwnPatch> patches;
    private final PinnedClasses pinned;
    private final Remapper remapper = new Remapper() {
        @Override
        public String map(String internalName) {
            return JarRelocation.this.classes.contains(internalName) ? JarRelocation.this.prefix + internalName
                    : internalName;
        }

        @Override
        public Object mapValue(Object value) {
            return value instanceof String text ? JarRelocation.this.mapText(text) : super.mapValue(value);
        }
    };

    private JarRelocation(String tag, BiPredicate<String, String> takesCarried,
            Function<String, byte @Nullable []> running, List<OwnPatch> patches, PinnedClasses pinned) {
        this.patches = patches;
        this.pinned = pinned;
        this.tag = tag;
        this.prefix = ROOT + tag + "/";
        this.filePrefix = tag + ".";
        this.takesCarried = takesCarried;
        this.running = running;
    }

    public static String findLiveId(String id, String tag) {
        String live = id + "_" + tag;
        if (live.length() <= MOST_ID_LENGTH) {
            return live;
        }
        String mark = Integer.toHexString(id.hashCode()) + "_" + tag;
        return id.substring(0, MOST_ID_LENGTH - mark.length() - 1) + "_" + mark;
    }

    public static List<Path> write(List<Path> jars, Path folder, String tag, BiPredicate<String, String> takesCarried,
            Function<String, byte @Nullable []> running) throws IOException {
        return write(jars, folder, tag, takesCarried, running, List.of(), new PinnedClasses(Set.of()));
    }

    public static List<Path> write(List<Path> jars, Path folder, String tag, BiPredicate<String, String> takesCarried,
            Function<String, byte @Nullable []> running, List<OwnPatch> patches, PinnedClasses pinned)
            throws IOException {
        Files.createDirectories(folder);
        JarRelocation copy = new JarRelocation(tag, takesCarried, running, patches, pinned);
        for (Path jar : jars) {
            copy.scan(readAll(jar));
        }
        LiveNames.learn(tag, copy.classes);
        LiveNames.learnIds(copy.liveIds);
        if (!copy.bound.isEmpty()) {
            List<Map<String, byte[]>> group = new ArrayList<>();
            for (Path jar : jars) {
                collectGroup(readAll(jar), takesCarried, group);
            }
            copy.bound.check(group, copy.classes);
        }
        List<Path> out = new ArrayList<>();
        jars.forEach(jar -> out.add(folder.resolve(tag + "-" + jar.getFileName())));
        Map<Path, Path> into = new LinkedHashMap<>();
        for (int i = 0; i < jars.size(); i++) {
            into.put(jars.get(i), out.get(i));
        }
        try {
            Parallel.runEachBeside(jars, jar -> {
                try {
                    Files.write(into.get(jar), copy.makeCopy(readAll(jar)));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        return out;
    }

    public static Set<String> findCarriedWithState(Path jar, BiPredicate<String, String> takesCarried)
            throws IOException {
        Set<String> carried = new HashSet<>();
        collectWithState(readAll(jar), takesCarried, carried);
        return carried;
    }

    private static void collectWithState(Map<String, byte[]> entries, BiPredicate<String, String> takes,
            Set<String> into) throws IOException {
        for (Map<String, byte[]> nested : findGoingAlong(entries, takes).values()) {
            JsonObject manifest = readManifest(nested);
            if ((manifest.has("mixins") && !manifest.getAsJsonArray("mixins").isEmpty())
                    || (manifest.has("entrypoints") && !manifest.getAsJsonObject("entrypoints").isEmpty())) {
                into.add(manifest.get("id").getAsString());
            }
            collectWithState(nested, takes, into);
        }
    }

    public static List<byte[]> readMixinClasses(Path jar, BiPredicate<String, String> takesCarried) throws IOException {
        List<byte[]> classes = new ArrayList<>();
        collectMixinClasses(readAll(jar), takesCarried, classes);
        return classes;
    }

    private static void collectMixinClasses(Map<String, byte[]> entries, BiPredicate<String, String> takes,
            List<byte[]> into) throws IOException {
        Set<String> packages = new HashSet<>();
        for (String config : findConfigsNamed(entries)) {
            byte[] json = entries.get(config);
            if (json != null && isMixinConfig(json)) {
                JsonElement parsed = JsonParser.parseString(new String(json, StandardCharsets.UTF_8));
                packages.add(parsed.getAsJsonObject().get("package").getAsString().replace('.', '/') + "/");
            }
        }
        for (String name : entries.keySet()) {
            if (name.endsWith(CLASS_SUFFIX) && packages.stream().anyMatch(name::startsWith)) {
                into.add(entries.get(name));
            }
        }
        for (Map<String, byte[]> nested : findGoingAlong(entries, takes).values()) {
            collectMixinClasses(nested, takes, into);
        }
    }

    private static Set<String> findConfigsNamed(Map<String, byte[]> entries) throws IOException {
        Set<String> configs = new HashSet<>();
        for (String name : entries.keySet()) {
            if (name.endsWith(".json") && name.indexOf('/') < 0 && !name.equals(MANIFEST)) {
                configs.add(name);
            }
        }
        JsonArray mixins = entries.containsKey(MANIFEST) ? readManifest(entries).getAsJsonArray("mixins") : null;
        if (mixins != null) {
            for (JsonElement one : mixins) {
                configs.add(one.isJsonPrimitive() ? one.getAsString()
                        : one.getAsJsonObject().get("config").getAsString());
            }
        }
        return configs;
    }

    public static Map<String,
            String> findCarried(Path jar, BiPredicate<String, String> takesCarried) throws IOException {
        Map<String, String> carried = new HashMap<>();
        collectCarried(readAll(jar), takesCarried, carried);
        return carried;
    }

    private static void collectCarried(Map<String, byte[]> entries, BiPredicate<String, String> takes,
            Map<String, String> into) throws IOException {
        for (Map<String, byte[]> nested : findGoingAlong(entries, takes).values()) {
            JsonObject manifest = readManifest(nested);
            into.put(manifest.get("id").getAsString(), manifest.get("version").getAsString());
            collectCarried(nested, takes, into);
        }
    }

    private static void collectGroup(Map<String, byte[]> entries, BiPredicate<String, String> takes,
            List<Map<String, byte[]>> into) throws IOException {
        into.add(entries);
        for (Map<String, byte[]> nested : findGoingAlong(entries, takes).values()) {
            collectGroup(nested, takes, into);
        }
    }

    public static Map<String, String> findPicked(List<Path> jars, Comparator<String> byVersion) throws IOException {
        return findPickedIn(readTops(jars), byVersion);
    }

    private static List<Map<String, byte[]>> readTops(List<Path> jars) throws IOException {
        List<Map<String, byte[]>> tops = new ArrayList<>();
        for (Path jar : jars) {
            tops.add(readAll(jar));
        }
        return tops;
    }

    private static Map<String, String> findPickedIn(List<Map<String, byte[]>> tops, Comparator<String> byVersion)
            throws IOException {
        IdentityHashMap<byte[], Map<String, byte[]>> opened = new IdentityHashMap<>();
        Map<String, String> picked = new HashMap<>();

        for (int round = 0; round < MOST_NESTED; round++) {
            Map<String, String> next = new HashMap<>();
            for (Map<String, byte[]> top : tops) {
                collectPicked(top, picked, byVersion, opened, next);
            }
            if (next.equals(picked)) {
                break;
            }
            picked = next;
        }
        return picked;
    }

    public static Map<String, Map<String, byte[]>> readPicked(List<Path> jars, Comparator<String> byVersion)
            throws IOException {
        List<Map<String, byte[]>> tops = readTops(jars);
        Map<String, String> picked = findPickedIn(tops, byVersion);
        Map<String, Map<String, byte[]>> files = new HashMap<>();
        for (Map<String, byte[]> top : tops) {
            collectFiles(top, picked, files);
        }
        return files;
    }

    public static Set<String> readClassesOf(Path jar, Predicate<String> nestedGoes) throws IOException {
        Set<String> classes = new HashSet<>();
        collectClassesOf(readAll(jar), nestedGoes, classes);
        return classes;
    }

    private static void collectClassesOf(Map<String, byte[]> entries, Predicate<String> nestedGoes, Set<String> into)
            throws IOException {
        entries.keySet().stream().filter(name -> name.endsWith(".class"))
                .forEach(name -> into.add(name.substring(0, name.length() - 6)));
        JsonArray jars = entries.containsKey(MANIFEST) ? readManifest(entries).getAsJsonArray("jars") : null;
        if (jars == null) {
            return;
        }
        for (JsonElement one : jars) {
            byte[] bytes = entries.get(one.getAsJsonObject().get("file").getAsString());
            Map<String, byte[]> nested = bytes == null ? Map.of() : readAll(bytes);
            if (nested.containsKey(MANIFEST) && nestedGoes.test(readManifest(nested).get("id").getAsString())) {
                collectClassesOf(nested, nestedGoes, into);
            }
        }
    }

    public static Set<String> findApart(Map<String, Map<String, byte[]>> libraries, Set<String> changing,
            Set<String> changingMods) {
        Set<String> moving = new HashSet<>(changing);
        Set<String> movingMods = new HashSet<>(changingMods);
        Map<String, Map<String, byte[]>> apart = new HashMap<>(libraries);
        boolean fewer = true;
        while (fewer) {
            fewer = apart.entrySet().removeIf(library -> {
                Map<String, byte[]> files = library.getValue();
                boolean leans = needsAnyOf(files, movingMods) || files.entrySet().stream().anyMatch(
                        file -> file.getKey().endsWith(".class")
                                && JarNames.readClassesNamed(file.getValue()).stream().anyMatch(moving::contains));
                if (leans) {
                    movingMods.add(library.getKey());
                    files.keySet().stream().filter(name -> name.endsWith(".class"))
                            .forEach(name -> moving.add(name.substring(0, name.length() - 6)));
                }
                return leans;
            });
        }
        return apart.keySet();
    }

    private static boolean needsAnyOf(Map<String, byte[]> files, Set<String> mods) {
        try {
            JsonObject depends = files.containsKey(MANIFEST) ? readManifest(files).getAsJsonObject("depends") : null;
            return depends != null && depends.keySet().stream().anyMatch(mods::contains);
        } catch (IOException | RuntimeException e) {
            return true;
        }
    }

    private static void collectFiles(Map<String, byte[]> entries, Map<String, String> picked,
            Map<String, Map<String, byte[]>> into) throws IOException {
        JsonArray jars = entries.containsKey(MANIFEST) ? readManifest(entries).getAsJsonArray("jars") : null;
        if (jars == null) {
            return;
        }
        for (JsonElement one : jars) {
            byte[] bytes = entries.get(one.getAsJsonObject().get("file").getAsString());
            Map<String, byte[]> nested = bytes == null ? Map.of() : readAll(bytes);
            if (!nested.containsKey(MANIFEST)) {
                continue;
            }
            JsonObject manifest = readManifest(nested);
            String id = manifest.get("id").getAsString();
            if (manifest.get("version").getAsString().equals(picked.get(id)) && into.putIfAbsent(id, nested) == null) {
                collectFiles(nested, picked, into);
            }
        }
    }

    private static void collectPicked(Map<String, byte[]> entries, Map<String, String> picked,
            Comparator<String> byVersion, IdentityHashMap<byte[], Map<String, byte[]>> opened,
            Map<String, String> into) throws IOException {
        JsonArray jars = entries.containsKey(MANIFEST) ? readManifest(entries).getAsJsonArray("jars") : null;
        if (jars == null) {
            return;
        }
        for (JsonElement one : jars) {
            byte[] bytes = entries.get(one.getAsJsonObject().get("file").getAsString());
            if (bytes == null) {
                continue;
            }
            Map<String, byte[]> nested = opened.get(bytes);
            if (nested == null) {
                nested = readAll(bytes);
                opened.put(bytes, nested);
            }
            if (!nested.containsKey(MANIFEST)) {
                continue;
            }
            JsonObject manifest = readManifest(nested);
            String id = manifest.get("id").getAsString();
            String version = manifest.get("version").getAsString();
            into.merge(id, version, (had, other) -> byVersion.compare(had, other) >= 0 ? had : other);
            if (version.equals(picked.get(id))) {
                collectPicked(nested, picked, byVersion, opened, into);
            }
        }
    }

    private static Map<String, Map<String, byte[]>> findGoingAlong(Map<String, byte[]> entries,
            BiPredicate<String, String> takes) throws IOException {
        Map<String, Map<String, byte[]>> goingAlong = new LinkedHashMap<>();
        if (!entries.containsKey(MANIFEST)) {
            return goingAlong;
        }
        JsonObject parent = readManifest(entries);
        JsonArray jars = parent.getAsJsonArray("jars");
        if (jars != null) {
            for (JsonElement one : jars) {
                String file = one.getAsJsonObject().get("file").getAsString();
                byte[] bytes = entries.get(file);
                Map<String, byte[]> nested = bytes == null ? Map.of() : readAll(bytes);
                if (nested.containsKey(MANIFEST)) {
                    JsonObject manifest = readManifest(nested);

                    if (holdsConfigOf(parent, nested)
                            || takes.test(manifest.get("id").getAsString(), manifest.get("version").getAsString())) {
                        goingAlong.put(file, nested);
                    }
                }
            }
        }
        return goingAlong;
    }

    private static boolean holdsConfigOf(JsonObject parent, Map<String, byte[]> nested) {
        JsonArray mixins = parent.getAsJsonArray("mixins");
        if (mixins != null) {
            for (JsonElement one : mixins) {
                String config = one.isJsonPrimitive() ? one.getAsString()
                        : one.getAsJsonObject().get("config").getAsString();
                if (nested.containsKey(config)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void scan(Map<String, byte[]> entries) throws IOException {
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String name = entry.getKey();
            if (name.endsWith(CLASS_SUFFIX) && !name.startsWith(META_INF)) {
                String internal = name.substring(0, name.length() - CLASS_SUFFIX.length());
                if (internal.startsWith("net/minecraft/") || internal.startsWith("com/mojang/")
                        || this.pinned.has(internal)) {
                    continue;
                }
                if (hasNative(entry.getValue())) {
                    byte[] runs = this.running.apply(internal);
                    if (runs != null) {
                        this.bound.add(internal, runs, entry.getValue());
                    }
                    continue;
                }
                this.classes.add(internal);
                int slash = internal.lastIndexOf('/');
                if (slash > 0) {
                    this.packages.add(internal.substring(0, slash));
                }
            }
        }

        for (String name : entries.keySet()) {
            if (name.endsWith(".json") && name.indexOf('/') < 0 && !name.equals(MANIFEST)
                    && isMixinConfig(entries.get(name))) {
                this.configs.add(name);
            }
        }
        JsonObject manifest = readManifest(entries);
        String id = manifest.get("id").getAsString();
        String live = findLiveId(id, this.tag);
        this.liveIds.put(id, live);
        if (manifest.has("provides")) {
            manifest.getAsJsonArray("provides").forEach(one -> this.liveIds.putIfAbsent(one.getAsString(), live));
        }
        for (Map<String, byte[]> nested : findGoingAlong(entries, this.takesCarried).values()) {
            this.scan(nested);
        }
    }

    private static boolean isMixinConfig(byte[] bytes) {
        try {
            JsonElement root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            return root.isJsonObject() && root.getAsJsonObject().has("package");
        } catch (RuntimeException notJson) {
            return false;
        }
    }

    private static boolean hasNative(byte[] bytes) {
        boolean[] found = new boolean[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public @Nullable MethodVisitor visitMethod(int access, String name, String descriptor,
                    @Nullable String signature, String @Nullable [] exceptions) {
                found[0] |= (access & Opcodes.ACC_NATIVE) != 0;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private byte[] makeCopy(Map<String, byte[]> entries) throws IOException {
        JsonObject manifest = readManifest(entries);
        Map<String, String> renamed = this.findRenamedFiles(entries, manifest);
        Map<String, Map<String, byte[]>> goingAlong = findGoingAlong(entries, this.takesCarried);
        this.requireLeftLibraries(entries, manifest, goingAlong.keySet());
        Map<String, byte[]> twins = this.makeTwins(entries, manifest, renamed);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream into = new ZipOutputStream(bytes)) {
            into.setLevel(Deflater.NO_COMPRESSION);
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                this.copyEntry(into, entry.getKey(), entry.getValue(), manifest, renamed, goingAlong);
            }
            for (Map.Entry<String, byte[]> twin : twins.entrySet()) {
                put(into, twin.getKey(), twin.getValue());
            }
        }
        return bytes.toByteArray();
    }

    private void notePinned(String internalName, byte[] made) throws IOException {
        byte[] runs = this.running.apply(internalName);
        if (runs == null) {
            throw new IOException(internalName + " is called by what stays on, and what runs of it could not be read");
        }
        Set<JarDiff.ShapeChange> shape = UpdateDiffer.compareClass(runs, made).shape();
        if (!shape.isEmpty()) {
            throw new IOException(internalName + " is called by what stays on, and its new version differs in "
                    + shape);
        }
        this.pinned.put(internalName, made, runs);
    }

    private Map<String, byte[]> makeTwins(Map<String, byte[]> entries, JsonObject manifest,
            Map<String, String> renamed) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        JsonArray names = new JsonArray();
        for (OwnPatch patch : this.patches) {
            if (!patch.canFollow() || patch.getTargets().stream().noneMatch(
                    target -> entries.containsKey(target + CLASS_SUFFIX))) {
                continue;
            }
            ClassWriter writer = new ClassWriter(0);
            new ClassReader(patch.getBytes()).accept(new ClassRemapper(writer, new Remapper() {
                @Override
                public String map(String internalName) {
                    return internalName.equals(patch.getName()) ? JarRelocation.this.prefix + internalName
                            : JarRelocation.this.remapper.map(internalName);
                }

                @Override
                public Object mapValue(Object value) {
                    return JarRelocation.this.remapper.mapValue(value);
                }
            }), 0);
            files.put(this.prefix + patch.getName() + CLASS_SUFFIX, writer.toByteArray());
            names.add(patch.getName().replace('/', '.'));
        }
        if (files.isEmpty()) {
            return files;
        }
        String config = this.filePrefix + manifest.get("id").getAsString() + ".patches.mixins.json";
        JsonObject root = new JsonObject();
        root.addProperty("required", false);
        root.addProperty("package", this.prefix.substring(0, this.prefix.length() - 1).replace('/', '.'));
        root.addProperty("compatibilityLevel", "JAVA_21");
        root.add("mixins", names);
        files.put(config, root.toString().getBytes(StandardCharsets.UTF_8));
        JsonArray mixins = manifest.has("mixins") ? manifest.getAsJsonArray("mixins") : new JsonArray();
        mixins.add(config);
        manifest.add("mixins", mixins);
        renamed.put(config, config);
        return files;
    }

    private void copyEntry(ZipOutputStream into, String name, byte[] body, JsonObject manifest,
            Map<String, String> renamed, Map<String, Map<String, byte[]>> goingAlong) throws IOException {
        if (goingAlong.containsKey(name)) {
            put(into, this.mapNestedName(name), this.makeCopy(goingAlong.get(name)));
        } else if (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA")
                || name.startsWith("META-INF/jars/") || name.startsWith("META-INF/versions/")) {
            return;
        } else if (name.equals(MANIFEST)) {
            put(into, name, this.makeManifest(manifest, renamed, goingAlong.keySet()));
        } else if (name.endsWith(CLASS_SUFFIX)) {
            ClassWriter writer = new ClassWriter(0);
            new ClassReader(body).accept(makeNamesOwn(new ClassRemapper(writer, this.remapper)), 0);
            String internal = name.substring(0, name.length() - CLASS_SUFFIX.length());

            if (this.pinned.has(internal)) {
                this.notePinned(internal, writer.toByteArray());
            } else {
                put(into, this.remapper.map(internal) + CLASS_SUFFIX, writer.toByteArray());
            }
        } else if (renamed.containsKey(name)) {
            put(into, renamed.get(name), this.makeRenamed(body, renamed));
        } else if (name.startsWith(SERVICES)) {
            put(into, SERVICES + this.mapText(name.substring(SERVICES.length())),
                    this.mapText(new String(body, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
        } else if (name.endsWith(".accesswidener") || name.endsWith(".classtweaker")) {
            put(into, name, this.mapText(new String(body, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
        } else {
            put(into, name, body);

            int slash = name.lastIndexOf('/');
            if (slash > 0 && this.packages.contains(name.substring(0, slash))) {
                put(into, this.prefix + name, body);
            }
        }
    }

    private Map<String, String> findRenamedFiles(Map<String, byte[]> entries, JsonObject manifest) {
        Map<String, String> renamed = new HashMap<>();
        JsonArray mixins = manifest.getAsJsonArray("mixins");
        if (mixins != null) {
            for (JsonElement one : mixins) {
                String config = one.isJsonPrimitive() ? one.getAsString()
                        : one.getAsJsonObject().get("config").getAsString();
                renamed.put(config, this.filePrefix + config);
            }
        }
        for (String config : this.configs) {
            byte[] body = entries.get(config);
            if (body != null) {
                renamed.put(config, this.filePrefix + config);
                JsonObject root = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
                if (root.has("refmap")) {
                    String refmap = root.get("refmap").getAsString();
                    renamed.put(refmap, this.filePrefix + refmap);
                }
            }
        }
        return renamed;
    }

    private void requireLeftLibraries(Map<String, byte[]> entries, JsonObject manifest, Set<String> goingAlong)
            throws IOException {
        JsonArray jars = manifest.getAsJsonArray("jars");
        if (jars == null) {
            return;
        }
        JsonObject depends = manifest.has("depends") ? manifest.getAsJsonObject("depends") : new JsonObject();
        for (JsonElement one : jars) {
            String file = one.getAsJsonObject().get("file").getAsString();
            byte[] bytes = entries.get(file);
            Map<String, byte[]> nested = bytes == null || goingAlong.contains(file) ? Map.of() : readAll(bytes);
            if (nested.containsKey(MANIFEST)) {
                String id = readManifest(nested).get("id").getAsString();
                if (!depends.has(id)) {
                    depends.addProperty(id, "*");
                }
            }
        }
        manifest.add("depends", depends);
    }

    private static ClassVisitor makeNamesOwn(ClassVisitor next) {
        return new ClassVisitor(Opcodes.ASM9, next) {
            private String asking = "";

            @Override
            public void visit(int version, int access, String name, @Nullable String signature,
                    @Nullable String superName, String @Nullable [] interfaces) {
                this.asking = name;
                super.visit(version, access, name, signature, superName, interfaces);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, @Nullable String signature,
                    String @Nullable [] exceptions) {
                String asking = this.asking;
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature,
                        exceptions)) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String called, String calledDescriptor,
                            boolean isInterface) {
                        String byName = findByName(owner, called, calledDescriptor);
                        if (byName != null) {
                            super.visitLdcInsn(Type.getObjectType(asking));
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, LIVE_NAMES,
                                    called.equals("forName") ? "findClass" : called, byName, false);
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, called, calledDescriptor, isInterface);
                        if (isNameOf(owner, called, calledDescriptor)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, LIVE_NAMES, "findOwnName",
                                    "(Ljava/lang/String;)Ljava/lang/String;", false);
                        }
                    }

                    @Override
                    public void visitMaxs(int maxStack, int maxLocals) {
                        super.visitMaxs(maxStack + 1, maxLocals);
                    }
                };
            }
        };
    }

    private static @Nullable String findByName(String owner, String called, String descriptor) {
        if (owner.equals("java/lang/Class") && called.equals("forName")) {
            return switch (descriptor) {
                case "(Ljava/lang/String;)Ljava/lang/Class;" ->
                        "(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Class;";
                case "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;" ->
                        "(Ljava/lang/String;ZLjava/lang/ClassLoader;Ljava/lang/Class;)Ljava/lang/Class;";
                default -> null;
            };
        }
        return owner.equals("java/lang/ClassLoader") && called.equals("loadClass")
                && descriptor.equals("(Ljava/lang/String;)Ljava/lang/Class;")
                        ? "(Ljava/lang/ClassLoader;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Class;" : null;
    }

    private static boolean isNameOf(String owner, String called, String descriptor) {
        if (!descriptor.equals("()Ljava/lang/String;")) {
            return false;
        }
        return owner.equals("java/lang/Class") ? called.equals("getName") || called.equals("getTypeName")
                || called.equals("getCanonicalName")
                : owner.equals("kotlin/reflect/KClass") && called.equals("getQualifiedName");
    }

    private String mapNestedName(String name) {
        int slash = name.lastIndexOf('/') + 1;
        return name.substring(0, slash) + this.tag + "-" + name.substring(slash);
    }

    private byte[] makeManifest(JsonObject manifest, Map<String, String> renamed, Set<String> goingAlong) {
        String id = manifest.get("id").getAsString();
        manifest.addProperty("id", findLiveId(id, this.tag));
        JsonArray provides = manifest.has("provides") ? manifest.getAsJsonArray("provides") : new JsonArray();
        provides.add(id);
        manifest.add("provides", provides);
        JsonArray jars = manifest.getAsJsonArray("jars");
        if (jars != null) {
            JsonArray listed = new JsonArray();
            jars.forEach(one -> {
                String file = one.getAsJsonObject().get("file").getAsString();
                if (goingAlong.contains(file)) {
                    JsonObject entry = one.getAsJsonObject().deepCopy();
                    entry.addProperty("file", this.mapNestedName(file));
                    listed.add(entry);
                }
            });
            manifest.add("jars", listed);
        }
        JsonObject depends = manifest.getAsJsonObject("depends");
        if (depends != null) {
            JsonObject mapped = new JsonObject();
            depends.entrySet().forEach(one -> mapped.add(this.liveIds.getOrDefault(one.getKey(), one.getKey()),
                    one.getValue()));
            manifest.add("depends", mapped);
        }
        JsonObject adapters = manifest.getAsJsonObject("languageAdapters");
        if (adapters != null) {
            for (String key : Set.copyOf(adapters.keySet())) {
                adapters.addProperty(key, this.mapText(adapters.get(key).getAsString()));
            }
        }
        JsonObject entrypoints = manifest.getAsJsonObject("entrypoints");
        if (entrypoints != null) {
            for (String key : Set.copyOf(entrypoints.keySet())) {
                JsonArray mapped = new JsonArray();
                for (JsonElement one : entrypoints.getAsJsonArray(key)) {
                    if (one.isJsonPrimitive()) {
                        mapped.add(this.mapText(one.getAsString()));
                    } else {
                        JsonObject object = one.getAsJsonObject();
                        object.add("value", new JsonPrimitive(this.mapText(object.get("value").getAsString())));
                        mapped.add(object);
                    }
                }
                entrypoints.add(key, mapped);
            }
        }
        JsonArray mixins = manifest.getAsJsonArray("mixins");
        if (mixins != null) {
            JsonArray mapped = new JsonArray();
            for (JsonElement one : mixins) {
                if (one.isJsonPrimitive()) {
                    mapped.add(renamed.get(one.getAsString()));
                } else {
                    JsonObject object = one.getAsJsonObject();
                    object.addProperty("config", renamed.get(object.get("config").getAsString()));
                    mapped.add(object);
                }
            }
            manifest.add("mixins", mapped);
        }
        return manifest.toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] makeRenamed(byte[] bytes, Map<String, String> renamed) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        if (root.has("mappings") || root.has("data")) {
            return this.mapText(text).getBytes(StandardCharsets.UTF_8);
        }

        if (root.has("package")) {
            root.addProperty("package", this.prefix.replace('/', '.') + root.get("package").getAsString());
        }
        if (root.has("plugin")) {
            root.addProperty("plugin", this.mapText(root.get("plugin").getAsString()));
        }
        if (root.has("refmap")) {
            String refmap = root.get("refmap").getAsString();
            root.addProperty("refmap", renamed.getOrDefault(refmap, refmap));
        }
        if (root.has("parent")) {
            String config = root.get("parent").getAsString();
            root.addProperty("parent", this.configs.contains(config) ? this.filePrefix + config : config);
        }
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String mapText(String text) {
        if (text.indexOf('/') < 0 && text.indexOf('.') < 0) {
            return text;
        }
        String slashed = mapNames(text, '/', token -> {
            if (this.isOurs(token)) {
                return this.prefix + token;
            }
            return token.length() > 1 && token.charAt(0) == 'L' && this.isOurs(token.substring(1))
                    ? "L" + this.prefix + token.substring(1) : token;
        });
        return mapNames(slashed, '.', token -> this.isOurs(token.replace('.', '/'))
                ? this.prefix.replace('/', '.') + token : token);
    }

    static String mapNames(String text, char joint, UnaryOperator<String> map) {
        StringBuilder out = null;
        int copied = 0;
        int at = 0;
        int length = text.length();
        while (at < length) {
            if (!isOfName(text.charAt(at))) {
                at++;
                continue;
            }
            int start = at;
            boolean joined = false;
            while (true) {
                while (at < length && isOfName(text.charAt(at))) {
                    at++;
                }
                if (at + 1 >= length || text.charAt(at) != joint || !isOfName(text.charAt(at + 1))) {
                    break;
                }
                joined = true;
                at++;
            }
            if (!joined) {
                continue;
            }
            String token = text.substring(start, at);
            String mapped = map.apply(token);
            if (!mapped.equals(token)) {
                if (out == null) {
                    out = new StringBuilder(length + 64);
                }
                out.append(text, copied, start).append(mapped);
                copied = at;
            }
        }
        return out == null ? text : out.append(text, copied, length).toString();
    }

    private static boolean isOfName(char c) {
        return c == '$' || c == '-' || c == '_' || (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z');
    }

    private boolean isOurs(String slashed) {
        return this.classes.contains(slashed) || this.packages.contains(slashed);
    }

    private static JsonObject readManifest(Map<String, byte[]> entries) throws IOException {
        byte[] bytes = entries.get(MANIFEST);
        if (bytes == null) {
            throw new IOException("no " + MANIFEST);
        }
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static final class JarEntries extends AbstractMap<String, byte[]> {
        private final ZipBytes zip;
        private final List<String> names = new ArrayList<>();

        private final Map<String, byte[]> nestedJars = new HashMap<>();

        JarEntries(byte[] jar) throws IOException {
            this.zip = new ZipBytes(jar);
            for (String name : this.zip.getNames()) {
                if (!name.endsWith("/")) {
                    this.names.add(name);
                }
            }
        }

        private byte[] read(String name) {
            try {
                return Objects.requireNonNull(this.zip.read(name));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public boolean containsKey(Object name) {
            return this.zip.getNames().contains(name) && !String.valueOf(name).endsWith("/");
        }

        @Override
        public byte @Nullable [] get(Object name) {
            if (!this.containsKey(name)) {
                return null;
            }
            String path = String.valueOf(name);
            return path.endsWith(".jar") ? this.nestedJars.computeIfAbsent(path, this::read) : this.read(path);
        }

        @Override
        public Set<Map.Entry<String, byte[]>> entrySet() {
            return new AbstractSet<>() {
                @Override
                public int size() {
                    return JarEntries.this.names.size();
                }

                @Override
                public Iterator<Map.Entry<String, byte[]>> iterator() {
                    Iterator<String> all = JarEntries.this.names.iterator();
                    return new Iterator<>() {
                        @Override
                        public boolean hasNext() {
                            return all.hasNext();
                        }

                        @Override
                        public Map.Entry<String, byte[]> next() {
                            String name = all.next();
                            return new AbstractMap.SimpleImmutableEntry<>(name, null) {
                                private static final long serialVersionUID = 1L;

                                @Override
                                public byte[] getValue() {
                                    return JarEntries.this.read(name);
                                }
                            };
                        }
                    };
                }
            };
        }
    }

    private static Map<String, byte[]> readAll(Path jar) throws IOException {
        try {
            return new JarEntries(Files.readAllBytes(jar));
        } catch (ZipException e) {
            return readAllOfFile(jar);
        }
    }

    private static Map<String, byte[]> readAllOfFile(Path jar) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (Enumeration<? extends ZipEntry> all = zip.entries(); all.hasMoreElements(); ) {
                ZipEntry entry = all.nextElement();
                if (!entry.isDirectory()) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        entries.put(entry.getName(), in.readAllBytes());
                    }
                }
            }
        }
        return entries;
    }

    private static Map<String, byte[]> readAll(byte[] jar) throws IOException {
        try {
            return new JarEntries(jar);
        } catch (ZipException e) {
            return readAllOfStream(jar);
        }
    }

    private static Map<String, byte[]> readAllOfStream(byte[] jar) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (!entry.isDirectory()) {
                    entries.put(entry.getName(), zip.readAllBytes());
                }
            }
        }
        return entries;
    }

    private static void put(ZipOutputStream into, String name, byte[] bytes) throws IOException {
        into.putNextEntry(new ZipEntry(name));
        into.write(bytes);
        into.closeEntry();
    }
}
