package dev.moon.hotwire.ui;

import dev.moon.hotwire.dev.BuildMerge;
import dev.moon.hotwire.dev.DevBuildInfo;
import dev.moon.hotwire.dev.DevCompare;
import dev.moon.hotwire.dev.DevHost;
import dev.moon.hotwire.dev.DevModView;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

final class BuildPicker {
    static final int TRY = 0;
    static final int RESTORE = 1;
    static final int COMPARE = 2;

    static final int NEWEST = -1;
    private static final int SIDE_BY_SIDE = 330;
    private static final int TIGHT = 240;
    private static final int ROW = 18;
    private static final int STACKED_ROWS = 4;
    private static final int COLUMN = 9;

    private static final long PINNED_WAIT_NANOS = 3_000_000_000L;
    private static final String PIN = "dev_pin";

    interface Opener {
        void open(int build, int mode);
    }

    private record BuildRow(int build, TreeRow tree, int columns, String number, String name, boolean pinned,
            boolean running, String tag, String date, int dateInk, String size, int sizeInk, boolean ticking,
            boolean tickable) {
    }

    final Part part;
    private final DevHost host;
    private final Runnable leave;
    private final Tabs modes = Kit.tabs(Tabs.Look.BOX, this::chooseMode).tab(Lang.get("hotwire.dev.mode.try"))
            .tab(Lang.get("hotwire.dev.mode.restore")).tab(Lang.get("hotwire.dev.mode.compare"));
    private final Text title = Kit.dim("").wrap();
    private final Rows<BuildRow> rows = new Rows<BuildRow>(row -> String.valueOf(row.build()), this::makeRow, ROW,
            0).mark(row -> this.isTicking() ? this.chosen.contains(row.build()) : row.build() == this.picked);
    private final Line report = Kit.column().gap(4);
    private final Scroll scroll = Kit.scroll(this.report);
    private final Line holder = Kit.column();
    private final Push confirm = Kit.lead("", this::confirm);
    private final Push merge = Kit.push(Lang.get("hotwire.dev.button.merge"), this::startMerge);
    private final Push manage = Kit.push(Lang.get("hotwire.dev.button.manage"), this::startManage);
    private final Push cancel = Kit.push(Lang.get("hotwire.dev.button.cancel"), this::cancel);
    private final TextField naming = new TextField(Lang.get("hotwire.dev.name_hint"), text -> { },
            this::finishNaming, this::cancel);
    private final Part namingRow = Kit.fit(this.naming).height(Push.HEIGHT).grow(1);
    private final BuildReport single;
    private final SortedSet<Integer> chosen = new TreeSet<>();
    private @Nullable DevModView mod;
    private int width;
    private boolean narrow;
    private int picked = NEWEST;
    private boolean merging;
    private boolean managing;
    private boolean renaming;

    private @Nullable Set<Integer> before;

    private BuildMerge.@Nullable Plan plan;
    private @Nullable DevModView planFor;
    private List<Integer> planOf = List.of();

    private int warnedFor = NEWEST;

    private long clearAt;
    private boolean side;
    private int listWidth;
    private @Nullable Object shown;
    private @Nullable Object risen;

    BuildPicker(DevHost host, Runnable leave) {
        this.host = host;
        this.leave = leave;
        this.single = new BuildReport(this, host);
        this.rows.grow(0);
        this.holder.grow(1);
        this.confirm.width(0).grow(1);
        this.part = Kit.column(Kit.row(this.modes), this.title, this.holder, Kit.rule(),
                Kit.row(this.namingRow, this.confirm, this.merge, this.manage, this.cancel).gap(6)).gap(6).pad(8, 8);
    }

    boolean isTicking() {
        return this.merging || this.managing;
    }

    boolean isRestoring() {
        return this.modes.getChosen() == RESTORE;
    }

    boolean isSettingKey() {
        return this.single.compare.isSettingKey();
    }

    void takeKey(int key, int escape) {
        this.single.compare.takeKey(key, escape);
    }

    SortedSet<Integer> getChosen() {
        return this.chosen;
    }

    int getPicked() {
        return this.picked;
    }

    void reset() {
        this.merging = false;
        this.managing = false;
        this.renaming = false;
        this.clearAt = 0;
        this.warnedFor = NEWEST;
        this.chosen.clear();
        this.before = null;
        this.single.reset();
    }

    void open(DevModView mod, int build, int mode) {
        this.reset();
        this.picked = mod.hasBuild(build) ? build : findFirstPick(mod);
        this.modes.choose(mode);
        this.rows.scrollToTop();
    }

    void chooseMode(int mode) {
        this.modes.choose(mode);
        this.warnedFor = NEWEST;
        this.refresh();
    }

    static int findFirstPick(DevModView mod) {
        Integer parent = mod.parents().get(mod.runningBuild());
        if (parent != null && parent != mod.runningBuild() && (parent == 0 || mod.savedBuilds().contains(parent))) {
            return parent;
        }
        for (int build : mod.savedBuilds()) {
            if (build != mod.runningBuild()) {
                return build;
            }
        }
        return 0;
    }

    @Nullable String findOtherBranch(DevModView mod, int build) {
        DevBuildInfo info = mod.infos().get(build);
        String made = info == null ? "" : DevBuildInfo.findBranchOf(info.git());
        String now = this.host.findBranch(mod.id());
        return made.isEmpty() || now.isEmpty() || made.equals(now) ? null : made;
    }

    static String findName(DevModView mod, int build) {
        String name = mod.names().get(build);
        if (name != null) {
            return name;
        }
        return build == 0 ? Lang.get("hotwire.dev.pick.start.short") : Lang.get("hotwire.dev.pick.build", build);
    }

    void cancel() {
        if (this.renaming) {
            this.renaming = false;
        } else if (this.isTicking()) {
            this.merging = false;
            this.managing = false;
            this.chosen.clear();
            this.clearAt = 0;
        } else {
            this.leave.run();
        }
        this.refresh();
    }

    void startMerge() {
        this.managing = false;
        this.merging = true;
        this.renaming = false;
        this.chosen.clear();
        if (this.picked > 0) {
            this.chosen.add(this.picked);
        }
        this.refresh();
    }

    void startManage() {
        this.managing = true;
        this.merging = false;
        this.renaming = false;
        this.chosen.clear();
        this.clearAt = 0;
        this.refresh();
    }

    void pick(int build) {
        DevModView shownMod = this.mod;
        if (!this.isTicking()) {
            this.picked = build;
        } else if (build != 0 && (this.merging || (shownMod != null && BuildManage.canClear(shownMod, build)))) {
            if (!this.chosen.remove(build)) {
                this.chosen.add(build);
            }
            this.clearAt = 0;
        }
        this.refresh();
    }

    void tickOnly(List<Integer> builds) {
        this.chosen.clear();
        this.chosen.addAll(builds);
        this.clearAt = 0;
        this.refresh();
    }

    void pin() {
        DevModView shownMod = this.mod;
        if (shownMod != null) {
            this.host.pin(shownMod.id(), this.picked, !shownMod.pinned().contains(this.picked));
        }
    }

    void copy() {
        DevModView shownMod = this.mod;
        if (shownMod != null && this.picked != 0) {
            this.before = new HashSet<>(shownMod.findBuilds());
            this.host.copyBuild(shownMod.id(), this.picked);
        }
    }

    void startNaming() {
        DevModView shownMod = this.mod;
        if (shownMod != null) {
            this.renaming = true;
            this.naming.setText(shownMod.names().getOrDefault(this.picked, ""));
            this.refresh();
        }
    }

    private void finishNaming() {
        DevModView shownMod = this.mod;
        if (shownMod != null && this.renaming) {
            this.host.rename(shownMod.id(), this.picked, this.naming.getText());
        }
        this.renaming = false;
        this.refresh();
    }

    @SuppressWarnings("ReferenceEquality")
    BuildMerge.@Nullable Plan findPlan(DevModView mod) {
        if (this.chosen.size() < 2) {
            return null;
        }
        List<Integer> of = List.copyOf(this.chosen);
        if (this.plan == null || this.planFor != mod || !this.planOf.equals(of)) {
            Set<Integer> there = new HashSet<>(mod.savedBuilds());
            there.add(mod.runningBuild());
            there.add(0);
            this.plan = BuildMerge.plan(of, mod.parents(), mod.deltas(), there, mod.runningBuild(), mod.pinned());
            this.planFor = mod;
            this.planOf = of;
        }
        return this.plan;
    }

    private boolean hasPinnedTicked(DevModView mod) {
        return this.chosen.stream().anyMatch(mod.pinned()::contains);
    }

    void confirm() {
        DevModView shownMod = this.mod;
        if (shownMod == null) {
            return;
        }
        String id = shownMod.id();
        if (this.managing) {
            long now = System.nanoTime();
            if (this.chosen.isEmpty()) {
                return;
            }
            if (this.hasPinnedTicked(shownMod) && (this.clearAt == 0 || now - this.clearAt < 0)) {
                this.clearAt = this.clearAt == 0 ? now + PINNED_WAIT_NANOS : this.clearAt;
            } else {
                this.host.clear(id, List.copyOf(this.chosen));
                this.chosen.clear();
                this.clearAt = 0;
            }
        } else if (this.merging) {
            BuildMerge.Plan made = this.findPlan(shownMod);
            if (made == null || made.refusal() != null) {
                return;
            }
            this.host.merge(id, List.copyOf(this.chosen), !made.conflicts().isEmpty());
            this.merging = false;
            this.chosen.clear();
            if (made.isFast()) {
                this.picked = made.tip();
            } else {
                this.before = new HashSet<>(shownMod.findBuilds());
            }
        } else if (this.modes.getChosen() == COMPARE) {
            if (this.picked != shownMod.runningBuild() && shownMod.hasBuild(this.picked)) {
                this.host.startCompare(id, shownMod.runningBuild(), this.picked);
            }
        } else if (this.isRestoring()) {
            if (!shownMod.hasBuild(this.picked) || this.picked == shownMod.codeBuild()) {
                return;
            }

            if (this.findOtherBranch(shownMod, this.picked) != null && this.warnedFor != this.picked) {
                this.warnedFor = this.picked;
            } else {
                this.warnedFor = NEWEST;
                this.host.restore(id, this.picked);
            }
        } else if (this.picked != shownMod.runningBuild() && shownMod.savedBuilds().contains(this.picked)) {
            this.host.revertTo(id, this.picked);
        }
        this.refresh();
    }

    private boolean isConfirmActive(DevModView mod) {
        if (this.managing) {
            return !this.chosen.isEmpty() && (this.clearAt == 0 || System.nanoTime() - this.clearAt >= 0);
        }
        if (this.merging) {
            BuildMerge.Plan made = this.findPlan(mod);
            return made != null && made.refusal() == null;
        }
        if (this.modes.getChosen() == COMPARE) {
            DevCompare running = mod.compare();
            return this.picked != mod.runningBuild() && mod.hasBuild(this.picked)
                    && (running == null || running.done());
        }
        if (this.isRestoring()) {
            return mod.hasBuild(this.picked) && this.picked != mod.codeBuild();
        }
        return this.picked != mod.runningBuild() && mod.savedBuilds().contains(this.picked);
    }

    private String describeClear(DevModView mod) {
        String size = CacheFill.describeBytes(BuildManage.sumSizes(mod, this.chosen));
        if (this.chosen.isEmpty()) {
            return Lang.get("hotwire.dev.manage.clear_none");
        }
        if (!this.hasPinnedTicked(mod)) {
            return this.chosen.size() == 1 ? Lang.get("hotwire.dev.manage.clear.one", size)
                    : Lang.get("hotwire.dev.manage.clear", this.chosen.size(), size);
        }
        long left = this.clearAt - System.nanoTime();
        if (this.clearAt != 0 && left > 0) {
            return Lang.get("hotwire.dev.manage.wait", (int) Math.ceil(left / 1e9));
        }
        return Lang.get(this.clearAt != 0 ? "hotwire.dev.manage.clear_anyway" : "hotwire.dev.manage.clear_pinned",
                this.chosen.size(), size);
    }

    private String describeConfirm(DevModView mod) {
        if (this.managing) {
            return this.narrow ? Lang.get("hotwire.dev.manage.clear.short") : this.describeClear(mod);
        }
        if (this.merging) {
            BuildMerge.Plan made = this.findPlan(mod);
            if (this.narrow || made == null || made.refusal() != null) {
                return Lang.get("hotwire.dev.button.merge");
            }
            return made.conflicts().isEmpty() ? Lang.get("hotwire.dev.button.merge_these", this.chosen.size())
                    : Lang.get("hotwire.dev.button.merge_anyway");
        }
        if (this.modes.getChosen() == COMPARE) {
            return this.narrow ? Lang.get("hotwire.dev.mode.compare")
                    : Lang.get("hotwire.dev.button.compare", mod.runningBuild(), this.picked);
        }
        if (!this.isRestoring()) {
            if (this.narrow) {
                return Lang.get("hotwire.dev.mode.try");
            }
            return this.picked == 0 ? Lang.get("hotwire.dev.button.try.start")
                    : Lang.get("hotwire.dev.button.try", this.picked);
        }
        if (this.narrow) {
            return Lang.get("hotwire.dev.mode.restore");
        }
        if (this.warnedFor == this.picked && this.findOtherBranch(mod, this.picked) != null) {
            return Lang.get("hotwire.dev.button.restore.anyway", this.picked);
        }
        return this.picked == 0 ? Lang.get("hotwire.dev.button.restore.start")
                : Lang.get("hotwire.dev.button.restore", this.picked);
    }

    void refresh() {
        DevModView shownMod = this.mod;
        if (shownMod != null) {
            this.show(shownMod, this.width, this.narrow);
        }
    }

    void show(DevModView mod, int width, boolean narrow) {
        this.mod = mod;
        this.width = width;
        this.narrow = narrow;
        this.chosen.removeIf(build -> !mod.hasBuild(build));
        List<Integer> builds = mod.findBuilds();
        Set<Integer> waited = this.before;
        if (waited != null && !waited.containsAll(builds)) {
            this.picked = builds.stream().filter(build -> !waited.contains(build)).findFirst().orElse(this.picked);
            this.before = null;
        }
        if (!mod.hasBuild(this.picked)) {
            this.picked = findFirstPick(mod);
        }
        boolean beside = width >= SIDE_BY_SIDE;
        int list = beside ? Math.clamp((width - 16) * 2 / 5, 120, 220) : Math.min(builds.size(), STACKED_ROWS) * ROW;
        if (beside != this.side || list != this.listWidth || this.holder.isEmpty()) {
            this.side = beside;
            this.listWidth = list;
            this.rows.width(beside ? list : Part.HUG).height(beside ? Part.HUG : list);
            this.holder.clear().with((beside ? Kit.row(this.rows, this.scroll).gap(8).cross(Line.Cross.STRETCH)
                    : Kit.column(this.rows, this.scroll).gap(6)).grow(1));
        }
        boolean ticking = this.isTicking();
        this.modes.show(!ticking);
        if (this.merging) {
            this.title.set(Lang.get("hotwire.dev.merge.title"));
        } else if (this.managing) {
            this.title.set(Lang.get("hotwire.dev.manage.title", builds.size(), CacheFill.describeBytes(
                    BuildManage.sumSizes(mod, builds))));
        } else {
            this.title.set(Lang.get("hotwire.dev.pick.title", mod.runningBuild()));
        }
        this.namingRow.show(this.renaming);
        this.confirm.label(this.describeConfirm(mod)).active(this.isConfirmActive(mod))
                .ink(this.managing ? Palette.BAD : Palette.ACCENT).show(!this.renaming);
        this.merge.show(!this.renaming && !ticking);
        this.manage.show(!this.renaming && !ticking);
        this.cancel.show(!this.renaming && ticking);
        boolean tight = width - 22 - (beside ? list + 8 : 0) < TIGHT;
        Object key = List.of(mod.withoutCompare(), this.picked, this.modes.getChosen(), this.merging, this.managing,
                List.copyOf(this.chosen), this.single.getShownClass(), this.host.getExtras(),
                this.host.findBranch(mod.id()), tight);
        if (!key.equals(this.shown)) {
            this.shown = key;
            this.fill(mod, builds, tight);
        }
        this.single.compare.show(mod, this.picked);
    }

    private void fill(DevModView mod, List<Integer> builds, boolean tight) {
        List<TreeRow> tree = BuildTree.lay(builds, mod.parents());
        int columns = tree.stream().mapToInt(TreeRow::width).max().orElse(1);
        long now = System.currentTimeMillis();
        boolean sized = this.managing || this.host.getExtras().sizes();
        List<BuildRow> made = new ArrayList<>();
        for (int at = 0; at < builds.size(); at++) {
            int build = builds.get(at);
            boolean running = build == mod.runningBuild();
            String tag = "";
            if (running) {
                tag = Lang.get("hotwire.dev.pick.runs");
            } else if (build > mod.runningBuild()) {
                tag = Lang.get("hotwire.dev.pick.newer.short");
            }
            Long size = mod.sizes().get(build);
            DevBuildInfo info = mod.infos().get(build);
            made.add(new BuildRow(build, tree.get(at), columns, mod.names().containsKey(build) ? "#" + build : "",
                    findName(mod, build), mod.pinned().contains(build), running, tag,
                    info == null ? "" : BuildManage.describeShortDate(info.at(), now, ZoneId.systemDefault(),
                            Locale.getDefault()),
                    info == null ? 0 : BuildManage.findColor(BuildManage.findAge(info.at(), now)),
                    size == null || !sized ? "" : CacheFill.describeBytes(size),
                    size == null ? 0 : BuildManage.findColor(BuildManage.findWeight(size, mod.sizes().values())),
                    this.isTicking(), build != 0 && (this.merging || BuildManage.canClear(mod, build))));
        }
        this.rows.set(made);
        List<Part> parts;
        if (this.managing) {
            parts = ManagePane.make(this, this.host, mod);
        } else if (this.merging) {
            parts = MergePane.make(mod, this.findPlan(mod));
        } else {
            parts = this.single.make(mod, this.picked, tight);
        }
        this.report.clear().with(parts.toArray(Part[]::new));

        Object choice = List.of(this.picked, this.merging, this.managing);
        if (!choice.equals(this.risen)) {
            this.risen = choice;
            this.report.rise();
            this.scroll.reset();
        }
    }

    private Part makeRow(BuildRow row) {
        int build = row.build();
        Line line = Kit.row(new TreeCell(row.tree(), row.columns(), row.running())).gap(5);
        if (row.ticking() && row.tickable()) {
            line.with(Kit.check(() -> this.chosen.contains(build), () -> this.pick(build)));
        } else if (row.ticking()) {
            line.with(new Part().width(Kit.Check.SIDE));
        }
        if (!row.number().isEmpty()) {
            line.with(Kit.text(row.number(), Palette.FAINT));
        }
        line.with(Kit.text(row.name()).grow(1));
        if (row.pinned()) {
            line.with(Kit.icon(PIN, 9, Palette.WARN));
        }

        if (!row.size().isEmpty()) {
            line.with(Kit.text(row.size(), row.sizeInk()).spare());
        }
        if (!row.date().isEmpty()) {
            line.with(Kit.text(row.date(), row.dateInk()).spare());
        }
        if (!row.tag().isEmpty()) {
            line.with(Kit.text(row.tag(), row.running() ? Palette.GOOD : Palette.FAINT).spare());
        }
        return line.press((button, modifiers) -> this.pick(build));
    }

    private static final class TreeCell extends Part {
        private final int column;
        private final boolean fromAbove;
        private final boolean goesOn;
        private final int[] joined;
        private final int[] passing;
        private final int dot;

        TreeCell(TreeRow row, int columns, boolean running) {
            this.column = row.column();
            this.fromAbove = row.fromAbove();
            this.goesOn = row.goesOn();
            this.joined = row.joined().stream().mapToInt(Integer::intValue).toArray();
            this.passing = row.passing().stream().mapToInt(Integer::intValue).toArray();
            this.dot = running ? Palette.GOOD : Palette.DIM;
            this.width(columns * COLUMN + 4).height(ROW);
        }

        @Override
        protected void paint(Canvas canvas, float alpha) {
            int x = this.getX();
            int top = this.getY();
            int bottom = top + this.getHeight();
            int ink = Colors.fade(Palette.DIM, alpha * 0.55f);
            int dotY = top + 6;
            int dotX = x + this.column * COLUMN + 2;
            for (int other : this.passing) {
                canvas.fill(x + other * COLUMN + 4, top, x + other * COLUMN + 5, bottom, ink);
            }
            if (this.fromAbove) {
                canvas.fill(dotX + 2, top, dotX + 3, dotY, ink);
            }
            if (this.goesOn) {
                canvas.fill(dotX + 2, dotY, dotX + 3, bottom, ink);
            }
            for (int other : this.joined) {
                int lineX = x + other * COLUMN + 4;
                canvas.fill(lineX, top, lineX + 1, dotY + 2, ink);
                canvas.fill(Math.min(lineX, dotX + 2), dotY + 2, Math.max(lineX + 1, dotX + 3), dotY + 3, ink);
            }
            canvas.fill(dotX, dotY, dotX + 5, dotY + 5, Colors.fade(this.dot, alpha));
        }

        @Override
        protected boolean takesPointer() {
            return false;
        }
    }
}
