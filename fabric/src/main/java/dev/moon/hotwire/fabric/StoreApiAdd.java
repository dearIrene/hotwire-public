package dev.moon.hotwire.fabric;

import dev.moon.hotwire.api.StoreAdd;
import dev.moon.hotwire.api.StoreKind;
import dev.moon.hotwire.api.StoreProject;
import dev.moon.hotwire.api.StoreQuery;
import dev.moon.hotwire.api.SwitchResult;
import dev.moon.hotwire.fabric.add.ModAdder;
import dev.moon.hotwire.fabric.store.ModStore;
import dev.moon.hotwire.ui.AddMode;
import dev.moon.hotwire.ui.AddPreview;
import dev.moon.hotwire.ui.Lang;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

final class StoreApiAdd {
    interface Then<T> {
        T run(HotwireEngine engine, String name, List<Path> jars);
    }

    private interface Taker {
        void take(HotwireEngine engine, String name, List<Path> jars);
    }

    private StoreApiAdd() {
    }

    static CompletableFuture<StoreAdd> request(String idOrSlug, String versionId) {
        CompletableFuture<StoreAdd> made = new CompletableFuture<>();
        fetch(idOrSlug, versionId, made, (engine, name, jars) -> {
            List<CompletableFuture<AddPreview>> runs = jars.stream().map(engine::dryRunAdd).toList();
            completeWhenAll(runs, made, () -> new Add(engine, name, jars, runs.stream().map(CompletableFuture::join)
                    .toList()));
        }, why -> new Add(null, idOrSlug, List.of(), List.of(AddPreview.ofRefusal(idOrSlug, why))));
        return made;
    }

    static CompletableFuture<SwitchResult> fetchThen(String idOrSlug, String versionId, Then<SwitchResult> then) {
        CompletableFuture<SwitchResult> made = new CompletableFuture<>();
        fetch(idOrSlug, versionId, made, (engine, name, jars) -> {
            made.complete(then.run(engine, name, jars));
        }, why -> new SwitchResult(SwitchResult.Kind.REFUSED, List.of(), why));
        return made;
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private static <T> void completeWhenAll(List<CompletableFuture<AddPreview>> runs, CompletableFuture<T> made,
            Supplier<T> done) {
        CompletableFuture.allOf(runs.toArray(CompletableFuture[]::new)).whenComplete((all, failure) -> {
            if (failure == null) {
                made.complete(done.get());
            } else {
                made.completeExceptionally(failure);
            }
        });
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private static <T> void fetch(String idOrSlug, String versionId, CompletableFuture<T> made, Taker then,
            Function<String, T> refusal) {
        HotwireEngine engine = HotwireClient.findEngine();
        String why = engine == null ? Lang.get("hotwire.api.starting") : !engine.canAdd() ? engine.getSwitchRefusal()
                : !engine.isFirstOpenAccepted() ? Lang.get("hotwire.api.add.no_prompt") : null;
        if (why != null || engine == null) {
            made.complete(refusal.apply(why == null ? "" : why));
            return;
        }
        StoreApi.findProject(new StoreQuery(StoreKind.MOD, "", "", 0, "", ""), idOrSlug)
                .whenComplete((found, failure) -> {
                    if (failure != null || found == null || found.isEmpty()) {
                        made.complete(refusal.apply(Lang.get("hotwire.api.add.not_found", idOrSlug)));
                        return;
                    }
                    StoreProject project = found.get();
                    Minecraft.getInstance().execute(() -> {
                        ModStore store = engine.findStore();
                        if (!store.getModel().getClient().isBrowsingOwn()) {
                            made.complete(refusal.apply(Lang.get("hotwire.api.add.browsing")));
                            return;
                        }
                        store.fetch(project.id(), versionId, project.name(),
                                (jars, projects) -> then.take(engine, project.name(), jars),
                                failed -> made.complete(refusal.apply(failed)));
                    });
                });
    }

    static final class Add implements StoreAdd {
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
