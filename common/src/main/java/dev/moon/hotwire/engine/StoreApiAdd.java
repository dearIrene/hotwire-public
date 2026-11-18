package dev.moon.hotwire.engine;

import dev.moon.hotwire.api.Reason;
import dev.moon.hotwire.api.StoreKind;
import dev.moon.hotwire.api.StoreProject;
import dev.moon.hotwire.api.StoreQuery;
import dev.moon.hotwire.api.SwitchResult;
import dev.moon.hotwire.modrinth.Downloads;
import dev.moon.hotwire.seam.Games;
import dev.moon.hotwire.store.ModStore;
import dev.moon.hotwire.ui.Lang;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

final class StoreApiAdd {
    interface Then<T> {
        T run(HotwireEngine engine, String name, List<Path> jars);
    }

    private interface Taker {
        void take(HotwireEngine engine, StoreProject project, List<Path> jars);
    }

    private StoreApiAdd() {
    }

    record ProjectJars(List<Path> jars, String why, String name, String slug, long downloads) {
    }

    static CompletableFuture<ProjectJars> fetchJars(String idOrSlug, String versionId) {
        CompletableFuture<ProjectJars> made = new CompletableFuture<>();
        fetch(idOrSlug, versionId, made, (engine, project, jars) -> made.complete(new ProjectJars(List.copyOf(jars),
                "", project.name(), project.slug(), project.downloads())),
                why -> new ProjectJars(List.of(), why, "", "", 0));
        return made;
    }

    static CompletableFuture<SwitchResult> fetchThen(String idOrSlug, String versionId, Then<SwitchResult> then) {
        CompletableFuture<SwitchResult> made = new CompletableFuture<>();
        fetch(idOrSlug, versionId, made, (engine, project, jars) -> {
            made.complete(then.run(engine, project.name(), jars));
        }, why -> SwitchResult.ofRefusal(Reason.REFUSED, why));
        return made;
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private static <T> void fetch(String idOrSlug, String versionId, CompletableFuture<T> made, Taker then,
            Function<String, T> refusal) {
        HotwireEngine engine = EngineApi.findEngine();
        String why = engine == null ? Lang.get("hotwire.api.starting") : !engine.canAdd() ? engine.getSwitchRefusal()
                : !engine.isFirstOpenAccepted() ? Lang.get("hotwire.api.add.no_prompt") : null;
        if (why != null || engine == null) {
            made.complete(refusal.apply(why == null ? "" : why));
            return;
        }
        Runnable endQuiet = Downloads.beginQuiet();
        made.whenComplete((done, failed) -> endQuiet.run());
        StoreApi.findProject(new StoreQuery(StoreKind.MOD, "", "", 0, "", ""), idOrSlug)
                .whenComplete((found, failure) -> {
                    if (failure != null || found == null || found.isEmpty()) {
                        made.complete(refusal.apply(Lang.get("hotwire.api.add.not_found", idOrSlug)));
                        return;
                    }
                    StoreProject project = found.get();
                    Games.getNow().runOnGame(() -> {
                        ModStore store = engine.findStore();
                        if (!store.getModel().getClient().isBrowsingOwn()) {
                            made.complete(refusal.apply(Lang.get("hotwire.api.add.browsing")));
                            return;
                        }
                        store.fetch(project.id(), versionId, project.name(),
                                (jars, projects) -> {
                                    engine.storeJars.remember(jars, projects);
                                    then.take(engine, project, jars);
                                },
                                failed -> made.complete(refusal.apply(failed)));
                    });
                });
    }
}
