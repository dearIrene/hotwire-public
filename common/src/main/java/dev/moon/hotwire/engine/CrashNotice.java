package dev.moon.hotwire.engine;

import dev.moon.hotwire.config.AtomicFiles;
import dev.moon.hotwire.report.ReportZips.CrashWords;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CrashNotice {
    private static final Logger LOGGER = LoggerFactory.getLogger(CrashNotice.class);
    private static final String NOTE = "crash-notice.txt";

    private static final String OURS = "ours";
    private static final String UNSURE = "unsure";
    private static volatile Optional<CrashWords> due = Optional.empty();

    private CrashNotice() {
    }

    public static void save(CrashWords words) {
        Path note = findNote();
        try {
            AtomicFiles.writeString(note, (words.ours() ? OURS : UNSURE) + "\n" + words.where());
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] the crash notice could not be kept for the next launch", e);
        }
    }

    public static void readAtStart() {
        Path note = findNote();
        try {
            if (Files.isRegularFile(note)) {
                String text = Files.readString(note).strip();
                int line = text.indexOf('\n');
                String first = line < 0 ? "" : text.substring(0, line);

                due = Optional.of(first.equals(OURS) || first.equals(UNSURE)
                        ? new CrashWords(text.substring(line + 1).strip(), first.equals(OURS))
                        : new CrashWords(text, true));
                Files.delete(note);
            }
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] the crash notice could not be read", e);
        }
    }

    public static void setDue(CrashWords words) {
        due = Optional.of(words);
    }

    public static void showNow(CrashWords words) {
        try {
            Files.deleteIfExists(findNote());
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] the crash notice could not be dropped", e);
        }
        HotwireEngine engine = EngineApi.findEngine();
        if (engine != null) {
            engine.getScreens().openOnCrashReport(words);
        }
    }

    public static void showIfDue() {
        Optional<CrashWords> words = due;
        HotwireEngine engine = EngineApi.findEngine();
        if (words.isEmpty() || engine == null) {
            return;
        }
        due = Optional.empty();
        engine.getScreens().openOnCrashReport(words.get());
    }

    private static Path findNote() {
        return FabricLoader.getInstance().getGameDir().resolve("hotwire").resolve(NOTE);
    }
}
