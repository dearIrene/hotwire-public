package dev.moon.hotwire.jar;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;

public final class JarLookup {
    private record Stamp(Path path, long size, long changed) {
    }

    private static final Map<Stamp, Optional<ModManifest>> MANIFESTS = new ConcurrentHashMap<>();

    private JarLookup() {
    }

    public static @Nullable ModManifest findManifest(Path jar) {
        Stamp stamp;
        try {
            stamp = new Stamp(jar.toAbsolutePath().normalize(), Files.size(jar),
                    Files.getLastModifiedTime(jar).toMillis());
        } catch (IOException e) {
            return null;
        }
        return MANIFESTS.computeIfAbsent(stamp, any -> Optional.ofNullable(readManifest(jar))).orElse(null);
    }

    private static @Nullable ModManifest readManifest(Path jar) {
        try {
            byte[] json = readEntry(jar, ModJarReader.MANIFEST);
            return json == null ? null : ModManifestParser.parse(json, String.valueOf(jar.getFileName()));
        } catch (IOException | ModJarException | RuntimeException e) {
            return null;
        }
    }

    public static byte @Nullable [] readEntry(Path jar, String path) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(path);
            if (entry == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    public static boolean copyEntry(Path jar, String path, Path at) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(path);
            if (entry == null) {
                return false;
            }
            Path folder = at.getParent();
            if (folder != null) {
                Files.createDirectories(folder);
            }
            try (InputStream in = zip.getInputStream(entry)) {
                Files.copy(in, at, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        }
    }
}
