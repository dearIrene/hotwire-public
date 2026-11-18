package dev.moon.hotwire.update;

import dev.moon.hotwire.classfile.ClassNodes;
import dev.moon.hotwire.jar.ModJarException;
import dev.moon.hotwire.jar.ModJarReader;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.jar.ModManifestParser;
import dev.moon.hotwire.jar.ZipBudget;
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
import java.util.function.Predicate;
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

    static final int FLAGS = 0xFFFF;

    private UpdateDiffer() {
    }

    public static JarDiff compare(Path running, Path incoming) throws ModJarException {
        try (ZipFile before = new ZipFile(running.toFile()); ZipFile after = new ZipFile(incoming.toFile())) {
            Set<String> paths = new TreeSet<>();
            before.stream().filter(entry -> !entry.isDirectory()).forEach(entry -> paths.add(entry.getName()));
            after.stream().filter(entry -> !entry.isDirectory()).forEach(entry -> paths.add(entry.getName()));
            List<String> resources = new ArrayList<>();
            for (String path : paths) {
                if (!isClass(path) && !isSame(before, after, path)) {
                    resources.add(path);
                }
            }
            return compare(readEntries(before, UpdateDiffer::isLookedInto),
                    readEntries(after, UpdateDiffer::isLookedInto), resources);
        } catch (IOException e) {
            throw new ModJarException(ModJarException.Reason.UNREADABLE, String.valueOf(incoming.getFileName()),
                    "it could not be read as a jar", e);
        }
    }

    private static boolean isLookedInto(String path) {
        return path.endsWith(CLASS_SUFFIX) || path.equals(ModJarReader.MANIFEST);
    }

    private static boolean isSame(ZipFile before, ZipFile after, String path) throws IOException {
        ZipEntry was = before.getEntry(path);
        ZipEntry now = after.getEntry(path);
        if (was == null || now == null || was.getSize() != now.getSize() || was.getCrc() != now.getCrc()) {
            return false;
        }
        byte[] one = new byte[1 << 16];
        byte[] other = new byte[1 << 16];
        try (InputStream first = before.getInputStream(was); InputStream second = after.getInputStream(now)) {
            while (true) {
                int read = first.readNBytes(one, 0, one.length);
                int also = second.readNBytes(other, 0, other.length);
                if (!Arrays.equals(one, 0, read, other, 0, also)) {
                    return false;
                }
                if (read < one.length) {
                    return true;
                }
            }
        }
    }

    public static JarDiff compare(Map<String, byte[]> running, Map<String, byte[]> incoming)
            throws ModJarException {
        Set<String> paths = new TreeSet<>(running.keySet());
        paths.addAll(incoming.keySet());
        List<String> resources = new ArrayList<>();
        for (String path : paths) {
            byte[] before = running.get(path);
            if (!isClass(path) && (before == null || !Arrays.equals(before, incoming.get(path)))) {
                resources.add(path);
            }
        }
        return compare(running, incoming, resources);
    }

    private static JarDiff compare(Map<String, byte[]> running, Map<String, byte[]> incoming, List<String> resources)
            throws ModJarException {
        ModManifest was = readManifest(running, "the running jar");
        ModManifest now = readManifest(incoming, "the new jar");
        Set<String> paths = new TreeSet<>(running.keySet());
        paths.addAll(incoming.keySet());
        List<ClassDiff> classes = new ArrayList<>();
        for (String path : paths) {
            if (isClass(path)) {
                classes.add(compareEntry(path.substring(0, path.length() - CLASS_SUFFIX.length()), running.get(path),
                        incoming.get(path)));
            }
        }
        List<ClassDiff> refined = HierarchyShapes.classifyAbstracts(
                ConstructorUses.classifyChanges(classes, running, incoming), incoming);
        return new JarDiff(was, now, refined, resources, OpenedAccess.find(running, incoming, refined),
                ConstructorUses.findAbstractsGained(refined, running, incoming),
                ConstructorUses.findChains(refined, running, incoming),
                StaticReads.findStartedElsewhere(refined, incoming), ReplacedFields.find(refined, running, incoming));
    }

    static ClassDiff compareClass(byte[] running, byte[] incoming) {
        if (Arrays.equals(running, incoming)) {
            return new ClassDiff(new ClassReader(running).getClassName(), ClassChange.IDENTICAL, Set.of());
        }
        ClassNode was = read(running);
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
        HierarchyShapes.addFlagChange(was, now, shape);
        if (!Objects.equals(was.superName, now.superName)) {
            shape.add(ShapeChange.SUPERCLASS);
        }
        if (!was.interfaces.equals(now.interfaces)) {
            shape.add(new HashSet<>(was.interfaces).containsAll(now.interfaces) ? ShapeChange.INTERFACES_DROPPED
                    : ShapeChange.INTERFACES);
        }
        compareFields(was.fields, now.fields, shape);
        if (ConstructorUses.hasStaticSetElsewhere(was, now)) {
            shape.add(ShapeChange.STATIC_SET_ELSEWHERE);
        }
        Set<String> constants = new HashSet<>();
        was.fields.forEach(field -> constants.add(field.name));
        if (now.fields.stream().anyMatch(field -> (field.access & Opcodes.ACC_ENUM) != 0
                && !constants.contains(field.name))) {
            shape.add(ShapeChange.ENUM_CONSTANT_ADDED);
        }
        compareMethods(was.methods, now.methods, shape);
        if (!Objects.equals(was.nestHostClass, now.nestHostClass)
                || !asSet(was.nestMembers).equals(asSet(now.nestMembers))) {
            shape.add(ShapeChange.NEST);
        }
        if (!asSet(was.permittedSubclasses).equals(asSet(now.permittedSubclasses))) {
            shape.add(HierarchyShapes.classifyPermitted(asSet(was.permittedSubclasses),
                    asSet(now.permittedSubclasses)));
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

    private static void compareMethods(List<MethodNode> was, List<MethodNode> now, Set<ShapeChange> shape) {
        Map<String, Integer> before = new HashMap<>();
        was.forEach(m -> before.put(m.name + m.desc, m.access & FLAGS));
        Map<String, Integer> after = new HashMap<>();
        now.forEach(m -> after.put(m.name + m.desc, m.access & FLAGS));
        compareMembers(before, after, shape, ShapeChange.METHOD_ADDED, ShapeChange.METHOD_REMOVED,
                ShapeChange.METHOD_MODIFIERS);
        if (now.stream().anyMatch(m -> ((before.getOrDefault(m.name + m.desc, m.access) ^ m.access)
                & Opcodes.ACC_NATIVE) != 0)) {
            shape.add(ShapeChange.METHOD_KIND);
        }
        if (now.stream().anyMatch(m -> ((before.getOrDefault(m.name + m.desc, m.access) ^ m.access)
                & Opcodes.ACC_ABSTRACT) != 0)) {
            shape.add(ShapeChange.METHOD_ABSTRACTNESS);
        }
        if (now.stream().anyMatch(m -> !before.containsKey(m.name + m.desc)
                && (m.access & Opcodes.ACC_NATIVE) != 0)) {
            shape.add(ShapeChange.BODILESS_METHOD_ADDED);
        }
        if (now.stream().anyMatch(m -> !before.containsKey(m.name + m.desc)
                && (m.access & Opcodes.ACC_ABSTRACT) != 0)) {
            shape.add(ShapeChange.INTERFACE_METHOD_ADDED);
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
        return ClassNodes.read(bytes, 0);
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
        return readEntries(jar, path -> true);
    }

    public static Map<String, byte[]> readEntries(Path jar, Predicate<String> wanted) throws ModJarException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return readEntries(zip, wanted);
        } catch (IOException e) {
            throw new ModJarException(ModJarException.Reason.UNREADABLE, String.valueOf(jar.getFileName()),
                    "it could not be read as a jar", e);
        }
    }

    private static Map<String, byte[]> readEntries(ZipFile zip, Predicate<String> wanted) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        ZipBudget budget = new ZipBudget();
        for (ZipEntry entry : zip.stream().toList()) {
            if (entry.isDirectory() || !wanted.test(entry.getName())) {
                continue;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                entries.put(entry.getName(), budget.read(in));
            }
        }
        return entries;
    }
}
