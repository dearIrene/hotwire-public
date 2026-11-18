package dev.moon.hotwire.store;

import dev.moon.hotwire.engine.CacheLimit;
import dev.moon.hotwire.engine.InstalledMods;
import dev.moon.hotwire.engine.LoadedMods;
import dev.moon.hotwire.modrinth.Downloads;
import dev.moon.hotwire.modrinth.ModrinthClient;
import dev.moon.hotwire.modrinth.StoreCache;
import dev.moon.hotwire.modrinth.StoreModel;
import dev.moon.hotwire.seam.Game;
import dev.moon.hotwire.seam.Games;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.widgets.Canvas;
import dev.moon.hotwire.ui.widgets.ImageDecoder;
import dev.moon.hotwire.update.UpdateOffer;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ModStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModStore.class);
    private static final String LOADER = "fabric";

    private final UpdateCheck check;
    private final StoreModel model;
    private final StoreIconLoader icons;
    private final String gameVersion;
    private final Path downloads;

    public ModStore(InstalledMods installed, UpdateCheck check, Function<String, Canvas.@Nullable Image> icons,
            ImageDecoder decoder) {
        this.check = check;
        Path game = FabricLoader.getInstance().getGameDir();
        this.gameVersion = findVersionOf("minecraft");
        ModrinthClient client = LoadedMods.makeStoreClient();
        StoreCache cache = new StoreCache(CacheLimit.findStoreFolder().resolve("answers"),
                (long) CacheLimit.readStore() << 20);
        this.model = new StoreModel(client, cache, id -> installed.isInstalled(id)
                || FabricLoader.getInstance().isModLoaded(id), () -> { });
        this.icons = new StoreIconLoader(this.model, cache, project -> {
            String id = check.getIdByProject().get(project);
            return id == null ? null : icons.apply(id);
        }, decoder);
        this.downloads = game.resolve("hotwire").resolve("store");
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
        this.check.start(offers -> this.model.run(() -> {
            try {
                Map<String, ModrinthClient.Version> updates = new HashMap<>();
                for (UpdateOffer offer : offers) {
                    updates.put(offer.projectId(), new ModrinthClient.Version(offer.versionId(), offer.projectId(),
                            offer.newVersion(), offer.newVersion(), offer.fileName(), offer.url(), offer.sha512(),
                            offer.size(), List.of(), offer.requiredProjects(), offer.changelog()));
                }
                this.model.setInstalled(this.check.getInstalledProjects(), updates);
            } finally {
                this.installedKnown.complete(null);
            }
        }));
    }

    private final CompletableFuture<Void> installedKnown = new CompletableFuture<>();

    public void awaitInstalledKnown() {
        try {
            this.installedKnown.get(INSTALLED_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.info("[Hotwire] the store's search goes on without the check of the installed mods: {}",
                    e.toString());
        }
    }

    private static final int INSTALLED_WAIT_SECONDS = 15;

    public void fetch(String projectId, String versionId, String title, BiConsumer<List<Path>, List<String>> onJars,
            Consumer<String> onFailure) {
        Game client = Games.getNow();

        Thread.ofPlatform().name("hotwire-store-fetch").daemon().start(() -> {
            List<ModrinthClient.Version> versions;
            try {
                versions = this.model.getClient().findVersions(ModrinthClient.Kind.MOD, projectId);
            } catch (IOException | RuntimeException e) {
                String why = Lang.get("hotwire.store.failed", e.getMessage());
                client.runOnGame(() -> onFailure.accept(why));
                return;
            }
            ModrinthClient.Version wanted = versions.stream()
                    .filter(version -> versionId.isEmpty() || version.id().equals(versionId)).findFirst()
                    .orElse(null);
            if (wanted == null) {
                String why = Lang.get("hotwire.store.no_version", title);
                client.runOnGame(() -> onFailure.accept(why));
                return;
            }
            this.fetchFor(wanted, title, onJars, onFailure);
        });
    }

    private void fetchFor(ModrinthClient.Version wanted, String title,
            BiConsumer<List<Path>, List<String>> onJars, Consumer<String> onFailure) {
        Game client = Games.getNow();
        try {
            List<ModrinthClient.Version> all = new ArrayList<>(this.findNeeded(wanted, new HashSet<>()));
            all.add(wanted);
            List<Path> jars = this.fetchAll(all);
            List<String> projects = all.stream().map(ModrinthClient.Version::projectId).toList();
            client.runOnGame(() -> onJars.accept(jars, projects));
        } catch (Downloads.DownloadStop e) {
            LOGGER.info("[Hotwire] the download of {} was stopped", title);
            client.runOnGame(() -> onFailure.accept(""));
        } catch (IOException e) {
            LOGGER.info("[Hotwire] the store could not get {}: {}", title, e.toString());
            client.runOnGame(() -> onFailure.accept(Lang.get("hotwire.store.download_failed", title,
                    e.getMessage())));
        }
    }

    private List<ModrinthClient.Version> findNeeded(ModrinthClient.Version version, Set<String> seen)
            throws IOException {
        List<Callable<List<ModrinthClient.Version>>> asks = new ArrayList<>();
        for (String needed : version.requiredProjects()) {
            if (!seen.contains(needed) && !this.model.isInstalledProject(needed)) {
                asks.add(() -> {
                    if (this.model.isInstalledProject(this.model.getClient().findProject(needed).slug())) {
                        return List.of();
                    }
                    List<ModrinthClient.Version> versions = this.model.getClient()
                            .findVersions(ModrinthClient.Kind.MOD, needed);
                    if (!versions.isEmpty()) {
                        return versions.subList(0, 1);
                    }
                    if (!this.model.getClient().isForThisLoader(needed)) {
                        return List.of();
                    }
                    throw new IOException(Lang.get("hotwire.store.needs_no_version",
                            this.model.getClient().findProject(needed).title()));
                });
            }
        }
        List<ModrinthClient.Version> out = new ArrayList<>();

        for (ModrinthClient.Version newest : runAtOnce(asks).stream().flatMap(List::stream).toList()) {
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
        List<Integer> jobs = new ArrayList<>();
        for (ModrinthClient.Version version : versions) {
            int job = downloads.begin(version.fileName(), version.size());
            jobs.add(job);
            fetches.add(() -> {
                try {
                    return this.fetchJar(version, job);
                } catch (Downloads.DownloadStop e) {
                    jobs.forEach(Downloads::stop);
                    throw e;
                }
            });
        }
        return runAtOnce(fetches);
    }

    private Path fetchJar(ModrinthClient.Version version, int job) throws IOException {
        Downloads downloads = this.model.getDownloads();
        downloads.start(job);
        try {
            Path jar = version.resolveIn(this.downloads);
            Files.createDirectories(jar.getParent());
            this.model.getClient().fetchTo(version, jar, downloads.findFeed(job));
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
