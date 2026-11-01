package dev.moon.hotwire.fabric.update;

import dev.moon.hotwire.jar.FileHashes;
import dev.moon.hotwire.jar.ModJar;
import dev.moon.hotwire.jar.ModJarException;
import dev.moon.hotwire.jar.ModJarReader;
import dev.moon.hotwire.update.JarDiff;
import dev.moon.hotwire.update.UpdateDiffer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class PlanCache {
    private static final int MOST = 256;

    private final Map<String, ModJar> reads = new ConcurrentHashMap<>();
    private final Map<String, JarDiff> diffs = new ConcurrentHashMap<>();

    ModJar read(Path jar) throws ModJarException {
        String key = hash(jar);
        ModJar known = this.reads.get(key);
        if (known != null) {
            return known;
        }
        ModJar read = ModJarReader.read(jar);
        this.store(this.reads, key, read);
        return read;
    }

    JarDiff compare(Path running, Path incoming) throws ModJarException {
        String key = hash(running) + " " + hash(incoming);
        JarDiff known = this.diffs.get(key);
        if (known != null) {
            return known;
        }
        JarDiff diff = UpdateDiffer.compare(running, incoming);
        this.store(this.diffs, key, diff);
        return diff;
    }

    void clear() {
        this.reads.clear();
        this.diffs.clear();
    }

    private <T> void store(Map<String, T> into, String key, T value) {
        if (into.size() >= MOST) {
            into.clear();
        }
        into.put(key, value);
    }

    private static String hash(Path jar) throws ModJarException {
        try {
            return FileHashes.findSha256(jar);
        } catch (IOException e) {
            throw new ModJarException(ModJarException.Reason.UNREADABLE, String.valueOf(jar.getFileName()),
                    "it could not be read as a jar", e);
        }
    }
}
