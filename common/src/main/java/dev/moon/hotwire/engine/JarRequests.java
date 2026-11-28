package dev.moon.hotwire.engine;

import dev.moon.hotwire.add.ModAdder;
import dev.moon.hotwire.concurrent.AheadWork;
import dev.moon.hotwire.concurrent.Tasks;
import dev.moon.hotwire.jar.JarLookup;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.seam.Games;
import dev.moon.hotwire.seam.SharedChange;
import dev.moon.hotwire.switching.Progress;
import dev.moon.hotwire.ui.AddMode;
import dev.moon.hotwire.ui.JarHost;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.panels.ReopenPanel;
import dev.moon.hotwire.update.ModUpdater;
import dev.moon.hotwire.update.UpdateMethod;
import dev.moon.hotwire.update.UpdateSupport;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class JarRequests {
    private static final Logger LOGGER = LoggerFactory.getLogger(JarRequests.class);

    private final HotwireEngine engine;
    private final ModChanges changes;
    private final Progress progress;
    private final Tasks tasks;
    private final StoreJars storeJars;

    JarRequests(HotwireEngine engine, ModChanges changes, Progress progress, Tasks tasks, StoreJars storeJars) {
        this.engine = engine;
        this.changes = changes;
        this.progress = progress;
        this.tasks = tasks;
        this.storeJars = storeJars;
    }

    void pick(String ask, boolean update) {
        this.engine.showMessage(ask, false);
        this.engine.units.chooseJar(jar -> {
            JarHost host = this.engine.findHost();
            if (host != null) {
                host.onJarPicked(jar, update);
            }
        }, () -> this.engine.showMessage(Lang.get("hotwire.screen.no_picker"), true));
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    void add(Path jar, AddMode mode, boolean byPlayer, BiConsumer<ModAdder.Result, String> then) {
        AheadWork.onSwitch();
        if (mode == AddMode.NEXT_START && this.engine.isInModsFolder(jar)) {
            String name = String.valueOf(jar.getFileName());

            ModManifest inFolder = JarLookup.findManifest(jar);
            ModAdder.Result queued = new ModAdder.Result.Queued(inFolder == null ? name : inFolder.id(),
                    Lang.get("hotwire.folder.next_start", name));
            this.engine.showMessage(ModAdder.describe(queued), false);
            then.accept(queued, ModAdder.describe(queued));
            return;
        }
        if (mode == AddMode.NEXT_START) {
            this.tasks.submit("queue add", () -> {
                try {
                    return this.changes.queueAddAlone(jar);
                } catch (RuntimeException | LinkageError e) {
                    LOGGER.warn("[Hotwire] the queueing of {} threw", jar, e);
                    return new ModAdder.Result.Failed(Lang.get("hotwire.failed", e), e);
                }
            }, queued -> {
                boolean ok = queued instanceof ModAdder.Result.Queued;
                this.engine.showMessage(ModAdder.describe(queued), !ok);
                if (ok) {
                    this.storeJars.markQueued(jar, this.engine.getPendingChanges());
                }
                then.accept(queued, ModAdder.describe(queued));
                this.engine.refreshScreen();
            });
            return;
        }
        this.progress.beginAdding(String.valueOf(jar.getFileName()).replaceFirst("(?i)\\.jar$", ""));
        this.tasks.submit("add", () -> {
            try {
                return mode == AddMode.SAVE_AND_REOPEN ? this.changes.addWithReopen(jar, byPlayer)
                        : this.changes.addNow(jar);
            } catch (RuntimeException | LinkageError e) {
                LOGGER.warn("[Hotwire] the add of {} threw", jar, e);
                return new ModAdder.Result.Failed(Lang.get("hotwire.add.start_failed",
                        String.valueOf(jar.getFileName()), e.toString()), e);
            } finally {
                this.progress.endAdding();
            }
        }, result -> {
            boolean more = this.storeJars.hasQueued();

            boolean later = byPlayer && more && ModAdder.isRestartOnly(result) && this.engine.findHost() != null;
            if (later) {
                this.storeJars.queueLater(jar);
            }
            if (mode == AddMode.SAVE_AND_REOPEN) {
                this.storeJars.endReopenWait(false);

                if (this.engine.findHost() != null && !Games.getNow().isHotwireShown()
                        && (!(result instanceof ModAdder.Result.Added) || this.storeJars.hasQueued())) {
                    this.engine.showScreen();
                }
            }
            boolean reopens = mode == AddMode.NOW && ModAdder.isWorldBuilt(result)
                    && Games.getNow().findReopenRefusal().isEmpty() && this.engine.findHost() != null;
            if (reopens && more) {
                this.storeJars.waitForReopen();
            }

            String copied = result instanceof ModAdder.Result.Added ? this.changes.copyIntoMods(jar) : "";
            boolean joins = result instanceof ModAdder.Result.Added && Games.getNow().getWorld() != null
                    && this.changes.setsUpOnJoin(jar);
            String atJoin = !joins ? "" : " " + Lang.get(Games.getNow().hasOwnServer()
                    ? "hotwire.add.finishes_at_join" : "hotwire.add.rejoin_for_all");
            then.accept(result, switch (result) {
                case ModAdder.Result.Added added -> Lang.get("hotwire.add.is_in", added.name()) + copied + atJoin;
                case ModAdder.Result.Queued queued -> queued.message();
                case ModAdder.Result.Refused refused -> refused.reason();
                case ModAdder.Result.Failed failed -> failed.reason();
            });
            if (result instanceof ModAdder.Result.Added added) {
                String project = this.storeJars.findProjectOf(jar);
                this.storeJars.markInstalled(jar);
                this.engine.shareWithOthers(SharedChange.Kind.ADD, added.ids().subList(0, 1),
                        project == null ? "" : project);
            }
            JarHost current = this.engine.findHost();
            if (reopens) {
                LOGGER.info("[Hotwire] {}", ((ModAdder.Result.Failed) result).reason());
                this.engine.findReopenHost().offerReopen(new ReopenPanel.Offer(false, List.of(), jar,
                        String.valueOf(jar.getFileName()), Lang.get("hotwire.add.world_open")));
            } else if (ModAdder.isRestartOnly(result) && current != null) {
                LOGGER.info("[Hotwire] {}", result instanceof ModAdder.Result.Refused refused ? refused.reason()
                        : ((ModAdder.Result.Failed) result).reason());
                if (!later) {
                    this.storeJars.offerLater(current, jar);
                }
            } else if (result instanceof ModAdder.Result.Added added && current != null
                    && Games.getNow().getWorld() != null
                    && (this.changes.setsUpOnJoin(jar) || (!more && this.storeJars.takeReopenWanted()))) {
                boolean own = Games.getNow().hasOwnServer();
                this.engine.showMessage(Lang.get("hotwire.add.is_in", added.name()) + copied
                        + (own ? "" : " " + Lang.get("hotwire.add.rejoin_for_all")), false);
                if (own && more) {
                    this.storeJars.markReopenWanted();
                } else if (own) {
                    this.engine.findReopenHost().offerReopen(new ReopenPanel.Offer(false, List.of(), null,
                            added.name(), Lang.get("hotwire.add.finishes_at_join")));
                }
            } else {
                this.engine.showMessage(switch (result) {
                    case ModAdder.Result.Added added -> Lang.get("hotwire.add.is_in", added.name()) + copied;
                    case ModAdder.Result.Queued queued -> queued.message();
                    case ModAdder.Result.Refused refused -> refused.reason();
                    case ModAdder.Result.Failed failed -> failed.reason();
                }, !(result instanceof ModAdder.Result.Added));
                if (result instanceof ModAdder.Result.Failed failed && FailureReports.isUnplanned(failed.cause())) {
                    FailureReports.report(this.engine, Lang.get("hotwire.report.file.add_failed"), failed.reason(),
                            false);
                }
            }

            if (mode == AddMode.SAVE_AND_REOPEN && this.storeJars.hasQueued() && this.engine.findHost() == null) {
                this.engine.open();
            }
            this.engine.refreshScreen();
        });
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    void addTogether(List<Path> jars, Runnable otherwise, Runnable then) {
        AheadWork.onSwitch();
        this.progress.beginAdding(Lang.get("hotwire.add.many", jars.size()));
        this.tasks.submit("add", () -> {
            try {
                return this.changes.addTogetherNow(jars);
            } catch (RuntimeException | LinkageError e) {
                LOGGER.warn("[Hotwire] the add of {} threw", jars, e);
                return new ModAdder.Result.Failed(Lang.get("hotwire.add.start_failed", jars.size(), e.toString()),
                        e);
            } finally {
                this.progress.endAdding();
            }
        }, result -> {
            if (!(result instanceof ModAdder.Result.Added added)) {
                LOGGER.info("[Hotwire] {} jars do not go in as one ({}): they are offered one by one", jars.size(),
                        result == null ? "not asked" : ModAdder.describe(result));
                otherwise.run();
                return;
            }
            boolean joins = false;
            Set<String> copies = new LinkedHashSet<>();
            for (Path jar : jars) {
                String project = this.storeJars.findProjectOf(jar);
                ModManifest manifest = JarLookup.findManifest(jar);
                this.storeJars.markInstalled(jar);
                if (manifest != null) {
                    this.engine.shareWithOthers(SharedChange.Kind.ADD, List.of(manifest.id()),
                            project == null ? "" : project);
                }
                joins |= this.changes.setsUpOnJoin(jar);
                copies.add(this.changes.copyIntoMods(jar));
            }
            boolean own = Games.getNow().hasOwnServer();
            boolean again = joins && Games.getNow().getWorld() != null && this.engine.findHost() != null;
            this.engine.showMessage(Lang.get("hotwire.add.many_in", jars.size()) + String.join("", copies)
                    + (again && !own ? " " + Lang.get("hotwire.add.rejoin_for_all") : ""), false);
            if (again && own) {
                this.engine.findReopenHost().offerReopen(new ReopenPanel.Offer(false, List.of(), null,
                        added.name(), Lang.get("hotwire.add.finishes_at_join")));
            }
            then.run();
            this.engine.refreshScreen();
        });
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    void update(Path jar, UpdateMethod method) {
        JvmParts parts = this.engine.findParts();
        if (parts == null) {
            ModAdder.Result queued = this.changes.queueAddAlone(jar);
            boolean ok = queued instanceof ModAdder.Result.Queued;
            this.engine.showMessage(ModAdder.describe(queued), !ok);
            if (ok) {
                this.storeJars.markInstalled(jar);
            }
            this.engine.refreshScreen();
            return;
        }
        this.tasks.submit("update", () -> parts.updates().update(jar, method), result -> {
            this.engine.showMessage(UpdateSupport.describe(result), !(result instanceof ModUpdater.Result.Updated
                    || result instanceof ModUpdater.Result.Queued));
            this.storeJars.dropOffer(result);
            if (result instanceof ModUpdater.Result.Updated || result instanceof ModUpdater.Result.Queued) {
                this.storeJars.markInstalled(jar);
            }
            this.engine.refreshScreen();
            this.storeJars.offerNext();
        });
    }
}
