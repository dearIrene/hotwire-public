package dev.moon.hotwire.ui;

import java.util.List;

final class UpdateSummaryPanel extends Sheet {
    private static final int LIST_TALL = 150;

    private final Runnable onPending;

    UpdateSummaryPanel(Runnable onPending) {
        super(440, null);
        this.onPending = onPending;
        this.setMark(ModScreen.Icons.UPDATES);
    }

    void open(List<String> first, List<String> now, List<String> later, List<String> not) {
        this.title.set(Lang.get("hotwire.updates.summary.title"));
        Line list = Kit.column().gap(8);
        if (!first.isEmpty()) {
            list.with(Kit.row(Kit.text(Lang.get("hotwire.updates.summary.first"), Palette.BAD), Kit.rule().grow(1))
                    .gap(6));
            first.forEach(line -> list.with(Kit.text(line, Palette.BAD).wrap()));
        }
        add(list, "hotwire.updates.summary.now", Palette.GOOD, now);
        add(list, "hotwire.updates.summary.later", Palette.WARN, later);
        add(list, "hotwire.updates.summary.not", Palette.BAD, not);
        this.body.clear().with(Kit.column(Kit.scroll(list).capAt(LIST_TALL)).pad(8, 6).face(Palette.HAIRLINE));
        this.buttons.clear().with(Kit.space());
        if (!later.isEmpty()) {
            this.buttons.with(Kit.push(Lang.get("hotwire.screen.pending"), () -> {
                this.dismiss();
                this.onPending.run();
            }));
        }
        this.buttons.with(this.setLead(Kit.lead(Lang.get("hotwire.button.done"), this::dismiss)));
        this.show();
    }

    private static void add(Line list, String heading, int ink, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        list.with(Kit.row(Kit.text(Lang.get(heading), ink), Kit.text(String.valueOf(lines.size()), ink),
                Kit.rule().grow(1)).gap(6));
        lines.forEach(line -> list.with(Kit.dim(line).wrap()));
    }

    @Override
    protected boolean onKey(int key, int modifiers) {
        if (key == TextField.ESCAPE || key == TextField.ENTER) {
            this.dismiss();
        }
        return true;
    }
}
