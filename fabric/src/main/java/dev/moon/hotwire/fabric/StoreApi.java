package dev.moon.hotwire.fabric;

import dev.moon.hotwire.api.Reason;
import dev.moon.hotwire.api.StoreCategory;
import dev.moon.hotwire.api.StoreDownload;
import dev.moon.hotwire.api.StoreKind;
import dev.moon.hotwire.api.StoreMod;
import dev.moon.hotwire.api.StorePackState;
import dev.moon.hotwire.api.StorePage;
import dev.moon.hotwire.api.StoreProject;
import dev.moon.hotwire.api.StoreQuery;
import dev.moon.hotwire.api.StoreVersion;
import dev.moon.hotwire.api.SwitchResult;
import dev.moon.hotwire.fabric.add.ModAdder;
import dev.moon.hotwire.fabric.screen.WebLinks;
import dev.moon.hotwire.fabric.store.ModStore;
import dev.moon.hotwire.modrinth.CachedAsks;
import dev.moon.hotwire.modrinth.Downloads;
import dev.moon.hotwire.modrinth.ModrinthClient;
import dev.moon.hotwire.modrinth.StoreCategories;
import dev.moon.hotwire.modrinth.StoreModel;
import dev.moon.hotwire.switching.Waits;
import dev.moon.hotwire.ui.AddMode;
import dev.moon.hotwire.ui.Lang;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class StoreApi {
    private static final Logger LOGGER = LoggerFactory.getLogger(StoreApi.class);
    private static final int PAGE = 20;

    private static final Executor ASK = run -> Thread.ofVirtual().name("hotwire-api-store").start(run);

    private static final Downloads SAVES = new Downloads();
    private static final Object BROWSING = new Object();

    private static final Map<String, String> IDS = new ConcurrentHashMap<>();
    private static volatile @Nullable ModrinthClient client;

    private StoreApi() {
    }

    private static ModrinthClient getClient() {
        ModrinthClient made = client;
        if (made == null) {
            made = ModStore.makeClient();
            client = made;
        }
        return made;
    }

    static ModrinthClient.Kind toKind(StoreKind kind) {
        return ModrinthClient.Kind.valueOf(kind.name());
    }

    private interface ClientCall<T> {
        T run(ModrinthClient made) throws IOException;
    }

    private interface IoSupplier<T> {
        T get() throws IOException;
    }

    private static <T> T askBrowsing(StoreQuery query, ClientCall<T> ask) throws IOException {
        ModrinthClient made = getClient();
        synchronized (BROWSING) {
            made.setBrowsing(query.loader().isEmpty() ? made.getLoader() : query.loader(),
                    query.gameVersion().isEmpty() ? made.getGameVersion() : query.gameVersion());
            return ask.run(made);
        }
    }

    private static <T> CompletableFuture<T> ask(IoSupplier<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return work.get();
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, ASK);
    }

    static @Nullable ModStore findStore() {
        HotwireEngine engine = HotwireClient.findEngine();
        if (engine == null) {
            return null;
        }
        ModStore made = engine.getStore();
        return made != null ? made : CompletableFuture.supplyAsync(engine::findStore, Minecraft.getInstance()).join();
    }

    private static @Nullable CachedAsks findAsks(@Nullable ModStore store) {
        return store == null ? null : new CachedAsks(store.getModel().getCache());
    }

    private static @Nullable ModStore findStoreIfMade() {
        HotwireEngine engine = HotwireClient.findEngine();
        return engine == null ? null : engine.getStore();
    }

    static CompletableFuture<StorePage> search(StoreQuery query) {
        return ask(() -> {
            ModStore store = findStore();
            if (store != null) {
                store.awaitInstalledKnown();
            }
            CachedAsks asks = findAsks(store);
            ModrinthClient.Search found = askBrowsing(query, made -> asks == null
                    ? made.search(toKind(query.kind()), query.words(), query.category(), Math.max(0, query.offset()),
                    PAGE) : asks.search(made, toKind(query.kind()), query.words(), query.category(),
                    Math.max(0, query.offset()), PAGE));
            StoreModel model = store == null ? null : store.getModel();
            found.hits().forEach(hit -> IDS.put(hit.slug(), hit.projectId()));
            return new StorePage(found.hits().stream().map(hit -> new StoreMod(hit.projectId(), hit.slug(),
                    hit.title(), hit.description(), hit.author(), hit.downloads(), hit.iconUrl(), hit.categories(),
                    model != null && (model.isInstalledProject(hit.projectId())
                            || model.isInstalledProject(hit.slug())),
                    model != null && model.getUpdates().containsKey(hit.projectId()))).toList(), found.total());
        });
    }

    static List<StoreCategory> getCategories(StoreKind kind) {
        return StoreCategories.of(toKind(kind)).stream().map(group -> new StoreCategory(group.name(),
                group.parts())).toList();
    }

    static CompletableFuture<List<String>> findGameVersions() {
        return ask(() -> {
            CachedAsks asks = findAsks(findStore());
            return asks == null ? getClient().findGameVersions() : asks.findGameVersions(getClient());
        });
    }

    static CompletableFuture<Optional<StoreProject>> findProject(StoreQuery browsing, String idOrSlug) {
        return ask(() -> {
            CachedAsks asks = findAsks(findStore());
            ModrinthClient.Project project;
            try {
                project = asks == null ? getClient().findProject(idOrSlug) : asks.findProject(getClient(), idOrSlug);
            } catch (IOException e) {
                return Optional.empty();
            }
            List<StoreVersion> versions = askBrowsing(browsing, made -> asks == null
                    ? made.findVersions(toKind(browsing.kind()), project.id())
                    : asks.findVersions(made, toKind(browsing.kind()), project.id())).stream()
                    .map(version -> new StoreVersion(version.id(), version.number(), version.name(), version.size(),
                            version.gameVersions(), version.requiredProjects(), version.changelog())).toList();
            IDS.put(project.slug(), project.id());
            return Optional.of(new StoreProject(project.id(), project.slug(), project.title(), project.description(),
                    project.body(), project.iconUrl(), project.downloads(), project.followers(), project.license(),
                    project.sourceUrl(), versions));
        });
    }

    static List<StoreDownload> getDownloads() {
        return Downloads.snapshotAll().stream().map(job -> new StoreDownload(job.name(), job.done(), job.total(),
                job.isOver(), job.state() == Downloads.State.DONE, job.id(),
                StoreDownload.State.valueOf(job.state().name()), Downloads.findTag(job.id()))).toList();
    }

    static StorePackState findPackState(StoreKind kind, String idOrSlug) {
        ModStore store = findStoreIfMade();
        return store == null || kind == StoreKind.MOD ? StorePackState.MISSING : StorePackState.valueOf(
                store.getModel().getPacks().find(toKind(kind), IDS.getOrDefault(idOrSlug, idOrSlug)).name());
    }

    static CompletableFuture<SwitchResult> togglePack(StoreKind kind, String idOrSlug) {
        CompletableFuture<SwitchResult> ended = new CompletableFuture<>();
        ModStore store = findStoreIfMade();
        if (store == null || kind == StoreKind.MOD) {
            ended.complete(SwitchResult.ofRefusal(store == null ? Reason.NOT_READY : Reason.REFUSED,
                    Lang.get(store == null ? "hotwire.api.starting" : "hotwire.api.pack.unchanged", idOrSlug)));
            return ended;
        }
        String id = IDS.getOrDefault(idOrSlug, idOrSlug);
        Minecraft.getInstance().execute(() -> {
            try {
                StoreModel.Packs packs = store.getModel().getPacks();
                StoreModel.PackState before = packs.find(toKind(kind), id);
                packs.toggle(toKind(kind), id);
                StoreModel.PackState now = packs.find(toKind(kind), id);
                ended.complete(now == before ? SwitchResult.ofRefusal(Reason.REFUSED,
                        Lang.get("hotwire.api.pack.unchanged", idOrSlug)) : new SwitchResult(SwitchResult.Kind.DONE,
                                List.of(), Lang.get(now == StoreModel.PackState.ON ? "hotwire.store.pack.turned_on"
                                        : "hotwire.store.pack.turned_off", idOrSlug)));
            } catch (RuntimeException e) {
                ended.complete(HotwireApiImpl.toFailure(e));
            }
        });
        return ended;
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    static CompletableFuture<SwitchResult> fetchPack(StoreKind kind, String idOrSlug, String versionId, Waits waits) {
        CompletableFuture<SwitchResult> ended = new CompletableFuture<>();
        findProject(new StoreQuery(kind, "", "", 0, "", ""), idOrSlug).whenComplete((found, failure) -> {
            ModStore store = failure == null && found.isPresent() && kind != StoreKind.MOD ? findStore() : null;
            if (store == null) {
                ended.complete(SwitchResult.ofRefusal(Reason.REFUSED, Lang.get("hotwire.api.add.not_found",
                        idOrSlug)));
                return;
            }
            StoreProject project = found.get();
            StoreModel.Packs packs = store.getModel().getPacks();
            Minecraft game = Minecraft.getInstance();
            game.execute(() -> {
                Downloads.markNextQuiet();
                packs.fetch(toKind(kind), project.id(), versionId, project.name());
                waits.awaitTrue(() -> packs.find(toKind(kind), project.id()) != StoreModel.PackState.DOWNLOADING)
                        .thenRun(() -> game.execute(() -> ended.complete(switch (packs.find(toKind(kind),
                                project.id())) {
                            case ON, OFF -> new SwitchResult(SwitchResult.Kind.DONE, List.of(),
                                    Lang.get("hotwire.store.pack.downloaded", project.name()));
                            case NEEDS_WORLD -> SwitchResult.ofRefusal(Reason.REFUSED,
                                    Lang.get("hotwire.store.pack.needs_world"));
                            default -> new SwitchResult(SwitchResult.Kind.FAILED, List.of(),
                                    Lang.get("hotwire.api.pack.not_fetched", project.name()));
                        })));
            });
        });
        return ended;
    }

    static CompletableFuture<SwitchResult> queueForNextStart(String idOrSlug, String versionId) {
        return StoreApiAdd.fetchThen(idOrSlug, versionId, (engine, name, jars) -> {
            List<ModAdder.Result> results = new ArrayList<>();
            jars.forEach(jar -> engine.add(jar, AddMode.NEXT_START, results::add));
            for (ModAdder.Result result : results) {
                if (!(result instanceof ModAdder.Result.Queued)) {
                    return HotwireApiImpl.toResult(result);
                }
            }
            return new SwitchResult(SwitchResult.Kind.DONE, List.of(), Lang.get("hotwire.api.add.next_start", name));
        });
    }

    static Path findDownloads() {
        return FabricLoader.getInstance().getGameDir().resolve("hotwire").resolve("downloads");
    }

    static void openDownloads() {
        Path folder = findDownloads();
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] the downloads folder {} could not be made: {}", folder, e.toString());
            return;
        }
        Minecraft.getInstance().execute(() -> WebLinks.openFolder(folder));
    }

    static CompletableFuture<SwitchResult> saveToDownloads(StoreQuery browsing, String idOrSlug, String versionId) {
        Path folder = findDownloads();
        if (browsing.kind() == StoreKind.MOD && browsing.gameVersion().isEmpty() && browsing.loader().isEmpty()) {
            return StoreApiAdd.fetchThen(idOrSlug, versionId, (engine, name, jars) -> copyInto(folder, name, jars));
        }
        return ask(() -> {
            CachedAsks asks = findAsks(findStore());
            String projectId = (asks == null ? getClient().findProject(idOrSlug)
                    : asks.findProject(getClient(), idOrSlug)).id();
            ModrinthClient.Version version = askBrowsing(browsing, made -> asks == null
                    ? made.findVersions(toKind(browsing.kind()), projectId)
                    : asks.findVersions(made, toKind(browsing.kind()), projectId)).stream()
                    .filter(one -> versionId.isEmpty() || one.id().equals(versionId)).findFirst()
                    .orElseThrow(() -> new IOException(Lang.get("hotwire.store.no_version", idOrSlug)));
            Files.createDirectories(folder);
            Runnable endQuiet = Downloads.beginQuiet();
            try {
                getClient().fetchTo(version, folder.resolve(version.fileName().replaceAll("[^A-Za-z0-9._+ -]", "_")),
                        SAVES);
            } finally {
                endQuiet.run();
            }
            return new SwitchResult(SwitchResult.Kind.DONE, List.of(), Lang.get("hotwire.store.saved", idOrSlug,
                    folder));
        }).exceptionally(failure -> new SwitchResult(SwitchResult.Kind.FAILED, List.of(),
                String.valueOf(failure.getCause() == null ? failure.getMessage() : failure.getCause().getMessage())));
    }

    private static SwitchResult copyInto(Path folder, String name, List<Path> jars) {
        try {
            Files.createDirectories(folder);
            for (Path jar : jars) {
                Files.copy(jar, folder.resolve(jar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
            return new SwitchResult(SwitchResult.Kind.DONE, List.of(), Lang.get("hotwire.store.saved", name, folder));
        } catch (IOException e) {
            return new SwitchResult(SwitchResult.Kind.FAILED, List.of(), Lang.get("hotwire.store.download_failed",
                    name, e.getMessage()));
        }
    }
}
