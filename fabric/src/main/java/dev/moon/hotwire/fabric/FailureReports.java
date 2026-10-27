package dev.moon.hotwire.fabric;

import dev.moon.hotwire.redefine.RebuildException;
import dev.moon.hotwire.ui.ModScreen;

final class FailureReports {
    private FailureReports() {
    }

    static boolean isUnplanned(Throwable cause) {
        return !(cause instanceof RebuildException rebuild && (rebuild.reopenFixes() || rebuild.isRestartOnly()));
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    static void report(HotwireEngine engine, String title, String failed) {
        engine.getTasks().submit("report of " + title, () -> engine.writeReport(title, failed), where -> {
            engine.open();
            ModScreen current = engine.getScreen();
            if (current != null) {
                current.showFailureReport(failed, where);
            }
        });
    }
}
