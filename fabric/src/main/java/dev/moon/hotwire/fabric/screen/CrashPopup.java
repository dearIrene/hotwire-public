package dev.moon.hotwire.fabric.screen;

import dev.moon.hotwire.fabric.version.Dialogs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CrashPopup {
    private static final Logger LOGGER = LoggerFactory.getLogger(CrashPopup.class);

    private CrashPopup() {
    }

    private static final long WAIT_MILLIS = 20_000;

    public static boolean showOnItsOwnThread(String title, String message, String showFile, String close,
            String ask) {
        boolean[] answer = new boolean[1];
        Thread box = new Thread(() -> answer[0] = show(title, message, showFile, close, ask), "hotwire-crash-box");
        box.setDaemon(true);
        box.start();
        try {
            box.join(WAIT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (box.isAlive()) {
            LOGGER.warn("[Hotwire] the crash's message box did not answer in {} s; the game goes down without it",
                    WAIT_MILLIS / 1000);
            return false;
        }
        return answer[0];
    }

    public static boolean show(String title, String message, String showFile, String close, String ask) {
        String shown = message.replace('\'', '’').replace('"', '”');
        try {
            return Dialogs.showCrashBox(title, shown, showFile, close, ask);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("[Hotwire] the crash's message box could not be shown: {}", e.toString());
            return false;
        }
    }
}
