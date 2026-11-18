package dev.moon.hotwire.mixin_copy;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class MixinCopyJar {
    private static final Logger LOGGER = LoggerFactory.getLogger(MixinCopyJar.class);
    private static final String COPY_PREFIX = "hotwire-mixin-copy";
    private static final String JAR_NAME = "mixin-copy.jar";

    private static final Duration OLD_COPY = Duration.ofDays(1);

    private MixinCopyJar() {
    }

    static Path write(byte[] bytes) throws IOException {
        Path temp = Path.of(System.getProperty("java.io.tmpdir"));
        removeOldCopies(temp);
        Path jar = Files.createTempDirectory(temp, COPY_PREFIX + "-").resolve(JAR_NAME);
        Files.write(jar, bytes, StandardOpenOption.CREATE_NEW);
        return jar;
    }

    private static void removeOldCopies(Path temp) {
        Instant old = Instant.now().minus(OLD_COPY);
        try (DirectoryStream<Path> copies = Files.newDirectoryStream(temp, COPY_PREFIX + "*")) {
            for (Path copy : copies) {
                try {
                    BasicFileAttributes attributes =
                            Files.readAttributes(copy, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (attributes.lastModifiedTime().toInstant().isAfter(old)) {
                        continue;
                    }
                    if (attributes.isDirectory()) {
                        Files.deleteIfExists(copy.resolve(JAR_NAME));
                        Files.deleteIfExists(copy);
                    } else if (attributes.isRegularFile()) {
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
