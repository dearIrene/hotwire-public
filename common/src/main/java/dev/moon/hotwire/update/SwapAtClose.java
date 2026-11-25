package dev.moon.hotwire.update;

import com.google.gson.JsonParseException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SwapAtClose {
    public static final String LAST_LOGS = "last-close.log";
    private static final Logger LOGGER = LoggerFactory.getLogger(SwapAtClose.class);
    private static final String SWAPPER = "dev/moon/hotwire/update/JarSwapper.class";

    private static final String CORE = "META-INF/jars/hotwire-core.jar";

    private final UpdateQueue queue;
    private final UpdateFolders folders;
    private final Path hotwireJar;
    private final Path swapperCopy;
    private boolean started;

    private boolean inProcess;

    private static final long STARTED_MILLIS = 1500;

    public SwapAtClose(UpdateQueue queue, UpdateFolders folders, Path hotwireJar) {
        this.queue = queue;
        this.folders = folders;
        this.hotwireJar = hotwireJar;
        this.swapperCopy = folders.swapperJar();
    }

    public void arm() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::start, "Hotwire swapper launch"));
        Runtime.getRuntime().addShutdownHook(new Thread(this::watchClose, "Hotwire close watch launch"));

        this.queue.setOnAdded(() -> Thread.ofPlatform().name("hotwire-swapper-start").daemon().start(this::start));
        if (!this.queue.getEntries().isEmpty()) {
            Thread.ofPlatform().name("Hotwire swapper launch").daemon().start(this::start);
        }
    }

    private synchronized void start() {
        List<UpdateQueue.Entry> entries = this.queue.getEntries();
        if (this.started || entries.isEmpty()) {
            return;
        }
        Optional<String> java = findJava();
        if (java.isEmpty()) {
            LOGGER.error("[Hotwire] the running Java is not known; {} queued updates go in as the game closes",
                    entries.size());
            this.swapInProcess();
            return;
        }
        try {
            copySwapper(this.hotwireJar, this.swapperCopy);
            Files.write(this.folders.swapperFolders(), List.of(this.folders.queueFile().toAbsolutePath().toString(),
                    this.folders.mods().toAbsolutePath().toString(),
                    this.folders.replaced().toAbsolutePath().toString(),
                    FabricLoader.getInstance().getGameDir().resolve("hotwire").resolve("store").toAbsolutePath()
                            .toString()));
            Process swapper = new ProcessBuilder(java.get(), "-Xmx16m", "-XX:+UseSerialGC",
                    "-XX:TieredStopAtLevel=1", "-cp", this.copyClassPath(), JarSwapper.class.getName(),
                    Long.toString(ProcessHandle.current().pid()))
                    .directory(this.folders.swapper().toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(this.folders.swapperLog().toFile())
                    .start();
            this.started = true;

            if (swapper.waitFor(STARTED_MILLIS, TimeUnit.MILLISECONDS)) {
                LOGGER.warn("[Hotwire] the swapper ended at once (exit {}); queued updates go in as the game closes",
                        swapper.exitValue());
                this.swapInProcess();
                return;
            }
            LOGGER.info("[Hotwire] the swapper waits to put what is queued in place once the game ends ({} now)",
                    entries.size());
        } catch (IOException e) {
            LOGGER.error("[Hotwire] the swapper could not start; {} queued updates go in as the game closes",
                    entries.size(), e);
            this.swapInProcess();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void swapInProcess() {
        if (this.inProcess || System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            return;
        }
        this.inProcess = true;
        this.started = true;
        Runnable swap = () -> {
            try {
                JarSwapper.swap(this.queue, this.folders.mods(), this.folders.replaced());
                LOGGER.info("[Hotwire] the queued jars were put in place as the game closed");
            } catch (IOException | RuntimeException e) {
                LOGGER.error("[Hotwire] the queued jars could not be put in place as the game closed", e);
            }
        };
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(swap, "Hotwire swap in the game"));
        } catch (IllegalStateException closing) {
            swap.run();
        }
    }

    private synchronized void watchClose() {
        Optional<String> java = findJava();
        if (java.isEmpty()) {
            return;
        }
        try {
            try {
                copySwapper(this.hotwireJar, this.swapperCopy);
            } catch (IOException running) {
            }
            new ProcessBuilder(java.get(), "-Xmx16m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-cp",
                    this.swapperCopy.getFileName().toString(), CloseWatch.class.getName(),
                    Long.toString(ProcessHandle.current().pid()))
                    .directory(this.folders.swapper().toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(this.folders.swapperLog().resolveSibling("close-watch.log").toFile())
                    .start();
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[Hotwire] the close watch could not start: {}", e.toString());
        }
    }

    static void copySwapper(Path hotwireJar, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        Path own = findOwnJar();
        if (own != null) {
            Files.copy(own, to, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        try (ZipFile outer = new ZipFile(hotwireJar.toFile())) {
            ZipEntry core = outer.getEntry(CORE);
            if (core != null) {
                try (InputStream in = outer.getInputStream(core)) {
                    Files.copy(in, to, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            }
        }
        Files.copy(hotwireJar, to, StandardCopyOption.REPLACE_EXISTING);
    }

    public static boolean canRunFrom(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return zip.getEntry(SWAPPER) != null || zip.getEntry(CORE) != null;
        } catch (IOException e) {
            return false;
        }
    }

    private static @Nullable Path findOwnJar() {
        CodeSource source = JarSwapper.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            return null;
        }
        try {
            Path home = Path.of(source.getLocation().toURI());
            if (!Files.isRegularFile(home)) {
                return null;
            }
            try (ZipFile zip = new ZipFile(home.toFile())) {
                return zip.getEntry(SWAPPER) != null ? home : null;
            }
        } catch (URISyntaxException | IOException | RuntimeException e) {
            return null;
        }
    }

    private String copyClassPath() throws IOException {
        List<String> entries = new ArrayList<>();
        entries.add(this.swapperCopy.getFileName().toString());
        List<Path> copies = this.folders.swapperLibraries();
        List<Class<?>> libraries = List.of(JsonParseException.class, Logger.class);
        for (int at = 0; at < libraries.size(); at++) {
            CodeSource source = libraries.get(at).getProtectionDomain().getCodeSource();
            if (source != null) {
                Files.copy(Path.of(URI.create(source.getLocation().toString())), copies.get(at),
                        StandardCopyOption.REPLACE_EXISTING);
                entries.add(copies.get(at).getFileName().toString());
            }
        }
        return String.join(File.pathSeparator, entries);
    }

    private static Optional<String> findJava() {
        Optional<String> command = ProcessHandle.current().info().command();
        if (command.isPresent()) {
            return command;
        }
        Path bin = Path.of(System.getProperty("java.home", "")).resolve("bin");
        return Stream.of("javaw.exe", "java.exe", "java").map(bin::resolve).filter(Files::isRegularFile)
                .map(String::valueOf).findFirst();
    }

    public static List<String> getUnfinished(UpdateQueue queue) {
        return queue.getEntries().stream().filter(entry -> !entry.reason().isEmpty())
                .map(entry -> entry.name() + " " + entry.toVersion() + " was not put in place: " + entry.reason()
                        + " It is tried again at the next close; cancel it any time from Pending tasks.")
                .toList();
    }

    public static void saveLastLogs(Path swapper, Path logs) {
        if (!Files.isDirectory(swapper)) {
            return;
        }
        StringBuilder all = new StringBuilder();
        try (DirectoryStream<Path> written = Files.newDirectoryStream(swapper, "*.log")) {
            for (Path log : written) {
                String text = Files.readString(log);
                if (!text.isBlank()) {
                    all.append(log.getFileName()).append('\n').append(text).append('\n');
                }
            }
            if (all.isEmpty()) {
                Files.deleteIfExists(logs.resolve(LAST_LOGS));
            } else {
                Files.createDirectories(logs);
                Files.writeString(logs.resolve(LAST_LOGS), all);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("[Hotwire] the last close's logs could not be read: {}", e.toString());
        }
    }
}
