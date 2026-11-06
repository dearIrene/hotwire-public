package dev.moon.hotwire.ui;

import dev.moon.hotwire.dev.DevEvent;
import dev.moon.hotwire.dev.DevMixin;
import dev.moon.hotwire.dev.DevModView;
import dev.moon.hotwire.dev.DevState;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class DevCards {
    private static final int PAD = 8;
    private static final int LINE = 10;
    private static final int GAP = 4;
    private static final int BUTTON = 14;
    private static final int CARD = 0xFF1A1427;

    private DevCards() {
    }

    static void paintButtons(Canvas canvas, float a, int y, int maxX, int closeX, boolean onMax, boolean onClose,
            boolean maximized) {
        if (onMax) {
            canvas.fill(maxX, y, maxX + BUTTON, y + BUTTON, Colors.fade(0x30B48CFF, a));
        }
        if (onClose) {
            canvas.fill(closeX, y, closeX + BUTTON, y + BUTTON, Colors.fade(Palette.BAD, a * 0.85f));
        }
        int ink = Colors.fade(Palette.DIM, a);
        if (maximized) {
            paintBox(canvas, maxX + 5, y + 3, 6, 6, ink);
            paintBox(canvas, maxX + 3, y + 5, 6, 6, ink);
            canvas.fill(maxX + 4, y + 6, maxX + 8, y + 10, Colors.fade(Palette.PANEL, a));
        } else {
            paintBox(canvas, maxX + 3, y + 3, 8, 8, ink);
        }
        int cross = Colors.fade(onClose ? Palette.TEXT : Palette.DIM, a);
        for (int i = 0; i < 7; i++) {
            canvas.fill(closeX + 4 + i, y + 4 + i, closeX + 5 + i, y + 5 + i, cross);
            canvas.fill(closeX + 10 - i, y + 4 + i, closeX + 11 - i, y + 5 + i, cross);
        }
    }

    static void paintBox(Canvas canvas, int x, int y, int w, int h, int ink) {
        canvas.fill(x, y, x + w, y + 1, ink);
        canvas.fill(x, y + h - 1, x + w, y + h, ink);
        canvas.fill(x, y, x + 1, y + h, ink);
        canvas.fill(x + w - 1, y, x + w, y + h, ink);
    }

    static int paintPill(Canvas canvas, DevState state, int right, int y, float a) {
        String word = Lang.get("hotwire.dev.state." + state.name().toLowerCase(Locale.ROOT));
        int x = right - canvas.measureText(word) - 18;
        canvas.fill(x, y, right, y + 14, Colors.fade(Palette.PANEL_EDGE, a));
        canvas.fill(x + 1, y + 1, right - 1, y + 13, Colors.fade(CARD, a));
        canvas.fill(x + 5, y + 5, x + 9, y + 9, Colors.fade(findColor(state), a));
        canvas.drawText(word, x + 12, y + 3, Colors.fade(Palette.TEXT, a));
        return x;
    }

    static int paintCard(Canvas canvas, int x, int y, int w, int stripe, String heading, String aside,
            List<String> lines, int lineColor, float a) {
        int textX = x + PAD + 2;
        int textW = Math.max(1, w - 2 * PAD - 2);
        int asideW = aside.isEmpty() ? 0 : canvas.measureText(aside) + 6;
        List<String> head = wrap(canvas, heading, Math.max(1, textW - asideW));
        List<String> wrapped = new ArrayList<>();
        lines.forEach(line -> wrapped.addAll(wrap(canvas, line, textW)));
        int h = 2 * PAD - 2 + head.size() * LINE + wrapped.size() * LINE + (wrapped.isEmpty() ? 0 : 2);
        canvas.fill(x, y, x + w, y + h, Colors.fade(Palette.PANEL_EDGE, a));
        canvas.fill(x + 1, y + 1, x + w - 1, y + h - 1, Colors.fade(CARD, a));
        canvas.fill(x + 1, y + 1, x + 3, y + h - 1, Colors.fade(stripe, a));
        int lineY = y + PAD;
        for (String line : head) {
            canvas.drawText(line, textX, lineY, Colors.fade(Palette.TEXT, a));
            lineY += LINE;
        }
        if (!aside.isEmpty()) {
            canvas.drawText(aside, x + w - PAD - canvas.measureText(aside), y + PAD, Colors.fade(Palette.FAINT, a));
        }
        lineY += 2;
        for (String line : wrapped) {
            canvas.drawText(line, textX, lineY, Colors.fade(lineColor, a));
            lineY += LINE;
        }
        return y + h + GAP;
    }

    static int paintMixins(Canvas canvas, DevModView mod, int x, int y, int w, float a) {
        if (mod.mixins().isEmpty()) {
            return paintText(canvas, Lang.get("hotwire.dev.window.no_mixins"), x, y, w, Palette.DIM, a);
        }
        for (DevMixin mixin : mod.mixins()) {
            boolean shared = !mixin.others().isEmpty();
            String line = shared ? Lang.get("hotwire.dev.mixin.also", String.join(", ", mixin.others()))
                    : Lang.get("hotwire.dev.mixin.alone");
            y = paintCard(canvas, x, y, w, shared ? Palette.WARN : Palette.FAINT, mixin.target(), "", List.of(line),
                    shared ? Palette.WARN : Palette.FAINT, a);
        }
        return y;
    }

    static int paintText(Canvas canvas, String text, int x, int y, int w, int color, float a) {
        for (String line : wrap(canvas, text, w)) {
            canvas.drawText(line, x, y + 4, Colors.fade(color, a));
            y += LINE;
        }
        return y + 8;
    }

    static List<String> wrap(Canvas canvas, String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ", -1)) {
            if (word.isEmpty()) {
                continue;
            }
            String tried = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && canvas.measureText(tried) > width) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(tried);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    static int findColor(DevState state) {
        return switch (state) {
            case UP_TO_DATE -> Palette.GOOD;
            case LOADING, WAITING -> Palette.WARN;
            case NEEDS_RESTART -> Palette.ON;
            case FAILED -> Palette.BAD;
        };
    }

    static int findColor(DevEvent.Kind kind) {
        return switch (kind) {
            case IN, RESTARTED, ASSETS -> Palette.GOOD;
            case MERGED -> Palette.ACCENT_SOFT;
            case NEEDS_RESTART -> Palette.ON;
            case FAILED, CRASH, LEAK -> Palette.BAD;
        };
    }

    static String describeAgo(long at) {
        long seconds = Math.max(0, (System.currentTimeMillis() - at) / 1000);
        if (seconds < 60) {
            return Lang.get("hotwire.dev.ago.seconds", seconds);
        }
        return seconds < 3600 ? Lang.get("hotwire.dev.ago.minutes", seconds / 60)
                : Lang.get("hotwire.dev.ago.hours", seconds / 3600);
    }
}
