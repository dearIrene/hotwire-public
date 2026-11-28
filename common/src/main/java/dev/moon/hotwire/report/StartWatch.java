package dev.moon.hotwire.report;

import dev.moon.hotwire.config.Flags;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class StartWatch {
    private static final Logger LOGGER = LoggerFactory.getLogger(StartWatch.class);
    private static final long WAIT_MS = Flags.START_WATCH_MILLIS;
    private static final int MOST_FILE_BYTES = 16 << 20;
    private static final int CRASHES = 3;
    private static volatile Instant began = Instant.EPOCH;
    private static volatile boolean reached;
    private static volatile boolean written;

    private static volatile @Nullable Path quiet;

    private StartWatch() {
    }

    public static void begin(Path game, BooleanSupplier loaded) {
        began = Instant.now();
        Thread wait = new Thread(() -> {
            long until = System.nanoTime() + WAIT_MS * 1_000_000L;
            try {
                while (!reached && System.nanoTime() < until) {
                    Thread.sleep(1_000);
                    if (isLoaded(loaded)) {
                        reached = true;
                    }
                }
            } catch (InterruptedException e) {
                return;
            }
            if (reached) {
                return;
            }
            String why = "The game had not reached its menu " + WAIT_MS / 1000 + " s after it started";
            quiet = write(game, why);
            if (reached) {
                reachMenu();
                return;
            }
            long shown = System.nanoTime() + 3 * WAIT_MS * 1_000_000L;
            try {
                while (!reached && System.nanoTime() < shown) {
                    Thread.sleep(1_000);
                    if (isLoaded(loaded)) {
                        reachMenu();
                    }
                }
            } catch (InterruptedException e) {
                return;
            }
            Path zip = quiet;
            if (!reached && zip != null) {
                showWhere(zip, why, false);
            }
        }, "hotwire-start-watch");
        wait.setDaemon(true);
        wait.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!reached) {
                String why = "The game closed before it reached its menu";
                Path zip = quiet != null ? quiet : write(game, why);
                if (zip != null) {
                    showWhere(zip, why, true);
                }
            }
        }, "hotwire-start-close"));
    }

    private static boolean isLoaded(BooleanSupplier loaded) {
        try {
            return loaded.getAsBoolean();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    public static void reachMenu() {
        reached = true;
        Path zip = quiet;
        quiet = null;
        if (zip != null) {
            try {
                Files.deleteIfExists(zip);
            } catch (IOException e) {
                LOGGER.warn("[Hotwire] the start report of a slow start could not be removed", e);
            }
        }
    }

    private static synchronized @Nullable Path write(Path game, String why) {
        if (written) {
            return null;
        }
        written = true;
        Set<String> personal = findPersonal();
        String stamp = LocalDateTime.now(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT));
        Path zip = game.resolve("hotwire").resolve("reports").resolve("Hotwire start report " + stamp + ".zip");
        try {
            Files.createDirectories(zip.getParent());
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
                put(out, "diagnostic.txt", describe(game, why), personal);
                putFile(out, "logs/latest.log", game.resolve("logs").resolve("latest.log"), personal);
                putFile(out, "logs/debug.log", game.resolve("logs").resolve("debug.log"), personal);
                for (Path file : list(game.resolve("hotwire").resolve("logs"))) {
                    putFile(out, "hotwire/logs/" + file.getFileName(), file, personal);
                }
                putFile(out, "hotwire/last-freeze.txt", game.resolve("hotwire").resolve("last-freeze.txt"), personal);
                List<Path> crashes = new ArrayList<>(list(game.resolve("crash-reports")));
                crashes.sort(Comparator.comparing(StartWatch::findModified).reversed());
                for (Path crash : crashes.subList(0, Math.min(CRASHES, crashes.size()))) {
                    putFile(out, "crash-reports/" + crash.getFileName(), crash, personal);
                }
                for (Path file : list(game)) {
                    if (file.getFileName().toString().startsWith("hs_err")) {
                        putFile(out, file.getFileName().toString(), file, personal);
                    }
                }
            }
            LOGGER.error("[Hotwire] {}: everything that says why is in {}", why, zip);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[Hotwire] {}, and the start report could not be written", why, e);
            return null;
        }
        return zip;
    }

    private static String describe(Path game, String why) {
        StringBuilder out = new StringBuilder();
        out.append("Hotwire start report\n").append(why).append(", at ").append(Instant.now()).append('\n');
        out.append("Started ").append(began).append("\n\n");
        for (String key : List.of("java.version", "java.vendor", "java.vm.name", "os.name", "os.version", "os.arch")) {
            out.append(key).append(": ").append(System.getProperty(key)).append('\n');
        }
        Runtime runtime = Runtime.getRuntime();
        out.append("memory: ").append(runtime.maxMemory() >> 20).append(" MB at most, ")
                .append(runtime.availableProcessors()).append(" processors\n");
        out.append("JVM switches: ").append(String.join(" ", ManagementFactory.getRuntimeMXBean().getInputArguments()))
                .append("\n\nMods folder:\n");
        for (Path mod : list(game.resolve("mods"))) {
            out.append("  ").append(mod.getFileName()).append("  ").append(findSize(mod)).append('\n');
        }
        out.append("\nThreads:\n");
        for (ThreadInfo thread : ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)) {
            out.append('"').append(thread.getThreadName()).append("\" ").append(thread.getThreadState()).append('\n');
            for (StackTraceElement frame : thread.getStackTrace()) {
                out.append("    at ").append(frame).append('\n');
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static Set<String> findPersonal() {
        Set<String> personal = new HashSet<>();
        List<String> words = List.of(System.getProperty("sun.java.command", "").split(" ", -1));
        int at = words.indexOf("--username");
        if (at >= 0 && at + 1 < words.size()) {
            personal.add(words.get(at + 1));
        }
        String user = System.getProperty("user.name");
        if (user != null) {
            personal.add(user);
        }
        return personal;
    }

    private static void putFile(ZipOutputStream out, String name, Path file, Set<String> personal) throws IOException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file)) {
            bytes = in.readNBytes(MOST_FILE_BYTES);
        }
        put(out, name, new String(bytes, StandardCharsets.UTF_8), personal);
    }

    private static void put(ZipOutputStream out, String name, String text, Set<String> personal) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(ReportBundle.redact(text, personal).getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    private static List<Path> list(Path folder) {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static Instant findModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }

    private static String findSize(Path file) {
        try {
            return Files.size(file) / 1024 + " KB";
        } catch (IOException e) {
            return "?";
        }
    }

    private static void showWhere(Path zip, String why, boolean closing) {
        String message = why + ".\n\nSend this file to Hotwire:\n" + zip + "\n\nIts folder is open now.";
        openFolder(zip);
        Thread show = new Thread(() -> showBox("Hotwire: the game did not start", message), "hotwire-start-box");
        show.setDaemon(!closing);
        show.start();
        if (closing) {
            try {
                show.join(10 * 60_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void openFolder(Path zip) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> command = os.contains("win") ? List.of("explorer.exe", "/select," + zip)
                : os.contains("mac") ? List.of("open", "-R", zip.toString())
                : List.of("xdg-open", String.valueOf(zip.getParent()));
        try {
            new ProcessBuilder(command).start();
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] the reports folder could not be opened", e);
        }
    }

    private static void showBox(String title, String message) {
        if (call("org.lwjgl.util.tinyfd.TinyFileDialogs", "tinyfd_messageBox", title, message) == null) {
            call("org.lwjgl.sdl.SDLMessageBox", "SDL_ShowSimpleMessageBox", title, message);
        }
    }

    private static @Nullable Object call(String owner, String name, String title, String message) {
        try {
            Class<?> type = Class.forName(owner, true, StartWatch.class.getClassLoader());
            for (Method method : type.getMethods()) {
                if (!method.getName().equals(name)) {
                    continue;
                }
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length == 5 && parameters[0] == CharSequence.class) {
                    return method.invoke(null, title, message, "ok", "error", true);
                }
                if (parameters.length == 4 && parameters[0] == int.class && parameters[1] == CharSequence.class) {
                    return method.invoke(null, 0x10, title, message, 0L);
                }
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            LOGGER.warn("[Hotwire] {} could not show the start report's window", owner, e);
        }
        return null;
    }
}
