package dev.moon.hotwire.fabric;

import dev.moon.hotwire.concurrent.Tasks;
import dev.moon.hotwire.fabric.report.GameReports;
import dev.moon.hotwire.fabric.report.ReportZips;
import dev.moon.hotwire.fabric.screen.CrashPopup;
import dev.moon.hotwire.ui.Lang;
import java.io.File;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import net.minecraft.CrashReport;
import net.minecraft.ReportType;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CrashReports {
    private static final Logger LOGGER = LoggerFactory.getLogger(CrashReports.class);

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss", Locale.ROOT);

    private static volatile boolean goingDown;

    private CrashReports() {
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    static void watch(Tasks tasks, ReportZips zips) {
        CrashNotice.readAtStart();
        tasks.submit("report of the last session's crash", zips::findLastSessionCrash,
                where -> where.ifPresent(CrashNotice::setDue));
        GameReports.listenForCrashes(crash -> {
            String words = zips.writeForCrash(crash);
            LOGGER.info("[Hotwire] {}", words);
            CrashNotice.save(words);
            if (!goingDown) {
                Minecraft.getInstance().execute(() -> CrashNotice.showNow(words));
            } else if (!Minecraft.getInstance().isSameThread() || CrashPopup.show(
                    Lang.get("hotwire.report.crash_title"),
                    Lang.get("hotwire.report.crash_blame") + "\n\n" + words + "\n" + Lang.get("hotwire.report.send"),
                    Lang.get("hotwire.report.show_file_now"), Lang.get("hotwire.button.close"),
                    Lang.get("hotwire.report.show_ask"))) {
                zips.revealLastBeforeExit();
            }
        });
    }

    public static void markGoingDown() {
        goingDown = true;
    }

    public static void saveBeforeWorld(CrashReport report, File gameDirectory) {
        if (report.getSaveFile() == null) {
            report.saveToFile(gameDirectory.toPath().resolve("crash-reports").resolve("crash-"
                    + LocalDateTime.now(ZoneId.systemDefault()).format(FILE_TIME) + "-client.txt"), ReportType.CRASH);
        }
    }
}
