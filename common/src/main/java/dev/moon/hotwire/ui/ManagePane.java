package dev.moon.hotwire.ui;

import dev.moon.hotwire.dev.DevBuildInfo;
import dev.moon.hotwire.dev.DevClassChange;
import dev.moon.hotwire.dev.DevHost;
import dev.moon.hotwire.dev.DevModView;
import java.util.ArrayList;
import java.util.List;

final class ManagePane {
    private static final int NEWEST_LEFT = 10;
    private static final long HOUR_MILLIS = 60L * 60 * 1000;
    private static final int MOST_MANAGED = 8;

    private ManagePane() {
    }

    static List<Part> make(BuildPicker picker, DevHost host, DevModView mod) {
        List<Part> parts = new ArrayList<>();
        List<Integer> ticked = picker.getChosen().stream().sorted((p, q) -> Integer.compare(q, p)).toList();
        parts.add(DevCards.makeLine(ticked.isEmpty() ? Lang.get("hotwire.dev.manage.pick") : Lang.get(
                "hotwire.dev.manage.frees", ticked.size(), CacheFill.describeBytes(BuildManage.sumSizes(mod,
                        ticked))), Palette.TEXT));
        List<CodeBlock.Code> picks = new ArrayList<>();
        long now = System.currentTimeMillis();
        picks.add(makePick("hotwire.dev.manage.pick_but_newest", Palette.ACCENT_SOFT,
                () -> picker.tickOnly(BuildManage.pickAllButNewest(mod, NEWEST_LEFT))));
        picks.add(makePick("hotwire.dev.manage.pick_older", Palette.ACCENT_SOFT,
                () -> picker.tickOnly(BuildManage.pickOlderThan(mod, HOUR_MILLIS, now))));
        picks.add(makePick("hotwire.dev.manage.pick_none", Palette.ACCENT_SOFT, () -> picker.tickOnly(List.of())));
        if (!mod.cleared().isEmpty()) {
            picks.add(new CodeBlock.Code("", 0, Lang.get("hotwire.dev.manage.bring_back", mod.cleared().size()),
                    Palette.GOOD, "", 0, () -> host.restoreCleared(mod.id())));
        }
        parts.add(CodeBlock.makeBlock(Lang.get("hotwire.dev.manage.picks"), "", Palette.ACCENT_SOFT, picks, false));
        if (ticked.stream().anyMatch(mod.pinned()::contains)) {
            parts.add(DevCards.makeLine(Lang.get("hotwire.dev.manage.pinned_warning"), Palette.BAD));
        }

        for (int build : ticked.subList(0, Math.min(MOST_MANAGED, ticked.size()))) {
            DevBuildInfo info = mod.infos().get(build);
            List<CodeBlock.Code> rows = new ArrayList<>();
            if (info != null) {
                for (DevClassChange change : info.classes()) {
                    int[] lines = BuildManage.countLines(change);
                    rows.add(CodeBlock.Code.ofMark("~", Palette.WARN, change.file().isEmpty() ? change.name()
                            : change.file(), Palette.TEXT, Lang.get("hotwire.dev.manage.lines", lines[0], lines[1])));
                }
                info.addedClasses().forEach(name -> rows.add(CodeBlock.Code.ofMark("+", Palette.GOOD,
                        name + ".class", Palette.TEXT, "")));
                info.removedClasses().forEach(name -> rows.add(CodeBlock.Code.ofMark("-", Palette.BAD,
                        name + ".class", Palette.TEXT, "")));
                info.files().forEach(name -> rows.add(CodeBlock.Code.ofMark("~", Palette.WARN, name, Palette.TEXT,
                        "")));
            }
            long size = mod.sizes().getOrDefault(build, 0L);
            parts.add(CodeBlock.makeBlock(BuildPicker.findName(mod, build), CacheFill.describeBytes(size),
                    mod.pinned().contains(build) ? Palette.BAD : BuildManage.findColor(BuildManage.findWeight(size,
                            mod.sizes().values())), CodeBlock.limitRows(rows), false));
        }
        if (ticked.size() > MOST_MANAGED) {
            parts.add(DevCards.makeLine(Lang.get("hotwire.dev.manage.more", ticked.size() - MOST_MANAGED),
                    Palette.DIM));
        }
        return parts;
    }

    private static CodeBlock.Code makePick(String key, int ink, Runnable pick) {
        return new CodeBlock.Code("", 0, Lang.get(key), ink, "", 0, pick);
    }
}
