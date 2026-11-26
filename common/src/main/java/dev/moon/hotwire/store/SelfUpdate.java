package dev.moon.hotwire.store;

import dev.moon.hotwire.classfile.GameNames;
import dev.moon.hotwire.engine.LoadedMods;
import dev.moon.hotwire.engine.ModPolicy;
import dev.moon.hotwire.jar.FileHashes;
import dev.moon.hotwire.jar.JarLookup;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.modrinth.Downloads;
import dev.moon.hotwire.modrinth.ModrinthClient;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.update.HotwireChanges;
import dev.moon.hotwire.update.UrgentUpdate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SelfUpdate {
    private static final Logger LOGGER = LoggerFactory.getLogger(SelfUpdate.class);

    private final ModrinthClient client = LoadedMods.makeStoreClient();
    private final Downloads downloads = new Downloads();
    private final Path folder = FabricLoader.getInstance().getGameDir().resolve("hotwire").resolve("store");

    private static final String OWN_PROJECT = "6CCeZxnF";
    private volatile ModrinthClient.@Nullable Version newer;
    private volatile String changes = "";
    private boolean started;

    public void start(Consumer<ModrinthClient.Version> then, Consumer<Optional<UrgentUpdate>> checked) {
        if (this.started) {
            return;
        }
        this.started = true;
        Thread.ofPlatform().name("hotwire-self-update").daemon().start(() -> {
            ModrinthClient.Version found = this.find(checked);
            if (found != null) {
                this.newer = found;
                OutdatedNotice.set(found.number());
                then.accept(found);
            }
        });
    }

    public ModrinthClient.@Nullable Version findNow() {
        ModrinthClient.Version found = this.find(urgent -> { });
        if (found != null) {
            this.newer = found;
            OutdatedNotice.set(found.number());
        }
        return found;
    }

    private ModrinthClient.@Nullable Version find(Consumer<Optional<UrgentUpdate>> checked) {
        try {
            String project = this.findOwnProject();
            if (project == null) {
                LOGGER.info("[Hotwire] this Hotwire does not run from one jar, so no newer one is looked for");
                return null;
            }
            List<ModrinthClient.Version> versions = this.client.findVersions(ModrinthClient.Kind.MOD, project);
            String running = LoadedMods.findVersion(GameNames.HOTWIRE, "0");
            this.changes = HotwireChanges.join(versions, number -> ModPolicy.isNewer(number, running));
            checked.accept(Optional.ofNullable(UrgentUpdate.find(versions,
                    number -> ModPolicy.isNewer(number, running))));
            if (versions.isEmpty()) {
                return null;
            }
            ModrinthClient.Version newest = versions.getFirst();
            return ModPolicy.isNewer(newest.number(), running) ? newest : null;
        } catch (IOException | RuntimeException e) {
            LOGGER.info("[Hotwire] could not ask Modrinth for a newer Hotwire: {}", e.toString());
            this.started = false;
            return null;
        }
    }

    private @Nullable String findOwnProject() throws IOException {
        ModContainer own = FabricLoader.getInstance().getModContainer(GameNames.HOTWIRE).orElse(null);
        if (own == null || own.getOrigin().getKind() != ModOrigin.Kind.PATH
                || own.getOrigin().getPaths().size() != 1) {
            return null;
        }
        Path jar = own.getOrigin().getPaths().getFirst();
        if (!Files.isRegularFile(jar)) {
            return null;
        }
        ModrinthClient.Version version = this.client.findByHashes(Map.of(GameNames.HOTWIRE,
                FileHashes.findSha512(jar))).get(GameNames.HOTWIRE);
        return version == null ? OWN_PROJECT : version.projectId();
    }

    public void allowAgain() {
        this.started = false;
    }

    public ModrinthClient.@Nullable Version getNewer() {
        return this.newer;
    }

    public String getChanges() {
        return this.changes;
    }

    public Path fetch(ModrinthClient.Version version) throws IOException {
        Path jar = version.resolveIn(this.folder);
        Files.createDirectories(jar.getParent());
        this.client.fetchTo(version, jar, this.downloads);
        ModManifest manifest = JarLookup.findManifest(jar);
        if (manifest == null || !manifest.id().equals(GameNames.HOTWIRE)) {
            Files.deleteIfExists(jar);
            throw new IOException(Lang.get("hotwire.self_update.not_hotwire"));
        }
        return jar;
    }
}
