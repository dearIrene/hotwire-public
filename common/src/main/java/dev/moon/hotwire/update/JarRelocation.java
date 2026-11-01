package dev.moon.hotwire.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.moon.hotwire.concurrent.Parallel;
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
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

public final class JarRelocation {
    private static final String ROOT = "hotwire_live/";
    private static final Pattern SLASHED = Pattern.compile("[\\w$-]+(?:/[\\w$-]+)+");
    private static final Pattern DOTTED = Pattern.compile("[\\w$-]+(?:\\.[\\w$-]+)+");
    private static final String MANIFEST = "fabric.mod.json";
    private static final String CLASS_SUFFIX = ".class";
    private static final String SERVICES = "META-INF/services/";
    private static final String META_INF = "META-INF/";

    private final Set<String> classes = new HashSet<>();
    private final Set<String> packages = new HashSet<>();
    private final Set<String> configs = new HashSet<>();

    private final Map<String, String> liveIds = new HashMap<>();
    private static final int MOST_ID_LENGTH = 64;
    private final String tag;
    private final String prefix;
    private final String filePrefix;
    private final BiPredicate<String, String> takesCarried;
    private final Function<String, byte @Nullable []> running;
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
            Function<String, byte @Nullable []> running) {
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
        Files.createDirectories(folder);
        JarRelocation copy = new JarRelocation(tag, takesCarried, running);
        for (Path jar : jars) {
            copy.scan(readAll(jar));
        }
        List<Path> out = new ArrayList<>();
        jars.forEach(jar -> out.add(folder.resolve(tag + "-" + jar.getFileName())));
        Map<Path, Path> into = new LinkedHashMap<>();
        for (int i = 0; i < jars.size(); i++) {
            into.put(jars.get(i), out.get(i));
        }
        try {
            Parallel.forEachBeside(jars, jar -> {
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
        for (Map<String, byte[]> nested : findTaken(entries, takes).values()) {
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
        for (Map<String, byte[]> nested : findTaken(entries, takes).values()) {
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
        for (Map<String, byte[]> nested : findTaken(entries, takes).values()) {
            JsonObject manifest = readManifest(nested);
            into.put(manifest.get("id").getAsString(), manifest.get("version").getAsString());
            collectCarried(nested, takes, into);
        }
    }

    private static Map<String, Map<String, byte[]>> findTaken(Map<String, byte[]> entries,
            BiPredicate<String, String> takes) throws IOException {
        Map<String, Map<String, byte[]>> taken = new LinkedHashMap<>();
        if (!entries.containsKey(MANIFEST)) {
            return taken;
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
                        taken.put(file, nested);
                    }
                }
            }
        }
        return taken;
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
                if (internal.startsWith("net/minecraft/") || internal.startsWith("com/mojang/")) {
                    continue;
                }
                if (hasNative(entry.getValue())) {
                    byte[] runs = this.running.apply(internal);
                    if (runs != null && !findMethods(runs).containsAll(findMethods(entry.getValue()))) {
                        throw new IOException(internal + " has native methods and gained others");
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
        for (Map<String, byte[]> nested : findTaken(entries, this.takesCarried).values()) {
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

    private static Set<String> findMethods(byte[] bytes) {
        Set<String> methods = new HashSet<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public @Nullable MethodVisitor visitMethod(int access, String name, String descriptor,
                    @Nullable String signature, String @Nullable [] exceptions) {
                methods.add(name + descriptor);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return methods;
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
        Map<String, Map<String, byte[]>> taken = findTaken(entries, this.takesCarried);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream into = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                this.copyEntry(into, entry.getKey(), entry.getValue(), manifest, renamed, taken);
            }
        }
        return bytes.toByteArray();
    }

    private void copyEntry(ZipOutputStream into, String name, byte[] body, JsonObject manifest,
            Map<String, String> renamed, Map<String, Map<String, byte[]>> taken) throws IOException {
        if (taken.containsKey(name)) {
            put(into, this.mapNestedName(name), this.makeCopy(taken.get(name)));
        } else if (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA")
                || name.startsWith("META-INF/jars/") || name.startsWith("META-INF/versions/")) {
            return;
        } else if (name.equals(MANIFEST)) {
            put(into, name, this.makeManifest(manifest, renamed, taken.keySet()));
        } else if (name.endsWith(CLASS_SUFFIX)) {
            ClassWriter writer = new ClassWriter(0);
            new ClassReader(body).accept(new ClassRemapper(writer, this.remapper), 0);
            put(into, this.remapper.map(name.substring(0, name.length() - CLASS_SUFFIX.length())) + CLASS_SUFFIX,
                    writer.toByteArray());
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

    private String mapNestedName(String name) {
        int slash = name.lastIndexOf('/') + 1;
        return name.substring(0, slash) + this.tag + "-" + name.substring(slash);
    }

    private byte[] makeManifest(JsonObject manifest, Map<String, String> renamed, Set<String> taken) {
        String id = manifest.get("id").getAsString();
        manifest.addProperty("id", findLiveId(id, this.tag));
        JsonArray provides = manifest.has("provides") ? manifest.getAsJsonArray("provides") : new JsonArray();
        provides.add(id);
        manifest.add("provides", provides);
        JsonArray jars = manifest.getAsJsonArray("jars");
        if (jars != null) {
            JsonArray kept = new JsonArray();
            jars.forEach(one -> {
                String file = one.getAsJsonObject().get("file").getAsString();
                if (taken.contains(file)) {
                    JsonObject entry = one.getAsJsonObject().deepCopy();
                    entry.addProperty("file", this.mapNestedName(file));
                    kept.add(entry);
                }
            });
            manifest.add("jars", kept);
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
        Matcher slashed = SLASHED.matcher(text);
        StringBuilder out = new StringBuilder();
        while (slashed.find()) {
            String token = slashed.group();
            String mapped = token;
            if (this.isOurs(token)) {
                mapped = this.prefix + token;
            } else if (token.length() > 1 && token.charAt(0) == 'L' && this.isOurs(token.substring(1))) {
                mapped = "L" + this.prefix + token.substring(1);
            }
            slashed.appendReplacement(out, Matcher.quoteReplacement(mapped));
        }
        slashed.appendTail(out);
        Matcher dotted = DOTTED.matcher(out);
        StringBuilder done = new StringBuilder();
        while (dotted.find()) {
            String token = dotted.group();
            boolean ours = this.isOurs(token.replace('.', '/'));
            dotted.appendReplacement(done,
                    Matcher.quoteReplacement(ours ? this.prefix.replace('/', '.') + token : token));
        }
        dotted.appendTail(done);
        return done.toString();
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
            return this.containsKey(name) ? this.read(String.valueOf(name)) : null;
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
