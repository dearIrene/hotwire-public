package dev.moon.hotwire.ui;

import dev.moon.hotwire.dev.DevWhy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

record WhyRows(String title, List<Row> rows, int width) {
    private static final int MOST = 3;
    private static final int LINE = 10;
    private static final int GAP = 12;

    private static final int NAME_WIDTH = 150;

    record Row(String name, String cost, int ink) {
    }

    WhyRows {
        rows = List.copyOf(rows);
    }

    static WhyRows make(Canvas canvas, List<DevWhy> why) {
        String title = Lang.get("hotwire.dev.why.title");
        List<Row> rows = new ArrayList<>();
        int width = why.isEmpty() ? 0 : canvas.measureText(title);
        for (DevWhy one : why.subList(0, Math.min(MOST, why.size()))) {
            boolean timed = one.timeA() != one.timeB();
            Row row = new Row(canvas.clip(one.findName(), NAME_WIDTH), timed ? describeTime(one) : describeMade(one),
                    findInk(timed ? one.timeB() - one.timeA() : one.madeB() - one.madeA()));
            rows.add(row);
            width = Math.max(width, canvas.measureText(row.name()) + GAP + canvas.measureText(row.cost()));
        }
        return new WhyRows(title, rows, width);
    }

    int findHeight() {
        return this.rows.isEmpty() ? 0 : 4 + LINE + this.rows.size() * LINE;
    }

    void paintOn(Canvas canvas, int x, int y, int inner, float alpha) {
        if (this.rows.isEmpty()) {
            return;
        }
        int lineY = y + 4;
        canvas.drawText(this.title, x, lineY, Colors.fade(Palette.FAINT, alpha));
        for (Row row : this.rows) {
            lineY += LINE;
            canvas.drawText(row.name(), x, lineY, Colors.fade(Palette.TEXT, alpha));
            canvas.drawText(row.cost(), x + inner - canvas.measureText(row.cost()), lineY,
                    Colors.fade(row.ink(), alpha));
        }
    }

    static List<CodeBlock.Code> makeCode(List<DevWhy> why, Consumer<DevWhy> open) {
        List<CodeBlock.Code> rows = new ArrayList<>();
        if (!why.isEmpty()) {
            rows.add(CodeBlock.Code.of(Lang.get("hotwire.dev.why.title"), Palette.FAINT));
        }
        for (DevWhy one : why) {
            boolean timed = one.timeA() != one.timeB();
            boolean made = one.madeA() != one.madeB();
            String cost = timed ? describeTime(one) : describeMade(one);
            if (timed && made) {
                cost += ", " + describeMade(one);
            }
            int ink = findInk(timed ? one.timeB() - one.timeA() : one.madeB() - one.madeA());
            rows.add(new CodeBlock.Code("", 0, one.findName(), Palette.TEXT, cost, ink, () -> open.accept(one)));
        }
        return rows;
    }

    private static int findInk(double more) {
        return more > 0 ? Palette.BAD : Palette.GOOD;
    }

    private static String describeTime(DevWhy why) {
        return Lang.get("hotwire.dev.why.time", describe((why.timeB() - why.timeA()) * 1000));
    }

    private static String describeMade(DevWhy why) {
        return Lang.get("hotwire.dev.why.made", describe(why.madeB() - why.madeA()));
    }

    private static String describe(double more) {
        return String.format(Locale.ROOT, Math.abs(more) >= 10 ? "%+.0f" : "%+.1f", more);
    }
}
