package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.store.UpdateCheck;
import dev.moon.hotwire.fabric.update.ModUpdater;
import dev.moon.hotwire.fabric.update.UpdateSupport;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.Progress;
import dev.moon.hotwire.update.UpdateOffer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class StoreUpdates {
    private static final Logger LOGGER = LoggerFactory.getLogger(StoreUpdates.class);

    private static final int FETCHES = 8;

    private static final int REFUSALS_SAID = 5;

    record Ran(List<ModUpdater.Result> results, List<Path> updated, long fetchMillis, long updateMillis) {
        String describe() {
            long now = this.results.stream().filter(ModUpdater.Result.Updated.class::isInstance).count();
            long later = this.results.stream().filter(ModUpdater.Result.Queued.class::isInstance).count();
            return this.results.size() + " jars: " + now + " in place, " + later + " next start, "
                    + (this.results.size() - now - later) + " not; fetched in " + this.fetchMillis + " ms, updated in "
                    + this.updateMillis + " ms";
        }
    }

    private final UpdateCheck check;
    private final ModChanges changes;
    private final Progress progress;
    private final BiConsumer<String, Boolean> say;
    private volatile CompletableFuture<Ran> last = CompletableFuture.completedFuture(
            new Ran(List.of(), List.of(), 0, 0));

    StoreUpdates(UpdateCheck check, ModChanges changes, Progress progress, BiConsumer<String, Boolean> say) {
        this.check = check;
        this.changes = changes;
        this.progress = progress;
        this.say = say;
    }

    CompletableFuture<Ran> getLast() {
        return this.last;
    }

    @SuppressWarnings("FutureReturnValueIgnored")

    void run(List<UpdateOffer> offers, Consumer<List<Path>> needing, BiConsumer<Path, ModUpdater.Result> done,
            Runnable landed) {
        long began = System.nanoTime();
        this.say.accept(Lang.get("hotwire.updates.getting_all", offers.size()), false);
        CompletableFuture<Ran> ran = new CompletableFuture<>();
        this.last = ran;
        Thread.ofPlatform().name("hotwire-updates-fetch").daemon().start(() -> {
            List<List<Path>> fetched = this.fetchAll(offers, this::fetch);
            long fetchMillis = (System.nanoTime() - began) / 1_000_000;
            List<Path> jars = new ArrayList<>();
            List<Path> needed = new ArrayList<>();
            for (List<Path> one : fetched) {
                if (one.size() == 1) {
                    jars.add(one.getFirst());
                } else if (one.size() > 1) {
                    needed.addAll(one);
                }
            }
            LOGGER.info("[Hotwire] Update all fetched {} of {} jars in {} ms; {} come with mods they need", jars.size()
                    + needed.size(), offers.size(), fetchMillis, needed.size());
            Minecraft.getInstance().execute(() -> {
                landed.run();
                this.progress.begin(Lang.get("hotwire.updates.all_subject", jars.size()), false, jars.size());
                this.progress.count(Lang.get("hotwire.updates.applying_all", jars.size()), 0, 0);
            });
            long applying = System.nanoTime();
            this.changes.updateAll(jars).whenComplete((results, failed) -> Minecraft.getInstance().execute(() -> {
                long updateMillis = (System.nanoTime() - applying) / 1_000_000;
                this.progress.end();
                List<ModUpdater.Result> made = results == null ? List.of() : results;
                if (failed != null) {
                    this.say.accept(String.valueOf(failed), true);
                }
                this.tell(made);
                for (int i = 0; i < made.size(); i++) {
                    done.accept(jars.get(i), made.get(i));
                }
                LOGGER.info("[Hotwire] Update all: fetched in {} ms, updated in {} ms", fetchMillis, updateMillis);
                ran.complete(new Ran(made, jars, fetchMillis, updateMillis));
                if (!needed.isEmpty()) {
                    needing.accept(needed);
                }
            }));
        });
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    List<List<Path>> fetchAll(List<UpdateOffer> offers, Function<UpdateOffer, List<Path>> fetch) {
        ExecutorService pool = Executors.newFixedThreadPool(FETCHES,
                Thread.ofPlatform().name("hotwire-updates-fetch-", 1).daemon().factory());
        List<Future<List<Path>>> futures = new ArrayList<>();
        for (UpdateOffer offer : offers) {
            futures.add(pool.submit(() -> {
                List<Path> jars = fetch.apply(offer);
                if (jars.size() == 1) {
                    this.changes.planAhead(jars.getFirst());
                }
                return jars;
            }));
        }
        List<List<Path>> out = new ArrayList<>();
        try {
            for (Future<List<Path>> future : futures) {
                try {
                    out.add(future.get());
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof UncheckedIOException failed) {
                        throw failed;
                    }
                    LOGGER.warn("[Hotwire] an update's fetch failed", e.getCause());
                    out.add(List.of());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdown();
        }
        return out;
    }

    private List<Path> fetch(UpdateOffer offer) {
        try {
            return this.check.fetchWithNeeds(offer, said -> LOGGER.info("[Hotwire] {}", said));
        } catch (IOException e) {
            Minecraft.getInstance().execute(() -> this.say.accept(Lang.get("hotwire.store.download_failed",
                    offer.name(), e.getMessage() == null ? e.toString() : e.getMessage()), true));
            return List.of();
        }
    }

    private void tell(List<ModUpdater.Result> results) {
        int now = 0;
        int later = 0;
        List<String> refused = new ArrayList<>();
        for (ModUpdater.Result result : results) {
            switch (result) {
                case ModUpdater.Result.Updated updated -> now++;
                case ModUpdater.Result.Queued queued -> later++;
                default -> refused.add(UpdateSupport.describe(result));
            }
        }
        this.say.accept(Lang.get("hotwire.updates.all_done", now, later, refused.size()), !refused.isEmpty());
        refused.subList(0, Math.min(REFUSALS_SAID, refused.size())).forEach(why -> this.say.accept(why, true));
        for (String why : refused) {
            LOGGER.info("[Hotwire] Update all: not updated: {}", why);
        }
    }
}
