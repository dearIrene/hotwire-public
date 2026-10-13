package dev.moon.hotwire.ui;

import java.util.List;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;

final class BottomBar {
    private final List<Button> seven;
    private final Button done;
    private int layoutWidth = -1;

    BottomBar(List<Button> seven, Button done) {
        this.seven = List.copyOf(seven);
        this.done = done;
    }

    void markStale() {
        this.layoutWidth = -1;
    }

    void layIfStale(Canvas canvas, int width, int height) {
        if (this.layoutWidth != width) {
            this.lay(canvas, width, height);
        }
    }

    void lay(@Nullable Canvas canvas, int width, int height) {
        int count = this.seven.size();
        int doneWidth = canvas == null ? 92 : Math.min(92, Math.max(this.done.measure(canvas, false), width / 6));
        int doneX = width - 20 - doneWidth;
        int room = doneX - 4 - 24 - 4 * (count - 1);
        int slot = Math.min(150, (width - 164) * 10 / 64);
        int[] widths = new int[count];
        boolean fits = true;
        for (int i = 0; i < count; i++) {
            widths[i] = i < 2 ? slot * 7 / 10 : slot;
            fits &= canvas == null || widths[i] >= this.seven.get(i).measure(canvas, false);
        }
        boolean shortForm = false;
        if (canvas != null && (!fits || IntStream.of(widths).sum() > room)) {
            int[] needs = this.measure(canvas, false);
            if (IntStream.of(needs).sum() > room) {
                needs = this.measure(canvas, true);
                shortForm = true;
            }
            int total = IntStream.of(needs).sum();
            int left = room - total;
            for (int i = 0; i < count; i++) {
                widths[i] = left >= 0 ? needs[i] + left / count + (i < left % count ? 1 : 0)
                        : Math.max(Button.PADDING * 2, room * needs[i] / total);
            }
        }
        int x = 24;
        for (int i = 0; i < count; i++) {
            this.seven.get(i).useShortForm(shortForm);
            this.seven.get(i).setBounds(x, height - 32, widths[i], 20);
            x += widths[i] + 4;
        }
        this.done.setBounds(doneX, height - 32, doneWidth, 20);
        if (canvas != null) {
            this.layoutWidth = width;
        }
    }

    private int[] measure(Canvas canvas, boolean shortForm) {
        return this.seven.stream().mapToInt(button -> button.measure(canvas, shortForm)).toArray();
    }
}
