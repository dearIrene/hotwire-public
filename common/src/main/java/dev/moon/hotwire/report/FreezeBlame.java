package dev.moon.hotwire.report;

import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

public final class FreezeBlame {
    private static final String RENDER_THREAD = "\"Render thread\"";

    private static final Pattern FRAME_PREFIX = Pattern.compile("^.*/(?!0x)");
    private static final String HOTWIRE = "dev.moon.hotwire.";

    private static final List<String> ON_BEHALF = List.of("java.", "javax.", "jdk.", "sun.", "com.sun.",
            "org.objectweb.asm.", "org.spongepowered.", "net.fabricmc.loader.");

    private static final List<String> HOTWIRE_ON_BEHALF = List.of(".rebuild.TransformerChain", ".harness.");

    private FreezeBlame() {
    }

    public static boolean isHotwires(String dump, String journal) {
        return isSwitchUnderWay(journal) || isStuckInHotwire(dump);
    }

    static boolean isSwitchUnderWay(String journal) {
        int open = 0;
        for (Iterator<String> lines = journal.lines().iterator(); lines.hasNext();) {
            String line = lines.next();
            if (line.contains("\tstart\t")) {
                open++;
            } else if (line.contains("\tend\t")) {
                open--;
            }
        }
        return open > 0;
    }

    static boolean isStuckInHotwire(String dump) {
        boolean inThread = false;
        for (Iterator<String> lines = dump.lines().iterator(); lines.hasNext();) {
            String line = lines.next().strip();
            if (!inThread) {
                inThread = line.startsWith(RENDER_THREAD);
            } else if (line.isEmpty()) {
                return false;
            } else if (line.startsWith("at ")) {
                String frame = FRAME_PREFIX.matcher(line.substring(3)).replaceFirst("");
                if (!isOnBehalf(frame)) {
                    return frame.startsWith(HOTWIRE);
                }
            }
        }
        return false;
    }

    private static boolean isOnBehalf(String frame) {
        return ON_BEHALF.stream().anyMatch(frame::startsWith)
                || (frame.startsWith(HOTWIRE) && HOTWIRE_ON_BEHALF.stream().anyMatch(frame::contains));
    }
}
