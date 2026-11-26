package dev.moon.hotwire.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

public final class AtomicFiles {
    private AtomicFiles() {
    }

    public static void write(Path file, byte[] bytes) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path temp = Files.createTempFile(parent == null ? Path.of("") : parent, file.getFileName() + ".", ".tmp");
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            moveInto(temp, file);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public static void moveInto(Path whole, Path file) throws IOException {
        synchronized (AtomicFiles.class) {
            try {
                Files.move(whole, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(whole, file, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    public static void writeString(Path file, String text) throws IOException {
        write(file, text.getBytes(StandardCharsets.UTF_8));
    }
}
