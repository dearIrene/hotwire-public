package dev.moon.hotwire.ui;

import dev.moon.hotwire.dev.DevCompare;
import dev.moon.hotwire.dev.DevExtras;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

public final class CompareHud {
    private static final int PAD = 6;
    private static final int LINE = 10;
    private static final int COLUMN_GAP = 12;

    private static final double SAME = 0.02;

    private static @Nullable HudWords words;

    @SuppressWarnings("ArrayRecordComponent")
    private record HudWords(DevCompare compare, String key, DevExtras extras, List<String[]> rows, List<Integer> inks,
            String[] heads, String hint, int[] widths, WhyRows why) {
    }

    private CompareHud() {
    }

    public static void paintOn(Canvas canvas, int width, @Nullable DevCompare compare, String key, DevExtras extras,
            float alpha) {
        if (compare == null || compare.done()) {
            return;
        }
        HudWords last = words;
        if (last == null || !last.compare.equals(compare) || !last.key.equals(key) || !last.extras.equals(extras)) {
            last = make(canvas, compare, key, extras);
            words = last;
        }
        List<String[]> rows = last.rows;
        List<Integer> inks = last.inks;
        String[] heads = last.heads;
        int[] widths = last.widths;
        String hint = last.hint;
        int inner = Math.max(Math.max(widths[0] + widths[1] + widths[2] + 2 * COLUMN_GAP, canvas.measureText(hint)),
                last.why.width());
        int boxW = inner + 2 * PAD;
        int boxH = PAD + LINE + 2 + rows.size() * LINE + last.why.findHeight() + 4 + LINE + PAD - 2;
        int x = Math.max(0, width - boxW - 8);
        int y = 8;
        canvas.fill(x, y, x + boxW, y + boxH, Colors.fade(Palette.PANEL_EDGE, alpha));
        canvas.fill(x + 1, y + 1, x + boxW - 1, y + boxH - 1, Colors.fade(Palette.PANEL, alpha * 0.92f));
        canvas.fill(x + 1, y + 1, x + boxW - 1, y + 2, Colors.fade(Palette.ACCENT, alpha));
        int[] columnX = {x + PAD, x + PAD + widths[0] + COLUMN_GAP, x + PAD + widths[0] + widths[1] + 2 * COLUMN_GAP};
        int lineY = y + PAD;
        int on = -1;
        if (compare.on() == compare.a()) {
            on = 0;
        } else if (compare.on() == compare.b()) {
            on = 1;
        }
        for (int column = 0; column < 3; column++) {
            int ink = Palette.TEXT;
            if (column == on) {
                ink = Palette.ACCENT;
            } else if (column == 2) {
                ink = Palette.DIM;
            }
            canvas.drawText(heads[column], columnX[column], lineY, Colors.fade(ink, alpha));
        }
        lineY += LINE + 2;
        for (int row = 0; row < rows.size(); row++) {
            for (int column = 0; column < 3; column++) {
                int ink = column == 2 ? inks.get(row) : Palette.TEXT;
                canvas.drawText(rows.get(row)[column], columnX[column], lineY, Colors.fade(ink, alpha));
            }
            lineY += LINE;
        }
        last.why.paintOn(canvas, x + PAD, lineY, inner, alpha);
        lineY += last.why.findHeight();
        canvas.drawText(hint, x + PAD, lineY + 4, Colors.fade(Palette.FAINT, alpha));
    }

    private static HudWords make(Canvas canvas, DevCompare compare, String key, DevExtras extras) {
        List<String[]> rows = new ArrayList<>();
        List<Integer> inks = new ArrayList<>();
        addRow(rows, inks, "fps", compare.fpsA(), compare.fpsB(), true);
        if (extras.tick()) {
            addRow(rows, inks, "ms", compare.tickMillisA(), compare.tickMillisB(), false);
        }
        if (extras.memory()) {
            addRow(rows, inks, "MB/s", compare.allocMbA(), compare.allocMbB(), false);
        }
        String[] heads = {Lang.get("hotwire.dev.compare.side", "A", compare.a()),
            Lang.get("hotwire.dev.compare.side", "B", compare.b()), Lang.get("hotwire.dev.compare.difference")};
        int[] widths = new int[3];
        for (int column = 0; column < 3; column++) {
            widths[column] = canvas.measureText(heads[column]);
            for (String[] row : rows) {
                widths[column] = Math.max(widths[column], canvas.measureText(row[column]));
            }
        }
        String hint = key.isEmpty() ? Lang.get("hotwire.dev.compare.hint.no_key")
                : Lang.get("hotwire.dev.compare.hint", key);
        return new HudWords(compare, key, extras, rows, inks, heads, hint, widths,
                WhyRows.make(canvas, compare.why()));
    }

    private static void addRow(List<String[]> rows, List<Integer> inks, String unit, double a, double b,
            boolean higherIsBetter) {
        double change = DevCompare.findChange(a, b);
        int ink = Palette.DIM;
        if (a > 0 && b > 0 && Math.abs(change) >= SAME) {
            ink = (change > 0) == higherIsBetter ? Palette.GOOD : Palette.BAD;
        }
        String difference = "-";
        if (a > 0 && b > 0) {
            difference = Math.abs(change) < 0.005 ? "0%" : String.format(Locale.ROOT, "%+.0f%%", change * 100);
        }
        rows.add(new String[] {describe(a, unit), describe(b, unit), difference});
        inks.add(ink);
    }

    static String describe(double value, String unit) {
        if (value <= 0) {
            return "-";
        }
        return String.format(Locale.ROOT, value >= 10 ? "%.0f %s" : "%.1f %s", value, unit);
    }
}
