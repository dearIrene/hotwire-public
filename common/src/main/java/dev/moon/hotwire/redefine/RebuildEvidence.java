package dev.moon.hotwire.redefine;

import dev.moon.hotwire.classfile.ClassHeader;
import dev.moon.hotwire.config.Flags;
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
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class RebuildEvidence {
    private static final Logger LOGGER = LoggerFactory.getLogger(RebuildEvidence.class);

    private RebuildEvidence() {
    }

    static void dump(String name, byte[] running, byte[] mixed, ClassSplit.Result split) {
        String folder = Flags.DUMP;
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

    static void dumpFailed(String name, byte[] running, byte[] mixed) {
        String folder = Flags.DUMP;
        if (folder == null) {
            return;
        }
        try {
            Path base = Path.of(folder).resolve(name.replace('/', '.') + ".failed." + System.currentTimeMillis());
            Files.createDirectories(base);
            Files.write(base.resolve("running.class"), running);
            Files.write(base.resolve("mixed.class"), mixed);
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] could not dump {} to {}", name, folder, e);
        }
    }

    static void checkRunsAsGiven(Class<?> type, byte[] given, Predicate<String> loaded) {
        for (ClassHeader.Member field : ClassHeader.of(given).fields()) {
            String binary = FieldSlots.findClassName(field.desc());
            if (binary != null && !FieldSlots.isLoaded(binary, loaded)) {
                return;
            }
        }
        List<String> live = listLiveFields(type);
        List<String> fields = listFields(given);
        if (!live.equals(fields)) {
            LOGGER.error("[Hotwire] After redefining {}, the JVM runs it with fields {} where it was given {}; the"
                    + " next change of this class will be refused. Please report this with the log.", type.getName(),
                    live, fields);
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
        dumpCopyTo(Flags.DUMP, name, bytes);
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
        String folder = Flags.DUMP;
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

    static List<String> findShapeChanges(ClassRebuilder.PreparedRebuild prepared, Map<String, byte[]> against) {
        List<String> out = new ArrayList<>();
        prepared.splits().forEach((type, split) -> {
            String name = Type.getInternalName(type);
            byte[] now = against.get(name);
            String change = now == null ? null : describeShapeChange(now, split.target());
            if (change != null) {
                out.add(name + ": " + change);
            }
        });
        return out;
    }

    static @Nullable String describeShapeChange(byte[] now, byte[] target) {
        List<String> changes = new ArrayList<>();
        Set<String> missing = new TreeSet<>(listMethods(now));
        missing.removeAll(listMethods(target));
        Set<String> gained = new TreeSet<>(listMethods(target));
        gained.removeAll(listMethods(now));
        if (!missing.isEmpty()) {
            changes.add("methods it runs that the rebuild lacks " + missing);
        }
        if (!gained.isEmpty()) {
            changes.add("methods only the rebuild has " + gained);
        }
        if (!listFieldNames(now).equals(listFieldNames(target))) {
            changes.add("fields " + listFieldNames(now) + " become " + listFieldNames(target));
        }
        ClassHeader before = ClassHeader.of(now);
        ClassHeader after = ClassHeader.of(target);
        if (!Set.copyOf(before.interfaces()).equals(Set.copyOf(after.interfaces()))
                || !Objects.equals(before.superName(), after.superName())) {
            changes.add("interfaces " + before.interfaces() + " become " + after.interfaces());
        }
        return changes.isEmpty() ? null : String.join(", ", changes);
    }

    private static Set<String> listFieldNames(byte[] bytes) {
        Set<String> out = new TreeSet<>();
        ClassHeader.of(bytes).fields().forEach(field -> out.add(field.name() + " " + field.desc()));
        return out;
    }

    static Set<String> listMethods(byte @Nullable [] bytes) {
        Set<String> out = new TreeSet<>();
        if (bytes != null) {
            ClassHeader.of(bytes).methods().forEach(method -> out.add(method.name() + method.desc()));
        }
        return out;
    }

    static List<String> listFields(byte @Nullable [] bytes) {
        if (bytes == null) {
            return List.of("unreadable");
        }
        List<String> out = new ArrayList<>();

        ClassHeader.of(bytes).fields().forEach(field -> out.add(Integer.toHexString(field.access()
                & ~Opcodes.ACC_DEPRECATED) + " " + field.name() + " " + field.desc()));
        return out;
    }

    static String describeFieldDifferences(ClassRebuilder.PreparedRebuild prepared, LoadedClassBytes loadedBytes,
            Instrumentation instrumentation) {
        Map<String, Class<?>> byName = new LinkedHashMap<>();
        prepared.splits().keySet().forEach(type -> byName.put(Type.getInternalName(type), type));
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
            String name = Type.getInternalName(type);
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
