package dev.moon.hotwire.fabric.store;

import dev.moon.hotwire.config.AtomicFiles;
import dev.moon.hotwire.fabric.CacheLimit;
import dev.moon.hotwire.fabric.InstalledMods;
import dev.moon.hotwire.fabric.LoadedMods;
import dev.moon.hotwire.jar.FileHashes;
import dev.moon.hotwire.modrinth.Downloads;
import dev.moon.hotwire.modrinth.LocalJars;
import dev.moon.hotwire.modrinth.ModrinthClient;
import dev.moon.hotwire.modrinth.StoreCache;
import dev.moon.hotwire.modrinth.StoreModel;
import dev.moon.hotwire.ui.Canvas;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
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

    private volatile Map<String, String> modByProject = Map.of();

    public ModStore(InstalledMods installed, Function<String, Canvas.@Nullable Image> icons) {
        this.installed = installed;
        Path game = FabricLoader.getInstance().getGameDir();
        this.gameVersion = findVersionOf("minecraft");
        ModrinthClient client = makeClient();
        StoreCache cache = new StoreCache(CacheLimit.findStoreFolder().resolve("answers"),
                (long) CacheLimit.readStore() << 20);
        this.model = new StoreModel(client, cache, id -> installed.isInstalled(id)
                || FabricLoader.getInstance().isModLoaded(id), () -> { });
        this.icons = new StoreIconLoader(this.model, cache, project -> {
            String id = this.modByProject.get(project);
            return id == null ? null : icons.apply(id);
        });
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
            Map<String, String> hashByName = FileHashes.findSha512ByName(jars.values());
            if (hashByName.isEmpty()) {
                return;
            }
            Map<String, String> idByName = new HashMap<>();
            jars.forEach((id, jar) -> idByName.put(String.valueOf(jar.getFileName()), id));
            try {
                Map<String, ModrinthClient.Version> newest = this.model.getClient().findNewest(hashByName);
                Set<String> projects = new HashSet<>();
                Map<String, String> mods = new HashMap<>();
                Map<String, ModrinthClient.Version> updates = new HashMap<>();
                newest.forEach((name, version) -> {
                    projects.add(version.projectId());
                    String id = idByName.get(name);
                    if (id != null) {
                        mods.put(version.projectId(), id);
                    }
                    if (!version.sha512().equalsIgnoreCase(hashByName.get(name))) {
                        updates.put(version.projectId(), version);
                    }
                });
                LOGGER.info("[Hotwire] the store knows {} of {} mods; {} have a newer version", projects.size(),
                        hashByName.size(), updates.size());
                this.modByProject = Map.copyOf(mods);
                this.model.setInstalled(projects, updates);
            } catch (IOException | RuntimeException e) {
                LOGGER.info("[Hotwire] the store could not check the installed mods: {}", e.toString());
            }
        });
    }

    public void fetch(String projectId, String versionId, String title, BiConsumer<List<Path>, List<String>> onJars,
            Consumer<String> onFailure) {
        Minecraft client = Minecraft.getInstance();

        Thread.ofPlatform().name("hotwire-store-fetch").daemon().start(() -> {
            List<ModrinthClient.Version> versions;
            try {
                versions = this.model.getClient().findVersions(ModrinthClient.Kind.MOD, projectId);
            } catch (IOException | RuntimeException e) {
                String why = Lang.get("hotwire.store.failed", e.getMessage());
                client.execute(() -> onFailure.accept(why));
                return;
            }
            ModrinthClient.Version wanted = versions.stream()
                    .filter(version -> versionId.isEmpty() || version.id().equals(versionId)).findFirst()
                    .orElse(null);
            if (wanted == null) {
                String why = Lang.get("hotwire.store.no_version", title);
                client.execute(() -> onFailure.accept(why));
                return;
            }
            this.fetchFor(wanted, title, onJars, onFailure);
        });
    }

    private void fetchFor(ModrinthClient.Version wanted, String title,
            BiConsumer<List<Path>, List<String>> onJars, Consumer<String> onFailure) {
        Minecraft client = Minecraft.getInstance();
        try {
            List<ModrinthClient.Version> all = new ArrayList<>(this.findNeeded(wanted, new HashSet<>()));
            all.add(wanted);
            List<Path> jars = this.fetchAll(all);
            List<String> projects = all.stream().map(ModrinthClient.Version::projectId).toList();
            client.execute(() -> onJars.accept(jars, projects));
        } catch (IOException e) {
            LOGGER.info("[Hotwire] the store could not get {}: {}", title, e.toString());
            client.execute(() -> onFailure.accept(Lang.get("hotwire.store.download_failed", title,
                    e.getMessage())));
        }
    }

    private List<ModrinthClient.Version> findNeeded(ModrinthClient.Version version, Set<String> seen)
            throws IOException {
        List<Callable<ModrinthClient.Version>> asks = new ArrayList<>();
        for (String needed : version.requiredProjects()) {
            if (!seen.contains(needed) && !this.model.isInstalledProject(needed)) {
                asks.add(() -> {
                    List<ModrinthClient.Version> versions = this.model.getClient()
                            .findVersions(ModrinthClient.Kind.MOD, needed);
                    if (versions.isEmpty()) {
                        throw new IOException("a mod it needs has no version for this game: " + needed);
                    }
                    return versions.getFirst();
                });
            }
        }
        List<ModrinthClient.Version> out = new ArrayList<>();

        for (ModrinthClient.Version newest : runAtOnce(asks)) {
            if (seen.contains(newest.projectId())) {
                continue;
            }
            out.addAll(this.findNeeded(newest, seen));
            if (seen.add(newest.projectId())) {
                out.add(newest);
            }
        }
        return out;
    }

    private static <T> List<T> runAtOnce(List<Callable<T>> tasks) throws IOException {
        List<Future<T>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            tasks.forEach(task -> futures.add(pool.submit(task)));
        }
        List<T> out = new ArrayList<>();
        for (Future<T> future : futures) {
            try {
                out.add(future.get());
            } catch (ExecutionException e) {
                throw e.getCause() instanceof IOException failed ? failed : new IOException(e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        }
        return out;
    }

    private List<Path> fetchAll(List<ModrinthClient.Version> versions) throws IOException {
        Downloads downloads = this.model.getDownloads();
        List<Callable<Path>> fetches = new ArrayList<>();
        for (ModrinthClient.Version version : versions) {
            int job = downloads.begin(version.fileName(), version.size());
            fetches.add(() -> this.fetchJar(version, job));
        }
        return runAtOnce(fetches);
    }

    private Path fetchJar(ModrinthClient.Version version, int job) throws IOException {
        Downloads downloads = this.model.getDownloads();
        downloads.start(job);
        try {
            byte[] bytes = this.model.getClient().fetch(version, done -> downloads.advance(job, done));
            Files.createDirectories(this.downloads);
            Path jar = this.downloads.resolve(version.fileName().replaceAll("[^A-Za-z0-9._+ -]", "_"));

            AtomicFiles.write(jar, bytes);
            downloads.end(job, true);
            return jar;
        } catch (IOException | RuntimeException e) {
            downloads.end(job, false);
            throw e;
        }
    }

    private static String findVersionOf(String id) {
        return LoadedMods.findVersion(id, "unknown");
    }
}
