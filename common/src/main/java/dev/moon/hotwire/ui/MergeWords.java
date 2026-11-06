package dev.moon.hotwire.ui;

import dev.moon.hotwire.dev.BuildMerge;
import dev.moon.hotwire.dev.DevBuildInfo;
import dev.moon.hotwire.dev.DevClassChange;
import java.util.ArrayList;
import java.util.List;

public final class MergeWords {
    private MergeWords() {
    }

    public static String list(List<Integer> builds) {
        if (builds.size() <= 1) {
            return builds.isEmpty() ? "" : String.valueOf(builds.getFirst());
        }
        List<String> head = new ArrayList<>();
        for (int build : builds.subList(0, builds.size() - 1)) {
            head.add(String.valueOf(build));
        }
        return Lang.get("hotwire.dev.merge.list", String.join(", ", head), builds.getLast());
    }

    public static String describeBuild(int build) {
        return build == 0 ? Lang.get("hotwire.dev.merge.the_start") : Lang.get("hotwire.dev.merge.build", build);
    }

    public static String describe(BuildMerge.Refusal refusal) {
        return switch (refusal.kind()) {
            case TOO_FEW -> Lang.get("hotwire.dev.merge.too_few");
            case START -> Lang.get("hotwire.dev.merge.start");
            case NOT_KEPT -> Lang.get("hotwire.dev.merge.not_kept", refusal.build());
            case NO_RECORD -> Lang.get("hotwire.dev.merge.no_record", refusal.build());
            case BASE_GONE -> Lang.get("hotwire.dev.merge.base_gone", refusal.build());
        };
    }

    public static String shorten(String entry) {
        return entry.substring(entry.lastIndexOf('/') + 1);
    }

    static String describeEntry(DevBuildInfo info, String entry) {
        if (!entry.endsWith(".class")) {
            return info.files().contains(shorten(entry)) ? Lang.get("hotwire.dev.merge.file_changed")
                    : Lang.get("hotwire.dev.merge.file_gone");
        }
        String type = entry.substring(0, entry.length() - ".class".length()).replace('/', '.');
        String simple = type.substring(type.lastIndexOf('.') + 1);
        for (DevClassChange change : info.classes()) {
            if (change.type().equals(type) || (change.type().isEmpty() && change.name().equals(simple))) {
                return describeMethods(change);
            }
        }
        if (info.addedClasses().contains(simple)) {
            return Lang.get("hotwire.dev.merge.class_new");
        }
        return info.removedClasses().contains(simple) ? Lang.get("hotwire.dev.merge.class_gone")
                : Lang.get("hotwire.dev.merge.class_changed");
    }

    static String describeMethods(DevClassChange change) {
        List<String> parts = new ArrayList<>();
        if (!change.changed().isEmpty()) {
            parts.add("~ " + String.join(", ", change.changed()));
        }
        if (!change.added().isEmpty()) {
            parts.add("+ " + String.join(", ", change.added()));
        }
        if (!change.removed().isEmpty()) {
            parts.add("- " + String.join(", ", change.removed()));
        }
        return parts.isEmpty() ? Lang.get("hotwire.dev.report.fields") : String.join("  ", parts);
    }
}
