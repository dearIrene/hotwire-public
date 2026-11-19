package dev.moon.hotwire.store;

import dev.moon.hotwire.classfile.GameNames;
import dev.moon.hotwire.engine.LoadedMods;
import dev.moon.hotwire.ui.Lang;

public final class OutdatedNotice {
    private static volatile String newer = "";

    private OutdatedNotice() {
    }

    public static void set(String version) {
        newer = version;
    }

    public static String warn() {
        return newer.isEmpty() ? "" : Lang.get("hotwire.outdated", getShort(getRunning()), getShort(newer));
    }

    public static String addTo(String words) {
        String line = warn();
        return line.isEmpty() ? words : words.isEmpty() ? line : line + "\n" + words;
    }

    public static String note() {
        return describe(getRunning(), newer);
    }

    static String describe(String running, String newer) {
        return newer.isEmpty() ? "" : "OUTDATED: this is Hotwire " + getShort(running) + " and " + getShort(newer)
                + " is out. Check the newer version before anything else.\n\n";
    }

    static String getShort(String version) {
        int plus = version.indexOf('+');
        return plus < 0 ? version : version.substring(0, plus);
    }

    private static String getRunning() {
        return LoadedMods.findVersion(GameNames.HOTWIRE, "unknown");
    }
}
