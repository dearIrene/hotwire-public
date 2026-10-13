package dev.moon.hotwire.fabric.report;

import dev.moon.hotwire.ui.list.ModStatus;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
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

    private String write(String why, List<Path> extra) {
        StringBuilder mods = new StringBuilder();
        for (ModStatus mod : this.mods.get()) {
            mods.append(mod.id()).append(' ').append(mod.version()).append(mod.on() ? " on" : " off")
                    .append(this.addedThisSession.test(mod.id()) ? ", added this session" : "")
                    .append(System.lineSeparator());
        }
        try {
            Path zip = this.reports.writeBundle(why, mods.toString(), extra);
            return "The report is at " + FabricLoader.getInstance().getGameDir().relativize(zip)
                    + "; send that one file.";
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] the report could not be written", e);
            return "The report could not be written: " + e.getMessage();
        }
    }
}
