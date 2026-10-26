package dev.moon.hotwire.fabric.store;

import dev.moon.hotwire.modrinth.ModrinthClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class JarHashes {
    private static final Logger LOGGER = LoggerFactory.getLogger(JarHashes.class);
    private static final Map<String, String> HASHES = new ConcurrentHashMap<>();

    private JarHashes() {
    }

    static Map<String, String> of(Collection<Path> jars) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Path jar : jars) {
            try {
                String key = jar + "|" + Files.size(jar) + "|" + Files.getLastModifiedTime(jar).toMillis();
                String known = HASHES.get(key);
                if (known == null) {
                    known = ModrinthClient.hashOf(Files.readAllBytes(jar));
                    HASHES.put(key, known);
                }
                out.put(String.valueOf(jar.getFileName()), known);
            } catch (IOException e) {
                LOGGER.info("[Hotwire] {} could not be hashed: {}", jar.getFileName(), e.toString());
            }
        }
        return out;
    }
}
