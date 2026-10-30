package dev.moon.hotwire.fabric.report;

import dev.moon.hotwire.fabric.screen.WebLinks;
import dev.moon.hotwire.report.GameLog;
import dev.moon.hotwire.report.TidyLog;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.list.ModStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

    private @Nullable Path replaceable;

    public String write(String title, String why) {
        return this.write(title, why, List.of(), true, false);
    }

    public String writeAsked(String title, String why) {
        return this.write(title, why, List.of(), true, true);
    }

    public Optional<String> findLastSessionCrash() {
        return this.reports.findLastSessionCrash().map(crash -> this.write(Lang.get("hotwire.report.file.last_crash"),
                "The game crashed last session (" + crash.getFileName() + ").", List.of(crash), true, false));
    }

    public String writeForCrash(Path crash) {
        String where = this.write(Lang.get("hotwire.report.file.game_crash"),
                "The game crashed (" + crash.getFileName() + ").", List.of(), false, false);
        this.reports.markReported(crash);
        return where;
    }

    private String write(String title, String why, List<Path> extra, boolean open, boolean asked) {
        try {
            Path zip = this.writeZip(title, why, extra, asked);
            if (open) {
                reveal(zip);
            }
            return Lang.get("hotwire.report.written", FabricLoader.getInstance().getGameDir().relativize(zip));
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] the report could not be written", e);
            return Lang.get("hotwire.report.failed", e.getMessage());
        }
    }

    public Path writeZip(String title, String why, List<Path> extra) throws IOException {
        return this.writeZip(title, why, extra, true);
    }

    private synchronized Path writeZip(String title, String why, List<Path> extra, boolean asked)
            throws IOException {
        List<TidyLog.Mod> mods = new ArrayList<>();
        for (ModStatus mod : this.mods.get()) {
            mods.add(new TidyLog.Mod(mod.name(), mod.version(), !mod.on() ? TidyLog.State.OFF
                    : this.addedThisSession.test(mod.id()) ? TidyLog.State.ADDED : TidyLog.State.ON));
        }
        Path zip = this.reports.writeBundle(title, why, mods, extra);
        Path before = this.replaceable;
        if (before != null && !before.equals(zip)) {
            Files.deleteIfExists(before);
            LOGGER.info("[Hotwire] {} carries {}, which it replaces", zip.getFileName(),
                    before.getFileName());
        }
        this.replaceable = asked ? null : zip;
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

    public void revealLastBeforeExit() {
        Path zip = this.last;
        Path folder = zip == null ? null : zip.toAbsolutePath().getParent();
        if (folder != null) {
            WebLinks.openFolderBeforeExit(folder);
        }
    }

    private static void reveal(Path zip) {
        Path folder = zip.toAbsolutePath().getParent();
        if (folder != null) {
            WebLinks.openFolder(folder);
        }
    }
}
