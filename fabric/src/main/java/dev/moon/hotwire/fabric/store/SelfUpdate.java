package dev.moon.hotwire.fabric.store;

import dev.moon.hotwire.config.AtomicFiles;
import dev.moon.hotwire.fabric.LoadedMods;
import dev.moon.hotwire.modrinth.ModrinthClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SelfUpdate {
    public static final String PROJECT = "irenehotwire";
    private static final Logger LOGGER = LoggerFactory.getLogger(SelfUpdate.class);

    private final ModrinthClient client = ModStore.makeClient();
    private final Path folder = FabricLoader.getInstance().getGameDir().resolve("hotwire").resolve("store");
    private volatile ModrinthClient.@Nullable Version newer;
    private boolean started;

    public void start(Consumer<ModrinthClient.Version> then) {
        if (this.started) {
            return;
        }
        this.started = true;
        Thread.ofPlatform().name("hotwire-self-update").daemon().start(() -> {
            ModrinthClient.Version found = this.find();
            if (found != null) {
                this.newer = found;
                then.accept(found);
            }
        });
    }

    private ModrinthClient.@Nullable Version find() {
        try {
            List<ModrinthClient.Version> versions = this.client.findVersions(ModrinthClient.Kind.MOD, PROJECT);
            if (versions.isEmpty()) {
                return null;
            }
            ModrinthClient.Version newest = versions.getFirst();
            return isNewer(newest.number(), LoadedMods.findVersion("hotwire", "0")) ? newest : null;
        } catch (IOException | RuntimeException e) {
            LOGGER.info("[Hotwire] could not ask Modrinth for a newer Hotwire: {}", e.toString());
            return null;
        }
    }

    static boolean isNewer(String modrinth, String running) {
        try {
            return Version.parse(modrinth).compareTo(Version.parse(running)) > 0;
        } catch (VersionParsingException e) {
            return false;
        }
    }

    public ModrinthClient.@Nullable Version getNewer() {
        return this.newer;
    }

    public Path fetch(ModrinthClient.Version version) throws IOException {
        byte[] bytes = this.client.fetch(version);
        Files.createDirectories(this.folder);
        Path jar = this.folder.resolve(version.fileName().replaceAll("[^A-Za-z0-9._+ -]", "_"));
        AtomicFiles.write(jar, bytes);
        return jar;
    }
}
