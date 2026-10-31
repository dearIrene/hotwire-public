package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.store.SelfUpdate;
import dev.moon.hotwire.modrinth.ModrinthClient;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.ModScreen;
import dev.moon.hotwire.update.PendingChange;
import dev.moon.hotwire.update.UrgentUpdate;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

final class SelfUpdateRequests {
    private final SelfUpdate check = new SelfUpdate();
    private final StoreJars jars;
    private final Supplier<@Nullable ModScreen> screen;
    private final BiConsumer<String, Boolean> say;

    private boolean toldInChat;

    private @Nullable List<PendingChange> waiting;

    SelfUpdateRequests(StoreJars jars, Supplier<@Nullable ModScreen> screen, BiConsumer<String, Boolean> say) {
        this.jars = jars;
        this.screen = screen;
        this.say = say;
    }

    void start() {
        this.check.start(found -> Minecraft.getInstance().execute(() -> {
            ModScreen current = this.screen.get();
            if (current != null) {
                current.showNewerHotwire();
            }
            this.showInChat();
            List<PendingChange> asked = this.waiting;
            if (asked != null) {
                this.waiting = null;
                this.updateAtNextStart(asked);
            }
        }), urgent -> Minecraft.getInstance().execute(() -> UrgentNotice.onChecked(urgent)));
    }

    void recheck() {
        this.check.allowAgain();
        this.start();
    }

    void showInChat() {
        UrgentUpdate urgent = UrgentNotice.find();
        String newer = this.findNewer();
        if (this.toldInChat || (newer.isEmpty() && urgent == null) || Minecraft.getInstance().player == null) {
            this.start();
            return;
        }
        this.toldInChat = true;
        if (urgent != null) {
            UpdateChat.showUrgent(urgent.version());
        } else {
            UpdateChat.show(newer);
        }
    }

    String findNewer() {
        ModrinthClient.Version newer = this.check.getNewer();
        return newer == null ? "" : newer.number();
    }

    void updateAtNextStart(List<PendingChange> pending) {
        ModrinthClient.Version newer = this.check.getNewer();
        if (newer == null) {
            this.waiting = pending;
            this.start();
            return;
        }
        this.say.accept(Lang.get("hotwire.updates.getting", "Hotwire", newer.number()), false);
        Thread.ofPlatform().name("hotwire-self-update-fetch").daemon().start(() -> {
            try {
                Path jar = this.check.fetch(newer);
                Minecraft.getInstance().execute(() -> {
                    this.jars.queueForNextStart(List.of(jar), List.of(SelfUpdate.PROJECT), pending);
                    ModScreen current = this.screen.get();
                    if (current != null) {
                        current.showHotwireUpdateReady(newer.number());
                    }
                });
            } catch (IOException e) {
                Minecraft.getInstance().execute(() -> this.say.accept(
                        Lang.get("hotwire.store.download_failed", "Hotwire", e.getMessage()), true));
            }
        });
    }
}
