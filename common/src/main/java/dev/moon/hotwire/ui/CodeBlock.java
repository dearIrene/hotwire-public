package dev.moon.hotwire.ui;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

final class CodeBlock extends Part {
    static final int CHIP = 14;
    private static final int ROW = 12;
    private static final int PAD = 6;
    private static final int MARK = 9;
    private static final int MOST_ROWS = 12;
    private static final String MORE = "    ";
    private static final int HOT = 0x24B48CFF;
    private static final int FACE = 0xFF0E0B17;
    static final int HEAD = 0xFF181229;

    record Code(String mark, int markInk, String text, int ink, String aside, int asideInk,
            @Nullable Runnable open) {
        static Code of(String text, int ink) {
            return new Code("", 0, text, ink, "", 0, null);
        }

        static Code ofMark(String mark, int markInk, String text, int ink, String aside) {
            return new Code(mark, markInk, text, ink, aside, Palette.FAINT, null);
        }
    }

    private record Row(Code code, String text, boolean first, int asideWidth) {
    }

    private final EasedValue markAt = EasedValue.ofSpeed(24);
    private final EasedValue markShown = EasedValue.ofSpeed(16);
    private List<Code> rows;
    private List<Row> shown = List.of();
    private boolean marked;

    CodeBlock(List<Code> rows) {
        this.rows = List.copyOf(rows);
    }

    void set(List<Code> rows) {
        this.rows = List.copyOf(rows);
        markChanged();
    }

    static Line makeBlock(String title, String aside, int edge, List<Code> rows, boolean tight, Part... buttons) {
        Line head = Kit.row(Kit.text(title).grow(1)).gap(4).pad(PAD, 3);
        Line inside = Kit.column(DevCards.makePane(HEAD, head));
        if (!aside.isEmpty()) {
            head.with(Kit.text(aside, Palette.FAINT));
        }
        if (tight && buttons.length > 0) {
            inside.with(DevCards.makePane(HEAD, Kit.row(buttons).gap(4).pad(PAD, 2)));
        } else {
            head.with(buttons);
        }
        inside.with(new CodeBlock(rows));
        return Kit.row(new Kit.Fill(edge).width(2), inside.grow(1)).cross(Line.Cross.STRETCH);
    }

    static Part makeChip(String label, Runnable action) {
        return Kit.push(label, action).height(CHIP);
    }

    static List<Code> limitRows(List<Code> rows) {
        if (rows.size() <= MOST_ROWS) {
            return rows;
        }
        List<Code> some = new ArrayList<>(rows.subList(0, MOST_ROWS - 1));
        some.add(Code.of(Lang.get("hotwire.dev.and_more", rows.size() - MOST_ROWS + 1), Palette.FAINT));
        return some;
    }

    private static List<String> wrap(Canvas canvas, String text, int room) {
        if (canvas.measureText(text) <= room) {
            return List.of(text);
        }
        String code = text.stripLeading();
        String indent = text.substring(0, text.length() - code.length());
        List<String> lines = new ArrayList<>();
        for (String line : Text.findLines(canvas, code, Math.max(1, room - canvas.measureText(indent + MORE)))) {
            lines.add(canvas.clip(indent + (lines.isEmpty() ? "" : MORE) + line, room));
        }
        return lines.isEmpty() ? List.of(text) : lines;
    }

    @Override
    protected void measure(Canvas canvas, int room) {
        this.marked = this.rows.stream().anyMatch(row -> !row.mark().isEmpty());
        int inner = Math.max(1, room - 2 * PAD - (this.marked ? MARK : 0));
        List<Row> made = new ArrayList<>();
        for (Code row : this.rows) {
            int asideWidth = row.aside().isEmpty() ? 0 : canvas.measureText(row.aside());
            boolean first = true;
            for (String line : wrap(canvas, row.text(), Math.max(1, inner - (asideWidth > 0 ? asideWidth + 8 : 0)))) {
                made.add(new Row(row, line, first, first ? asideWidth : 0));
                first = false;
            }
        }
        this.shown = made;
        this.hugWidth = room;
        this.hugHeight = made.size() * ROW + 4;
    }

    private @Nullable Runnable findOpenAt(double py) {
        int under = (int) Math.floor((py - this.getY() - 2) / ROW);
        return under >= 0 && under < this.shown.size() ? this.shown.get(under).code().open() : null;
    }

    @Override
    public void step(float dt) {
        this.markAt.step(dt);
        this.markShown.step(dt);
    }

    @Override
    protected void paint(Canvas canvas, float alpha) {
        int x = this.getX();
        int y = this.getY();
        int right = x + this.getWidth();
        canvas.fill(x, y, right, y + this.getHeight(), Colors.fade(FACE, alpha * DevCards.getSolid()));
        boolean live = this.isHovered() && this.findOpenAt(this.getPointerY()) != null;
        if (live) {
            float at = (float) Math.floor((this.getPointerY() - y - 2) / ROW) * ROW;
            if (this.markShown.getValue() < 0.05f) {
                this.markAt.snap(at);
            }
            this.markAt.to(at);
        }
        this.markShown.to(live ? 1 : 0);
        int mark = y + 2 + this.markAt.getRounded();
        canvas.fill(x, mark, right, mark + ROW, Colors.fade(HOT, alpha * this.markShown.getValue()));
        int textX = x + PAD + (this.marked ? MARK : 0);
        for (int at = 0; at < this.shown.size(); at++) {
            Row row = this.shown.get(at);
            Code code = row.code();
            int rowY = y + 4 + at * ROW;
            if (row.first() && !code.mark().isEmpty()) {
                canvas.drawText(code.mark(), x + PAD, rowY + ("~".equals(code.mark()) ? 3 : 0),
                        Colors.fade(code.markInk(), alpha));
            }
            canvas.drawText(row.text(), textX, rowY, Colors.fade(code.ink(), alpha));
            if (row.asideWidth() > 0) {
                canvas.drawText(code.aside(), right - PAD - row.asideWidth(), rowY,
                        Colors.fade(code.asideInk(), alpha));
            }
        }
    }

    @Override
    protected boolean onClick(double px, double py, int button) {
        Runnable open = this.findOpenAt(py);
        if (open != null && button == 0) {
            open.run();
        }
        return open != null;
    }
}
