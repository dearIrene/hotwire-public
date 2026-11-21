package dev.moon.hotwire.engine;

import com.sun.jna.platform.FileUtils;
import com.sun.jna.platform.win32.Shell32;
import com.sun.jna.platform.win32.ShellAPI;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class JarBin {
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final int NAMES = 1000;
    private static final int GIO_SECONDS = 20;

    private static final int WANT_NUKE_WARNING = 0x4000;

    private JarBin() {
    }

    public static String throwAway(Path jar) throws IOException {
        String os = System.getProperty("os.name", "");
        try {
            if (os.startsWith("Windows")) {
                sendToRecycleBin(jar);
                return "the Recycle Bin";
            }
            if (os.startsWith("Mac")) {
                FileUtils system = FileUtils.getInstance();
                if (!system.hasTrash()) {
                    throw new IOException("the system has no Trash");
                }
                system.moveToTrash(jar.toFile());
                return "the Trash";
            }
        } catch (LinkageError e) {
            throw new IOException("the system's bin could not be reached: " + e, e);
        }
        if (isTrashedByGio(jar)) {
            return "the desktop's Trash";
        }
        Map<String, String> env = System.getenv();

        if (env.containsKey("FLATPAK_ID") || env.containsKey("SNAP")) {
            throw new IOException("the launcher's sandbox has no way to the Trash");
        }
        return String.valueOf(moveToTrash(jar, findHomeTrash(env, System.getProperty("user.home"))));
    }

    private static void sendToRecycleBin(Path jar) throws IOException {
        ShellAPI.SHFILEOPSTRUCT operation = new ShellAPI.SHFILEOPSTRUCT();
        operation.wFunc = ShellAPI.FO_DELETE;
        operation.pFrom = operation.encodePaths(new String[] {jar.toAbsolutePath().toString()});
        operation.fFlags = (short) (ShellAPI.FOF_ALLOWUNDO | ShellAPI.FOF_NOCONFIRMATION | ShellAPI.FOF_SILENT
                | ShellAPI.FOF_NOERRORUI | WANT_NUKE_WARNING);
        int result = Shell32.INSTANCE.SHFileOperation(operation);
        if (result != 0 || operation.fAnyOperationsAborted || Files.exists(jar)) {
            throw new IOException("the Recycle Bin did not take it (" + result + ")");
        }
    }

    private static boolean isTrashedByGio(Path jar) {
        try {
            Process gio = new ProcessBuilder("gio", "trash", "--", jar.toAbsolutePath().toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!gio.waitFor(GIO_SECONDS, TimeUnit.SECONDS)) {
                gio.destroyForcibly().waitFor(GIO_SECONDS, TimeUnit.SECONDS);
            }
            return Files.notExists(jar);
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static Path findHomeTrash(Map<String, String> env, String home) {
        String data = env.get(env.containsKey("FLATPAK_ID") ? "HOST_XDG_DATA_HOME" : "XDG_DATA_HOME");
        return (data == null || data.isBlank() ? Path.of(home, ".local", "share") : Path.of(data)).resolve("Trash");
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
