package dev.moon.hotwire.fabric.dev;

import dev.moon.hotwire.dev.DevBuild;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.UpdatePreview;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SortedSet;

final class BuildWords {
    private static final int LISTED = 6;

    private BuildWords() {
    }

    static List<String> list(SortedSet<String> names) {
        List<String> shown = new ArrayList<>();
        for (String name : names) {
            if (shown.size() == LISTED) {
                shown.add(Lang.get("hotwire.dev.and_more", names.size() - LISTED));
                break;
            }
            String plain = name.endsWith(".class") ? name.substring(0, name.length() - ".class".length()) : name;
            shown.add(plain.substring(plain.lastIndexOf('/') + 1));
        }
        return shown;
    }

    static String describeClasses(DevBuild.Diff diff) {
        int count = diff.changedClasses().size() + diff.addedClasses().size() + diff.removedClasses().size();
        return count == 1 ? Lang.get("hotwire.dev.class.one") : Lang.get("hotwire.dev.class.many", count);
    }

    static String describeFiles(DevBuild.Diff diff) {
        int count = diff.changedFiles().size();
        return count == 1 ? Lang.get("hotwire.dev.file.one") : Lang.get("hotwire.dev.file.many", count);
    }

    static String findSeconds(long began) {
        return String.format(Locale.ROOT, "%.1f s", (System.nanoTime() - began) / 1e9);
    }

    static String describePreview(UpdatePreview preview) {
        return switch (preview) {
            case UpdatePreview.Refused refused -> refused.reason();
            case UpdatePreview.SameVersion same -> Lang.get("hotwire.dev.same");
            case UpdatePreview.Unreadable unreadable -> Lang.get("hotwire.dev.unreadable");
            case UpdatePreview.Ready ready -> String.join(" ", ready.lines());
        };
    }
}
