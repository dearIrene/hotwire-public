package dev.moon.hotwire.update;

import dev.moon.hotwire.classfile.ClassHeader;
import dev.moon.hotwire.jar.ZipBytes;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;

public final class JarLinks {
    private static final List<String> OWN_CLASSES_OF_NOBODY = List.of("java/", "javax/", "jdk/", "sun/", "[");
    private static final String CLASS_SUFFIX = ".class";
    private static final String JAR_SUFFIX = ".jar";
    private static final int CLASS_ITEM = 7;
    private static final int FIELD_ITEM = 9;
    private static final int METHOD_ITEM = 10;
    private static final int INTERFACE_METHOD_ITEM = 11;

    private JarLinks() {
    }

    private static void forEachClass(InputStream jar, Consumer<byte[]> each) throws IOException {
        ZipInputStream zip = new ZipInputStream(jar);
        for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
            if (entry.getName().endsWith(CLASS_SUFFIX)) {
                each.accept(zip.readAllBytes());
            } else if (entry.getName().endsWith(JAR_SUFFIX)) {
                forEachClass(new ByteArrayInputStream(zip.readAllBytes()), each);
            }
        }
    }

    public static Set<String> readClassNames(Path jar) throws IOException {
        Set<String> names = new HashSet<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                addClassName(entry.getName(), names);
                if (entry.getName().endsWith(JAR_SUFFIX)) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        collectNames(in.readAllBytes(), names);
                    }
                }
            }
        }
        return names;
    }

    private static void collectNames(byte[] jar, Set<String> into) throws IOException {
        try {
            ZipBytes zip = new ZipBytes(jar);
            for (String name : zip.getNames()) {
                addClassName(name, into);
                byte[] nested = name.endsWith(JAR_SUFFIX) ? zip.read(name) : null;
                if (nested != null) {
                    collectNames(nested, into);
                }
            }
        } catch (ZipException e) {
            collectNamesOfStream(new ByteArrayInputStream(jar), into);
        }
    }

    private static void collectNamesOfStream(InputStream jar, Set<String> into) throws IOException {
        ZipInputStream zip = new ZipInputStream(jar);
        for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
            addClassName(entry.getName(), into);
            if (entry.getName().endsWith(JAR_SUFFIX)) {
                collectNames(zip.readAllBytes(), into);
            }
        }
    }

    private static void addClassName(String entry, Set<String> into) {
        if (entry.endsWith(CLASS_SUFFIX)) {
            into.add(entry.substring(0, entry.length() - CLASS_SUFFIX.length()));
        }
    }

    public static Map<String, Set<String>> readNamed(Path jar, Set<String> notLoaded, Set<String> mayDiffer)
            throws IOException {
        Set<String> own = readClassNames(jar);
        Map<String, Map<String, Set<String>>> byClass = new HashMap<>();
        Map<String, Set<String>> namedBy = new HashMap<>();
        try (InputStream in = Files.newInputStream(jar)) {
            forEachClass(in, bytes -> {
                ClassReader reader = new ClassReader(bytes);
                String self = reader.getClassName();
                collectNamed(reader, own, mayDiffer, byClass.computeIfAbsent(self, any -> new HashMap<>()));
                if (!notLoaded.isEmpty()) {
                    collectOwn(reader, self, own, namedBy);
                }
            });
        }
        Set<String> out = new HashSet<>(notLoaded);
        boolean grew = !notLoaded.isEmpty();
        while (grew) {
            grew = false;
            for (Map.Entry<String, Set<String>> one : namedBy.entrySet()) {
                if (!out.contains(one.getKey()) && out.containsAll(one.getValue())) {
                    out.add(one.getKey());
                    grew = true;
                }
            }
        }
        Map<String, Set<String>> named = new HashMap<>();
        byClass.forEach((self, names) -> {
            if (!out.contains(self)) {
                names.forEach((owner, members) -> named.computeIfAbsent(owner, any -> new HashSet<>()).addAll(members));
            }
        });
        return named;
    }

    private static void collectOwn(ClassReader reader, String self, Set<String> own, Map<String, Set<String>> namedBy) {
        char[] buffer = new char[reader.getMaxStringLength()];
        for (int i = 1; i < reader.getItemCount(); i++) {
            int at = reader.getItem(i);
            if (at != 0 && reader.readByte(at - 1) == CLASS_ITEM) {
                String name = reader.readUTF8(at, buffer);
                if (own.contains(name) && !name.equals(self)) {
                    namedBy.computeIfAbsent(name, any -> new HashSet<>()).add(self);
                }
            }
        }
    }

    private static void collectNamed(ClassReader reader, Set<String> own, Set<String> mayDiffer,
            Map<String, Set<String>> into) {
        char[] buffer = new char[reader.getMaxStringLength()];
        for (int i = 1; i < reader.getItemCount(); i++) {
            int at = reader.getItem(i);
            if (at == 0) {
                continue;
            }
            int tag = reader.readByte(at - 1);
            if (tag == CLASS_ITEM) {
                String owner = reader.readUTF8(at, buffer);
                if (mayDiffer.contains(owner) && isForeign(owner, own)) {
                    into.computeIfAbsent(owner, any -> new HashSet<>());
                }
            } else if (tag == FIELD_ITEM || tag == METHOD_ITEM || tag == INTERFACE_METHOD_ITEM) {
                String owner = reader.readClass(at, buffer);
                if (mayDiffer.contains(owner) && isForeign(owner, own)) {
                    int described = reader.getItem(reader.readUnsignedShort(at + 2));
                    into.computeIfAbsent(owner, any -> new HashSet<>()).add(reader.readUTF8(described, buffer) + " "
                            + reader.readUTF8(described + 2, buffer));
                }
            }
        }
    }

    private static boolean isForeign(String owner, Set<String> own) {
        return !own.contains(owner) && OWN_CLASSES_OF_NOBODY.stream().noneMatch(owner::startsWith);
    }

    public static final class Running {
        private final Function<String, byte @Nullable []> reader;
        private final Map<String, Optional<ClassHeader>> headers = new HashMap<>();

        public Running(Function<String, byte @Nullable []> reader) {
            this.reader = reader;
        }

        private @Nullable ClassHeader find(String name) {
            return this.headers.computeIfAbsent(name, any -> Optional.ofNullable(this.reader.apply(any))
                    .map(ClassHeader::of)).orElse(null);
        }
    }

    public static @Nullable String findMissing(Map<String, Set<String>> named, Set<String> suspects, Running running) {
        for (Map.Entry<String, Set<String>> one : named.entrySet()) {
            String owner = one.getKey();
            if (!suspects.contains(owner)) {
                continue;
            }
            if (running.find(owner) == null) {
                return "the class " + owner;
            }
            for (String member : one.getValue()) {
                if (!hasMember(owner, member, running)) {
                    return owner + "." + member;
                }
            }
        }
        return null;
    }

    private static boolean hasMember(String owner, String member, Running running) {
        int space = member.indexOf(' ');
        String name = member.substring(0, space);
        String desc = member.substring(space + 1);
        Deque<String> next = new ArrayDeque<>(List.of(owner));
        Set<String> seen = new HashSet<>();
        while (!next.isEmpty()) {
            String type = next.removeFirst();
            if (!seen.add(type) || type.equals("java/lang/Object")) {
                continue;
            }
            ClassHeader header = running.find(type);
            if (header == null) {
                return true;
            }
            boolean found = desc.startsWith("(")
                    ? header.methods().stream().anyMatch(one -> one.name().equals(name) && one.desc().equals(desc))
                    : header.fields().stream().anyMatch(one -> one.name().equals(name) && one.desc().equals(desc));
            if (found) {
                return true;
            }
            next.addAll(header.findAbove());
        }
        return false;
    }
}
