package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.store.SelfUpdate;
import dev.moon.hotwire.update.UrgentUpdate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class UrgentNotice {
    private static final Logger LOGGER = LoggerFactory.getLogger(UrgentNotice.class);
    private static final String NOTE = "urgent-update.txt";
    private static volatile Optional<UrgentUpdate> due = Optional.empty();

    private static volatile boolean shown;
    private static volatile boolean read;

    private UrgentNotice() {
    }

    private static synchronized void readOnce() {
        if (read) {
            return;
        }
        read = true;
        Path note = findNote();
        try {
            if (!Files.isRegularFile(note)) {
                return;
            }
            List<String> lines = Files.readAllLines(note);
            if (lines.isEmpty() || !SelfUpdate.isNewer(lines.getFirst(), LoadedMods.findVersion("hotwire", "0"))) {
                Files.delete(note);
                return;
            }
            due = Optional.of(new UrgentUpdate(lines.getFirst(), lines.size() > 1 ? lines.get(1) : ""));
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] the urgent update note could not be read", e);
        }
    }

    static void onChecked(Optional<UrgentUpdate> found) {
        Path note = findNote();
        try {
            if (found.isEmpty()) {
                Files.deleteIfExists(note);
            } else {
                Files.createDirectories(note.getParent());
                Files.writeString(note, found.get().version() + "\n" + found.get().message().replace('\n', ' '));
                LOGGER.info("[Hotwire] Hotwire {} is marked urgent on Modrinth: {}", found.get().version(),
                        found.get().message());
            }
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] the urgent update note could not be kept", e);
        }
        read = true;
        due = found;
        if (found.isEmpty()) {
            return;
        }
        Minecraft game = Minecraft.getInstance();
        if (ModViews.openScreen() instanceof TitleScreen) {
            showIfDue();
        } else if (game.player != null) {
            UpdateChat.showUrgent(found.get().version());
        }
    }

    public static void showIfDue() {
        UrgentUpdate urgent = find();
        if (urgent == null || shown) {
            return;
        }
        shown = true;
        HotwireClient.getEngine().openOnUrgentUpdate(urgent);
    }

    static @Nullable UrgentUpdate find() {
        readOnce();
        return Boolean.parseBoolean(System.getProperty("hotwire.noUrgent")) ? null : due.orElse(null);
    }

    private static Path findNote() {
        return FabricLoader.getInstance().getGameDir().resolve("hotwire").resolve(NOTE);
    }
}
