package dev.moon.hotwire.report;

import dev.moon.hotwire.config.Flags;
import dev.moon.hotwire.seam.Games;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.list.ModStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
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
    private final GameEnvironment environment;

    public ReportZips(GameReports reports, Supplier<List<ModStatus>> mods, Predicate<String> addedThisSession,
            GameEnvironment environment) {
        this.reports = reports;
        this.mods = mods;
        this.addedThisSession = addedThisSession;
        this.environment = environment;
        environment.hashSoon();
    }

    private volatile @Nullable Path last;

    private @Nullable Path replaceable;

    public String write(String title, String why) {
        return this.write(title, why, List.of(), false);
    }

    public String writeAsked(String title, String why) {
        return this.write(title, why, List.of(), true);
    }

    public Optional<String> findLastSessionCrash() {
        return this.reports.findLastSessionCrash().map(crash -> crash.getFileName().toString().equals(FreezeDump.FILE)
                ? this.write(Lang.get("hotwire.report.file.last_freeze"), "The game froze last session, and its"
                        + " threads are in " + crash.getFileName() + ".", List.of(), false)
                : this.write(Lang.get("hotwire.report.file.last_crash"),
                        "The game crashed last session (" + crash.getFileName() + ").", List.of(crash), false));
    }

    public String writeForCrash(Path crash) {
        String where = this.write(Lang.get("hotwire.report.file.game_crash"),
                "The game crashed (" + crash.getFileName() + ").", List.of(), false);
        this.reports.markReported(crash);
        return where;
    }

    private String write(String title, String why, List<Path> extra, boolean asked) {
        try {
            Path zip = this.writeZip(title, why, extra, asked);
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
        ActionJournal.get().note(ActionJournal.Action.REPORT, asked ? "asked" : "by itself");
        List<TidyLog.Mod> mods = new ArrayList<>();
        for (ModStatus mod : this.mods.get()) {
            mods.add(new TidyLog.Mod(mod.name(), mod.version(), !mod.on() ? TidyLog.State.OFF
                    : this.addedThisSession.test(mod.id()) ? TidyLog.State.ADDED : TidyLog.State.ON));
        }
        Path zip = this.reports.writeBundle(title, why, mods, extra, this.environment.describe());
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

    public @Nullable Path findLast() {
        return this.last;
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
            openFolderBeforeExit(folder);
        }
    }

    private static void openFolderBeforeExit(Path folder) {
        if (Flags.ONECLIENT) {
            return;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String opener = "xdg-open";
        if (os.contains("win")) {
            opener = "explorer";
        } else if (os.contains("mac")) {
            opener = "open";
        }
        try {
            new ProcessBuilder(List.of(opener, folder.toString())).start().waitFor(2, TimeUnit.SECONDS);
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] {} could not open {}: {}", opener, folder, e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void reveal(Path zip) {
        Path folder = zip.toAbsolutePath().getParent();
        if (folder != null) {
            Games.getNow().openFolder(folder);
        }
    }
}
