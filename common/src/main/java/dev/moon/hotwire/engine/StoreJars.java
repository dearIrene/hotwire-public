package dev.moon.hotwire.engine;

import dev.moon.hotwire.add.ModAdder;
import dev.moon.hotwire.jar.JarLookup;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.modrinth.Downloads;
import dev.moon.hotwire.seam.Games;
import dev.moon.hotwire.store.ModStore;
import dev.moon.hotwire.store.UpdateCheck;
import dev.moon.hotwire.switching.Progress;
import dev.moon.hotwire.ui.AddPreview;
import dev.moon.hotwire.ui.JarHost;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.ModScreen;
import dev.moon.hotwire.update.ModUpdater;
import dev.moon.hotwire.update.PendingChange;
import dev.moon.hotwire.update.UpdateOffer;
import dev.moon.hotwire.update.UpdatePlan;
import dev.moon.hotwire.update.UpdatePlanner;
import dev.moon.hotwire.update.UpdatePreview;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class StoreJars {
    private static final Logger LOGGER = LoggerFactory.getLogger(StoreJars.class);

    private record StoreJar(Path jar, boolean update) {
    }

    private final InstalledMods installed;
    private final ModChanges changes;
    private final UpdateCheck check;

    private static final long RECHECK_SECONDS = 60;

    private long lastStarted;
    private final Supplier<@Nullable JarHost> host;
    private final Supplier<@Nullable ModScreen> screen;
    private final Supplier<@Nullable ModStore> store;
    private final BiConsumer<String, Boolean> say;
    private final Deque<StoreJar> queued = new ArrayDeque<>();

    private final Map<Path, String> projects = new HashMap<>();

    private boolean reopenWanted;

    private boolean reopenWait;

    private final Set<Path> later = new HashSet<>();

    private final Set<Path> unasked = new HashSet<>();

    private Runnable drained = () -> { };

    private BooleanSupplier moreComing = () -> false;

    private final Set<String> needsMet = new HashSet<>();
    private List<UpdateOffer> offers = List.of();
    private boolean checked;
    private boolean started;

    private boolean again;
    private final StoreUpdates all;
    private final Runnable refresh;

    public StoreJars(InstalledMods installed, ModChanges changes, Supplier<@Nullable JarHost> host,
            Supplier<@Nullable ModScreen> screen, Supplier<@Nullable ModStore> store, BiConsumer<String, Boolean> say,
            Progress progress, Runnable refresh) {
        this.installed = installed;
        this.changes = changes;
        this.check = new UpdateCheck(installed);
        this.host = host;
        this.screen = screen;
        this.store = store;
        this.say = say;
        this.refresh = refresh;
        this.all = new StoreUpdates(this.check, changes, progress, say, (first, now, later, not) -> {
            ModScreen shown = screen.get();
            if (shown != null) {
                shown.showUpdateSummary(first, now, later, not);
            }
        });
    }

    public StoreUpdates getAll() {
        return this.all;
    }

    public UpdateCheck getCheck() {
        return this.check;
    }

    public List<UpdateOffer> getOffers() {
        return this.offers;
    }

    public boolean isChecked() {
        return this.checked;
    }

    public boolean checkAgain() {
        long limited = this.check.findWaitSeconds();
        if (limited > 0) {
            this.say.accept(Lang.get("hotwire.updates.rate_limited", limited), true);
            return false;
        }
        long since = (System.nanoTime() - this.lastStarted) / 1_000_000_000L;
        if (this.lastStarted != 0 && since < RECHECK_SECONDS) {
            this.say.accept(Lang.get("hotwire.updates.wait", RECHECK_SECONDS - since), false);
            return false;
        }
        this.started = false;
        this.checked = false;
        ModScreen current = this.screen.get();
        if (current != null) {
            current.setUpdates(this.offers, false);
        }
        this.startCheck();
        return true;
    }

    public void startCheck() {
        if (this.started) {
            return;
        }
        this.started = true;
        this.lastStarted = System.nanoTime();
        this.check.start(found -> Games.getNow().runOnGame(() -> {
            this.offers = found;
            this.checked = true;
            if (this.again) {
                this.again = false;
                this.started = false;
                this.startCheck();
            }
            ModScreen current = this.screen.get();
            if (current != null) {
                current.setUpdates(found, true);
            }

            int fresh = this.check.takeNewlyFound();
            if (fresh > 0) {
                this.say.accept(fresh == 1 ? Lang.get("hotwire.updates.found_one")
                        : Lang.get("hotwire.updates.found", fresh), false);
            }
        }));
    }

    public void updateAll(List<UpdateOffer> asked) {
        if (asked.size() > 1) {
            this.reportDownloads();
            ModScreen shown = this.screen.get();
            if (shown != null) {
                shown.startUpdateRun(asked);
            }
            this.all.run(this.check.orderByNeeds(asked, this.offers), needing -> {
                this.queued.clear();
                needing.forEach(jar -> this.queued.add(new StoreJar(jar, this.isUpdate(jar))));
                this.offerNext();
            }, (jar, result) -> {
                this.dropOffer(result);
                this.noteLoaderNeed(jar, result);
                if (result instanceof ModUpdater.Result.Updated || result instanceof ModUpdater.Result.Queued) {
                    this.markInstalled(jar);
                }
                this.refresh.run();
            }, () -> {
                ModScreen now = this.screen.get();
                if (now != null) {
                    now.endUpdateRun();
                }
            });
            return;
        }
        this.updateOne(asked);
    }

    private boolean isUpdate(Path jar) {
        ModManifest manifest = JarLookup.findManifest(jar);
        return manifest != null && (this.installed.isInstalled(manifest.id())
                || this.installed.findOfferedFor(jar) != null);
    }

    private void updateOne(List<UpdateOffer> asked) {
        if (asked.isEmpty()) {
            return;
        }
        this.say.accept(Lang.get("hotwire.updates.getting", asked.getFirst().name(), asked.getFirst().newVersion()),
                false);
        this.reportDownloads();
        Thread.ofPlatform().name("hotwire-updates-fetch").daemon().start(() -> {
            List<StoreJar> jars = new ArrayList<>();
            String failure = null;
            for (UpdateOffer offer : this.check.orderByNeeds(asked, this.offers)) {
                try {
                    List<Path> fetched = this.check.fetchWithNeeds(offer, said -> Games.getNow()
                            .runOnGame(() -> this.say.accept(said, false)));
                    for (int i = 0; i < fetched.size(); i++) {
                        jars.add(new StoreJar(fetched.get(i), i == fetched.size() - 1));
                    }
                } catch (IOException e) {
                    failure = Lang.get("hotwire.store.download_failed", offer.name(), e.getMessage());
                    break;
                }
            }
            String why = failure;
            Games.getNow().runOnGame(() -> {
                if (why != null) {
                    this.say.accept(why, true);
                }

                boolean batch = !this.queued.isEmpty();
                this.queued.addAll(jars);
                if (!batch) {
                    this.offerNext();
                }
            });
        });
    }

    public boolean updateById(String id) {
        for (UpdateOffer offer : this.offers) {
            if (offer.id().equals(id)) {
                this.updateAll(List.of(offer));
                return true;
            }
        }
        return false;
    }

    public void setDrained(Runnable drained, BooleanSupplier moreComing) {
        this.drained = drained;
        this.moreComing = moreComing;
    }

    public boolean isUnasked(Path jar) {
        return this.unasked.contains(jar);
    }

    public void queue(List<Path> jars, boolean update, List<String> projects, boolean unasked) {
        this.queued.clear();
        if (unasked) {
            this.unasked.addAll(jars);
        }

        this.reopenWanted = this.reopenWanted && unasked;
        this.reopenWait = false;
        for (int i = 0; i < jars.size(); i++) {
            this.queued.add(new StoreJar(jars.get(i), update && i == jars.size() - 1));
            this.projects.put(jars.get(i), projects.get(i));
        }
        this.offerNext();
    }

    public void remember(List<Path> jars, List<String> projects) {
        for (int i = 0; i < jars.size(); i++) {
            this.projects.put(jars.get(i), projects.get(i));
        }
    }

    public List<Downloads.Job> findDownloads() {
        return this.check.getDownloads().snapshot();
    }

    private void reportDownloads() {
        ModStore made = this.store.get();
        if (made != null) {
            this.check.reportTo(made.getModel().getDownloads());
        }
    }

    public List<ModAdder.Result> queueForNextStart(List<Path> jars, List<String> projects,
            List<PendingChange> pending) {
        List<ModAdder.Result> results = new ArrayList<>();
        for (int i = 0; i < jars.size(); i++) {
            this.projects.put(jars.get(i), projects.get(i));
            results.add(this.changes.queueAdd(jars.get(i)));
        }

        Map<String, String> dropped = this.changes.checkQueue();
        for (int i = 0; i < jars.size(); i++) {
            ModAdder.Result queued = results.get(i);
            if (queued instanceof ModAdder.Result.Queued one && dropped.containsKey(one.id())) {
                queued = new ModAdder.Result.Refused(dropped.get(one.id()));
                results.set(i, queued);
            }
            boolean ok = queued instanceof ModAdder.Result.Queued;
            this.say.accept(ModAdder.describe(queued), !ok);
            if (ok) {
                this.markQueued(jars.get(i), pending);
            }
        }
        return results;
    }

    public boolean hasQueued() {
        return !this.queued.isEmpty() || this.moreComing.getAsBoolean();
    }

    public void waitForReopen() {
        this.reopenWait = true;
    }

    public void endReopenWait(boolean offer) {
        boolean waited = this.reopenWait;
        this.reopenWait = false;
        if (waited && offer) {
            this.offerNext();
        }
    }

    public void markReopenWanted() {
        this.reopenWanted = true;
    }

    public boolean takeReopenWanted() {
        boolean wanted = this.reopenWanted;
        this.reopenWanted = false;
        return wanted;
    }

    public void offerLater(JarHost current, Path jar) {
        List<String> why = List.of(Lang.get("hotwire.add.start_only"));
        current.offerAdd(jar, this.changes.dryRunAdd(jar).thenApply(preview -> AddPreview.ofLater(preview.name(),
                preview.version(), why)));
    }

    public void queueLater(Path jar) {
        this.later.add(jar);
        this.queued.addFirst(new StoreJar(jar, false));
    }

    public void clear() {
        this.reopenWait = false;
        this.queued.clear();
        this.later.clear();
        this.unasked.clear();
        this.reopenWanted = false;
        this.projects.clear();
        this.needsMet.clear();
    }

    private void noteLoaderNeed(Path jar, ModUpdater.Result result) {
        String id = this.installed.findOfferedFor(jar);
        if (id == null || !(result instanceof ModUpdater.Result.Refused refused) || refused.loader() == null) {
            return;
        }
        List<UpdateOffer> now = new ArrayList<>();
        for (UpdateOffer offer : this.offers) {
            if (offer.id().equals(id)) {
                this.check.noteLoaderNeed(offer.versionId(), refused.loader());
                now.add(offer.withLoaderNeeded(refused.loader()));
            } else {
                now.add(offer);
            }
        }
        this.offers = List.copyOf(now);
        ModScreen current = this.screen.get();
        if (current != null) {
            current.setUpdates(this.offers, true);
        }
    }

    public void dropOffer(ModUpdater.Result result) {
        String id = switch (result) {
            case ModUpdater.Result.Updated updated -> updated.id();
            case ModUpdater.Result.Queued queuedUpdate -> queuedUpdate.id();
            default -> null;
        };
        if (id == null) {
            return;
        }
        this.offers = this.offers.stream().filter(offer -> !offer.id().equals(id)).toList();
        ModScreen current = this.screen.get();
        if (current != null) {
            current.setUpdates(this.offers, true);
        }
    }

    public void noteUpdated(ModUpdater.Result result) {
        String id = switch (result) {
            case ModUpdater.Result.Updated updated -> updated.id();
            case ModUpdater.Result.Queued queuedUpdate -> queuedUpdate.id();
            default -> null;
        };
        this.dropOffer(result);
        ModStore made = this.store.get();
        this.check.getIdByProject().forEach((project, mod) -> {
            if (mod.equals(id)) {
                this.check.noteInstalled(project);
                if (made != null) {
                    made.getModel().markInstalled(project);
                }
            }
        });
    }

    public void markQueued(Path jar, List<PendingChange> pending) {
        String project = this.projects.remove(jar);
        ModStore made = this.store.get();
        if (project != null && made != null) {
            made.getModel().markQueued(project, String.valueOf(jar.getFileName()));
        }
        this.dropUnqueued(pending);
    }

    public void dropUnqueued(List<PendingChange> pending) {
        ModStore made = this.store.get();
        if (made != null) {
            made.getModel().dropUnqueued(pending.stream().filter(PendingChange.Update.class::isInstance)
                    .map(change -> ((PendingChange.Update) change).newJar()).collect(Collectors.toSet()));
        }
    }

    public @Nullable String findProjectOf(Path jar) {
        return this.projects.get(jar);
    }

    public void markInstalled(Path jar) {
        String project = this.projects.remove(jar);
        ModStore made = this.store.get();
        if (project != null && made != null) {
            made.getModel().markInstalled(project);
        }

        if (project != null) {
            this.check.noteInstalled(project);
        } else if (made != null) {
            made.checkInstalled();
        }
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    public void offerNext() {
        JarHost current = this.host.get();
        if (this.reopenWait) {
            return;
        }
        StoreJar next = current == null ? null : this.queued.poll();
        if (next == null || current == null) {
            if (current != null) {
                this.drained.run();
            }
            return;
        }
        if (this.later.remove(next.jar())) {
            this.offerLater(current, next.jar());
            return;
        }

        if (!next.update() && !this.queued.isEmpty() && this.changes.isInstalledJar(next.jar())) {
            this.offerNext();
            return;
        }

        if (!next.update() && this.hasNeedsToMeet(next)) {
            return;
        }
        if (!next.update() || this.queued.isEmpty()) {
            current.onJarPicked(next.jar(), next.update());
            return;
        }
        this.changes.dryRunUpdate(next.jar()).whenComplete((preview, failed) -> Games.getNow()
                .runOnGame(() -> {
                    if (failed == null && preview instanceof UpdatePreview.Refused && this.hasNeedsToMeet(next)) {
                        return;
                    }
                    String skip = failed != null ? failed.toString() : switch (preview) {
                        case UpdatePreview.Ready ready -> null;
                        case UpdatePreview.Refused refused -> refused.name() + ": " + refused.reason();
                        case UpdatePreview.SameVersion same -> Lang.get("hotwire.update.same_version", same.name(),
                                same.version());
                        case UpdatePreview.Unreadable unreadable -> Lang.get("hotwire.update.unreadable");
                    };
                    if (skip == null) {
                        JarHost shown = this.host.get();
                        if (shown != null) {
                            shown.onJarPicked(next.jar(), true);
                        } else {
                            this.queued.addFirst(next);
                        }
                    } else {
                        this.say.accept(skip, true);
                        this.offerNext();
                    }
                }));
    }

    private Set<String> findOlderNeeds(Path jar) {
        ModManifest manifest = JarLookup.findManifest(jar);
        Set<String> older = new LinkedHashSet<>();
        if (manifest == null) {
            return older;
        }
        manifest.getRelation(ModManifest.Relation.DEPENDS).forEach((id, ranges) -> {
            String have = this.installed.getGraph().resolve(id);
            String version = this.installed.find(have).map(ModManifest::version).orElse(null);
            if (version != null && !ranges.isEmpty() && !ModPolicy.satisfies(version, ranges)) {
                older.add(have);
            }
        });
        return older;
    }

    private boolean hasNeedsToMeet(StoreJar asked) {
        Set<String> unmet;
        if (asked.update()) {
            UpdatePlan plan = this.changes.planUpdate(asked.jar());
            if (plan == null) {
                return false;
            }
            unmet = UpdatePlanner.findUnmet(plan).keySet();
        } else {
            unmet = this.findOlderNeeds(asked.jar());
        }
        List<String> needs = new ArrayList<>();
        for (String id : unmet) {
            if (this.needsMet.add(id)) {
                needs.add(id);
            }
        }
        List<Path> carried = new ArrayList<>();
        for (Path library : asked.update() ? this.changes.findCarriedToAdd(asked.jar()) : List.<Path>of()) {
            if (this.needsMet.add("carried:" + library.getFileName())) {
                carried.add(library);
            }
        }
        if (needs.isEmpty() && carried.isEmpty()) {
            return false;
        }
        List<UpdateOffer> known = this.offers;
        this.reportDownloads();
        Thread.ofPlatform().name("hotwire-updates-needs").daemon().start(() -> {
            List<StoreJar> front = new ArrayList<>();
            carried.forEach(library -> front.add(new StoreJar(library, false)));
            for (String id : needs) {
                try {
                    List<Path> jars = this.check.fetchById(id, known, said -> Games.getNow()
                            .runOnGame(() -> this.say.accept(said, false)));
                    for (int i = 0; i < jars.size(); i++) {
                        boolean update = i == jars.size() - 1 && this.installed.isInstalled(id);
                        front.add(new StoreJar(jars.get(i), update));
                    }
                    if (jars.isEmpty()) {
                        LOGGER.info("[Hotwire] {} needs {}, which the update check found no newer version of",
                                asked.jar().getFileName(), id);
                    }
                } catch (IOException e) {
                    LOGGER.info("[Hotwire] {} could not be fetched for {}: {}", id, asked.jar().getFileName(),
                            e.toString());
                }
            }
            Games.getNow().runOnGame(() -> {
                this.queued.addFirst(asked);
                for (int i = front.size() - 1; i >= 0; i--) {
                    this.queued.addFirst(front.get(i));
                }
                if (!front.isEmpty()) {
                    this.say.accept(Lang.get("hotwire.updates.needs_first", String.valueOf(asked.jar().getFileName()),
                            front.size()), false);
                }
                this.offerNext();
            });
        });
        return true;
    }
}
