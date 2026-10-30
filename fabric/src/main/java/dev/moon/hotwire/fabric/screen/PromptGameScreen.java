package dev.moon.hotwire.fabric.screen;

import dev.moon.hotwire.ui.PromptScreen;
import dev.moon.hotwire.ui.Root;

public final class PromptGameScreen extends RootScreen {
    private final PromptScreen prompt;

    public PromptGameScreen(PromptScreen prompt) {
        super("Hotwire");
        this.prompt = prompt;
    }

    @Override
    protected Root getRoot() {
        return this.prompt.getRoot();
    }

    @Override
    protected void onResized(int width, int height) {
        this.prompt.resize(width, height);
    }

    @Override
    protected void drawFrame(GuiCanvas canvas, long nowNanos, int mouseX, int mouseY) {
        this.prompt.drawFrame(canvas, nowNanos, mouseX, mouseY);
    }
}
