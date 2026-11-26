package dev.moon.hotwire.engine;

import dev.moon.hotwire.add.ModAdder;
import dev.moon.hotwire.api.SwitchResult;
import dev.moon.hotwire.classfile.GameNames;
import dev.moon.hotwire.modrinth.Downloads;
import dev.moon.hotwire.modrinth.ModrinthClient;
import dev.moon.hotwire.seam.Games;
import dev.moon.hotwire.store.SelfUpdate;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.ModScreen;
import dev.moon.hotwire.update.PendingChange;
import dev.moon.hotwire.update.UrgentUpdate;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

final class SelfUpdateRequests {
    private final SelfUpdate check = new SelfUpdate();
    private final StoreJars jars;
    private final Supplier<@Nullable ModScreen> screen;
    private final BiConsumer<String, Boolean> say;
    private final EngineGame units;

    private boolean toldInChat;

    private @Nullable List<PendingChange> waiting;

    SelfUpdateRequests(StoreJars jars, Supplier<@Nullable ModScreen> screen, BiConsumer<String, Boolean> say,
            EngineGame units) {
        this.units = units;
        this.jars = jars;
        this.screen = screen;
        this.say = say;
    }

    void start() {
        this.check.start(found -> Games.getNow().runOnGame(() -> {
            ModScreen current = this.screen.get();
            if (current != null) {
                current.showNewerHotwire();
            }
            this.showInChat();
            List<PendingChange> asked = this.waiting;
            if (asked != null) {
                this.waiting = null;
                this.updateAtNextStart(asked, null);
            }
        }), urgent -> Games.getNow().runOnGame(() -> this.units.tellUrgent(urgent)));
    }

    void checkAgain() {
        this.check.allowAgain();
        this.start();
    }

    void showInChat() {
        UrgentUpdate urgent = this.units.findUrgent();
        String newer = this.findNewer();
        if (this.toldInChat || (newer.isEmpty() && urgent == null)
                || !this.units.showUpdateInChat(urgent != null ? urgent.version() : newer, urgent != null)) {
            this.start();
            return;
        }
        this.toldInChat = true;
    }

    String findNewer() {
        ModrinthClient.Version newer = this.check.getNewer();
        return newer == null ? "" : newer.number();
    }

    String findChanges() {
        return this.check.getChanges();
    }

    void updateAtNextStart(List<PendingChange> pending, @Nullable CompletableFuture<SwitchResult> done) {
        ModrinthClient.Version newer = this.check.getNewer();
        if (newer == null) {
            if (done != null) {
                Thread.ofPlatform().name("hotwire-self-update").daemon().start(() -> {
                    boolean found = this.check.findNow() != null;
                    Games.getNow().runOnGame(() -> {
                        if (found) {
                            this.updateAtNextStart(pending, done);
                        } else {
                            done.complete(new SwitchResult(SwitchResult.Kind.REFUSED, List.of(),
                                    Lang.get("hotwire.api.self_update.none")));
                        }
                    });
                });
                return;
            }
            this.waiting = pending;
            this.start();
            return;
        }
        this.say.accept(Lang.get("hotwire.updates.getting", "Hotwire", newer.number()), false);
        Thread.ofPlatform().name("hotwire-self-update-fetch").daemon().start(() -> {
            try {
                Path jar = this.check.fetch(newer);
                Games.getNow().runOnGame(() -> {
                    ModAdder.Result queued = this.jars.queueForNextStart(List.of(jar), List.of(newer.projectId()),
                            pending).getFirst();
                    ModScreen current = this.screen.get();
                    if (current != null && queued instanceof ModAdder.Result.Queued) {
                        current.showHotwireUpdateReady(newer.number());
                    }
                    if (done != null) {
                        done.complete(queued instanceof ModAdder.Result.Queued
                                ? new SwitchResult(SwitchResult.Kind.DONE, List.of(), ModAdder.describe(queued),
                                        List.of(), List.of(GameNames.HOTWIRE))
                                : EngineApi.toResult(queued));
                    }
                });
            } catch (IOException | RuntimeException e) {
                if (!(e instanceof Downloads.DownloadStop)) {
                    Games.getNow().runOnGame(() -> this.say.accept(
                            Lang.get("hotwire.store.download_failed", "Hotwire", e.getMessage()), true));
                }
                if (done != null) {
                    done.complete(new SwitchResult(SwitchResult.Kind.FAILED, List.of(),
                            Lang.get("hotwire.store.download_failed", "Hotwire", e.getMessage())));
                }
            }
        });
    }
}
