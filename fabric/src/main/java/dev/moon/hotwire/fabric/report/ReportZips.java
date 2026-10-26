package dev.moon.hotwire.fabric.report;

import dev.moon.hotwire.report.GameLog;
import dev.moon.hotwire.report.TidyLog;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.list.ModStatus;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;
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

    private volatile @Nullable Path last;

    public String write(String why) {
        return this.write(why, false, List.of(), true);
    }

    public Optional<String> findLastSessionCrash() {
        return this.reports.findLastSessionCrash().map(crash -> this.write("The game crashed last session ("
                + crash.getFileName() + ").", true, List.of(crash), true));
    }

    public String writeForCrash(Path crash) {
        String where = this.write("The game crashed (" + crash.getFileName() + ").", true, List.of(), false);
        this.reports.markReported(crash);
        return where;
    }

    private String write(String why, boolean crash, List<Path> extra, boolean open) {
        try {
            Path zip = this.writeZip(why, crash, extra);
            if (open) {
                reveal(zip);
            }
            return Lang.get("hotwire.report.written", FabricLoader.getInstance().getGameDir().relativize(zip));
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] the report could not be written", e);
            return Lang.get("hotwire.report.failed", e.getMessage());
        }
    }

    public Path writeZip(String why, boolean crash, List<Path> extra) throws IOException {
        List<TidyLog.Mod> mods = new ArrayList<>();
        for (ModStatus mod : this.mods.get()) {
            mods.add(new TidyLog.Mod(mod.name(), mod.version(), !mod.on() ? TidyLog.State.OFF
                    : this.addedThisSession.test(mod.id()) ? TidyLog.State.ADDED : TidyLog.State.ON));
        }
        Path zip = this.reports.writeBundle(why, crash, mods, extra);
        this.last = zip;
        GameLog.LOGGER.info("[Hotwire] wrote the report {}: {}", zip.getFileName(), why);
        return zip;
    }

    public void revealLast() {
        Path zip = this.last;
        if (zip != null) {
            reveal(zip);
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
