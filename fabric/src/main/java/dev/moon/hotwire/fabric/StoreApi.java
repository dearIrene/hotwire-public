package dev.moon.hotwire.fabric;

import dev.moon.hotwire.api.StoreAdd;
import dev.moon.hotwire.api.StoreMod;
import dev.moon.hotwire.api.StoreProject;
import dev.moon.hotwire.api.StoreVersion;
import dev.moon.hotwire.api.SwitchResult;
import dev.moon.hotwire.fabric.add.ModAdder;
import dev.moon.hotwire.fabric.store.ModStore;
import dev.moon.hotwire.modrinth.ModrinthClient;
import dev.moon.hotwire.ui.AddMode;
import dev.moon.hotwire.ui.AddPreview;
import dev.moon.hotwire.ui.Lang;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class StoreApi {
    private static final Logger LOGGER = LoggerFactory.getLogger(StoreApi.class);
    private static final int PAGE = 20;

    private static final Executor ASK = run -> Thread.ofVirtual().name("hotwire-api-store").start(run);
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

    static CompletableFuture<List<StoreMod>> search(String words) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return getClient().search(ModrinthClient.Kind.MOD, words, "", 0, PAGE).hits().stream()
                        .map(hit -> new StoreMod(hit.projectId(), hit.slug(), hit.title(), hit.description(),
                                hit.author(), hit.downloads(), hit.iconUrl())).toList();
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, ASK);
    }

    static CompletableFuture<Optional<StoreProject>> findProject(String idOrSlug) {
        return CompletableFuture.supplyAsync(() -> {
            ModrinthClient.Project project;
            try {
                project = getClient().findProject(idOrSlug);
            } catch (IOException e) {
                return Optional.empty();
            }
            try {
                List<StoreVersion> versions = getClient().findVersions(ModrinthClient.Kind.MOD, project.id()).stream()
                        .map(version -> new StoreVersion(version.id(), version.number(), version.name(),
                                version.size(), version.requiredProjects())).toList();
                return Optional.of(new StoreProject(project.id(), project.slug(), project.title(),
                        project.description(), project.body(), project.iconUrl(), project.downloads(), versions));
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, ASK);
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    static CompletableFuture<StoreAdd> requestAdd(String idOrSlug, @Nullable String asker) {
        HotwireEngine engine = HotwireClient.findEngine();
        if (engine == null || !engine.canAdd()) {
            String why = engine == null ? Lang.get("hotwire.api.starting") : engine.getSwitchRefusal();
            return CompletableFuture.completedFuture(makeRefusal(idOrSlug, why));
        }
        if (!engine.isFirstOpenAccepted()) {
            return CompletableFuture.completedFuture(makeRefusal(idOrSlug, Lang.get("hotwire.api.add.no_prompt")));
        }
        LOGGER.info("[Hotwire] {} asked to add {} from Modrinth", asker == null ? "a mod" : asker, idOrSlug);
        CompletableFuture<StoreAdd> made = new CompletableFuture<>();
        findProject(idOrSlug).whenComplete((found, failure) -> {
            if (failure != null || found == null || found.isEmpty()) {
                made.complete(makeRefusal(idOrSlug, Lang.get("hotwire.api.add.not_found", idOrSlug)));
                return;
            }
            StoreProject project = found.get();
            Minecraft.getInstance().execute(() -> engine.findStore().fetch(project.id(), "", project.name(),
                    (jars, projects) -> dryRun(engine, project.name(), jars, made),
                    why -> made.complete(makeRefusal(project.name(), why))));
        });
        return made;
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private static void dryRun(HotwireEngine engine, String name, List<Path> jars, CompletableFuture<StoreAdd> made) {
        List<CompletableFuture<AddPreview>> runs = jars.stream().map(engine::dryRunAdd).toList();
        CompletableFuture.allOf(runs.toArray(CompletableFuture[]::new)).whenComplete((done, failure) -> {
            if (failure != null) {
                made.complete(makeRefusal(name, failure.toString()));
                return;
            }
            made.complete(new Add(engine, name, jars, runs.stream().map(CompletableFuture::join).toList()));
        });
    }

    private static StoreAdd makeRefusal(String name, String why) {
        return new Add(null, name, List.of(), List.of(AddPreview.ofRefusal(name, why)));
    }

    private static final class Add implements StoreAdd {
        private final @Nullable HotwireEngine engine;
        private final String name;
        private final List<Path> jars;
        private final List<AddPreview> previews;
        private final AtomicBoolean answered = new AtomicBoolean();

        Add(@Nullable HotwireEngine engine, String name, List<Path> jars, List<AddPreview> previews) {
            this.engine = engine;
            this.name = name;
            this.jars = List.copyOf(jars);
            this.previews = List.copyOf(previews);
        }

        @Override
        public String getTitle() {
            return Lang.get("hotwire.api.add.title", this.name);
        }

        @Override
        public List<String> getLines() {
            List<String> lines = new ArrayList<>();
            for (int at = 0; at < this.previews.size() - 1; at++) {
                lines.add(Lang.get("hotwire.api.add.with", this.previews.get(at).name()));
            }
            for (int at = 0; at < this.previews.size(); at++) {
                AddPreview preview = this.previews.get(at);
                if (preview.mode() == AddMode.REFUSED || at == this.previews.size() - 1) {
                    lines.addAll(preview.lines());
                }
            }
            long size = 0;
            for (Path jar : this.jars) {
                try {
                    size += Files.size(jar);
                } catch (IOException e) {
                }
            }
            if (size > 0) {
                lines.add(Lang.get("hotwire.api.add.size", String.format(Locale.ROOT, "%.1f MB",
                        size / (1024.0 * 1024.0))));
            }
            return List.copyOf(lines);
        }

        @Override
        public boolean needsReopen() {
            return this.previews.stream().anyMatch(preview -> preview.mode() == AddMode.SAVE_AND_REOPEN);
        }

        @Override
        public boolean canAdd() {
            return this.engine != null && !this.jars.isEmpty()
                    && this.previews.stream().noneMatch(preview -> preview.mode() == AddMode.REFUSED);
        }

        @Override
        public CompletableFuture<SwitchResult> accept() {
            CompletableFuture<SwitchResult> ended = new CompletableFuture<>();
            HotwireEngine at = this.engine;
            if (at == null || !this.canAdd() || !this.answered.compareAndSet(false, true)) {
                ended.complete(new SwitchResult(SwitchResult.Kind.REFUSED, List.of(),
                        String.join(" ", this.getLines())));
                return ended;
            }
            Minecraft.getInstance().execute(() -> this.addFrom(at, 0, new ArrayList<>(), ended));
            return ended;
        }

        private void addFrom(HotwireEngine at, int index, List<String> added, CompletableFuture<SwitchResult> ended) {
            if (index == this.jars.size()) {
                ended.complete(new SwitchResult(SwitchResult.Kind.DONE, List.copyOf(added),
                        Lang.get("hotwire.api.add.done", this.name)));
                return;
            }
            AddPreview preview = this.previews.get(index);
            if (preview.mode() == AddMode.NEXT_START) {
                at.add(this.jars.get(index), AddMode.NEXT_START, result -> { });
                this.addFrom(at, index + 1, added, ended);
                return;
            }
            at.add(this.jars.get(index), preview.mode(), result -> {
                switch (result) {
                    case ModAdder.Result.Added one -> {
                        added.addAll(one.ids());
                        this.addFrom(at, index + 1, added, ended);
                    }
                    case ModAdder.Result.Refused refused -> ended.complete(new SwitchResult(
                            SwitchResult.Kind.REFUSED, List.copyOf(added), refused.reason()));
                    case ModAdder.Result.Failed failed -> ended.complete(new SwitchResult(
                            ModAdder.isWorldBuilt(failed) ? SwitchResult.Kind.NEEDS_REOPEN : SwitchResult.Kind.FAILED,
                            List.copyOf(added), failed.reason()));
                }
            });
        }

        @Override
        public void cancel() {
            this.answered.set(true);
        }
    }
}
