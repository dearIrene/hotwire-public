package dev.moon.hotwire.fabric;

import dev.moon.hotwire.modrinth.Downloads;
import dev.moon.hotwire.store.UpdateCheck;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.Progress;
import dev.moon.hotwire.update.EarlyUpdates;
import dev.moon.hotwire.update.ModUpdater;
import dev.moon.hotwire.update.UpdateOffer;
import dev.moon.hotwire.update.UpdateSupport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class StoreUpdates {
    private static final Logger LOGGER = LoggerFactory.getLogger(StoreUpdates.class);

    private static final int FETCHES = 8;

    private static final double WORTH_SECONDS = 10;
    private static final long QUIET_NANOS = 3_000_000_000L;
    private static final long POLL_MILLIS = 200;
    private static final double NEARLY_IN = 0.5;

    private static final int REFUSALS_SAID = 5;

    record Ran(List<ModUpdater.Result> results, long fetchMillis, long updateMillis) {
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
    private final Summary summary;
    private volatile CompletableFuture<Ran> last = CompletableFuture.completedFuture(
            new Ran(List.of(), 0, 0));

    private volatile boolean early;

    private record Fetches(ExecutorService pool, List<Future<List<Path>>> futures) {
    }

    interface Summary {
        void show(List<String> first, List<String> now, List<String> later, List<String> not);
    }

    StoreUpdates(UpdateCheck check, ModChanges changes, Progress progress, BiConsumer<String, Boolean> say,
            Summary summary) {
        this.summary = summary;
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
        if (!this.last.isDone()) {
            return;
        }
        long began = System.nanoTime();
        this.say.accept(Lang.get("hotwire.updates.getting_all", offers.size()), false);
        this.progress.beginUpdating(Lang.get("hotwire.updates.all_subject", offers.size()));
        this.progress.count(Lang.get("hotwire.progress.update.getting"), 0, offers.size());
        CompletableFuture<Ran> ran = new CompletableFuture<>();
        this.last = ran;
        Thread.ofPlatform().name("hotwire-updates-fetch").daemon().start(() -> {
            Fetches fetches = this.beginFetches(offers, this::fetch);
            List<Integer> apart = this.awaitApart(offers, fetches.futures(), began);
            List<Path> firstJars = apart.stream().map(at -> fetches.futures().get(at).resultNow().getFirst()).toList();
            List<ModUpdater.Result> firstMade = List.of();
            if (!firstJars.isEmpty()) {
                LOGGER.info("[Hotwire] Update all takes {} of {} updates now, {} ms in: the downloads still coming have"
                        + " nothing to do with them", firstJars.size(), offers.size(),
                        (System.nanoTime() - began) / 1_000_000);
                this.early = true;
                this.progress.stepUpdating(1);
                try {
                    firstMade = this.changes.updateAll(firstJars).join();
                } catch (CompletionException e) {
                    LOGGER.warn("[Hotwire] Update all's first updates failed as a set", e.getCause());
                    apart = List.of();
                    firstJars = List.of();
                } finally {
                    this.early = false;
                }
                this.progress.count(Lang.get("hotwire.progress.update.getting"),
                        (int) fetches.futures().stream().filter(Future::isDone).count(), offers.size());

                List<Path> in = firstJars;
                List<ModUpdater.Result> inMade = firstMade;
                long now = inMade.stream().filter(ModUpdater.Result.Updated.class::isInstance).count();
                Minecraft.getInstance().execute(() -> {
                    if (now > 0) {
                        this.say.accept(Lang.get("hotwire.updates.first_in", now, offers.size()), false);
                    }
                    for (int i = 0; i < inMade.size(); i++) {
                        done.accept(in.get(i), inMade.get(i));
                    }
                });
            }
            List<List<Path>> fetched = collect(fetches);
            long fetchMillis = (System.nanoTime() - began) / 1_000_000;
            List<Path> jars = new ArrayList<>();
            List<Path> needed = new ArrayList<>();
            for (int i = 0; i < fetched.size(); i++) {
                List<Path> one = fetched.get(i);
                if (one.size() == 1 && !apart.contains(i)) {
                    jars.add(one.getFirst());
                } else if (one.size() > 1) {
                    needed.addAll(one);
                }
            }
            List<Path> first = firstJars;
            List<ModUpdater.Result> firstResults = firstMade;
            LOGGER.info("[Hotwire] Update all fetched {} of {} jars in {} ms; {} come with mods they need", jars.size()
                    + needed.size(), offers.size(), fetchMillis, needed.size());
            Minecraft.getInstance().execute(landed);
            this.progress.stepUpdating(1);
            long applying = System.nanoTime();
            this.changes.updateAll(jars).whenComplete((results, failed) -> Minecraft.getInstance().execute(() -> {
                long updateMillis = (System.nanoTime() - applying) / 1_000_000;
                this.progress.endAdding();
                List<ModUpdater.Result> made = new ArrayList<>(firstResults);
                made.addAll(results == null ? List.of() : results);
                List<Path> all = new ArrayList<>(first);
                all.addAll(jars);
                if (failed != null) {
                    this.say.accept(String.valueOf(failed), true);
                }
                this.tell(made);
                for (int i = firstResults.size(); i < made.size(); i++) {
                    done.accept(all.get(i), made.get(i));
                }
                LOGGER.info("[Hotwire] Update all: fetched in {} ms, updated in {} ms", fetchMillis, updateMillis);
                ran.complete(new Ran(made, fetchMillis, updateMillis));
                if (!needed.isEmpty()) {
                    needing.accept(needed);
                }
            }));
        });
    }

    private List<Integer> awaitApart(List<UpdateOffer> offers, List<Future<List<Path>>> futures, long began) {
        int seen = 0;
        long lastLanding = began;
        try {
            while (true) {
                Set<Integer> landed = new HashSet<>();
                int over = 0;
                long got = 0;
                for (int i = 0; i < futures.size(); i++) {
                    Future<List<Path>> future = futures.get(i);
                    if (future.isDone()) {
                        over++;
                        got += offers.get(i).size();
                        if (future.state() == Future.State.SUCCESS && future.resultNow().size() == 1) {
                            landed.add(i);
                        }
                    }
                }
                if (over == futures.size()) {
                    return List.of();
                }
                long now = System.nanoTime();
                if (over != seen) {
                    seen = over;
                    lastLanding = now;
                }
                if (!landed.isEmpty() && now - lastLanding >= QUIET_NANOS) {
                    long left = 0;
                    boolean known = true;
                    for (Downloads.Job job : Downloads.findAllUnderWay()) {
                        known &= job.total() > 0 && job.getFraction() < NEARLY_IN;
                        left += job.total() - job.done();
                        got += job.done();
                    }
                    if (known && EarlyUpdates.estimateSecondsLeft(got, left, now - began) >= WORTH_SECONDS) {
                        return EarlyUpdates.findApart(offers.size(), landed,
                                (one, other) -> this.check.isRelated(offers.get(one), offers.get(other)));
                    }
                }
                Thread.sleep(POLL_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    List<List<Path>> fetchAll(List<UpdateOffer> offers, Function<UpdateOffer, List<Path>> fetch) {
        return collect(this.beginFetches(offers, fetch));
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private Fetches beginFetches(List<UpdateOffer> offers, Function<UpdateOffer, List<Path>> fetch) {
        ExecutorService pool = Executors.newFixedThreadPool(FETCHES,
                Thread.ofPlatform().name("hotwire-updates-fetch-", 1).daemon().factory());
        List<Future<List<Path>>> futures = new ArrayList<>();
        AtomicInteger landed = new AtomicInteger();
        for (UpdateOffer offer : offers) {
            futures.add(pool.submit(() -> {
                List<Path> jars = fetch.apply(offer);
                int in = landed.incrementAndGet();
                if (!this.early) {
                    this.progress.count(Lang.get("hotwire.progress.update.getting"), in, offers.size());
                }
                if (jars.size() == 1) {
                    this.changes.planAhead(jars.getFirst());
                }
                return jars;
            }));
        }
        return new Fetches(pool, futures);
    }

    private static List<List<Path>> collect(Fetches fetches) {
        ExecutorService pool = fetches.pool();
        List<List<Path>> out = new ArrayList<>();
        try {
            for (Future<List<Path>> future : fetches.futures()) {
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
        List<String> now = new ArrayList<>();
        List<String> later = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        List<String> loaders = new ArrayList<>();
        for (ModUpdater.Result result : results) {
            switch (result) {
                case ModUpdater.Result.Updated updated -> now.add(updated.name() + " "
                        + updated.plan().incomingVersion());
                case ModUpdater.Result.Queued queued -> later.add(queued.name() + " "
                        + queued.plan().incomingVersion());

                case ModUpdater.Result.Refused byLoader when byLoader.loader() != null -> {
                    refused.add(loaders.size(), byLoader.reason());
                    loaders.add(byLoader.loader());
                }
                default -> refused.add(UpdateSupport.describe(result));
            }
        }

        List<String> first = loaders.isEmpty() ? List.of() : List.of(Lang.get("hotwire.updates.loader_first",
                loaders.size(), String.join(" / ", new TreeSet<>(loaders)), FabricLoader.getInstance()
                        .getModContainer("fabricloader").map(loader -> loader.getMetadata().getVersion()
                                .getFriendlyString()).orElse("an older one")));
        first.forEach(line -> this.say.accept(line, true));
        this.say.accept(Lang.get("hotwire.updates.all_done", now.size(), later.size(), refused.size()),
                !refused.isEmpty());
        if (!results.isEmpty()) {
            this.summary.show(first, now, later, refused);
        }
        refused.subList(0, Math.min(REFUSALS_SAID, refused.size())).forEach(why -> this.say.accept(why, true));
        for (String why : refused) {
            LOGGER.info("[Hotwire] Update all: not updated: {}", why);
        }
    }
}
