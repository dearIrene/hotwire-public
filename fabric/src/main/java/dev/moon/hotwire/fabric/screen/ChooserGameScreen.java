package dev.moon.hotwire.fabric.screen;

import dev.moon.hotwire.ui.ChooserScreen;
import dev.moon.hotwire.ui.Root;

public final class ChooserGameScreen extends RootScreen {
    private final ChooserScreen chooser;

    public ChooserGameScreen(ChooserScreen chooser) {
        super("Hotwire");
        this.chooser = chooser;
    }

    @Override
    protected Root getRoot() {
        return this.chooser.getRoot();
    }

    @Override
    protected void onResized(int width, int height) {
        this.chooser.resize(width, height);
    }

    @Override
    protected void drawFrame(GuiCanvas canvas, long nowNanos, int mouseX, int mouseY) {
        this.chooser.drawFrame(canvas, nowNanos, mouseX, mouseY);
    }

    @Override
    protected boolean onKeyLeft(int key, int modifiers) {
        return this.chooser.pressKey(key, modifiers);
    }
}
