package dev.moon.hotwire.fabric;

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
    private static volatile Optional<String> due = Optional.empty();

    private CrashNotice() {
    }

    static void save(String where) {
        Path note = findNote();
        try {
            Files.createDirectories(note.getParent());
            Files.writeString(note, where);
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] the crash notice could not be kept for the next launch", e);
        }
    }

    static void readAtStart() {
        Path note = findNote();
        try {
            if (Files.isRegularFile(note)) {
                due = Optional.of(Files.readString(note).strip());
                Files.delete(note);
            }
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] the crash notice could not be read", e);
        }
    }

    static void setDue(String where) {
        due = Optional.of(where);
    }

    public static void showIfDue() {
        Optional<String> words = due;
        HotwireEngine engine = HotwireClient.findEngine();
        if (words.isEmpty() || engine == null) {
            return;
        }
        due = Optional.empty();
        engine.openOnCrashReport(words.get());
    }

    private static Path findNote() {
        return FabricLoader.getInstance().getGameDir().resolve("hotwire").resolve(NOTE);
    }
}
