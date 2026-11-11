package dev.moon.hotwire.ui;

import dev.moon.hotwire.dev.DevCompare;
import dev.moon.hotwire.dev.DevExtras;
import dev.moon.hotwire.dev.DevHost;
import dev.moon.hotwire.dev.DevModView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

final class ComparePane {
    final Line part;
    private final DevHost host;
    private final Push switchTo = Kit.push("", () -> this.act(true));
    private final Push key = Kit.push("", () -> this.settingKey = true);
    private final Line chips;
    private final Text title = Kit.text("");
    private final CodeBlock rows = new CodeBlock(List.of());
    private String id = "";
    private boolean settingKey;
    private @Nullable Object shown;

    ComparePane(DevHost host) {
        this.host = host;
        this.switchTo.height(CodeBlock.CHIP);
        this.key.height(CodeBlock.CHIP);
        this.chips = Kit.column(Kit.row(this.switchTo, CodeBlock.makeChip(Lang.get("hotwire.dev.compare.stop"),
                () -> this.act(false))).gap(4), this.key).gap(4).cross(Line.Cross.START);
        this.part = Kit.column(this.chips, Kit.row(new Kit.Fill(Palette.ACCENT_SOFT).width(2), Kit.column(
                DevCards.makePane(CodeBlock.HEAD, Kit.row(this.title.grow(1)).pad(6, 3)), this.rows).grow(1))
                .cross(Line.Cross.STRETCH)).gap(4);
    }

    private void act(boolean switching) {
        if (switching) {
            this.host.switchCompare(this.id);
        } else {
            this.host.stopCompare(this.id);
        }
    }

    void reset() {
        this.settingKey = false;
    }

    boolean isSettingKey() {
        return this.settingKey;
    }

    void takeKey(int pressed, int escape) {
        this.settingKey = false;
        if (pressed != escape) {
            this.host.setCompareKey(pressed);
        }
    }

    void show(DevModView mod, int build) {
        DevCompare compare = mod.compare();
        if (compare == null || (compare.a() != build && compare.b() != build)) {
            this.part.show(false);
            return;
        }
        this.part.show(true);
        this.id = mod.id();
        this.chips.show(!compare.done());
        this.switchTo.label(Lang.get("hotwire.dev.compare.switch", compare.findOther()));
        String bound = this.host.getCompareKey();
        if (this.settingKey) {
            this.key.label(Lang.get("hotwire.dev.compare.key.waiting"));
        } else {
            this.key.label(bound.isEmpty() ? Lang.get("hotwire.dev.compare.key.none")
                    : Lang.get("hotwire.dev.compare.key", bound));
        }
        DevExtras extras = this.host.getExtras();
        Object now = List.of(compare, extras);
        if (now.equals(this.shown)) {
            return;
        }
        this.shown = now;
        this.title.set(Lang.get("hotwire.dev.compare.title", compare.a(), compare.b()));
        List<CodeBlock.Code> made = new ArrayList<>();
        made.add(CodeBlock.Code.of(compare.done() ? Lang.get("hotwire.dev.compare.stopped") : Lang.get(
                "hotwire.dev.compare.measured", Math.round(compare.secondsA()), Math.round(compare.secondsB())),
                Palette.FAINT));
        if (!compare.failed().isEmpty()) {
            made.add(CodeBlock.Code.ofMark("!", Palette.BAD, compare.failed(), Palette.TEXT, ""));
        }
        made.add(makeMeasure("hotwire.dev.compare.fps", compare.fpsA(), compare.fpsB(), true));
        if (extras.tick()) {
            made.add(makeMeasure("hotwire.dev.compare.tick", compare.tickMillisA(), compare.tickMillisB(), false));
        }
        if (extras.memory()) {
            made.add(makeMeasure("hotwire.dev.compare.alloc", compare.allocMbA(), compare.allocMbB(), false));
        }
        String modId = mod.id();
        made.addAll(WhyRows.makeCode(compare.why(),
                why -> this.host.openPlace(modId, why.type(), why.line(), why.findName())));
        this.rows.set(made);
    }

    private static CodeBlock.Code makeMeasure(String key, double a, double b, boolean higherIsBetter) {
        double change = DevCompare.findChange(a, b);
        int ink = Palette.DIM;
        if (higherIsBetter ? change > 0.02 : change < -0.02) {
            ink = Palette.GOOD;
        } else if (higherIsBetter ? change < -0.02 : change > 0.02) {
            ink = Palette.BAD;
        }

        String text = Lang.get(key, a > 0 ? String.format(Locale.ROOT, "%.1f", a) : "-",
                b > 0 ? String.format(Locale.ROOT, "%.1f", b) : "-");
        String difference = a > 0 && b > 0 ? String.format(Locale.ROOT, "%+.0f%%", change * 100) : "";
        return new CodeBlock.Code("", ink, text, Palette.TEXT, difference, ink, null);
    }
}
