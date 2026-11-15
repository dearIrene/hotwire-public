package dev.moon.hotwire.cache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CacheBudget {
    private static final Logger LOGGER = LoggerFactory.getLogger(CacheBudget.class);

    public static final long LEAST_BYTES = 16L << 20;
    private static final String MIXED = "mixed";

    public record Version(String name, Path folder, long bytes, long lastUsedMillis) {
    }

    private CacheBudget() {
    }

    public static List<Version> measure(Path root) {
        List<Version> found = new ArrayList<>();
        try (Stream<Path> folders = Files.list(root)) {
            for (Path folder : folders.filter(Files::isDirectory).toList()) {
                String name = folder.getFileName().toString();
                if (name.matches("[0-9][0-9.]*[0-9]")) {
                    found.add(new Version(name, folder, measureFolder(folder), findLastUse(folder)));
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("[Hotwire] the switch caches under {} could not be listed: {}", root, e.toString());
        }
        return found;
    }

    public static long measureFolder(Path folder) {
        try (Stream<Path> files = Files.walk(folder)) {
            return files.filter(Files::isRegularFile).mapToLong(file -> file.toFile().length()).sum();
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    private static long findLastUse(Path folder) {
        try (Stream<Path> files = Files.walk(folder)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".head"))
                    .mapToLong(file -> file.toFile().lastModified()).max().orElse(0);
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    public static long findOwnCap(Path versionFolder, long limitBytes) {
        long others = measureFolder(versionFolder) - measureFolder(versionFolder.resolve(MIXED));
        return Math.max(LEAST_BYTES, limitBytes - Math.max(0, others));
    }

    public static long fit(Path root, String current, long limitBytes) {
        List<Version> all = measure(root);
        long left = limitBytes;
        List<Version> others = new ArrayList<>();
        for (Version one : all) {
            if (one.name().equals(current)) {
                left -= one.bytes();
            } else {
                others.add(one);
            }
        }
        others.sort(Comparator.comparingLong(Version::lastUsedMillis).reversed());
        long freed = 0;
        for (Version one : others) {
            if (one.bytes() <= left) {
                left -= one.bytes();
                continue;
            }
            Path mixed = one.folder().resolve(MIXED);
            long room = left - (one.bytes() - measureFolder(mixed));
            if (room >= LEAST_BYTES && Files.isDirectory(mixed)) {
                CacheStore store = CacheStore.of(mixed, MIXED);
                store.setCapBytes(room);
                store.trim();
            } else {
                clear(one.folder());
            }
            long now = measureFolder(one.folder());
            LOGGER.info("[Hotwire] the {} switch cache went from {} MB to {} MB: the limit is {} MB for every game"
                    + " version together", one.name(), one.bytes() >> 20, now >> 20, limitBytes >> 20);
            freed += one.bytes() - now;
            left = Math.max(0, left - now);
        }
        return freed;
    }

    public static void clearStores(Path versionFolder) {
        try (Stream<Path> kinds = Files.list(versionFolder)) {
            for (Path kind : kinds.filter(Files::isDirectory).toList()) {
                String name = kind.getFileName().toString();
                if (Files.exists(kind.resolve(name + ".head"))) {
                    CacheStore.of(kind, name).clear();
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("[Hotwire] the switch cache {} could not be listed: {}", versionFolder, e.toString());
        }
    }

    public static boolean clear(Path versionFolder) {
        clearStores(versionFolder);
        List<Path> inside;
        try (Stream<Path> files = Files.walk(versionFolder)) {
            inside = files.sorted(Comparator.reverseOrder()).toList();
        } catch (IOException | RuntimeException e) {
            return false;
        }
        boolean all = true;
        for (Path file : inside) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException | RuntimeException e) {
                all = false;
            }
        }
        return all;
    }
}
