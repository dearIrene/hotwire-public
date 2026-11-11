package dev.moon.hotwire.ui;

import dev.moon.hotwire.dev.DevBuildInfo;
import dev.moon.hotwire.dev.DevClassChange;
import dev.moon.hotwire.dev.DevHost;
import dev.moon.hotwire.dev.DevModView;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

final class BuildReport {
    final ComparePane compare;
    private final BuildPicker picker;
    private final DevHost host;

    private @Nullable String shownClass;

    private final Map<String, @Nullable String> addedCode = new HashMap<>();

    BuildReport(BuildPicker picker, DevHost host) {
        this.picker = picker;
        this.host = host;
        this.compare = new ComparePane(host);
    }

    void reset() {
        this.shownClass = null;

        this.addedCode.clear();
        this.compare.reset();
    }

    String getShownClass() {
        return String.valueOf(this.shownClass);
    }

    List<Part> make(DevModView mod, int build, boolean tight) {
        List<Part> parts = new ArrayList<>();
        String given = mod.names().get(build);
        String heading = given;
        if (heading == null) {
            heading = build == 0 ? Lang.get("hotwire.dev.pick.start") : Lang.get("hotwire.dev.pick.build", build);
        }
        parts.add(Kit.row(Kit.text(given == null ? "" : "#" + build, Palette.FAINT), Kit.text(heading).grow(1))
                .gap(given == null ? 0 : 5));
        parts.add(this.compare.part);
        Line status = Kit.row().gap(8);
        if (build == mod.runningBuild()) {
            status.with(Kit.text(Lang.get("hotwire.dev.report.running"), Palette.GOOD));
        } else if (build > mod.runningBuild()) {
            status.with(Kit.text(Lang.get("hotwire.dev.pick.newer"), Palette.FAINT));
        }
        boolean pinned = mod.pinned().contains(build);
        if (pinned) {
            status.with(Kit.text(Lang.get("hotwire.dev.report.pinned"), Palette.WARN));
        }
        if (!status.isEmpty()) {
            parts.add(status.cross(Line.Cross.START));
        }
        DevBuildInfo info = mod.infos().get(build);
        if (info != null && !info.git().isEmpty()) {
            parts.add(info.uncommitted() ? DevCards.makeLine(Lang.get("hotwire.dev.overview.git_changes", info.git()),
                    Palette.WARN) : DevCards.makeLine(info.git(), Palette.FAINT));
        }
        Part pin = CodeBlock.makeChip(Lang.get(pinned ? "hotwire.dev.chip.unpin" : "hotwire.dev.chip.pin"),
                this.picker::pin);
        Part rename = CodeBlock.makeChip(Lang.get("hotwire.dev.chip.rename"), this.picker::startNaming);
        Part copy = CodeBlock.makeChip(Lang.get("hotwire.dev.chip.copy"), this.picker::copy).show(build != 0);
        Part save = CodeBlock.makeChip(Lang.get("hotwire.dev.chip.save_jar"),
                () -> this.host.saveJar(mod.id(), build));
        if (tight) {
            parts.add(Kit.row(pin, rename).gap(4));
            parts.add(Kit.row(copy, save).gap(4));
        } else {
            parts.add(Kit.row(pin, rename, copy, save).gap(4));
        }
        if (info == null) {
            parts.add(DevCards.makeLine(Lang.get(build == 0 ? "hotwire.dev.report.first" : "hotwire.dev.report.none"),
                    Palette.DIM));
            return parts;
        }
        String on = MergeWords.describeBuild(info.madeOn());
        parts.add(DevCards.makeLine(info.merged().isEmpty() ? Lang.get("hotwire.dev.report.made", on,
                BuildManage.describeDate(info.at(), ZoneId.systemDefault(), Locale.getDefault(), true))
                : Lang.get("hotwire.dev.report.merged_made", MergeWords.list(info.merged()), on), Palette.DIM));
        String other = this.picker.isRestoring() ? this.picker.findOtherBranch(mod, build) : null;
        if (other != null) {
            parts.add(DevCards.makeLine(Lang.get("hotwire.dev.restore.other_branch", other,
                    this.host.findBranch(mod.id())), Palette.WARN));
        }
        this.addClasses(parts, mod, build, info, tight);
        List<CodeBlock.Code> others = new ArrayList<>();
        info.addedClasses().forEach(name -> others.add(CodeBlock.Code.ofMark("+", Palette.GOOD, name + ".class",
                Palette.TEXT, Lang.get("hotwire.dev.report.new"))));
        info.removedClasses().forEach(name -> others.add(CodeBlock.Code.ofMark("-", Palette.BAD, name + ".class",
                Palette.DIM, Lang.get("hotwire.dev.report.gone"))));
        info.files().forEach(name -> others.add(CodeBlock.Code.ofMark("~", Palette.WARN, name, Palette.TEXT, "")));
        if (!others.isEmpty()) {
            parts.add(CodeBlock.makeBlock(Lang.get("hotwire.dev.report.other"), "", Palette.FAINT,
                    CodeBlock.limitRows(others), false));
        }
        List<String> threw = mod.throwsByBuild().get(build);
        if (threw != null && !threw.isEmpty()) {
            List<CodeBlock.Code> rows = new ArrayList<>();
            threw.forEach(line -> rows.add(CodeBlock.Code.ofMark("!", Palette.BAD, line, Palette.TEXT, "")));
            parts.add(CodeBlock.makeBlock(Lang.get("hotwire.dev.report.threw"), "", Palette.BAD,
                    CodeBlock.limitRows(rows), false));
        } else {
            boolean ran = info.merged().isEmpty() || info.merged().contains(build);
            parts.add(DevCards.makeLine(Lang.get(ran ? "hotwire.dev.report.clean" : "hotwire.dev.report.not_run"),
                    ran ? Palette.GOOD : Palette.FAINT));
        }
        if (!info.notes().isEmpty()) {
            parts.add(CodeBlock.makeBlock(Lang.get("hotwire.dev.report.notes"), "", Palette.WARN, info.notes().stream()
                    .map(note -> CodeBlock.Code.of(note, Palette.WARN)).toList(), false));
        }
        return parts;
    }

    private void addClasses(List<Part> parts, DevModView mod, int build, DevBuildInfo info, boolean tight) {
        if (info.classes().isEmpty()) {
            return;
        }
        DevClassChange found = info.classes().getFirst();
        for (DevClassChange change : info.classes()) {
            if (change.name().equals(this.shownClass)) {
                found = change;
            }
        }
        DevClassChange change = found;
        List<CodeBlock.Code> classes = new ArrayList<>();
        for (DevClassChange each : info.classes()) {
            String name = each.name();
            boolean lit = name.equals(change.name());
            classes.add(new CodeBlock.Code(lit ? ">" : "", Palette.ON, each.file().isEmpty() ? name + ".class"
                    : each.file(), lit ? Palette.TEXT : Palette.DIM, MergeWords.describeMethods(each), Palette.FAINT,
                    () -> {
                        this.shownClass = name;
                        this.picker.refresh();
                    }));
        }
        parts.add(CodeBlock.makeBlock(Lang.get("hotwire.dev.report.classes"), "", Palette.ACCENT_SOFT, classes,
                false));
        for (String method : change.changed()) {
            parts.add(this.makeMethod(mod, build, change, method, false, tight));
        }
        for (String method : change.added()) {
            parts.add(this.makeMethod(mod, build, change, method, true, tight));
        }
        if (!change.removed().isEmpty()) {
            parts.add(DevCards.makeLine(Lang.get("hotwire.dev.report.removed", String.join(", ", change.removed())),
                    Palette.BAD));
        }
        if (change.changed().isEmpty() && change.added().isEmpty() && change.removed().isEmpty()) {
            parts.add(DevCards.makeLine(Lang.get("hotwire.dev.report.fields"), Palette.DIM));
        }
    }

    private Part makeMethod(DevModView mod, int build, DevClassChange change, String method, boolean added,
            boolean tight) {
        List<String> diff = change.diffs().get(method);
        List<String> lines = new ArrayList<>();
        if (diff != null) {
            lines.addAll(diff);
        } else if (added && !change.type().isEmpty()) {
            String code = this.findCode(mod, build, change.type(), method);
            if (code != null) {
                code.lines().forEach(line -> lines.add("+" + line));
            }
        }
        List<CodeBlock.Code> rows = new ArrayList<>();

        int indent = findIndent(lines);
        for (String line : lines) {
            char kind = line.charAt(0);
            int ink = switch (kind) {
                case '+' -> Palette.GOOD;
                case '-' -> Palette.BAD;
                default -> Palette.DIM;
            };
            String code = line.length() - 1 > indent ? line.substring(1 + indent) : line.substring(1).strip();
            rows.add(new CodeBlock.Code(kind == ' ' ? "" : String.valueOf(kind), ink, code, ink, "", 0, null));
        }
        List<Integer> span = change.lines().get(method);
        String label = change.name() + "." + method;
        List<Part> buttons = new ArrayList<>();
        if (span != null && !change.type().isEmpty()) {
            int first = span.get(0);
            buttons.add(CodeBlock.makeChip(Lang.get("hotwire.dev.report.open_button"),
                    () -> this.host.openPlace(mod.id(), change.type(), first, label)));
            if (this.host.getExtras().copyRows()) {
                buttons.add(CodeBlock.makeChip(Lang.get("hotwire.dev.report.copy"),
                        () -> this.copyCode(mod, build, change.type(), method, false, label)));
            }
            if (this.host.getExtras().copyRows() && diff != null) {
                buttons.add(CodeBlock.makeChip(Lang.get("hotwire.dev.report.copy_old"),
                        () -> this.copyCode(mod, build, change.type(), method, true, label)));
            }
        }
        return CodeBlock.makeBlock((added ? "+ " : "~ ") + method, "", added ? Palette.GOOD : Palette.WARN, rows,
                tight, buttons.toArray(Part[]::new));
    }

    static int findIndent(List<String> lines) {
        int least = Integer.MAX_VALUE;
        for (String line : lines) {
            String code = line.length() > 1 ? line.substring(1) : "";
            if (!code.isBlank()) {
                least = Math.min(least, code.length() - code.stripLeading().length());
            }
        }
        return least == Integer.MAX_VALUE ? 0 : least;
    }

    private void copyCode(DevModView mod, int build, String type, String method, boolean before, String label) {
        String code = this.host.findCode(mod.id(), build, type, method, before);
        if (code != null) {
            this.host.copy(code, label);
        }
    }

    private @Nullable String findCode(DevModView mod, int build, String type, String method) {
        String key = mod.id() + "/" + build + "/" + type + "#" + method;
        if (!this.addedCode.containsKey(key)) {
            this.addedCode.put(key, this.host.findCode(mod.id(), build, type, method, false));
        }
        return this.addedCode.get(key);
    }
}
