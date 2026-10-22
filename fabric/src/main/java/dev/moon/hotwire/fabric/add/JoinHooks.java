package dev.moon.hotwire.fabric.add;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class JoinHooks {
    private static final List<byte[]> JOIN_METHODS = List.of("handleLogin", "onGameJoin", "method_11120",
            "placeNewPlayer", "onPlayerConnect", "method_14570").stream()
            .map(name -> name.getBytes(StandardCharsets.UTF_8)).toList();
    private static final byte[] MIXIN = "Lorg/spongepowered/asm/mixin/".getBytes(StandardCharsets.UTF_8);

    private static final int DEPTH = 3;

    private JoinHooks() {
    }

    static boolean isIn(Path jar) {
        try (InputStream in = Files.newInputStream(jar)) {
            return isIn(in, DEPTH);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isIn(InputStream jar, int depth) throws IOException {
        ZipInputStream zip = new ZipInputStream(jar);
        for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
            String name = entry.getName();
            if (name.endsWith(".class")) {
                byte[] bytes = zip.readAllBytes();
                if (contains(bytes, MIXIN) && JOIN_METHODS.stream().anyMatch(method -> contains(bytes, method))) {
                    return true;
                }
            } else if (depth > 0 && name.endsWith(".jar")
                    && isIn(new ByteArrayInputStream(zip.readAllBytes()), depth - 1)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(byte[] bytes, byte[] part) {
        outer:
        for (int at = 0; at <= bytes.length - part.length; at++) {
            for (int i = 0; i < part.length; i++) {
                if (bytes[at + i] != part[i]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
