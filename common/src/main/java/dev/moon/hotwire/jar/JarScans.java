package dev.moon.hotwire.jar;

import dev.moon.hotwire.cache.CacheKey;
import dev.moon.hotwire.cache.CacheStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

public final class JarScans {
    private static final String KEYS = "jar scans 3";
    private static final String STAMPS = "jar stamps 1";

    private static final long UNWRITTEN_MILLIS = 2_000;
    private static final String SCANNER = "dev/moon/hotwire/jar/";

    private final CacheStore store;

    private final byte @Nullable [] recipe;

    public JarScans(CacheStore store) {
        this(store, findRecipe());
    }

    JarScans(CacheStore store, byte @Nullable [] recipe) {
        this.store = store;
        this.recipe = recipe;
    }

    @SuppressWarnings("ArrayRecordComponent")
    record Scan(ModJar jar, byte[] manifest, List<Scan> nested) {
    }

    @Nullable CacheKey findKey(ZipFile zip, String name) {
        if (this.recipe == null) {
            return null;
        }
        CacheKey.Builder key = CacheKey.of(KEYS).add(this.recipe).add(name);
        zip.stream().forEach(entry -> key.add(entry.getName()).add(entry.getSize()).add(entry.getCrc()));
        return key.build();
    }

    @Nullable CacheKey findStamp(Path jar) {
        if (this.recipe == null) {
            return null;
        }
        try {
            BasicFileAttributes file = Files.readAttributes(jar, BasicFileAttributes.class);
            return CacheKey.of(STAMPS).add(this.recipe).add(jar.toAbsolutePath().toString()).add(file.size())
                    .add(file.lastModifiedTime().to(TimeUnit.MICROSECONDS))
                    .add(file.creationTime().to(TimeUnit.MICROSECONDS)).build();
        } catch (IOException e) {
            return null;
        }
    }

    @Nullable ModJar findStamped(CacheKey stamp) {
        byte[] key = this.store.get(stamp, null);
        if (key == null || key.length != 2 * Long.BYTES) {
            return null;
        }
        ByteBuffer both = ByteBuffer.wrap(key);
        return this.find(new CacheKey(both.getLong(), both.getLong()));
    }

    void saveStamp(Path jar, @Nullable CacheKey before, @Nullable CacheKey key) {
        if (before == null || key == null || !before.equals(this.findStamp(jar))) {
            return;
        }
        try {
            if (System.currentTimeMillis() - Files.getLastModifiedTime(jar).toMillis() < UNWRITTEN_MILLIS) {
                return;
            }
        } catch (IOException e) {
            return;
        }
        this.store.put(before, ByteBuffer.allocate(2 * Long.BYTES).putLong(key.high()).putLong(key.low()).array(),
                null);
    }

    @Nullable ModJar find(CacheKey key) {
        byte[] stored = this.store.get(key, null);
        if (stored == null) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(stored))) {
            return readJar(in);
        } catch (IOException | ModJarException | IllegalArgumentException e) {
            return null;
        }
    }

    void save(CacheKey key, Scan scanned) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            writeJar(out, scanned);
        } catch (IOException e) {
            return;
        }
        this.store.put(key, bytes.toByteArray(), null);
    }

    public void flush() {
        this.store.flush();
    }

    private static void writeJar(DataOutputStream out, Scan scanned) throws IOException {
        ModJar jar = scanned.jar();
        out.writeUTF(jar.name());
        writeBytes(out, scanned.manifest());
        ModPatches patches = jar.patches();
        writeAll(out, patches.owns());
        writeAll(out, patches.patches());
        writeAll(out, patches.hookOnly());
        writeAll(out, patches.missing());
        out.writeInt(patches.byConfig().size());
        for (Map.Entry<String, Set<String>> config : patches.byConfig().entrySet()) {
            out.writeUTF(config.getKey());
            writeAll(out, config.getValue());
        }
        writeAll(out, patches.calls());
        writeAll(out, patches.asks());
        out.writeBoolean(patches.callbacksOnly());
        writeAll(out, patches.rebuildOnly());
        patches.calledNames().writeTo(out);
        writeAll(out, patches.fieldsSetUp());
        out.writeInt(patches.overwrites().size());
        for (Map.Entry<String, Set<String>> method : patches.overwrites().entrySet()) {
            out.writeUTF(method.getKey());
            writeAll(out, method.getValue());
        }
        out.writeInt(scanned.nested().size());
        for (Scan nested : scanned.nested()) {
            writeJar(out, nested);
        }
        writeAll(out, jar.nestedNotMods());
        out.writeBoolean(jar.accessWidener().isPresent());
        if (jar.accessWidener().isPresent()) {
            writeBytes(out, jar.accessWidener().get().getBytes());
        }
    }

    private static ModJar readJar(DataInputStream in) throws IOException, ModJarException {
        String name = in.readUTF();
        ModManifest manifest = ModManifestParser.parse(readBytes(in), name);
        List<String> owns = readAll(in);
        List<String> patched = readAll(in);
        List<String> hookOnly = readAll(in);
        List<String> missing = readAll(in);
        Map<String, Set<String>> byConfig = new LinkedHashMap<>();
        for (int count = in.readInt(); count > 0; count--) {
            byConfig.put(in.readUTF(), Set.copyOf(readAll(in)));
        }
        List<String> calls = readAll(in);
        List<String> asks = readAll(in);
        boolean callbacksOnly = in.readBoolean();
        List<String> rebuildOnly = readAll(in);
        CalledNames calledNames = CalledNames.readFrom(in);
        List<String> fieldsSetUp = readAll(in);
        Map<String, Set<String>> overwrites = new LinkedHashMap<>();
        for (int count = in.readInt(); count > 0; count--) {
            overwrites.put(in.readUTF(), Set.copyOf(readAll(in)));
        }
        ModPatches patches = new ModPatches(Set.copyOf(owns), Set.copyOf(patched), Set.copyOf(hookOnly), missing,
                byConfig, Set.copyOf(calls), Set.copyOf(asks), callbacksOnly, Set.copyOf(rebuildOnly), calledNames,
                Set.copyOf(fieldsSetUp), overwrites);
        List<ModJar> nested = new ArrayList<>();
        for (int count = in.readInt(); count > 0; count--) {
            nested.add(readJar(in));
        }
        List<String> notMods = readAll(in);
        Optional<AccessWidenerText> widener = Optional.empty();
        if (in.readBoolean()) {
            String path = manifest.accessWidener().orElseThrow(() -> new IOException("its manifest names no widener"));
            widener = Optional.of(AccessWidenerText.parse(path, readBytes(in)));
        }
        return new ModJar(name, manifest, patches, nested, notMods, widener);
    }

    private static void writeAll(DataOutputStream out, Collection<String> names) throws IOException {
        out.writeInt(names.size());
        for (String name : names) {
            out.writeUTF(name);
        }
    }

    private static List<String> readAll(DataInputStream in) throws IOException {
        int count = in.readInt();
        List<String> names = new ArrayList<>(count);
        for (int at = 0; at < count; at++) {
            names.add(in.readUTF());
        }
        return names;
    }

    private static void writeBytes(DataOutputStream out, byte[] bytes) throws IOException {
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        byte[] bytes = new byte[in.readInt()];
        in.readFully(bytes);
        return bytes;
    }

    private static byte @Nullable [] findRecipe() {
        CacheKey.Builder recipe = CacheKey.of("jar scanner 1");
        boolean any = false;
        try {
            CodeSource source = JarScans.class.getProtectionDomain().getCodeSource();
            Path own = source == null ? null : Path.of(source.getLocation().toURI());
            if (own == null || !Files.isRegularFile(own)) {
                return null;
            }

            try (ZipFile zip = new ZipFile(own.toFile())) {
                for (ZipEntry entry : Collections.list(zip.entries())) {
                    if (entry.getName().startsWith(SCANNER) && entry.getName().endsWith(".class")) {
                        ClassWriter bare = new ClassWriter(0);
                        try (InputStream in = zip.getInputStream(entry)) {
                            new ClassReader(in.readAllBytes()).accept(bare, ClassReader.SKIP_DEBUG);
                        }
                        recipe.add(entry.getName()).add(bare.toByteArray());
                        any = true;
                    }
                }
            }
        } catch (IOException | URISyntaxException | RuntimeException e) {
            return null;
        }
        if (!any) {
            return null;
        }
        CacheKey made = recipe.build();
        return (Long.toHexString(made.high()) + Long.toHexString(made.low())).getBytes(StandardCharsets.UTF_8);
    }
}
