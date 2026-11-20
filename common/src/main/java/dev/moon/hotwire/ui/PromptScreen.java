package dev.moon.hotwire.ui;

import dev.moon.hotwire.ui.panels.FirstOpenPanel;
import dev.moon.hotwire.ui.widgets.Canvas;
import dev.moon.hotwire.ui.widgets.Sheet;

public final class PromptScreen {
    private final Root root = new Root();
    private final Sheet panel;

    public PromptScreen(Runnable onAccept, Runnable onOpenHotwire) {
        FirstOpenPanel first = this.root.getModal().add(new FirstOpenPanel(onAccept, onOpenHotwire));
        first.present();
        this.panel = first;
    }

    public PromptScreen(Sheet panel) {
        this.panel = this.root.getModal().add(panel);
        panel.show();
    }

    public Root getRoot() {
        return this.root;
    }

    public void resize(int width, int height) {
        this.root.resize(width, height);
        this.panel.setBounds(0, 0, width, height);
    }

    public void drawFrame(Canvas canvas, long nowNanos, double mouseX, double mouseY) {
        this.root.drawFrame(canvas, nowNanos, mouseX, mouseY);
    }
}
