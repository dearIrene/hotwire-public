package dev.moon.hotwire.fabric;

import dev.moon.hotwire.concurrent.Tasks;
import dev.moon.hotwire.fabric.report.GameReports;
import dev.moon.hotwire.fabric.report.ReportZips;
import dev.moon.hotwire.fabric.screen.CrashPopup;
import dev.moon.hotwire.ui.Lang;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class CrashReports {
    private static final Logger LOGGER = LoggerFactory.getLogger(CrashReports.class);

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
            ModViews.showToast(Lang.get("hotwire.report.crash"), words);

            if (!Minecraft.getInstance().isSameThread() || CrashPopup.show(Lang.get("hotwire.report.crash_title"),
                    Lang.get("hotwire.report.crash_blame") + "\n\n" + words + "\n" + Lang.get("hotwire.report.send"),
                    Lang.get("hotwire.report.show_file_now"), Lang.get("hotwire.button.close"),
                    Lang.get("hotwire.report.show_ask"))) {
                zips.revealLast();
            }
        });
    }
}
