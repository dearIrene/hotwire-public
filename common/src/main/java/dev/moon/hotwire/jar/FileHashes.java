package dev.moon.hotwire.jar;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FileHashes {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileHashes.class);

    private record Stamp(String algorithm, Path path, long size, long changed) {
    }

    private static final Map<Stamp, String> KNOWN = new ConcurrentHashMap<>();

    private FileHashes() {
    }

    public static String findSha256(Path file) throws IOException {
        return hash("SHA-256", file);
    }

    public static String findSha512(Path file) throws IOException {
        return hash("SHA-512", file);
    }

    public static Map<String, String> findSha512ByName(Collection<Path> files) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Path file : files) {
            try {
                out.put(String.valueOf(file.getFileName()), findSha512(file));
            } catch (IOException e) {
                LOGGER.info("[Hotwire] {} could not be hashed: {}", file.getFileName(), e.toString());
            }
        }
        return out;
    }

    private static String hash(String algorithm, Path file) throws IOException {
        Stamp stamp = new Stamp(algorithm, file.toAbsolutePath().normalize(), Files.size(file),
                Files.getLastModifiedTime(file).toMillis());
        String known = KNOWN.get(stamp);
        if (known != null) {
            return known;
        }
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] buffer = new byte[1 << 16];
            for (int read = in.read(buffer); read > 0; read = in.read(buffer)) {
                digest.update(buffer, 0, read);
            }
            String made = HexFormat.of().formatHex(digest.digest());
            KNOWN.put(stamp, made);
            return made;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " is missing from this JVM", e);
        }
    }
}
