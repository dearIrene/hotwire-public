package dev.moon.hotwire.add;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

public final class WorldBackups {
    private static final long LEFT_OVER = 512L * 1024 * 1024;

    private WorldBackups() {
    }

    public static long findRoomNeeded(long size) {
        return size + Math.max(size / 10, LEFT_OVER);
    }

    public static long findSize(Path world) throws IOException {
        try (Stream<Path> files = Files.walk(world)) {
            return files.filter(Files::isRegularFile).mapToLong(WorldBackups::findFileSize).sum();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static long findFileSize(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    public static long findFreeSpace(Path backups) throws IOException {
        @Nullable Path at = backups.toAbsolutePath();
        while (at != null && !Files.exists(at)) {
            at = at.getParent();
        }
        if (at == null) {
            throw new IOException("no folder above " + backups + " is there");
        }
        return Files.getFileStore(at).getUsableSpace();
    }

    public static Set<Path> list(Path backups) throws IOException {
        if (!Files.isDirectory(backups)) {
            return Set.of();
        }
        try (Stream<Path> files = Files.list(backups)) {
            return files.collect(Collectors.toSet());
        }
    }

    public static List<Path> deleteNewZips(Path backups, Set<Path> before) throws IOException {
        List<Path> deleted = new ArrayList<>();
        for (Path file : list(backups)) {
            if (!before.contains(file) && file.getFileName().toString().endsWith(".zip")) {
                Files.deleteIfExists(file);
                deleted.add(file);
            }
        }
        return deleted;
    }
}
