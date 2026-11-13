package dev.moon.hotwire.fabric.update;

import com.google.gson.JsonParseException;
import dev.moon.hotwire.update.CloseWatch;
import dev.moon.hotwire.update.JarSwapper;
import dev.moon.hotwire.update.UpdateFolders;
import dev.moon.hotwire.update.UpdateQueue;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SwapAtClose {
    public static final String LAST_LOGS = "last-close.log";
    private static final Logger LOGGER = LoggerFactory.getLogger(SwapAtClose.class);

    private final UpdateQueue queue;
    private final UpdateFolders folders;
    private final Path hotwireJar;
    private final Path swapperCopy;
    private boolean started;

    public SwapAtClose(UpdateQueue queue, UpdateFolders folders, Path hotwireJar) {
        this.queue = queue;
        this.folders = folders;
        this.hotwireJar = hotwireJar;
        this.swapperCopy = folders.swapperJar();
    }

    public void arm() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::start, "Hotwire swapper launch"));
        Runtime.getRuntime().addShutdownHook(new Thread(this::watchClose, "Hotwire close watch launch"));
        this.queue.setOnAdded(this::start);
        if (!this.queue.getEntries().isEmpty()) {
            Thread.ofPlatform().name("Hotwire swapper launch").daemon().start(this::start);
        }
    }

    private synchronized void start() {
        List<UpdateQueue.Entry> entries = this.queue.getEntries();
        if (this.started || entries.isEmpty()) {
            return;
        }
        Optional<String> java = ProcessHandle.current().info().command();
        if (java.isEmpty()) {
            LOGGER.error("[Hotwire] the running Java is not known, so {} queued updates wait for the next close",
                    entries.size());
            return;
        }
        try {
            Files.createDirectories(this.swapperCopy.getParent());
            Files.copy(this.hotwireJar, this.swapperCopy, StandardCopyOption.REPLACE_EXISTING);
            new ProcessBuilder(java.get(), "-Xmx16m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-cp",
                    this.getClassPath(), JarSwapper.class.getName(),
                    this.folders.queueFile().toString(), this.folders.mods().toString(),
                    this.folders.replaced().toString(), Long.toString(ProcessHandle.current().pid()),
                    FabricLoader.getInstance().getGameDir().resolve("hotwire").resolve("store").toString())
                    .redirectErrorStream(true)
                    .redirectOutput(this.folders.swapperLog().toFile())
                    .start();
            this.started = true;
            LOGGER.info("[Hotwire] the swapper waits to put what is queued in place once the game ends ({} now)",
                    entries.size());
        } catch (IOException e) {
            LOGGER.error("[Hotwire] the swapper could not start; {} queued updates wait for the next close",
                    entries.size(), e);
        }
    }

    private synchronized void watchClose() {
        Optional<String> java = ProcessHandle.current().info().command();
        if (java.isEmpty()) {
            return;
        }
        try {
            Files.createDirectories(this.swapperCopy.getParent());
            try {
                Files.copy(this.hotwireJar, this.swapperCopy, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException running) {
            }
            new ProcessBuilder(java.get(), "-Xmx16m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-cp",
                    this.swapperCopy.toString(), CloseWatch.class.getName(),
                    Long.toString(ProcessHandle.current().pid()))
                    .redirectErrorStream(true)
                    .redirectOutput(this.folders.swapperLog().resolveSibling("close-watch.log").toFile())
                    .start();
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[Hotwire] the close watch could not start: {}", e.toString());
        }
    }

    private String getClassPath() {
        List<String> entries = new ArrayList<>();
        entries.add(this.swapperCopy.toString());
        for (Class<?> library : List.of(JsonParseException.class, Logger.class)) {
            CodeSource source = library.getProtectionDomain().getCodeSource();
            if (source != null) {
                entries.add(Path.of(URI.create(source.getLocation().toString())).toString());
            }
        }
        return String.join(File.pathSeparator, entries);
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
