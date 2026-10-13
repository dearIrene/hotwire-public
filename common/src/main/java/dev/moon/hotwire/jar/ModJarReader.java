package dev.moon.hotwire.jar;

import dev.moon.hotwire.jar.ModJarException.Reason;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.jspecify.annotations.Nullable;

public final class ModJarReader {
    public static final String MANIFEST = "fabric.mod.json";
    static final int MAX_DEPTH = 8;

    private ModJarReader() {
    }

    public static ModJar read(Path jar) throws ModJarException {
        String name = String.valueOf(jar.getFileName());
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            List<String> names = zip.stream().map(ZipEntry::getName).toList();
            return build(name, 0, names, entry -> {
                ZipEntry found = zip.getEntry(entry);
                return found == null ? null : readBytes(zip, found);
            });
        } catch (IOException e) {
            throw new ModJarException(Reason.UNREADABLE, name, "it could not be read as a jar", e);
        }
    }

    private static ModJar readNested(byte[] jar, String name, int depth) throws ModJarException {
        if (depth > MAX_DEPTH) {
            throw new ModJarException(Reason.NESTED_TOO_DEEP, name, "its jars are nested more than " + MAX_DEPTH
                    + " deep");
        }
        ZipBytes zip;
        try {
            zip = new ZipBytes(jar);
        } catch (ZipBytes.Zip64Exception zip64) {
            return readNestedStreaming(jar, name, depth);
        } catch (IOException e) {
            throw new ModJarException(Reason.UNREADABLE, name, "it could not be read as a jar", e);
        }
        try {
            return build(name, depth, zip.getNames(), zip::read);
        } catch (IOException e) {
            throw new ModJarException(Reason.UNREADABLE, name, "it could not be read as a jar", e);
        }
    }

    private static ModJar readNestedStreaming(byte[] jar, String name, int depth) throws ModJarException {
        Map<String, byte[]> entries = new HashMap<>();
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                names.add(entry.getName());
                if (entry.getName().endsWith(".json") || entry.getName().endsWith(".jar")
                        || entry.getName().endsWith(".class")) {
                    entries.put(entry.getName(), zip.readAllBytes());
                }
            }
            return build(name, depth, names, entries::get);
        } catch (IOException e) {
            throw new ModJarException(Reason.UNREADABLE, name, "it could not be read as a jar", e);
        }
    }

    private static ModJar build(String name, int depth, Collection<String> names, EntryReader entries)
            throws ModJarException, IOException {
        byte[] manifestBytes = entries.read(MANIFEST);
        if (manifestBytes == null) {
            throw new ModJarException(Reason.NOT_A_MOD, name, "it has no " + MANIFEST);
        }
        ModManifest manifest = ModManifestParser.parse(manifestBytes, name);
        Map<String, byte[]> declared = new LinkedHashMap<>();
        for (String path : manifest.nestedJars()) {
            byte[] bytes = entries.read(path);
            if (bytes == null) {
                throw new ModJarException(Reason.NESTED_JAR_MISSING, name, "it declares " + path
                        + " but does not hold it");
            }
            declared.put(path, bytes);
        }
        ModPatches patches = PatchScanner.scan(manifest, names, withNested(entries, declared.values()));
        List<ModJar> mods = new ArrayList<>();
        List<String> notMods = new ArrayList<>();
        for (Map.Entry<String, byte[]> nested : declared.entrySet()) {
            try {
                mods.add(readNested(nested.getValue(), name + "!/" + nested.getKey(), depth + 1));
            } catch (ModJarException e) {
                if (e.getReason() != Reason.NOT_A_MOD) {
                    throw e;
                }
                notMods.add(nested.getKey());
            }
        }
        return new ModJar(name, manifest, patches, mods, notMods, readAccessWidener(name, manifest, entries));
    }

    private static Optional<AccessWidenerText> readAccessWidener(String name, ModManifest manifest, EntryReader entries)
            throws ModJarException, IOException {
        if (manifest.accessWidener().isEmpty()) {
            return Optional.empty();
        }
        String path = manifest.accessWidener().get();
        byte[] bytes = entries.read(path);
        if (bytes == null) {
            throw new ModJarException(Reason.ACCESS_WIDENER_MISSING, name, "it names the access widener " + path
                    + " but does not hold it");
        }
        try {
            return Optional.of(AccessWidenerText.parse(path, bytes));
        } catch (IllegalArgumentException e) {
            throw new ModJarException(Reason.ACCESS_WIDENER_MALFORMED, name, "its access widener cannot be read: "
                    + e.getMessage(), e);
        }
    }

    private static EntryReader withNested(EntryReader own, Collection<byte[]> nested) {
        List<ZipBytes> opened = new ArrayList<>();
        boolean[] open = {false};
        return entry -> {
            byte[] bytes = own.read(entry);
            if (bytes != null) {
                return bytes;
            }
            if (!open[0]) {
                open[0] = true;
                for (byte[] jar : nested) {
                    try {
                        opened.add(new ZipBytes(jar));
                    } catch (IOException e) {
                    }
                }
            }
            for (ZipBytes jar : opened) {
                byte[] found = jar.read(entry);
                if (found != null) {
                    return found;
                }
            }
            return null;
        };
    }

    private static byte[] readBytes(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    @FunctionalInterface
    interface EntryReader {
        byte @Nullable [] read(String entry) throws IOException;
    }
}
