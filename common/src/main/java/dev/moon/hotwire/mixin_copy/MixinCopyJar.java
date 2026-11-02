package dev.moon.hotwire.mixin_copy;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class MixinCopyJar {
    private static final Logger LOGGER = LoggerFactory.getLogger(MixinCopyJar.class);
    private static final String COPY_PREFIX = "hotwire-mixin-copy";
    private static final int HASH_BYTES = 8;

    private static final Duration OLD_COPY = Duration.ofDays(1);

    private MixinCopyJar() {
    }

    static Path write(byte[] bytes) throws IOException, NoSuchAlgorithmException {
        Path folder = Path.of(System.getProperty("java.io.tmpdir"));
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        Path jar = folder.resolve(COPY_PREFIX + "-" + HexFormat.of().formatHex(hash, 0, HASH_BYTES) + ".jar");
        if (!Files.isRegularFile(jar) || Files.size(jar) != bytes.length) {
            Path part = Files.createTempFile(folder, COPY_PREFIX + "-part", ".tmp");
            try {
                Files.write(part, bytes);
                Files.move(part, jar, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Files.deleteIfExists(part);
                if (!Files.isRegularFile(jar) || Files.size(jar) != bytes.length) {
                    throw e;
                }
            }
        }
        removeOldCopies(folder, jar);
        return jar;
    }

    private static void removeOldCopies(Path folder, Path current) {
        try (DirectoryStream<Path> copies = Files.newDirectoryStream(folder, COPY_PREFIX + "*.jar")) {
            for (Path copy : copies) {
                try {
                    if (!copy.equals(current)
                            && Files.getLastModifiedTime(copy).toInstant().isBefore(Instant.now().minus(OLD_COPY))) {
                        Files.deleteIfExists(copy);
                    }
                } catch (IOException e) {
                    LOGGER.debug("[Hotwire] {} stays: {}", copy, e.toString());
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("[Hotwire] the old mixin copies stay: {}", e.toString());
        }
    }
}
