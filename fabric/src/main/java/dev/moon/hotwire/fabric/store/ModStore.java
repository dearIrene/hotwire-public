package dev.moon.hotwire.fabric.store;

import dev.moon.hotwire.fabric.InstalledMods;
import dev.moon.hotwire.fabric.LoadedMods;
import dev.moon.hotwire.modrinth.LocalJars;
import dev.moon.hotwire.modrinth.ModrinthClient;
import dev.moon.hotwire.modrinth.StoreCache;
import dev.moon.hotwire.modrinth.StoreModel;
import dev.moon.hotwire.ui.Lang;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ModStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModStore.class);
    private static final String LOADER = "fabric";

    private final InstalledMods installed;
    private final StoreModel model;
    private final StoreIconLoader icons;
    private final String gameVersion;
    private final Path downloads;

    public ModStore(InstalledMods installed) {
        this.installed = installed;
        Path game = FabricLoader.getInstance().getGameDir();
        this.gameVersion = findVersionOf("minecraft");
        ModrinthClient client = makeClient();
        StoreCache cache = new StoreCache(game.resolve("hotwire").resolve("store-cache"));
        this.model = new StoreModel(client, cache, id -> installed.isInstalled(id)
                || FabricLoader.getInstance().isModLoaded(id), () -> { });
        this.icons = new StoreIconLoader(this.model, cache);
        this.downloads = game.resolve("hotwire").resolve("store");
    }

    public static ModrinthClient makeClient() {
        return new ModrinthClient(findVersionOf("hotwire"), LOADER, findVersionOf("minecraft"),
                new LocalJars(FabricLoader.getInstance().getGameDir(), LocalJars.findLauncherHomes()));
    }

    public StoreModel getModel() {
        return this.model;
    }

    public StoreIconLoader getIcons() {
        return this.icons;
    }

    public String getGameVersion() {
        return this.gameVersion;
    }

    public String getLoader() {
        return LOADER;
    }

    public void checkInstalled() {
        Map<String, Path> jars = this.installed.getJarsByTopLevel();
        this.model.run(() -> {
            Map<String, String> hashByName = JarHashes.of(jars.values());
            if (hashByName.isEmpty()) {
                return;
            }
            try {
                Map<String, ModrinthClient.Version> newest = this.model.getClient().findNewest(hashByName);
                Set<String> projects = new HashSet<>();
                Map<String, ModrinthClient.Version> updates = new HashMap<>();
                newest.forEach((name, version) -> {
                    projects.add(version.projectId());
                    if (!version.sha512().equalsIgnoreCase(hashByName.get(name))) {
                        updates.put(version.projectId(), version);
                    }
                });
                LOGGER.info("[Hotwire] the store knows {} of {} mods; {} have a newer version", projects.size(),
                        hashByName.size(), updates.size());
                this.model.setInstalled(projects, updates);
            } catch (IOException | RuntimeException e) {
                LOGGER.info("[Hotwire] the store could not check the installed mods: {}", e.toString());
            }
        });
    }

    public void fetch(String projectId, String versionId, String title, BiConsumer<List<Path>, String> onJars,
            Consumer<String> onFailure) {
        Minecraft client = Minecraft.getInstance();
        this.model.findVersions(projectId, versions -> {
            ModrinthClient.Version wanted = versions.stream()
                    .filter(version -> versionId.isEmpty() || version.id().equals(versionId)).findFirst()
                    .orElse(null);
            if (wanted == null) {
                String why = Lang.get(versionId.isEmpty() ? "hotwire.store.no_version"
                        : "hotwire.store.no_such_version", title);
                client.execute(() -> onFailure.accept(why));
                return;
            }
            try {
                List<Path> jars = new ArrayList<>();
                for (ModrinthClient.Version needed : this.findNeeded(wanted, new HashSet<>())) {
                    jars.add(this.fetchJar(needed));
                }
                jars.add(this.fetchJar(wanted));
                client.execute(() -> onJars.accept(jars, projectId));
            } catch (IOException e) {
                LOGGER.info("[Hotwire] the store could not get {}: {}", title, e.toString());
                client.execute(() -> onFailure.accept(Lang.get("hotwire.store.download_failed", title,
                        e.getMessage())));
            }
        }, failure -> client.execute(() -> onFailure.accept(Lang.get("hotwire.store.failed", failure))));
    }

    private List<ModrinthClient.Version> findNeeded(ModrinthClient.Version version, Set<String> seen)
            throws IOException {
        List<ModrinthClient.Version> out = new ArrayList<>();
        for (String needed : version.requiredProjects()) {
            if (!seen.add(needed) || this.model.isInstalledProject(needed)) {
                continue;
            }
            List<ModrinthClient.Version> versions = this.model.getClient().findVersions(needed);
            if (versions.isEmpty()) {
                throw new IOException("a mod it needs has no version for this game: " + needed);
            }
            out.addAll(this.findNeeded(versions.getFirst(), seen));
            out.add(versions.getFirst());
        }
        return out;
    }

    private Path fetchJar(ModrinthClient.Version version) throws IOException {
        byte[] bytes = this.model.getClient().fetch(version);
        Files.createDirectories(this.downloads);
        Path jar = this.downloads.resolve(version.fileName().replaceAll("[^A-Za-z0-9._+ -]", "_"));
        Files.write(jar, bytes);
        return jar;
    }

    private static String findVersionOf(String id) {
        return LoadedMods.findVersion(id, "unknown");
    }
}
