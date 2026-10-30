package dev.moon.hotwire.ui;

public final class PromptScreen {
    private final Root root = new Root();
    private final FirstOpenPanel panel;

    public PromptScreen(Runnable onAccept, Runnable onOpenHotwire) {
        this.panel = this.root.getModal().add(new FirstOpenPanel(onAccept, onOpenHotwire));
        this.panel.present();
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
