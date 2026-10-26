package dev.moon.hotwire.ui;

import java.util.List;
import org.jspecify.annotations.Nullable;

final class BottomBar {
    static final int SLOT = 110;
    static final int GAP = 4;
    static final int HEIGHT = 24;
    private static final int DONE_W = 90;
    private static final int DONE_GAP = 12;

    private final List<Button> buttons;
    private final Button done;
    private int layoutWidth = -1;

    BottomBar(List<Button> buttons, Button done) {
        this.buttons = List.copyOf(buttons);
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
        int count = this.buttons.size();
        int doneX = width - 24 - DONE_W;
        int room = doneX - DONE_GAP - 24 - GAP * (count - 1);
        int slot = Math.max(Button.PADDING * 2 + 2, Math.min(SLOT, room / count));
        int y = height - 12 - HEIGHT;
        int x = 24;
        for (Button button : this.buttons) {
            button.useShortForm(canvas != null && button.measure(canvas, false) > slot);
            button.setBounds(x, y, slot, HEIGHT);
            x += slot + GAP;
        }
        this.done.setBounds(doneX, y, DONE_W, HEIGHT);
        if (canvas != null) {
            this.layoutWidth = width;
        }
    }
}
