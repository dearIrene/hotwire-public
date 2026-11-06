package dev.moon.hotwire.ui;

import dev.moon.hotwire.dev.BuildMerge;
import dev.moon.hotwire.dev.DevBuildInfo;
import dev.moon.hotwire.dev.DevClassChange;
import dev.moon.hotwire.dev.DevHost;
import dev.moon.hotwire.dev.DevModView;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

final class BuildPicker {
    static final int ROW_H = 18;
    private static final int LANE = 9;
    private static final int SIDE_BY_SIDE = 330;
    private static final int PAD = 8;
    private static final int LINE = 10;
    private static final int GAP = 4;

    private static final int HEAD_H = 14;
    private static final int CODE_ROW = 12;
    private static final int GUTTER = 22;
    private static final int CODE_BG = 0xFF0E0B17;
    private static final int CODE_HEAD = 0xFF181229;
    private static final int HOT = 0x24B48CFF;
    private static final int CHIP_H = 13;

    private static final int MOST_ROWS = 12;
    private static final int MOST_CONFLICTS = 6;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    private final DevHost host;
    private @Nullable TextField naming;
    private boolean open;
    private int picked = -1;
    private boolean merging;
    private final SortedSet<Integer> chosen = new TreeSet<>();

    private int namingBuild = -1;

    private @Nullable Set<Integer> before;

    private BuildMerge.@Nullable Plan plan;
    private @Nullable DevModView planFor;
    private List<Integer> planOf = List.of();

    private final EasedValue glide = EasedValue.ofSpeed(20);
    private final EasedValue fade = EasedValue.ofSpeed(9);
    private boolean glideSet;

    private final List<Hit> hits = new ArrayList<>();

    private record Hit(int x0, int y0, int x1, int y1, Runnable action) {
    }

    private record Code(String mark, int markInk, String text, int ink, String aside, int asideInk, int line,
            @Nullable Runnable open) {
        static Code plain(String text, int ink) {
            return new Code("", 0, text, ink, "", 0, 0, null);
        }
    }

    BuildPicker(DevHost host) {
        this.host = host;
    }

    void setNamingField(TextField naming) {
        this.naming = naming;
    }

    boolean isOpen() {
        return this.open;
    }

    boolean isMerging() {
        return this.open && this.merging;
    }

    boolean isNaming() {
        return this.open && this.namingBuild >= 0;
    }

    int getPicked() {
        return this.picked;
    }

    void open(DevModView mod, int build) {
        this.open = true;
        this.merging = false;
        this.chosen.clear();
        this.namingBuild = -1;
        this.before = null;
        this.glideSet = false;
        this.picked = isThere(mod, build) ? build : mod.keptBuilds().isEmpty() ? mod.runningBuild()
                : mod.keptBuilds().getFirst();
        this.restartFade(0);
    }

    void close() {
        this.open = false;
        this.merging = false;
        this.namingBuild = -1;
    }

    void cancel() {
        if (this.namingBuild >= 0) {
            this.namingBuild = -1;
        } else if (this.merging) {
            this.merging = false;
            this.chosen.clear();
            this.restartFade(0.3f);
        } else {
            this.close();
        }
    }

    void startMerge() {
        this.merging = true;
        this.chosen.clear();
        this.namingBuild = -1;
        if (this.picked > 0) {
            this.chosen.add(this.picked);
        }
        this.restartFade(0.3f);
    }

    void update(@Nullable DevModView mod, float dt) {
        this.glide.step(dt);
        this.fade.step(dt);
        if (!this.open) {
            return;
        }
        if (mod == null) {
            this.close();
            return;
        }
        this.chosen.removeIf(build -> !isThere(mod, build));
        Set<Integer> waited = this.before;
        if (waited != null) {
            for (int build : findBuilds(mod)) {
                if (!waited.contains(build)) {
                    this.choose(build);
                    this.before = null;
                    break;
                }
            }
        }
        if (!isThere(mod, this.picked)) {
            this.choose(mod.runningBuild());
        }
    }

    private void choose(int build) {
        if (build != this.picked) {
            this.picked = build;
            this.restartFade(0);
        }
    }

    private void restartFade(float from) {
        this.fade.snap(from);
        this.fade.to(1);
    }

    private void tick(int build) {
        if (build == 0) {
            return;
        }
        if (!this.chosen.remove(build)) {
            this.chosen.add(build);
        }
        this.restartFade(0.55f);
    }

    void confirm(@Nullable DevModView mod) {
        if (mod == null) {
            return;
        }
        if (this.merging) {
            BuildMerge.Plan made = this.findPlan(mod);
            if (made == null || made.refusal() != null) {
                return;
            }
            this.host.merge(mod.id(), List.copyOf(this.chosen), !made.conflicts().isEmpty());
            this.merging = false;
            this.chosen.clear();
            if (made.isFast()) {
                this.choose(made.tip());
            } else {
                this.before = new HashSet<>(findBuilds(mod));
            }
            this.restartFade(0);
            return;
        }

        if (this.picked != mod.runningBuild() && mod.keptBuilds().contains(this.picked)) {
            this.host.rollBack(mod.id(), this.picked);
        }
    }

    boolean isConfirmActive(@Nullable DevModView mod) {
        if (mod == null) {
            return false;
        }
        if (this.merging) {
            BuildMerge.Plan made = this.findPlan(mod);
            return made != null && made.refusal() == null;
        }
        return this.picked != mod.runningBuild() && mod.keptBuilds().contains(this.picked);
    }

    String findConfirmLabel(@Nullable DevModView mod) {
        if (!this.merging) {
            return this.picked == 0 ? Lang.get("hotwire.dev.button.confirm_back.start")
                    : Lang.get("hotwire.dev.button.confirm_back", this.picked);
        }
        BuildMerge.Plan made = mod == null ? null : this.findPlan(mod);
        if (made == null || made.refusal() != null) {
            return Lang.get("hotwire.dev.button.merge");
        }
        return made.conflicts().isEmpty() ? Lang.get("hotwire.dev.button.merge_these", this.chosen.size())
                : Lang.get("hotwire.dev.button.merge_anyway");
    }

    String findConfirmShort() {
        return Lang.get(this.merging ? "hotwire.dev.button.merge" : "hotwire.dev.button.back.short");
    }

    void pin(DevModView mod) {
        this.host.pin(mod.id(), this.picked, !mod.pinned().contains(this.picked));
    }

    void copy(DevModView mod) {
        if (this.picked != 0) {
            this.before = new HashSet<>(findBuilds(mod));
            this.host.duplicate(mod.id(), this.picked);
        }
    }

    void startNaming(DevModView mod) {
        TextField field = this.naming;
        if (field == null) {
            return;
        }
        this.namingBuild = this.picked;
        String name = mod.names().get(this.picked);
        field.setText(name == null ? "" : name);
    }

    void finishNaming(@Nullable DevModView mod) {
        TextField field = this.naming;
        if (mod != null && field != null && this.namingBuild >= 0) {
            this.host.rename(mod.id(), this.namingBuild, field.getText());
        }
        this.namingBuild = -1;
    }

    @SuppressWarnings("ReferenceEquality")
    private BuildMerge.@Nullable Plan findPlan(DevModView mod) {
        if (this.chosen.size() < 2) {
            return null;
        }
        List<Integer> of = List.copyOf(this.chosen);
        if (this.plan == null || this.planFor != mod || !this.planOf.equals(of)) {
            Set<Integer> there = new HashSet<>(mod.keptBuilds());
            there.add(mod.runningBuild());
            there.add(0);
            this.plan = BuildMerge.plan(of, mod.parents(), mod.deltas(), there, mod.runningBuild(), mod.pinned());
            this.planFor = mod;
            this.planOf = of;
        }
        return this.plan;
    }

    private static List<Integer> findBuilds(DevModView mod) {
        List<Integer> builds = new ArrayList<>(mod.keptBuilds());
        if (!builds.contains(mod.runningBuild())) {
            builds.add(mod.runningBuild());
        }
        builds.sort(Comparator.reverseOrder());
        return builds;
    }

    private static boolean isThere(DevModView mod, int build) {
        return build == mod.runningBuild() || mod.keptBuilds().contains(build);
    }

    private static String findName(DevModView mod, int build) {
        String name = mod.names().get(build);
        if (name != null) {
            return name;
        }
        return build == 0 ? Lang.get("hotwire.dev.pick.start.short") : Lang.get("hotwire.dev.pick.build", build);
    }

    boolean click(double px, double py) {
        for (int at = this.hits.size() - 1; at >= 0; at--) {
            Hit hit = this.hits.get(at);
            if (px >= hit.x0() && px < hit.x1() && py >= hit.y0() && py < hit.y1()) {
                hit.action().run();
                return true;
            }
        }
        return false;
    }

    void pressRowForTests(int build) {
        if (this.merging) {
            this.tick(build);
        } else {
            this.choose(build);
        }
    }

    int paint(Canvas canvas, DevModView mod, int x, int y, int w, float a, double px, double py, boolean over) {
        this.hits.clear();
        String title = this.merging ? Lang.get("hotwire.dev.merge.title") : Lang.get("hotwire.dev.pick.title",
                mod.runningBuild());
        y = paintWrapped(canvas, title, x, y + 4, w, Palette.DIM, a) + 4;
        List<Integer> builds = findBuilds(mod);
        List<TreeRow> tree = BuildTree.lay(builds, mod.parents());
        int widest = 1;
        for (TreeRow row : tree) {
            widest = Math.max(widest, row.width());
        }
        int graphW = widest * LANE + 4;
        boolean side = w >= SIDE_BY_SIDE;
        int listW = side ? Math.clamp(w * 2 / 5, 120, 220) : w;
        int top = y;

        int index = builds.indexOf(this.picked);
        if (!this.merging && index >= 0) {
            float target = index * ROW_H;
            if (!this.glideSet) {
                this.glide.snap(target);
                this.glideSet = true;
            }
            this.glide.to(target);
            int hy = top + Math.round(this.glide.getValue());
            canvas.fill(x + graphW - 2, hy, x + listW, hy + ROW_H - 2, Colors.fade(Palette.PANEL_EDGE, a));
            canvas.fill(x + graphW - 2, hy, x + graphW, hy + ROW_H - 2, Colors.fade(Palette.ACCENT, a));
        }
        int[] tops = new int[builds.size()];
        int[] bottoms = new int[builds.size()];
        for (int at = 0; at < builds.size(); at++) {
            int build = builds.get(at);
            tops[at] = y;
            bottoms[at] = y + ROW_H;
            this.paintRow(canvas, mod, build, x + graphW, y, listW - graphW, a, px, py, over);
            y += ROW_H;
        }
        this.paintTree(canvas, tree, builds, mod.runningBuild(), x, tops, bottoms, a);
        int listBottom = y;
        int reportX = side ? x + listW + PAD : x;
        int reportY = side ? top : listBottom + PAD;
        int reportW = side ? w - listW - PAD : w;
        float in = this.fade.getValue();
        int shift = Math.round((1 - in) * 6);
        float ra = a * (0.25f + 0.75f * in);
        int reportBottom = this.merging ? this.paintMerge(canvas, mod, reportX, reportY + shift, reportW, ra, px, py,
                over) : this.paintReport(canvas, mod, this.picked, reportX, reportY + shift, reportW, ra, px, py, over);
        return Math.max(listBottom, reportBottom) + 4;
    }

    private void paintRow(Canvas canvas, DevModView mod, int build, int x, int y, int w, float a, double px,
            double py, boolean over) {
        boolean running = build == mod.runningBuild();
        boolean ticked = this.merging && this.chosen.contains(build);
        boolean lit = this.merging ? ticked : build == this.picked;
        boolean hot = over && px >= x - 2 && px < x + w && py >= y && py < y + ROW_H - 2;
        if (ticked) {
            canvas.fill(x - 2, y, x + w, y + ROW_H - 2, Colors.fade(Palette.PANEL_EDGE, a * 0.8f));
            canvas.fill(x - 2, y, x, y + ROW_H - 2, Colors.fade(Palette.ACCENT, a));
        } else if (hot) {
            canvas.fill(x - 2, y, x + w, y + ROW_H - 2, Colors.fade(HOT, a));
        }
        int textX = x + 4;
        if (this.merging) {
            int box = build == 0 ? Palette.HAIRLINE : ticked ? Palette.ACCENT : Palette.DIM;
            paintFrame(canvas, textX, y + 4, 8, 8, Colors.fade(box, a));
            if (ticked) {
                canvas.fill(textX + 2, y + 6, textX + 6, y + 10, Colors.fade(Palette.ACCENT, a));
            }
            textX += 12;
        }
        String tag = running ? Lang.get("hotwire.dev.pick.runs") : build > mod.runningBuild()
                ? Lang.get("hotwire.dev.pick.newer.short") : "";
        int right = x + w - 2;
        if (!tag.isEmpty()) {
            right -= canvas.measureText(tag);
            canvas.drawText(tag, right, y + 5, Colors.fade(running ? Palette.GOOD : Palette.FAINT, a));
            right -= 5;
        }
        if (mod.pinned().contains(build)) {
            right -= 6;
            paintPin(canvas, right, y + 4, Colors.fade(Palette.WARN, a));
            right -= 4;
        }
        String named = mod.names().get(build);
        int ink = Colors.fade(lit ? Palette.TEXT : Palette.DIM, a);
        if (named != null) {
            String number = "#" + build;
            canvas.drawText(number, textX, y + 5, Colors.fade(Palette.FAINT, a));
            textX += canvas.measureText(number) + 4;
        }
        canvas.drawText(canvas.clip(findName(mod, build), Math.max(1, right - textX)), textX, y + 5, ink);
        this.hits.add(new Hit(x - 2, y, x + w, y + ROW_H - 2, this.merging ? () -> this.tick(build)
                : () -> this.choose(build)));
    }

    private void paintTree(Canvas canvas, List<TreeRow> tree, List<Integer> builds, int running, int x, int[] tops,
            int[] bottoms, float a) {
        int line = Colors.fade(Palette.DIM, a * 0.55f);
        for (int at = 0; at < tree.size(); at++) {
            TreeRow row = tree.get(at);
            int top = tops[at];
            int bottom = bottoms[at];
            int dotY = top + 6;
            int dotX = x + row.column() * LANE + 2;
            for (int passing : row.passing()) {
                int lx = x + passing * LANE + 4;
                canvas.fill(lx, top, lx + 1, bottom, line);
            }
            if (row.fromAbove()) {
                canvas.fill(dotX + 2, top, dotX + 3, dotY, line);
            }
            if (row.goesOn()) {
                canvas.fill(dotX + 2, dotY, dotX + 3, bottom, line);
            }
            for (int joined : row.joined()) {
                int lx = x + joined * LANE + 4;
                canvas.fill(lx, top, lx + 1, dotY + 2, line);
                canvas.fill(Math.min(lx, dotX + 2), dotY + 2, Math.max(lx + 1, dotX + 3), dotY + 3, line);
            }
            int build = builds.get(at);
            boolean lit = this.merging ? this.chosen.contains(build) : build == this.picked;
            int dot = build == running ? Palette.GOOD : lit ? Palette.ACCENT : Palette.DIM;
            canvas.fill(dotX, dotY, dotX + 5, dotY + 5, Colors.fade(dot, a));
        }
    }

    private int paintReport(Canvas canvas, DevModView mod, int build, int x, int y, int w, float a, double px,
            double py, boolean over) {
        String named = mod.names().get(build);
        String heading = named != null ? named : build == 0 ? Lang.get("hotwire.dev.pick.start")
                : Lang.get("hotwire.dev.pick.build", build);
        int headX = x;
        if (named != null) {
            String number = "#" + build;
            canvas.drawText(number, x, y, Colors.fade(Palette.FAINT, a));
            headX += canvas.measureText(number) + 5;
        }
        canvas.drawText(canvas.clip(heading, Math.max(1, w - (headX - x))), headX, y, Colors.fade(Palette.TEXT, a));
        y += LINE + 3;
        List<String> tags = new ArrayList<>();
        List<Integer> tagInks = new ArrayList<>();
        if (build == mod.runningBuild()) {
            tags.add(Lang.get("hotwire.dev.report.running"));
            tagInks.add(Palette.GOOD);
        } else if (build > mod.runningBuild()) {
            tags.add(Lang.get("hotwire.dev.pick.newer"));
            tagInks.add(Palette.FAINT);
        }
        if (mod.pinned().contains(build)) {
            tags.add(Lang.get("hotwire.dev.report.pinned"));
            tagInks.add(Palette.WARN);
        }
        int tagX = x;
        for (int at = 0; at < tags.size(); at++) {
            canvas.drawText(tags.get(at), tagX, y, Colors.fade(tagInks.get(at), a));
            tagX += canvas.measureText(tags.get(at)) + 8;
        }
        if (!tags.isEmpty()) {
            y += LINE + 2;
        }

        boolean pinned = mod.pinned().contains(build);
        int chipX = x;
        chipX = this.paintChip(canvas, chipX, y, Lang.get(pinned ? "hotwire.dev.chip.unpin" : "hotwire.dev.chip.pin"),
                a, px, py, over, () -> this.pin(mod));
        chipX = this.paintChip(canvas, chipX, y, Lang.get("hotwire.dev.chip.rename"), a, px, py, over,
                () -> this.startNaming(mod));
        if (build != 0) {
            this.paintChip(canvas, chipX, y, Lang.get("hotwire.dev.chip.copy"), a, px, py, over, () -> this.copy(mod));
        }
        y += CHIP_H + 6;
        DevBuildInfo info = mod.infos().get(build);
        if (info == null) {
            return paintWrapped(canvas, Lang.get(build == 0 ? "hotwire.dev.report.first" : "hotwire.dev.report.none"),
                    x, y, w, Palette.DIM, a);
        }
        String clock = LocalTime.ofInstant(Instant.ofEpochMilli(info.at()), ZoneId.systemDefault()).format(CLOCK);
        String on = MergeWords.describeBuild(info.madeOn());
        String made = info.merged().isEmpty() ? Lang.get("hotwire.dev.report.made", on, clock)
                : Lang.get("hotwire.dev.report.merged_made", MergeWords.list(info.merged()), on);
        y = paintWrapped(canvas, made, x, y, w, Palette.DIM, a) + 2;
        boolean opens = false;
        for (DevClassChange change : info.classes()) {
            List<Code> rows = new ArrayList<>();
            opens |= this.addMethods(rows, mod, change, change.changed(), "~", Palette.WARN);
            opens |= this.addMethods(rows, mod, change, change.added(), "+", Palette.GOOD);
            for (String method : change.removed()) {
                rows.add(new Code("-", Palette.BAD, method, Palette.DIM, Lang.get("hotwire.dev.report.gone"),
                        Palette.FAINT, 0, null));
            }
            if (rows.isEmpty()) {
                rows.add(new Code("~", Palette.WARN, Lang.get("hotwire.dev.report.fields"), Palette.DIM, "", 0, 0,
                        null));
            }
            String file = change.file().isEmpty() ? change.name() + ".class" : change.file();
            String aside = file.startsWith(change.name() + ".") ? "" : change.name();
            y = this.paintBlock(canvas, x, y, w, file, aside, Palette.ACCENT_SOFT, rows, a, px, py, over);
        }
        List<Code> others = new ArrayList<>();
        info.addedClasses().forEach(name -> others.add(new Code("+", Palette.GOOD, name + ".class", Palette.TEXT,
                Lang.get("hotwire.dev.report.new"), Palette.FAINT, 0, null)));
        info.removedClasses().forEach(name -> others.add(new Code("-", Palette.BAD, name + ".class", Palette.DIM,
                Lang.get("hotwire.dev.report.gone"), Palette.FAINT, 0, null)));
        info.files().forEach(name -> others.add(new Code("~", Palette.WARN, name, Palette.TEXT, "", 0, 0, null)));
        if (!others.isEmpty()) {
            y = this.paintBlock(canvas, x, y, w, Lang.get("hotwire.dev.report.other"), "", Palette.FAINT,
                    cap(others), a, px, py, over);
        }
        List<String> threw = mod.throwsByBuild().get(build);
        if (threw != null && !threw.isEmpty()) {
            List<Code> rows = new ArrayList<>();
            threw.forEach(line -> rows.add(new Code("!", Palette.BAD, line, Palette.TEXT, "", 0, 0, null)));
            y = this.paintBlock(canvas, x, y, w, Lang.get("hotwire.dev.report.threw"), "", Palette.BAD, cap(rows), a,
                    px, py, over);
        } else {
            boolean ran = info.merged().isEmpty() || info.merged().contains(build);
            y = paintWrapped(canvas, Lang.get(ran ? "hotwire.dev.report.clean" : "hotwire.dev.report.not_run"), x, y,
                    w, ran ? Palette.GOOD : Palette.FAINT, a);
        }
        if (!info.notes().isEmpty()) {
            List<Code> rows = new ArrayList<>();
            info.notes().forEach(note -> rows.add(Code.plain(note, Palette.WARN)));
            y = this.paintBlock(canvas, x, y, w, Lang.get("hotwire.dev.report.notes"), "", Palette.WARN, rows, a, px,
                    py, over);
        }
        if (opens) {
            y = paintWrapped(canvas, Lang.get("hotwire.dev.report.open"), x, y, w, Palette.FAINT, a);
        }
        return y;
    }

    private boolean addMethods(List<Code> rows, DevModView mod, DevClassChange change, List<String> methods,
            String mark, int ink) {
        boolean opens = false;
        for (String method : methods) {
            List<Integer> span = change.lines().get(method);
            int first = span == null ? 0 : span.get(0);
            String aside = span == null ? "" : span.get(0).equals(span.get(1)) ? Lang.get("hotwire.dev.report.one_line")
                    : Lang.get("hotwire.dev.report.lines_long", span.get(1) - span.get(0) + 1);
            Runnable open = null;
            if (first > 0 && !change.type().isEmpty()) {
                String label = change.name() + "." + method;
                open = () -> this.host.openPlace(mod.id(), change.type(), first, label);
                opens = true;
            }
            rows.add(new Code(mark, ink, method, Palette.TEXT, aside, Palette.FAINT, first, open));
        }
        return opens;
    }

    private int paintMerge(Canvas canvas, DevModView mod, int x, int y, int w, float a, double px, double py,
            boolean over) {
        if (this.chosen.size() < 2) {
            y = paintWrapped(canvas, Lang.get("hotwire.dev.merge.pick"), x, y, w, Palette.TEXT, a);
            return paintWrapped(canvas, Lang.get("hotwire.dev.merge.how"), x, y, w, Palette.DIM, a);
        }
        BuildMerge.Plan made = this.findPlan(mod);
        if (made == null) {
            return y;
        }
        BuildMerge.Refusal refusal = made.refusal();
        if (refusal != null) {
            return paintWrapped(canvas, MergeWords.describe(refusal), x, y, w, Palette.BAD, a);
        }
        List<Integer> others = new ArrayList<>(made.builds());
        String summary;
        if (made.isFast()) {
            others.remove(Integer.valueOf(made.tip()));
            summary = Lang.get("hotwire.dev.merge.fast", made.tip(), MergeWords.list(others));
        } else {
            summary = Lang.get("hotwire.dev.merge.makes", MergeWords.list(made.builds()));
        }
        y = paintWrapped(canvas, summary, x, y, w, Palette.TEXT, a);
        y = paintWrapped(canvas, Lang.get("hotwire.dev.merge.made_on", MergeWords.describeBuild(made.parent())), x,
                y - 2, w, Palette.DIM, a);

        List<Code> files = new ArrayList<>();
        made.sources().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> files.add(new Code("~",
                Palette.WARN, MergeWords.shorten(entry.getKey()), Palette.TEXT, Lang.get("hotwire.dev.merge.from",
                entry.getValue()), Palette.FAINT, 0, null)));
        made.gone().stream().sorted().forEach(entry -> files.add(new Code("-", Palette.BAD, MergeWords.shorten(entry),
                Palette.DIM, Lang.get("hotwire.dev.merge.taken_out"), Palette.FAINT, 0, null)));
        if (!files.isEmpty()) {
            y = this.paintBlock(canvas, x, y, w, Lang.get("hotwire.dev.merge.has"),
                    Lang.get("hotwire.dev.merge.count", files.size()), Palette.ACCENT_SOFT, cap(files), a, px, py,
                    over);
        }

        List<BuildMerge.Conflict> conflicts = made.conflicts();
        for (BuildMerge.Conflict conflict : conflicts.subList(0, Math.min(MOST_CONFLICTS, conflicts.size()))) {
            List<Code> rows = new ArrayList<>();
            for (int build : conflict.builds()) {
                DevBuildInfo info = mod.infos().get(build);
                String what = info == null ? Lang.get("hotwire.dev.merge.class_changed")
                        : MergeWords.describeEntry(info, conflict.entry());
                boolean won = build == conflict.winner();
                boolean lost = conflict.dropped().contains(build);
                String aside = Lang.get(won ? "hotwire.dev.merge.kept" : lost ? "hotwire.dev.merge.dropped"
                        : "hotwire.dev.merge.inside");
                rows.add(new Code(won ? "+" : lost ? "x" : "~", won ? Palette.GOOD : lost ? Palette.BAD : Palette.DIM,
                        Lang.get("hotwire.dev.merge.build_did", build, what), won ? Palette.TEXT : Palette.DIM, aside,
                        won ? Palette.GOOD : lost ? Palette.BAD : Palette.FAINT, 0, null));
            }
            rows.add(Code.plain(Lang.get("hotwire.dev.merge.why_kept", conflict.winner(),
                    MergeWords.list(conflict.dropped())), Palette.FAINT));
            y = this.paintBlock(canvas, x, y, w, Lang.get("hotwire.dev.merge.conflict",
                    MergeWords.shorten(conflict.entry())), "", Palette.WARN, rows, a, px, py, over);
        }
        if (conflicts.size() > MOST_CONFLICTS) {
            y = paintWrapped(canvas, Lang.get("hotwire.dev.merge.more_conflicts", conflicts.size() - MOST_CONFLICTS), x,
                    y, w, Palette.WARN, a);
        }

        for (BuildMerge.LeftOut left : made.leftOut()) {
            if (left.entries().isEmpty()) {
                continue;
            }
            List<Code> rows = new ArrayList<>();
            rows.add(Code.plain(Lang.get("hotwire.dev.merge.left_why", left.build(), left.skipped().getFirst()),
                    Palette.DIM));
            left.entries().forEach(entry -> rows.add(new Code("?", Palette.FAINT, MergeWords.shorten(entry),
                    Palette.DIM, "", 0, 0, null)));
            List<Integer> skipped = new ArrayList<>(left.skipped());
            skipped.sort(null);
            String key = skipped.size() == 1 ? "hotwire.dev.merge.left_out" : "hotwire.dev.merge.left_out.many";
            y = this.paintBlock(canvas, x, y, w, Lang.get(key, MergeWords.list(skipped)), "", Palette.FAINT, cap(rows),
                    a, px, py, over);
        }

        for (int build : made.builds()) {
            if (made.removed().contains(build) || build == made.tip()) {
                continue;
            }
            String key = build == mod.runningBuild() ? "hotwire.dev.merge.stays_running"
                    : "hotwire.dev.merge.stays_pinned";
            y = paintWrapped(canvas, Lang.get(key, build), x, y, w, Palette.DIM, a);
        }
        if (!made.removed().isEmpty()) {
            String key = made.removed().size() == 1 ? "hotwire.dev.merge.goes.one" : "hotwire.dev.merge.goes";
            y = paintWrapped(canvas, Lang.get(key, MergeWords.list(List.copyOf(new TreeSet<>(made.removed())))), x, y,
                    w, conflicts.isEmpty() ? Palette.DIM : Palette.BAD, a);
        }
        return paintWrapped(canvas, Lang.get("hotwire.dev.merge.game"), x, y, w, Palette.FAINT, a);
    }

    private int paintBlock(Canvas canvas, int x, int y, int w, String title, String aside, int edge, List<Code> rows,
            float a, double px, double py, boolean over) {
        boolean gutter = rows.stream().anyMatch(row -> row.line() > 0);
        int h = HEAD_H + rows.size() * CODE_ROW + 4;
        canvas.fill(x, y, x + w, y + h, Colors.fade(Palette.PANEL_EDGE, a));
        canvas.fill(x + 1, y + 1, x + w - 1, y + h - 1, Colors.fade(CODE_BG, a));
        canvas.fill(x + 1, y + 1, x + w - 1, y + HEAD_H, Colors.fade(CODE_HEAD, a));
        canvas.fill(x + 1, y + 1, x + 3, y + h - 1, Colors.fade(edge, a));
        int asideW = aside.isEmpty() ? 0 : canvas.measureText(aside) + 8;
        canvas.drawText(canvas.clip(title, Math.max(1, w - 14 - asideW)), x + 8, y + 4, Colors.fade(Palette.TEXT, a));
        if (!aside.isEmpty()) {
            canvas.drawText(aside, x + w - 6 - canvas.measureText(aside), y + 4, Colors.fade(Palette.FAINT, a));
        }
        int textX = x + 8 + (gutter ? GUTTER : 0);
        if (gutter) {
            canvas.fill(x + 6 + GUTTER - 4, y + HEAD_H, x + 7 + GUTTER - 4, y + h - 1, Colors.fade(Palette.HAIRLINE,
                    a));
        }
        int rowY = y + HEAD_H + 2;
        for (Code row : rows) {
            Runnable open = row.open();
            boolean hot = open != null && over && px >= x + 3 && px < x + w - 1 && py >= rowY && py < rowY + CODE_ROW;
            if (hot) {
                canvas.fill(x + 3, rowY, x + w - 1, rowY + CODE_ROW, Colors.fade(HOT, a));
            }
            if (row.line() > 0) {
                String number = String.valueOf(row.line());
                canvas.drawText(number, x + 4 + GUTTER - 6 - canvas.measureText(number), rowY + 2,
                        Colors.fade(hot ? Palette.ACCENT : Palette.FAINT, a));
            }
            int at = textX;
            if (!row.mark().isEmpty()) {
                int markY = rowY + ("~".equals(row.mark()) ? 5 : 2);
                canvas.drawText(row.mark(), at, markY, Colors.fade(row.markInk(), a));
                at += 9;
            }
            int rowAsideW = row.aside().isEmpty() ? 0 : canvas.measureText(row.aside()) + 8;
            canvas.drawText(canvas.clip(row.text(), Math.max(1, x + w - 6 - rowAsideW - at)), at, rowY + 2,
                    Colors.fade(hot ? Palette.ACCENT : row.ink(), a));
            if (!row.aside().isEmpty()) {
                canvas.drawText(row.aside(), x + w - 6 - canvas.measureText(row.aside()), rowY + 2,
                        Colors.fade(row.asideInk(), a));
            }
            if (open != null) {
                this.hits.add(new Hit(x + 3, rowY, x + w - 1, rowY + CODE_ROW, open));
            }
            rowY += CODE_ROW;
        }
        return y + h + GAP;
    }

    private static List<Code> cap(List<Code> rows) {
        if (rows.size() <= MOST_ROWS) {
            return rows;
        }
        List<Code> shown = new ArrayList<>(rows.subList(0, MOST_ROWS - 1));
        shown.add(Code.plain(Lang.get("hotwire.dev.and_more", rows.size() - MOST_ROWS + 1), Palette.FAINT));
        return shown;
    }

    private int paintChip(Canvas canvas, int x, int y, String label, float a, double px, double py, boolean over,
            Runnable action) {
        int w = canvas.measureText(label) + 12;
        boolean hot = over && px >= x && px < x + w && py >= y && py < y + CHIP_H;
        canvas.fill(x, y, x + w, y + CHIP_H, Colors.fade(hot ? Palette.ACCENT_SOFT : Palette.PANEL_EDGE, a));
        canvas.fill(x + 1, y + 1, x + w - 1, y + CHIP_H - 1, Colors.fade(hot ? 0xFF2A2140 : CODE_HEAD, a));
        canvas.drawText(label, x + 6, y + 3, Colors.fade(hot ? Palette.TEXT : Palette.DIM, a));
        this.hits.add(new Hit(x, y, x + w, y + CHIP_H, action));
        return x + w + 4;
    }

    private static void paintFrame(Canvas canvas, int x, int y, int w, int h, int ink) {
        canvas.fill(x, y, x + w, y + 1, ink);
        canvas.fill(x, y + h - 1, x + w, y + h, ink);
        canvas.fill(x, y, x + 1, y + h, ink);
        canvas.fill(x + w - 1, y, x + w, y + h, ink);
    }

    private static void paintPin(Canvas canvas, int x, int y, int ink) {
        canvas.fill(x + 1, y, x + 4, y + 1, ink);
        canvas.fill(x, y + 1, x + 5, y + 4, ink);
        canvas.fill(x + 2, y + 4, x + 3, y + 9, ink);
    }

    private static int paintWrapped(Canvas canvas, String text, int x, int y, int w, int color, float a) {
        for (String line : DevWindow.wrap(canvas, text, w)) {
            canvas.drawText(line, x, y, Colors.fade(color, a));
            y += LINE;
        }
        return y + GAP;
    }
}
