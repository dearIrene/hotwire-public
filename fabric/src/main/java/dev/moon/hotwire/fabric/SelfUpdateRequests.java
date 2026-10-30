package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.store.SelfUpdate;
import dev.moon.hotwire.modrinth.ModrinthClient;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.ModScreen;
import dev.moon.hotwire.update.PendingChange;
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
        }));
    }

    void showInChat() {
        if (this.toldInChat || this.findNewer().isEmpty() || Minecraft.getInstance().player == null) {
            this.start();
            return;
        }
        this.toldInChat = true;
        UpdateChat.show();
    }

    String findNewer() {
        ModrinthClient.Version newer = this.check.getNewer();
        return newer == null ? "" : newer.number();
    }

    void updateAtNextStart(List<PendingChange> pending) {
        ModrinthClient.Version newer = this.check.getNewer();
        if (newer == null) {
            return;
        }
        this.say.accept(Lang.get("hotwire.updates.getting", "Hotwire", newer.number()), false);
        Thread.ofPlatform().name("hotwire-self-update-fetch").daemon().start(() -> {
            try {
                Path jar = this.check.fetch(newer);
                Minecraft.getInstance().execute(() -> this.jars.queueForNextStart(List.of(jar),
                        List.of(SelfUpdate.PROJECT), pending));
            } catch (IOException e) {
                Minecraft.getInstance().execute(() -> this.say.accept(
                        Lang.get("hotwire.store.download_failed", "Hotwire", e.getMessage()), true));
            }
        });
    }
}
