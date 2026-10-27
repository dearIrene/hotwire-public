package dev.moon.hotwire.redefine;

import java.io.IOException;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class RebuildEvidence {
    private static final Logger LOGGER = LoggerFactory.getLogger(RebuildEvidence.class);

    private RebuildEvidence() {
    }

    static void dump(String name, byte[] running, byte[] mixed, ClassSplit.Result split) {
        String folder = System.getProperty("hotwire.dump");
        if (folder == null) {
            return;
        }
        try {
            Path base = Path.of(folder).resolve(name.replace('/', '.') + "." + System.currentTimeMillis());
            Files.createDirectories(base);
            Files.write(base.resolve("running.class"), running);
            Files.write(base.resolve("mixed.class"), mixed);
            Files.write(base.resolve("target.class"), split.target());
            Files.write(base.resolve("sidecar.class"), split.sidecar());
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] could not dump {} to {}", name, folder, e);
        }
    }

    static List<String> listLiveFields(Class<?> type) {
        List<String> out = new ArrayList<>();
        try {
            for (Field field : type.getDeclaredFields()) {
                Class<?> of = field.getType();

                out.add(Integer.toHexString(field.getModifiers()) + " " + field.getName() + " "
                        + (of == null ? "<no type>" : of.descriptorString()));
            }
        } catch (LinkageError e) {
            out.add("unreadable: " + e);
        }
        return out;
    }

    static void dumpCopy(String name, byte[] bytes) {
        dumpCopyTo(System.getProperty("hotwire.dump"), name, bytes);
    }

    static void dumpCopyTo(@Nullable String folder, String name, byte[] bytes) {
        if (folder == null) {
            return;
        }
        try {
            Path base = Path.of(folder).resolve(name.replace('/', '.') + ".copy." + System.currentTimeMillis());
            Files.createDirectories(base);
            Files.write(base.resolve("copy.class"), bytes);
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] the copy's mix of {} could not be written out: {}", name, e.toString());
        }
    }

    static void dumpReadBack(String name, byte @Nullable [] bytes) {
        String folder = System.getProperty("hotwire.dump");
        if (folder == null || bytes == null) {
            return;
        }
        try {
            Path base = Path.of(folder).resolve(name.replace('/', '.') + ".readback");
            Files.createDirectories(base);
            Files.write(base.resolve("jvm.class"), bytes);
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] could not write the read-back bytes of {}", name, e);
        }
    }

    static Set<String> listMethods(byte @Nullable [] bytes) {
        Set<String> out = new TreeSet<>();
        if (bytes != null) {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
            node.methods.forEach(method -> out.add(method.name + method.desc));
        }
        return out;
    }

    static List<String> listFields(byte @Nullable [] bytes) {
        if (bytes == null) {
            return List.of("unreadable");
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
        List<String> out = new ArrayList<>();

        node.fields.forEach(field -> out.add(Integer.toHexString(field.access & ~Opcodes.ACC_DEPRECATED) + " "
                + field.name + " " + field.desc));
        return out;
    }

    static String describeFieldDifferences(ClassRebuilder.PreparedRebuild prepared, LoadedClassBytes loadedBytes,
            Instrumentation instrumentation) {
        Map<String, Class<?>> byName = new LinkedHashMap<>();
        prepared.splits().keySet().forEach(type -> byName.put(type.getName().replace('.', '/'), type));
        Map<String, byte[]> fresh = new ConcurrentHashMap<>();
        List<String> differences = new ArrayList<>();
        byName.forEach((name, type) -> {
            Map<String, String> refused = new LinkedHashMap<>();
            try {
                loadedBytes.capture(Map.of(name, type), fresh, refused);
            } catch (RebuildException | LinkageError e) {
                refused.put(name, e.toString());
            }
            refused.forEach((one, why) -> differences.add(one + " cannot be read back from the JVM (" + why + ")"));
        });
        prepared.splits().forEach((type, split) -> {
            String name = type.getName().replace('.', '/');
            byte[] jvm = fresh.containsKey(name) ? fresh.get(name) : loadedBytes.findKnown(type);
            List<String> ours = listFields(split.target());
            Set<String> missing = new TreeSet<>(listMethods(jvm));
            missing.removeAll(listMethods(split.target()));
            Set<String> gained = new TreeSet<>(listMethods(split.target()));
            gained.removeAll(listMethods(jvm));
            if (!missing.isEmpty() || !gained.isEmpty()) {
                differences.add(name + ": methods the JVM has that the new bytes lack " + missing + ", methods only the"
                        + " new bytes have " + gained);
            }
            if (!listFields(jvm).equals(ours) || !listLiveFields(type).equals(ours)) {
                differences.add(name + ": read back " + listFields(jvm) + ", live "
                        + listLiveFields(type) + ", held "
                        + listFields(loadedBytes.findKnown(type)) + ", new " + ours);
                dumpReadBack(name, jvm);
            }

            try {
                instrumentation.redefineClasses(new ClassDefinition(type, Objects.requireNonNull(jvm)));
            } catch (ClassNotFoundException | UnmodifiableClassException | UnsupportedOperationException
                    | LinkageError | InternalError e) {
                differences.add(name + " refuses even the bytes Hotwire has for it: " + e);
            }
        });
        return differences.isEmpty() ? "none" : String.join("; ", differences);
    }
}
