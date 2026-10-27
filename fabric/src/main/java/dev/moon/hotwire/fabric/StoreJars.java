package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.store.ModStore;
import dev.moon.hotwire.fabric.store.UpdateCheck;
import dev.moon.hotwire.fabric.update.ModUpdater;
import dev.moon.hotwire.ui.JarHost;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.ModScreen;
import dev.moon.hotwire.ui.UpdatePreview;
import dev.moon.hotwire.update.UpdateOffer;
import dev.moon.hotwire.update.UpdatePlan;
import dev.moon.hotwire.update.UpdatePlanner;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class StoreJars {
    private static final Logger LOGGER = LoggerFactory.getLogger(StoreJars.class);

    private record StoreJar(Path jar, boolean update) {
    }

    private final InstalledMods installed;
    private final ModChanges changes;
    private final UpdateCheck check;
    private final Supplier<@Nullable JarHost> host;
    private final Supplier<@Nullable ModScreen> screen;
    private final Supplier<@Nullable ModStore> store;
    private final BiConsumer<String, Boolean> say;
    private final Deque<StoreJar> queued = new ArrayDeque<>();

    private final Map<Path, String> projects = new HashMap<>();

    private final Set<String> needsMet = new HashSet<>();
    private List<UpdateOffer> offers = List.of();
    private boolean checked;
    private boolean started;

    StoreJars(InstalledMods installed, ModChanges changes, Supplier<@Nullable JarHost> host,
            Supplier<@Nullable ModScreen> screen, Supplier<@Nullable ModStore> store, BiConsumer<String, Boolean> say) {
        this.installed = installed;
        this.changes = changes;
        this.check = new UpdateCheck(installed);
        this.host = host;
        this.screen = screen;
        this.store = store;
        this.say = say;
    }

    UpdateCheck getCheck() {
        return this.check;
    }

    List<UpdateOffer> getOffers() {
        return this.offers;
    }

    boolean isChecked() {
        return this.checked;
    }

    void startCheck() {
        if (this.started) {
            return;
        }
        this.started = true;
        this.check.start(found -> Minecraft.getInstance().execute(() -> {
            this.offers = found;
            this.checked = true;
            ModScreen current = this.screen.get();
            if (current != null) {
                current.setUpdates(found, true);
            }
            if (!found.isEmpty()) {
                this.say.accept(found.size() == 1 ? Lang.get("hotwire.updates.found_one")
                        : Lang.get("hotwire.updates.found", found.size()), false);
            }
        }));
    }

    void updateAll(List<UpdateOffer> asked) {
        if (asked.isEmpty()) {
            return;
        }
        this.say.accept(Lang.get("hotwire.updates.getting", asked.getFirst().name(), asked.getFirst().newVersion()),
                false);
        Thread.ofPlatform().name("hotwire-updates-fetch").daemon().start(() -> {
            List<StoreJar> jars = new ArrayList<>();
            String failure = null;
            for (UpdateOffer offer : this.check.orderByNeeds(asked, this.offers)) {
                try {
                    List<Path> fetched = this.check.fetchWithNeeds(offer, said -> Minecraft.getInstance()
                            .execute(() -> this.say.accept(said, false)));
                    for (int i = 0; i < fetched.size(); i++) {
                        jars.add(new StoreJar(fetched.get(i), i == fetched.size() - 1));
                    }
                } catch (IOException e) {
                    failure = Lang.get("hotwire.store.download_failed", offer.name(), e.getMessage());
                    break;
                }
            }
            String why = failure;
            Minecraft.getInstance().execute(() -> {
                if (why != null) {
                    this.say.accept(why, true);
                }
                this.queued.clear();
                this.queued.addAll(jars);
                this.offerNext();
            });
        });
    }

    boolean updateById(String id) {
        for (UpdateOffer offer : this.offers) {
            if (offer.id().equals(id)) {
                this.updateAll(List.of(offer));
                return true;
            }
        }
        return false;
    }

    void queue(List<Path> jars, boolean update, String project) {
        this.queued.clear();
        for (int i = 0; i < jars.size(); i++) {
            this.queued.add(new StoreJar(jars.get(i), update && i == jars.size() - 1));
        }
        this.projects.put(jars.getLast(), project);
        this.offerNext();
    }

    void clear() {
        this.queued.clear();
        this.projects.clear();
        this.needsMet.clear();
    }

    void dropOffer(ModUpdater.Result result) {
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

    void markInstalled(Path jar) {
        String project = this.projects.remove(jar);
        ModStore made = this.store.get();
        if (project != null && made != null) {
            made.getModel().markInstalled(project);
        }
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    void offerNext() {
        StoreJar next = this.queued.poll();
        JarHost current = this.host.get();
        if (next == null || current == null) {
            return;
        }
        if (!next.update() || this.queued.isEmpty()) {
            current.onJarPicked(next.jar(), next.update());
            return;
        }
        this.changes.dryRunUpdate(next.jar()).whenComplete((preview, failed) -> Minecraft.getInstance()
                .execute(() -> {
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
                        }
                    } else {
                        this.say.accept(skip, true);
                        this.offerNext();
                    }
                }));
    }

    private boolean hasNeedsToMeet(StoreJar asked) {
        UpdatePlan plan = this.changes.planUpdate(asked.jar());
        if (plan == null) {
            return false;
        }
        List<String> needs = new ArrayList<>();
        for (String id : UpdatePlanner.findUnmet(plan).keySet()) {
            if (this.needsMet.add(id)) {
                needs.add(id);
            }
        }
        List<Path> carried = new ArrayList<>();
        for (Path library : this.changes.findCarriedToAdd(asked.jar())) {
            if (this.needsMet.add("carried:" + library.getFileName())) {
                carried.add(library);
            }
        }
        if (needs.isEmpty() && carried.isEmpty()) {
            return false;
        }
        List<UpdateOffer> known = this.offers;
        Thread.ofPlatform().name("hotwire-updates-needs").daemon().start(() -> {
            List<StoreJar> front = new ArrayList<>();
            carried.forEach(library -> front.add(new StoreJar(library, false)));
            for (String id : needs) {
                try {
                    List<Path> jars = this.check.fetchById(id, known, said -> Minecraft.getInstance()
                            .execute(() -> this.say.accept(said, false)));
                    for (int i = 0; i < jars.size(); i++) {
                        boolean update = i == jars.size() - 1 && this.installed.isInstalled(id);
                        front.add(new StoreJar(jars.get(i), update));
                    }
                    if (jars.isEmpty()) {
                        LOGGER.info("[Hotwire] {} needs {}, which Modrinth does not have for this game", asked.jar()
                                .getFileName(), id);
                    }
                } catch (IOException e) {
                    LOGGER.info("[Hotwire] {} could not be fetched for {}: {}", id, asked.jar().getFileName(),
                            e.toString());
                }
            }
            Minecraft.getInstance().execute(() -> {
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
