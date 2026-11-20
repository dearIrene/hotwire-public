package dev.moon.hotwire.engine;

import dev.moon.hotwire.config.AtomicFiles;
import dev.moon.hotwire.config.HotwireSettings;
import dev.moon.hotwire.report.Causes;
import dev.moon.hotwire.seam.Games;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.PromptScreen;
import dev.moon.hotwire.ui.notices.Notices;
import dev.moon.hotwire.ui.panels.AntiCrashOfferPanel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AntiCrashOffer {
    private static final Logger LOGGER = LoggerFactory.getLogger(AntiCrashOffer.class);
    private static final String NOTE = "anti-crash-offer.txt";

    private record OfferNote(String name, Throwable thrown) {
    }

    private static volatile @Nullable OfferNote lastNote;
    private static volatile Optional<String> due = Optional.empty();

    private AntiCrashOffer() {
    }

    static void noteThrow(String name, Throwable thrown) {
        lastNote = new OfferNote(name, thrown);
    }

    public static void onCrashSaved(Throwable crash) {
        OfferNote last = lastNote;
        if (last == null || !(Causes.list(crash).contains(last.thrown())
                || Causes.list(last.thrown()).contains(crash))) {
            return;
        }
        try {
            AtomicFiles.writeString(findNote(), last.name());
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] the Anti-Crash offer could not be kept for the next launch", e);
        }
    }

    public static void readAtStart() {
        Path note = findNote();
        try {
            if (Files.isRegularFile(note)) {
                due = Optional.of(Files.readString(note).strip());
                Files.delete(note);
            }
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] the Anti-Crash offer could not be read", e);
        }
    }

    public static void showIfDue() {
        Optional<String> mod = due;
        HotwireEngine engine = EngineApi.findEngine();
        if (mod.isEmpty() || engine == null) {
            return;
        }
        due = Optional.empty();
        HotwireSettings settings = engine.getSettings();
        if (settings.isAntiCrashOn() || settings.isAntiCrashOfferOff()) {
            return;
        }
        Object back = engine.units.findOpenScreen();
        Games.getNow().showScreen(engine.units.wrapPrompt(new PromptScreen(new AntiCrashOfferPanel(mod.get(), () -> {
            settings.turnOnAntiCrash();
            Games.getNow().showScreen(back);
            Notices.show(Lang.get("hotwire.anti_crash.title"), Lang.get("hotwire.anti_crash.offer.done"));
        }, never -> {
            if (never) {
                settings.stopAntiCrashOffer();
            }
            Games.getNow().showScreen(back);
        }))));
    }

    private static Path findNote() {
        return FabricLoader.getInstance().getGameDir().resolve("hotwire").resolve(NOTE);
    }
}
