package dev.moon.hotwire.fabric.update;

import com.google.gson.JsonParseException;
import dev.moon.hotwire.report.GameLog;
import dev.moon.hotwire.update.JarSwapper;
import dev.moon.hotwire.update.UpdateFolders;
import dev.moon.hotwire.update.UpdateQueue;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SwapAtClose {
    private static final Logger LOGGER = LoggerFactory.getLogger(SwapAtClose.class);

    private final UpdateQueue queue;
    private final UpdateFolders folders;
    private final Path hotwireJar;
    private final Path swapperCopy;

    public SwapAtClose(UpdateQueue queue, UpdateFolders folders, Path hotwireJar) {
        this.queue = queue;
        this.folders = folders;
        this.hotwireJar = hotwireJar;
        this.swapperCopy = folders.swapper().resolve("hotwire.jar");
    }

    public void arm() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::start, "Hotwire swapper launch"));
    }

    private void start() {
        List<UpdateQueue.Entry> entries = this.queue.getEntries();
        if (entries.isEmpty()) {
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
            new ProcessBuilder(java.get(), "-cp", this.getClassPath(), JarSwapper.class.getName(),
                    this.folders.queueFile().toString(), this.folders.mods().toString(),
                    this.folders.replaced().toString(), Long.toString(ProcessHandle.current().pid()))
                    .redirectErrorStream(true)
                    .redirectOutput(this.swapperCopy.resolveSibling("swapper.log").toFile())
                    .start();
            GameLog.LOGGER.info("[Hotwire] the swapper will put {} updates in place once the game closes",
                    entries.size());
        } catch (IOException e) {
            LOGGER.error("[Hotwire] the swapper could not start; {} queued updates wait for the next close",
                    entries.size(), e);
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
}
