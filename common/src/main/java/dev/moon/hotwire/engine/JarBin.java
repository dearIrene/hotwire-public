package dev.moon.hotwire.engine;

import com.sun.jna.platform.FileUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class JarBin {
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final int NAMES = 1000;

    private JarBin() {
    }

    public static void throwAway(Path jar) throws IOException {
        String os = System.getProperty("os.name", "");
        if (os.startsWith("Windows") || os.startsWith("Mac")) {
            FileUtils system = FileUtils.getInstance();
            if (!system.hasTrash()) {
                throw new IOException("the system has no bin");
            }
            system.moveToTrash(jar.toFile());
            return;
        }
        String data = System.getenv("XDG_DATA_HOME");
        Path home = data == null || data.isBlank() ? Path.of(System.getProperty("user.home"), ".local", "share")
                : Path.of(data);
        moveToTrash(jar, home.resolve("Trash"));
    }

    static Path moveToTrash(Path file, Path trash) throws IOException {
        Path files = Files.createDirectories(trash.resolve("files"));
        Path info = Files.createDirectories(trash.resolve("info"));
        String name = String.valueOf(file.getFileName());
        String note = "[Trash Info]\nPath=" + file.toAbsolutePath().toUri().getRawPath() + "\nDeletionDate="
                + LocalDateTime.now(ZoneId.systemDefault()).format(WHEN) + "\n";
        for (int count = 1; count <= NAMES; count++) {
            String free = count == 1 ? name : name + "." + count;
            Path noted = info.resolve(free + ".trashinfo");
            if (Files.exists(files.resolve(free)) || Files.exists(noted)) {
                continue;
            }
            Files.writeString(noted, note, StandardOpenOption.CREATE_NEW);
            try {
                return Files.move(file, files.resolve(free));
            } catch (IOException e) {
                Files.deleteIfExists(noted);
                throw e;
            }
        }
        throw new IOException("the Trash has no free name for " + name);
    }
}
