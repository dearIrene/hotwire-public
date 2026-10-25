package dev.moon.hotwire.fabric.report;

import dev.moon.hotwire.ui.list.ModStatus;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ReportZips {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReportZips.class);

    private final GameReports reports;
    private final Supplier<List<ModStatus>> mods;
    private final Predicate<String> addedThisSession;

    public ReportZips(GameReports reports, Supplier<List<ModStatus>> mods, Predicate<String> addedThisSession) {
        this.reports = reports;
        this.mods = mods;
        this.addedThisSession = addedThisSession;
    }

    public String write(String why) {
        return this.write(why, List.of());
    }

    public Optional<String> findLastSessionCrash() {
        return this.reports.findLastSessionCrash().map(crash -> this.write("The game crashed last session ("
                + crash.getFileName() + ").", List.of(crash)));
    }

    public String writeForCrash(Path crash) {
        String where = this.write("The game crashed (" + crash.getFileName() + ").", List.of());
        this.reports.markReported(crash);
        return where;
    }

    private String write(String why, List<Path> extra) {
        StringBuilder mods = new StringBuilder();
        for (ModStatus mod : this.mods.get()) {
            mods.append(mod.id()).append(' ').append(mod.version()).append(mod.on() ? " on" : " off")
                    .append(this.addedThisSession.test(mod.id()) ? ", added this session" : "")
                    .append(System.lineSeparator());
        }
        try {
            Path zip = this.reports.writeBundle(why, mods.toString(), extra);
            reveal(zip);
            return "The report is at " + FabricLoader.getInstance().getGameDir().relativize(zip)
                    + "; send that one file.";
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] the report could not be written", e);
            return "The report could not be written: " + e.getMessage();
        }
    }

    private static void reveal(Path zip) {
        if (System.getProperty("hotwire.oneclient") != null) {
            return;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String file = zip.toAbsolutePath().toString();

        List<String> command = os.contains("win") ? List.of("explorer.exe", "/select,", file)
                : os.contains("mac") ? List.of("open", "-R", file)
                : List.of("xdg-open", String.valueOf(zip.toAbsolutePath().getParent()));
        try {
            new ProcessBuilder(command).start();
        } catch (IOException e) {
            LOGGER.info("[Hotwire] the reports folder could not be opened: {}", e.toString());
        }
    }
}
