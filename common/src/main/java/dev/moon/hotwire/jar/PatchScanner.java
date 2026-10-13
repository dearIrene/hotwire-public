package dev.moon.hotwire.jar;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.moon.hotwire.jar.ModManifest.MixinConfigRef;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

final class PatchScanner {
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String PSEUDO = "Lorg/spongepowered/asm/mixin/Pseudo;";
    private static final List<String> MIXIN_ARRAYS = List.of("mixins", "client", "server");
    private static final int METHOD_ENTRY = 10;
    private static final int INTERFACE_METHOD_ENTRY = 11;

    private static final List<String> RUNS_ALWAYS = List.of("main", "client", "server", "preLaunch");

    private static final List<String> OUTSIDE = List.of("java/", "javax/", "jdk/", "sun/", "net/minecraft/",
            "com/mojang/");

    private PatchScanner() {
    }

    static ModPatches scan(ModManifest manifest, Collection<String> entries, ModJarReader.EntryReader reader)
            throws IOException {
        Set<String> owns = new TreeSet<>();
        for (String entry : entries) {
            if (entry.endsWith(".class") && !entry.startsWith("META-INF/") && !entry.endsWith("module-info.class")
                    && !entry.endsWith("package-info.class")) {
                owns.add(entry.substring(0, entry.length() - ".class".length()));
            }
        }
        Set<String> patches = new TreeSet<>();
        Set<String> hooked = new HashSet<>();
        Set<String> tied = new HashSet<>();
        List<String> missing = new ArrayList<>();
        Map<String, Set<String>> byConfig = new LinkedHashMap<>();
        Set<String> named = new HashSet<>();
        for (MixinConfigRef ref : manifest.mixinConfigs()) {
            byte[] config = reader.read(ref.config());
            if (config == null) {
                missing.add(ref.config());
                continue;
            }
            Set<String> configTargets = byConfig.computeIfAbsent(ref.config(), name -> new TreeSet<>());
            List<String> listed = readMixinClasses(config);
            Refmap refmap = Refmap.of(config, reader);
            boolean required = isRequired(config);
            for (String mixin : listed) {
                byte[] bytes = reader.read(mixin + ".class");
                if (bytes == null) {
                    if (required) {
                        missing.add(mixin);
                    }
                    continue;
                }
                Mixin read = read(bytes, mixin, refmap);
                patches.addAll(read.targets());
                configTargets.addAll(read.targets());
                (read.hook() ? hooked : tied).addAll(read.targets());
                collectNamedIfInTheGame(read, bytes, named);
            }
            String pluginPackage = readPluginPackage(config);
            if (pluginPackage != null) {
                Set<String> known = new HashSet<>(listed);
                for (String entry : entries) {
                    String name = entry.endsWith(".class") ? entry.substring(0, entry.length() - ".class".length())
                            : "";
                    byte[] bytes = name.startsWith(pluginPackage) && !known.contains(name) ? reader.read(entry) : null;
                    if (bytes != null) {
                        Mixin added = read(bytes, name, refmap);
                        configTargets.addAll(added.targets());
                        collectNamedIfInTheGame(added, bytes, named);
                    }
                }
            }
        }
        hooked.removeAll(tied);

        Deque<String> reached = new ArrayDeque<>();
        named.stream().filter(owns::contains).forEach(reached::add);
        for (String key : RUNS_ALWAYS) {
            for (ModManifest.Entrypoint entrypoint : manifest.entrypoints().getOrDefault(key, List.of())) {
                String value = entrypoint.value();
                int member = value.indexOf("::");
                String type = (member < 0 ? value : value.substring(0, member)).replace('.', '/');
                if (owns.contains(type)) {
                    reached.add(type);
                }
            }
        }
        Set<String> read = new HashSet<>();
        while (!reached.isEmpty()) {
            String type = reached.poll();
            byte[] bytes = read.add(type) ? reader.read(type + ".class") : null;
            if (bytes == null) {
                continue;
            }
            Set<String> refs = new HashSet<>();
            collectNamedClasses(bytes, refs);
            for (String ref : refs) {
                if (owns.contains(ref)) {
                    if (!read.contains(ref)) {
                        reached.add(ref);
                    }
                } else {
                    named.add(ref);
                }
            }
        }
        Set<String> ownPackages = new HashSet<>();
        owns.forEach(name -> ownPackages.add(getPackageOf(name)));
        Set<String> calls = new TreeSet<>();
        named.removeAll(patches);
        byConfig.values().forEach(named::removeAll);
        named.forEach(name -> {
            String in = getPackageOf(name);
            if (!ownPackages.contains(in) && OUTSIDE.stream().noneMatch(in::startsWith)) {
                calls.add(in);
            }
        });
        return new ModPatches(owns, patches, hooked, missing, byConfig, calls);
    }

    private static void collectNamedIfInTheGame(Mixin mixin, byte[] bytes, Set<String> into) {
        if (!mixin.targets().isEmpty() && mixin.targets().stream().allMatch(
                target -> target.startsWith("net/minecraft/") || target.startsWith("com/mojang/"))) {
            collectNamedClasses(bytes, into);
        }
    }

    private static void collectNamedClasses(byte[] bytes, Set<String> into) {
        ClassReader reader;
        try {
            reader = new ClassReader(bytes);
        } catch (RuntimeException e) {
            return;
        }
        char[] buffer = new char[reader.getMaxStringLength()];
        for (int item = 1; item < reader.getItemCount(); item++) {
            int offset = reader.getItem(item);
            if (offset == 0 || (bytes[offset - 1] != METHOD_ENTRY && bytes[offset - 1] != INTERFACE_METHOD_ENTRY)) {
                continue;
            }
            String name = reader.readClass(offset, buffer);
            if (name.startsWith("[")) {
                int element = name.lastIndexOf('[') + 1;
                if (name.charAt(element) != 'L') {
                    continue;
                }
                name = name.substring(element + 1, name.length() - 1);
            }
            into.add(name);
        }
    }

    private static String getPackageOf(String name) {
        int slash = name.lastIndexOf('/');
        return slash < 0 ? "" : name.substring(0, slash);
    }

    static List<PluginQuestions> readPluginQuestions(Path jar, ModManifest manifest) throws IOException {
        List<PluginQuestions> out = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ModJarReader.EntryReader reader = entry -> {
                ZipEntry found = zip.getEntry(entry);
                if (found == null) {
                    return null;
                }
                try (InputStream in = zip.getInputStream(found)) {
                    return in.readAllBytes();
                }
            };
            List<String> names = zip.stream().map(ZipEntry::getName).toList();
            for (MixinConfigRef ref : manifest.mixinConfigs()) {
                byte[] config = ref.environment() == ModManifest.Environment.SERVER ? null : reader.read(ref.config());
                if (config == null) {
                    continue;
                }
                JsonObject root = parseObject(config);
                if (root == null || !isText(root.get("plugin")) || !isText(root.get("package"))) {
                    continue;
                }
                String pkg = root.get("package").getAsString();
                String prefix = pkg.replace('.', '/') + "/";
                List<String> listed = new ArrayList<>();
                for (String key : List.of("mixins", "client")) {
                    JsonElement array = root.get(key);
                    if (array != null && array.isJsonArray()) {
                        array.getAsJsonArray().forEach(entry -> {
                            if (isText(entry) && !entry.getAsString().isBlank()) {
                                listed.add(pkg + "." + entry.getAsString());
                            }
                        });
                    }
                }
                Refmap refmap = Refmap.of(config, reader);
                Map<String, Set<String>> targets = new LinkedHashMap<>();
                for (String name : names) {
                    byte[] bytes = name.startsWith(prefix) && name.endsWith(".class") ? reader.read(name) : null;
                    if (bytes != null) {
                        String internal = name.substring(0, name.length() - ".class".length());
                        Set<String> patched = new TreeSet<>();
                        for (String target : read(bytes, internal, refmap).targets()) {
                            patched.add(target.replace('/', '.'));
                        }
                        if (!patched.isEmpty()) {
                            targets.put(internal.replace('/', '.'), patched);
                        }
                    }
                }
                out.add(new PluginQuestions(ref.config(), root.get("plugin").getAsString(), pkg, !root.has("refmap"),
                        listed, targets));
            }
        }
        return out;
    }

    private static @Nullable JsonObject parseObject(byte[] json) {
        try {
            JsonElement parsed = JsonParser.parseString(new String(json, StandardCharsets.UTF_8));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (JsonParseException e) {
            return null;
        }
    }

    private static boolean isText(@Nullable JsonElement element) {
        return element != null && element.isJsonPrimitive();
    }

    private static @Nullable String readPluginPackage(byte[] config) {
        JsonObject root = parseObject(config);
        if (root == null || !isText(root.get("plugin")) || !isText(root.get("package"))) {
            return null;
        }
        return root.get("package").getAsString().replace('.', '/') + "/";
    }

    private static boolean isRequired(byte[] config) {
        JsonObject root = parseObject(config);
        JsonElement required = root == null ? null : root.get("required");
        return required != null && required.isJsonPrimitive() && required.getAsJsonPrimitive().isBoolean()
                && required.getAsBoolean();
    }

    private static List<String> readMixinClasses(byte[] config) {
        JsonObject root = parseObject(config);
        if (root == null || !isText(root.get("package"))) {
            return List.of();
        }
        String prefix = root.get("package").getAsString().replace('.', '/') + "/";
        List<String> out = new ArrayList<>();
        for (String key : MIXIN_ARRAYS) {
            JsonElement array = root.get(key);
            if (array != null && array.isJsonArray()) {
                array.getAsJsonArray().forEach(entry -> {
                    if (entry.isJsonPrimitive() && !entry.getAsString().isBlank()) {
                        out.add(prefix + entry.getAsString().replace('.', '/'));
                    }
                });
            }
        }
        return out;
    }

    private record Mixin(Set<String> targets, boolean hook) {
    }

    private static Mixin read(byte[] bytes, String mixin, Refmap refmap) {
        Set<String> targets = new TreeSet<>();
        boolean[] pseudo = {false};
        ClassReader reader = new ClassReader(bytes);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public @Nullable AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (PSEUDO.equals(descriptor)) {
                    pseudo[0] = true;
                }
                return MIXIN.equals(descriptor) ? new Targets(targets, written -> refmap.getTarget(mixin, written))
                        : null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        boolean plain = (reader.getAccess() & Opcodes.ACC_INTERFACE) == 0 && reader.getInterfaces().length == 0;
        return new Mixin(targets, pseudo[0] && plain);
    }

    private static final class Targets extends AnnotationVisitor {
        private final Set<String> into;
        private final UnaryOperator<String> named;

        Targets(Set<String> into, UnaryOperator<String> named) {
            super(Opcodes.ASM9);
            this.into = into;
            this.named = named;
        }

        @Override
        public @Nullable AnnotationVisitor visitArray(String name) {
            return "value".equals(name) || "targets".equals(name) ? this : null;
        }

        @Override
        public void visit(@Nullable String name, Object value) {
            if (value instanceof Type type) {
                this.into.add(type.getInternalName());
            } else if (value instanceof String target && !target.isBlank()) {
                this.into.add(this.named.apply(target));
            }
        }
    }
}
