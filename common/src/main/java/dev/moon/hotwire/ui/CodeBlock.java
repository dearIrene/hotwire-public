package dev.moon.hotwire.ui;

import dev.moon.hotwire.dev.BuildMerge;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

final class CodeBlock {
    static final int HOT = 0x24B48CFF;
    private static final int LINE = 10;
    private static final int GAP = 4;

    private static final int HEAD_H = 14;
    private static final int CODE_ROW = 12;
    private static final int GUTTER = 22;
    private static final int CODE_BG = 0xFF0E0B17;
    private static final int CODE_HEAD = 0xFF181229;
    static final int CHIP_H = 13;

    private static final int MOST_ROWS = 12;

    private CodeBlock() {
    }

    record Hit(int x0, int y0, int x1, int y1, Runnable action) {
    }

    record Code(String mark, int markInk, String text, int ink, String aside, int asideInk, int line,
            @Nullable Runnable open) {
        static Code of(String text, int ink) {
            return new Code("", 0, text, ink, "", 0, 0, null);
        }
    }

    static int paintBlock(List<Hit> hits, Canvas canvas, int x, int y, int w, String title, String aside, int edge,
            List<Code> rows,
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
                hits.add(new Hit(x + 3, rowY, x + w - 1, rowY + CODE_ROW, open));
            }
            rowY += CODE_ROW;
        }
        return y + h + GAP;
    }

    static List<Code> limitRows(List<Code> rows) {
        if (rows.size() <= MOST_ROWS) {
            return rows;
        }
        List<Code> shown = new ArrayList<>(rows.subList(0, MOST_ROWS - 1));
        shown.add(Code.of(Lang.get("hotwire.dev.and_more", rows.size() - MOST_ROWS + 1), Palette.FAINT));
        return shown;
    }

    static int paintChip(List<Hit> hits, Canvas canvas, int x, int y, String label, float a, double px, double py,
            boolean over,
            Runnable action) {
        int w = canvas.measureText(label) + 12;
        boolean hot = over && px >= x && px < x + w && py >= y && py < y + CHIP_H;
        canvas.fill(x, y, x + w, y + CHIP_H, Colors.fade(hot ? Palette.ACCENT_SOFT : Palette.PANEL_EDGE, a));
        canvas.fill(x + 1, y + 1, x + w - 1, y + CHIP_H - 1, Colors.fade(hot ? 0xFF2A2140 : CODE_HEAD, a));
        canvas.drawText(label, x + 6, y + 3, Colors.fade(hot ? Palette.TEXT : Palette.DIM, a));
        hits.add(new Hit(x, y, x + w, y + CHIP_H, action));
        return x + w + 4;
    }

    static void paintFrame(Canvas canvas, int x, int y, int w, int h, int ink) {
        canvas.fill(x, y, x + w, y + 1, ink);
        canvas.fill(x, y + h - 1, x + w, y + h, ink);
        canvas.fill(x, y, x + 1, y + h, ink);
        canvas.fill(x + w - 1, y, x + w, y + h, ink);
    }

    static void paintPin(Canvas canvas, int x, int y, int ink) {
        canvas.fill(x + 1, y, x + 4, y + 1, ink);
        canvas.fill(x, y + 1, x + 5, y + 4, ink);
        canvas.fill(x + 2, y + 4, x + 3, y + 9, ink);
    }

    static int paintWrapped(Canvas canvas, String text, int x, int y, int w, int color, float a) {
        for (String line : DevCards.wrap(canvas, text, w)) {
            canvas.drawText(line, x, y, Colors.fade(color, a));
            y += LINE;
        }
        return y + GAP;
    }

    static Code makeConflictRow(int build, BuildMerge.Conflict conflict, String what) {
        String said = Lang.get("hotwire.dev.merge.build_did", build, what);
        if (build == conflict.winner()) {
            return new Code("+", Palette.GOOD, said, Palette.TEXT, Lang.get("hotwire.dev.merge.kept"), Palette.GOOD,
                    0, null);
        }
        if (conflict.dropped().contains(build)) {
            return new Code("x", Palette.BAD, said, Palette.DIM, Lang.get("hotwire.dev.merge.dropped"), Palette.BAD,
                    0, null);
        }
        return new Code("~", Palette.DIM, said, Palette.DIM, Lang.get("hotwire.dev.merge.inside"), Palette.FAINT, 0,
                null);
    }
}
