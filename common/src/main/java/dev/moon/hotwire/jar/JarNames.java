package dev.moon.hotwire.jar;

import dev.moon.hotwire.cache.CacheKey;
import dev.moon.hotwire.cache.CacheStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;

public final class JarNames {
    private static final String KEYS = "jar names 1";
    private static final String CLASS_SUFFIX = ".class";
    private static final String JAR_SUFFIX = ".jar";
    private static final int CLASS_ITEM = 7;
    private static volatile JarNames current = new JarNames(null);

    public record Names(Set<String> named, Set<String> withNested) {
    }

    private final @Nullable CacheStore store;

    public JarNames(@Nullable CacheStore store) {
        this.store = store;
    }

    public static JarNames get() {
        return current;
    }

    public static void open(CacheStore store) {
        current = new JarNames(store);
    }

    public Set<String> findNamedOf(Path jar, Set<String> of, boolean nested) throws IOException {
        Names names = this.find(jar);
        Set<String> all = nested ? names.withNested() : names.named();
        Set<String> found = new HashSet<>();

        if (of.size() < all.size()) {
            of.stream().filter(all::contains).forEach(found::add);
        } else {
            all.stream().filter(of::contains).forEach(found::add);
        }
        return found;
    }

    public Names find(Path jar) throws IOException {
        CacheStore records = this.store;
        CacheKey key = records == null ? null : findKey(jar);
        byte[] stored = records == null || key == null ? null : records.get(key, null);
        if (stored != null) {
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(stored))) {
                return new Names(readSet(in), readSet(in));
            } catch (IOException | IllegalArgumentException unreadable) {
            }
        }
        Names names = read(jar);
        if (records != null && key != null) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                writeSet(out, names.named());
                writeSet(out, names.withNested());
            }
            records.put(key, bytes.toByteArray(), null);
        }
        return names;
    }

    public void flush() {
        CacheStore records = this.store;
        if (records != null) {
            records.flush();
        }
    }

    private static CacheKey findKey(Path jar) throws IOException {
        CacheKey.Builder key = CacheKey.of(KEYS);
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            zip.stream().forEach(entry -> key.add(entry.getName()).add(entry.getSize()).add(entry.getCrc()));
        }
        return key.build();
    }

    static Names read(Path jar) throws IOException {
        Set<String> named = new HashSet<>();
        Set<String> own = new HashSet<>();
        Set<String> namedNested = new HashSet<>();
        Set<String> ownNested = new HashSet<>();
        try (InputStream in = Files.newInputStream(jar)) {
            ZipInputStream zip = new ZipInputStream(in);
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                collect(entry.getName(), zip, named, own, namedNested, ownNested);
            }
        }
        Set<String> withNested = new HashSet<>(named);
        withNested.addAll(namedNested);
        withNested.removeAll(own);
        withNested.removeAll(ownNested);
        named.removeAll(own);
        return new Names(Set.copyOf(named), Set.copyOf(withNested));
    }

    private static void collect(String name, ZipInputStream zip, Set<String> named, Set<String> own,
            Set<String> namedNested, Set<String> ownNested) throws IOException {
        if (name.endsWith(CLASS_SUFFIX)) {
            own.add(name.substring(0, name.length() - CLASS_SUFFIX.length()));
            try {
                named.addAll(readClassesNamed(zip.readAllBytes()));
            } catch (RuntimeException unreadable) {
            }
        } else if (name.endsWith(JAR_SUFFIX)) {
            ZipInputStream inner = new ZipInputStream(new ByteArrayInputStream(zip.readAllBytes()));
            for (ZipEntry entry = inner.getNextEntry(); entry != null; entry = inner.getNextEntry()) {
                collect(entry.getName(), inner, namedNested, ownNested, namedNested, ownNested);
            }
        }
    }

    public static Set<String> readClassesNamed(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        char[] buffer = new char[reader.getMaxStringLength()];
        Set<String> named = new HashSet<>();
        for (int item = 1; item < reader.getItemCount(); item++) {
            int at = reader.getItem(item);
            if (at > 0 && bytes[at - 1] == CLASS_ITEM) {
                named.add(reader.readUTF8(at, buffer));
            }
        }
        return named;
    }

    private static void writeSet(DataOutputStream out, Set<String> names) throws IOException {
        out.writeInt(names.size());
        for (String name : names) {
            out.writeUTF(name);
        }
    }

    private static Set<String> readSet(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0) {
            throw new IllegalArgumentException("a count below nothing");
        }
        Set<String> names = HashSet.newHashSet(count);
        for (int i = 0; i < count; i++) {
            names.add(in.readUTF());
        }
        return names;
    }
}
