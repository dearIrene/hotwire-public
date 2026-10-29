package dev.moon.hotwire.update;

import dev.moon.hotwire.jar.ModJarException;
import dev.moon.hotwire.jar.ModJarReader;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.jar.ModManifestParser;
import dev.moon.hotwire.update.JarDiff.ClassChange;
import dev.moon.hotwire.update.JarDiff.ClassDiff;
import dev.moon.hotwire.update.JarDiff.ShapeChange;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.RecordComponentNode;

public final class UpdateDiffer {
    private static final String CLASS_SUFFIX = ".class";

    private static final int FLAGS = 0xFFFF;

    private UpdateDiffer() {
    }

    public static JarDiff compare(Path running, Path incoming) throws ModJarException {
        return compare(readEntries(running), readEntries(incoming));
    }

    public static JarDiff compare(Map<String, byte[]> running, Map<String, byte[]> incoming)
            throws ModJarException {
        ModManifest was = readManifest(running, "the running jar");
        ModManifest now = readManifest(incoming, "the new jar");
        Set<String> paths = new TreeSet<>(running.keySet());
        paths.addAll(incoming.keySet());
        List<ClassDiff> classes = new ArrayList<>();
        List<String> resources = new ArrayList<>();
        for (String path : paths) {
            byte[] before = running.get(path);
            byte[] after = incoming.get(path);
            if (isClass(path)) {
                classes.add(compareEntry(path.substring(0, path.length() - CLASS_SUFFIX.length()), before, after));
            } else if (before == null || after == null || !Arrays.equals(before, after)) {
                resources.add(path);
            }
        }
        return new JarDiff(was, now, classes, resources);
    }

    static ClassDiff compareClass(byte[] running, byte[] incoming) {
        ClassNode was = read(running);
        if (Arrays.equals(running, incoming)) {
            return new ClassDiff(was.name, ClassChange.IDENTICAL, Set.of());
        }
        ClassNode now = read(incoming);
        Set<ShapeChange> shape = diffShape(was, now);
        if (!shape.isEmpty()) {
            return new ClassDiff(was.name, ClassChange.SHAPE, shape);
        }
        ClassChange change = Arrays.equals(canonicalize(running), canonicalize(incoming))
                ? ClassChange.CONSTANT_POOL
                : ClassChange.BODIES;
        return new ClassDiff(was.name, change, Set.of());
    }

    private static ClassDiff compareEntry(String name, byte @Nullable [] before, byte @Nullable [] after) {
        if (before == null) {
            return new ClassDiff(name, ClassChange.ADDED, Set.of());
        }
        if (after == null) {
            return new ClassDiff(name, ClassChange.REMOVED, Set.of());
        }
        return compareClass(before, after);
    }

    static Set<ShapeChange> diffShape(ClassNode was, ClassNode now) {
        Set<ShapeChange> shape = EnumSet.noneOf(ShapeChange.class);
        if (!was.name.equals(now.name)) {
            shape.add(ShapeChange.CLASS_NAME);
        }
        if ((was.access & FLAGS) != (now.access & FLAGS)) {
            int differing = (was.access ^ now.access) & FLAGS;
            boolean harmless = (differing & ~(Opcodes.ACC_FINAL | Opcodes.ACC_SUPER | Opcodes.ACC_SYNTHETIC)) == 0;
            shape.add(harmless ? ShapeChange.CLASS_FINALITY : ShapeChange.CLASS_MODIFIERS);
        }
        if (!Objects.equals(was.superName, now.superName)) {
            shape.add(ShapeChange.SUPERCLASS);
        }
        if (!was.interfaces.equals(now.interfaces)) {
            shape.add(ShapeChange.INTERFACES);
        }
        compareFields(was.fields, now.fields, shape);
        compareMethods(was.methods, now.methods, shape, (now.access & Opcodes.ACC_INTERFACE) != 0);
        if (!Objects.equals(was.nestHostClass, now.nestHostClass)
                || !asSet(was.nestMembers).equals(asSet(now.nestMembers))) {
            shape.add(ShapeChange.NEST);
        }
        if (!asSet(was.permittedSubclasses).equals(asSet(now.permittedSubclasses))) {
            shape.add(ShapeChange.PERMITTED_SUBCLASSES);
        }
        if (!listComponents(was.recordComponents).equals(listComponents(now.recordComponents))) {
            shape.add(ShapeChange.RECORD_COMPONENTS);
        }
        return shape;
    }

    private static void compareFields(List<FieldNode> was, List<FieldNode> now, Set<ShapeChange> shape) {
        Map<String, Integer> before = new LinkedHashMap<>();
        was.forEach(f -> before.put(f.name + ":" + f.desc, f.access & FLAGS));
        Map<String, Integer> after = new LinkedHashMap<>();
        now.forEach(f -> after.put(f.name + ":" + f.desc, f.access & FLAGS));
        compareMembers(before, after, shape, ShapeChange.FIELD_ADDED, ShapeChange.FIELD_REMOVED,
                ShapeChange.FIELD_MODIFIERS);
        List<String> retained = new ArrayList<>(before.keySet());
        retained.retainAll(after.keySet());
        List<String> retainedNow = new ArrayList<>(after.keySet());
        retainedNow.retainAll(before.keySet());
        if (!retained.equals(retainedNow)) {
            shape.add(ShapeChange.FIELD_ORDER);
        }
    }

    private static void compareMethods(List<MethodNode> was, List<MethodNode> now, Set<ShapeChange> shape,
            boolean isInterface) {
        Map<String, Integer> before = new HashMap<>();
        was.forEach(m -> before.put(m.name + m.desc, m.access & FLAGS));
        Map<String, Integer> after = new HashMap<>();
        now.forEach(m -> after.put(m.name + m.desc, m.access & FLAGS));
        compareMembers(before, after, shape, ShapeChange.METHOD_ADDED, ShapeChange.METHOD_REMOVED,
                ShapeChange.METHOD_MODIFIERS);
        if (now.stream().anyMatch(m -> !before.containsKey(m.name + m.desc)
                && (m.access & Opcodes.ACC_NATIVE) != 0)) {
            shape.add(ShapeChange.BODILESS_METHOD_ADDED);
        }
        if (now.stream().anyMatch(m -> !before.containsKey(m.name + m.desc)
                && (m.access & Opcodes.ACC_ABSTRACT) != 0)) {
            shape.add(isInterface ? ShapeChange.INTERFACE_METHOD_ADDED : ShapeChange.BODILESS_METHOD_ADDED);
        }
        if (now.stream().anyMatch(m -> !before.containsKey(m.name + m.desc) && m.name.equals("<init>"))) {
            shape.add(ShapeChange.CONSTRUCTOR_ADDED);
        }
    }

    private static void compareMembers(Map<String, Integer> before, Map<String, Integer> after,
            Set<ShapeChange> shape, ShapeChange added, ShapeChange removed, ShapeChange modifiers) {
        for (Map.Entry<String, Integer> member : after.entrySet()) {
            Integer access = before.get(member.getKey());
            if (access == null) {
                shape.add(added);
            } else if (!access.equals(member.getValue())) {
                shape.add(modifiers);
            }
        }
        if (!after.keySet().containsAll(before.keySet())) {
            shape.add(removed);
        }
    }

    private static Set<String> asSet(@Nullable List<String> names) {
        return names == null ? Set.of() : new HashSet<>(names);
    }

    private static List<String> listComponents(@Nullable List<RecordComponentNode> components) {
        List<String> out = new ArrayList<>();
        if (components != null) {
            components.forEach(c -> out.add(c.name + ":" + c.descriptor));
        }
        return out;
    }

    private static byte[] canonicalize(byte[] bytes) {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes).accept(writer, 0);
        return writer.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static boolean isClass(String path) {
        return path.endsWith(CLASS_SUFFIX) && !path.startsWith("META-INF/") && !path.endsWith("module-info.class");
    }

    private static ModManifest readManifest(Map<String, byte[]> entries, String jar) throws ModJarException {
        byte[] json = entries.get(ModJarReader.MANIFEST);
        if (json == null) {
            throw new ModJarException(ModJarException.Reason.NOT_A_MOD, jar, "it has no " + ModJarReader.MANIFEST);
        }
        return ModManifestParser.parse(json, jar);
    }

    public static Map<String, byte[]> readEntries(Path jar) throws ModJarException {
        String name = String.valueOf(jar.getFileName());
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : zip.stream().toList()) {
                if (entry.isDirectory()) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    entries.put(entry.getName(), in.readAllBytes());
                }
            }
        } catch (IOException e) {
            throw new ModJarException(ModJarException.Reason.UNREADABLE, name, "it could not be read as a jar", e);
        }
        return entries;
    }
}
