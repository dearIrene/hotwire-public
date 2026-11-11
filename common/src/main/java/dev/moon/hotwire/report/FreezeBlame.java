package dev.moon.hotwire.report;

import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FreezeBlame {
    private static final String RENDER_THREAD = "\"Render thread\"";

    private static final Pattern FRAME_PREFIX = Pattern.compile("^.*/(?!0x)");
    private static final String HOTWIRE = "dev.moon.hotwire.";

    private static final Pattern ENDED_BEFORE = Pattern.compile("\\) ended (\\d+) s before\\.");
    private static final long SHORTLY_SECONDS = 180;
    private static final String UNDER_WAY = "A switch of Hotwire's was under way";

    private static final List<String> ON_BEHALF = List.of("java.", "javax.", "jdk.", "sun.", "com.sun.",
            "org.objectweb.asm.", "org.spongepowered.", "net.fabricmc.loader.");

    private static final List<String> HOTWIRE_ON_BEHALF = List.of(".rebuild.TransformerChain", ".harness.");

    private FreezeBlame() {
    }

    public static boolean isHotwires(String dump, String journal) {
        return isSwitchUnderWay(journal) || isStuckInHotwire(dump) || isShortlyAfterSwitch(dump)
                || dump.contains(UNDER_WAY);
    }

    static boolean isShortlyAfterSwitch(String dump) {
        Matcher ended = ENDED_BEFORE.matcher(dump);
        return ended.find() && Long.parseLong(ended.group(1)) <= SHORTLY_SECONDS;
    }

    public static String describeStuck(StackTraceElement[] stack) {
        for (StackTraceElement frame : stack) {
            String name = frame.getClassName();
            String where = name.substring(name.lastIndexOf('.') + 1) + "." + frame.getMethodName();
            if (name.startsWith("org.lwjgl.")) {
                return "The game's thread was waiting on the graphics driver (" + where + ").";
            }
            if (!isOnBehalf(name + ".")) {
                return "The game's thread was in " + (name.startsWith(HOTWIRE) ? "Hotwire's own code" : name)
                        + " (" + where + ").";
            }
        }
        return "The game's thread had no code of the game's or a mod's on it.";
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
