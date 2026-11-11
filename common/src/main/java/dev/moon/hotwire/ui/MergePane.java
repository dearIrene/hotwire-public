package dev.moon.hotwire.ui;

import dev.moon.hotwire.dev.BuildMerge;
import dev.moon.hotwire.dev.DevBuildInfo;
import dev.moon.hotwire.dev.DevModView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

final class MergePane {
    private static final int MOST_CONFLICTS = 6;

    private MergePane() {
    }

    static List<Part> make(DevModView mod, BuildMerge.@Nullable Plan made) {
        List<Part> parts = new ArrayList<>();
        if (made == null) {
            parts.add(DevCards.makeLine(Lang.get("hotwire.dev.merge.pick"), Palette.TEXT));
            parts.add(DevCards.makeLine(Lang.get("hotwire.dev.merge.how"), Palette.DIM));
            return parts;
        }
        BuildMerge.Refusal refusal = made.refusal();
        if (refusal != null) {
            parts.add(DevCards.makeLine(MergeWords.describe(refusal), Palette.BAD));
            return parts;
        }
        List<Integer> others = new ArrayList<>(made.builds());
        others.remove(Integer.valueOf(made.tip()));
        parts.add(DevCards.makeLine(made.isFast() ? Lang.get("hotwire.dev.merge.fast", made.tip(),
                MergeWords.list(others)) : Lang.get("hotwire.dev.merge.makes", MergeWords.list(made.builds())),
                Palette.TEXT));
        parts.add(DevCards.makeLine(Lang.get("hotwire.dev.merge.made_on", MergeWords.describeBuild(made.parent())),
                Palette.DIM));

        List<CodeBlock.Code> files = new ArrayList<>();
        made.sources().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> files.add(
                CodeBlock.Code.ofMark("~", Palette.WARN, MergeWords.trimEntry(entry.getKey()), Palette.TEXT,
                        Lang.get("hotwire.dev.merge.from", entry.getValue()))));
        made.gone().stream().sorted().forEach(entry -> files.add(CodeBlock.Code.ofMark("-", Palette.BAD,
                MergeWords.trimEntry(entry), Palette.DIM, Lang.get("hotwire.dev.merge.taken_out"))));
        if (!files.isEmpty()) {
            parts.add(CodeBlock.makeBlock(Lang.get("hotwire.dev.merge.has"), Lang.get("hotwire.dev.merge.count",
                    files.size()), Palette.ACCENT_SOFT, CodeBlock.limitRows(files), false));
        }

        List<BuildMerge.Conflict> conflicts = made.conflicts();
        for (BuildMerge.Conflict conflict : conflicts.subList(0, Math.min(MOST_CONFLICTS, conflicts.size()))) {
            List<CodeBlock.Code> rows = new ArrayList<>();
            for (int build : conflict.builds()) {
                DevBuildInfo info = mod.infos().get(build);
                rows.add(makeConflictRow(build, conflict, info == null ? Lang.get("hotwire.dev.merge.class_changed")
                        : MergeWords.describeEntry(info, conflict.entry())));
            }
            rows.add(CodeBlock.Code.of(Lang.get("hotwire.dev.merge.why_kept", conflict.winner(),
                    MergeWords.list(conflict.dropped())), Palette.FAINT));
            parts.add(CodeBlock.makeBlock(Lang.get("hotwire.dev.merge.conflict",
                    MergeWords.trimEntry(conflict.entry())), "", Palette.WARN, rows, false));
        }
        if (conflicts.size() > MOST_CONFLICTS) {
            parts.add(DevCards.makeLine(Lang.get("hotwire.dev.merge.more_conflicts",
                    conflicts.size() - MOST_CONFLICTS), Palette.WARN));
        }

        for (BuildMerge.BuildGap gap : made.leftOut()) {
            if (gap.entries().isEmpty()) {
                continue;
            }
            List<CodeBlock.Code> rows = new ArrayList<>();
            rows.add(CodeBlock.Code.of(Lang.get("hotwire.dev.merge.left_why", gap.build(), gap.skipped().getFirst()),
                    Palette.DIM));
            gap.entries().forEach(entry -> rows.add(CodeBlock.Code.ofMark("?", Palette.FAINT,
                    MergeWords.trimEntry(entry), Palette.DIM, "")));
            List<Integer> skipped = gap.skipped().stream().sorted().toList();
            parts.add(CodeBlock.makeBlock(Lang.get(skipped.size() == 1 ? "hotwire.dev.merge.left_out"
                    : "hotwire.dev.merge.left_out.many", MergeWords.list(skipped)), "", Palette.FAINT,
                    CodeBlock.limitRows(rows), false));
        }

        for (int build : made.builds()) {
            if (!made.removed().contains(build) && build != made.tip()) {
                parts.add(DevCards.makeLine(Lang.get(build == mod.runningBuild() ? "hotwire.dev.merge.stays_running"
                        : "hotwire.dev.merge.stays_pinned", build), Palette.DIM));
            }
        }
        if (!made.removed().isEmpty()) {
            parts.add(DevCards.makeLine(Lang.get(made.removed().size() == 1 ? "hotwire.dev.merge.goes.one"
                    : "hotwire.dev.merge.goes", MergeWords.list(List.copyOf(new TreeSet<>(made.removed())))),
                    conflicts.isEmpty() ? Palette.DIM : Palette.BAD));
        }
        parts.add(DevCards.makeLine(Lang.get("hotwire.dev.merge.game"), Palette.FAINT));
        return parts;
    }

    private static CodeBlock.Code makeConflictRow(int build, BuildMerge.Conflict conflict, String what) {
        String said = Lang.get("hotwire.dev.merge.build_did", build, what);
        if (build == conflict.winner()) {
            return new CodeBlock.Code("+", Palette.GOOD, said, Palette.TEXT, Lang.get("hotwire.dev.merge.kept"),
                    Palette.GOOD, null);
        }
        if (conflict.dropped().contains(build)) {
            return new CodeBlock.Code("x", Palette.BAD, said, Palette.DIM, Lang.get("hotwire.dev.merge.dropped"),
                    Palette.BAD, null);
        }
        return CodeBlock.Code.ofMark("~", Palette.DIM, said, Palette.DIM, Lang.get("hotwire.dev.merge.inside"));
    }
}
