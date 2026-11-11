package dev.moon.hotwire.ui;

import dev.moon.hotwire.dev.DevBuildInfo;
import dev.moon.hotwire.dev.DevClassChange;
import dev.moon.hotwire.dev.DevCompare;
import dev.moon.hotwire.dev.DevHost;
import dev.moon.hotwire.dev.DevModView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.jspecify.annotations.Nullable;

final class DevOverview {
    final Part part;
    private final DevHost host;
    private final String runtime;
    private final boolean fullRuntime;
    private final BuildPicker.Opener opener;
    private final Line cards = DevCards.makeList();
    private final Push state = Kit.lead("", this::actOnState);
    private final Push back = Kit.push("", () -> this.act("back"));
    private final Push restore = Kit.push(Lang.get("hotwire.dev.overview.restore"), () -> this.act("restore"));
    private final Push ab = Kit.push("", () -> this.act("ab"));
    private final Push switchTo = Kit.push("", () -> this.act("switch"));
    private final Push stop = Kit.push(Lang.get("hotwire.dev.compare.stop"), () -> this.act("stop"));
    private final Line next = Kit.row(this.back, this.restore, this.ab, this.switchTo, this.stop).gap(4);
    private final Push reload = Kit.push("", () -> this.act("reload"));
    private final Push restart = Kit.push("", () -> this.act("restart"));
    private final Push rollBack = Kit.push("", () -> this.act("rollback"));
    private final Push pause = Kit.push("", this::togglePause);

    private final Map<String, Push> named = Map.of("back", this.back, "restore", this.restore, "ab", this.ab,
            "switch", this.switchTo, "stop", this.stop, "rollback", this.rollBack);
    private boolean mcpInstalled;
    private Runnable getMcp = () -> { };
    private @Nullable DevModView mod;
    private int running;
    private int before;
    private @Nullable Object shown;

    DevOverview(DevHost host, String runtime, boolean fullRuntime, BuildPicker.Opener opener) {
        this.host = host;
        this.runtime = runtime;
        this.fullRuntime = fullRuntime;
        this.opener = opener;
        for (Push chip : List.of(this.back, this.restore, this.ab, this.switchTo, this.stop)) {
            chip.height(CodeBlock.CHIP);
        }

        for (Push button : List.of(this.state, this.reload, this.restart, this.rollBack, this.pause)) {
            button.width(0).grow(1);
        }
        this.part = Kit.column(Kit.scroll(this.cards), Kit.rule(), Kit.row(this.reload, this.restart, this.rollBack,
                this.pause).gap(6).pad(8, 6));
    }

    void setMcp(boolean installed, Runnable getIt) {
        this.mcpInstalled = installed;
        this.getMcp = getIt;
    }

    boolean press(String name) {
        Push found = this.named.get(name);
        return found != null && found.press();
    }

    private void act(String name) {
        DevModView shownMod = this.mod;
        if (shownMod == null) {
            return;
        }
        String id = shownMod.id();
        switch (name) {
            case "back" -> this.host.revertTo(id, this.before);
            case "ab" -> this.host.startCompare(id, this.running, this.before);
            case "switch" -> this.host.switchCompare(id);
            case "stop" -> this.host.stopCompare(id);
            case "reload" -> this.host.reloadNow(id);
            case "restart" -> this.host.restartMod(id);
            case "restore" -> this.opener.open(BuildPicker.NEWEST, BuildPicker.RESTORE);
            default -> this.opener.open(BuildPicker.NEWEST, BuildPicker.TRY);
        }
    }

    private void togglePause() {
        this.host.setWatching(!this.host.isWatching());
    }

    private void actOnState() {
        DevModView shownMod = this.mod;
        if (shownMod == null) {
            return;
        }
        DevCompare compare = shownMod.compare();
        if (compare != null && !compare.done()) {
            this.host.stopCompare(shownMod.id());
        } else if (shownMod.isTrying()) {
            this.host.revertTo(shownMod.id(), shownMod.codeBuild());
        } else if (shownMod.canUndo()) {
            this.host.restoreSavePoint(shownMod.id());
        }
    }

    private static OptionalInt findBuildBefore(DevModView mod) {
        Integer parent = mod.parents().get(mod.runningBuild());
        if (parent != null && (parent == 0 || mod.savedBuilds().contains(parent)) && parent != mod.runningBuild()) {
            return OptionalInt.of(parent);
        }
        return mod.savedBuilds().stream().mapToInt(Integer::intValue).filter(build -> build != mod.runningBuild())
                .findFirst();
    }

    void show(DevModView mod, boolean narrow) {
        this.mod = mod;
        this.running = mod.runningBuild();
        DevCompare compare = mod.compare();
        boolean comparing = compare != null && !compare.done();
        OptionalInt found = findBuildBefore(mod);
        this.before = found.orElse(0);
        boolean away = mod.isTrying() || mod.canUndo();
        boolean saved = !mod.savedBuilds().isEmpty();
        this.switchTo.label(Lang.get("hotwire.dev.compare.switch", compare == null ? 0 : compare.findOther()))
                .show(comparing);
        this.stop.show(comparing);
        this.back.label(Lang.get("hotwire.dev.overview.back", this.before))
                .show(!comparing && !away && found.isPresent());
        this.restore.show(!comparing && saved);
        this.ab.label(Lang.get("hotwire.dev.overview.ab", this.before)).show(!comparing && found.isPresent());
        this.next.show(comparing || saved || found.isPresent());
        this.state.label(describeState(mod, narrow)).show(away);
        this.reload.label(Lang.get(narrow ? "hotwire.dev.button.reload.short" : "hotwire.dev.button.reload"));
        this.restart.label(Lang.get(narrow ? "hotwire.dev.button.restart.short" : "hotwire.dev.button.restart"));
        this.rollBack.label(Lang.get(narrow ? "hotwire.dev.button.back.short" : "hotwire.dev.button.back"))
                .show(saved);
        if (this.host.isWatching()) {
            this.pause.label(Lang.get(narrow ? "hotwire.dev.button.pause.short" : "hotwire.dev.button.pause"));
        } else {
            this.pause.label(Lang.get(narrow ? "hotwire.dev.button.resume.short" : "hotwire.dev.button.resume"));
        }
        String built = this.running == 0 ? "" : DevCards.describeAgo(mod.builtAt());
        String threw = mod.crashes().isEmpty() ? "" : DevCards.describeAgo(mod.crashes().getFirst().lastAt());
        Object key = List.of(mod.withoutCompare(), built, threw, this.mcpInstalled);
        if (!key.equals(this.shown)) {
            this.shown = key;
            this.fill(mod, built, threw);
        }
    }

    private static String describeState(DevModView mod, boolean narrow) {
        DevCompare compare = mod.compare();
        if (compare != null && !compare.done()) {
            return narrow ? Lang.get("hotwire.dev.compare.stop")
                    : Lang.get("hotwire.dev.compare.bar", compare.a(), compare.b(), mod.codeBuild());
        }
        if (mod.isTrying()) {
            return narrow ? Lang.get("hotwire.dev.trying.short", mod.codeBuild())
                    : Lang.get("hotwire.dev.trying", mod.runningBuild(), mod.codeBuild());
        }
        return narrow ? Lang.get("hotwire.dev.undo_restore.short")
                : Lang.get("hotwire.dev.undo_restore", mod.codeBuild());
    }

    private void fill(DevModView mod, String built, String threw) {
        DevBuildInfo info = mod.infos().get(this.running);
        String given = mod.names().get(this.running);
        String title = Lang.get("hotwire.dev.overview.running", this.running);
        if (this.running == 0) {
            title = Lang.get("hotwire.dev.window.started_with");
        } else if (given != null) {
            title = Lang.get("hotwire.dev.overview.named", this.running, given);
        }
        List<String> lines = new ArrayList<>();
        if (info != null && !info.git().isEmpty()) {
            lines.add(info.uncommitted() ? Lang.get("hotwire.dev.overview.git_changes", info.git()) : info.git());
        }
        if (!mod.line().isEmpty()) {
            lines.add(mod.line());
        }
        this.cards.clear().with(DevCards.makeCard(DevCards.findColor(mod.state()), title, built, Palette.DIM, lines),
                this.state, this.next);
        List<CodeBlock.Code> rows = info == null || this.running == 0 ? List.of() : makeChanges(info);
        if (info != null && !rows.isEmpty()) {
            this.cards.with(CodeBlock.makeBlock(Lang.get("hotwire.dev.overview.changed", info.madeOn()), "",
                    Palette.ACCENT_SOFT, rows, false));
        }
        if (!mod.crashes().isEmpty()) {
            this.cards.with(DevCards.makeCrash(this.host, mod.id(), mod.crashes().getFirst(), threw));
        }
        if (!this.fullRuntime) {
            this.cards.with(DevCards.makeCard(Palette.OFF_TINT, Lang.get("hotwire.dev.window.runtime"), "",
                    Palette.OFF_TINT, List.of(this.runtime)));
        }

        if (!this.mcpInstalled) {
            this.cards.with(DevCards.makeCard(Palette.ACCENT, Lang.get("hotwire.dev.mcp.get"), "", Palette.DIM,
                    List.of(Lang.get("hotwire.dev.mcp.get.line"))).press((button, modifiers) -> this.getMcp.run()));
        }
    }

    private static List<CodeBlock.Code> makeChanges(DevBuildInfo info) {
        List<CodeBlock.Code> rows = new ArrayList<>();
        for (DevClassChange change : info.classes()) {
            String type = change.name() + ".";
            change.changed().forEach(method -> rows.add(CodeBlock.Code.ofMark("~", Palette.WARN, type + method,
                    Palette.TEXT, "")));
            change.added().forEach(method -> rows.add(CodeBlock.Code.ofMark("+", Palette.GOOD, type + method,
                    Palette.TEXT, "")));
            change.removed().forEach(method -> rows.add(CodeBlock.Code.ofMark("-", Palette.BAD, type + method,
                    Palette.DIM, "")));
        }
        info.addedClasses().forEach(added -> rows.add(CodeBlock.Code.ofMark("+", Palette.GOOD, added, Palette.TEXT,
                Lang.get("hotwire.dev.overview.new_class"))));
        info.removedClasses().forEach(gone -> rows.add(CodeBlock.Code.ofMark("-", Palette.BAD, gone, Palette.DIM,
                Lang.get("hotwire.dev.overview.gone_class"))));
        if (!info.files().isEmpty()) {
            rows.add(CodeBlock.Code.ofMark("~", Palette.WARN, Lang.get("hotwire.dev.overview.files",
                    info.files().size()), Palette.DIM, ""));
        }
        return rows;
    }
}
