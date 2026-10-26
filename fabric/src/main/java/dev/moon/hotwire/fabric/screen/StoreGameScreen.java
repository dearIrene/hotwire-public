package dev.moon.hotwire.fabric.screen;

import dev.moon.hotwire.ui.Root;
import dev.moon.hotwire.ui.StoreScreen;

public final class StoreGameScreen extends RootScreen {
    private final StoreScreen store;
    private final Runnable onClosed;

    public StoreGameScreen(StoreScreen store, Runnable onClosed) {
        super("Modrinth Store");
        this.store = store;
        this.onClosed = onClosed;
    }

    @Override
    protected Root getRoot() {
        return this.store.getRoot();
    }

    @Override
    protected void onResized(int width, int height) {
        this.store.resize(width, height);
    }

    @Override
    protected void drawFrame(GuiCanvas canvas, long nowNanos, int mouseX, int mouseY) {
        this.store.drawFrame(canvas, nowNanos, mouseX, mouseY);
    }

    @Override
    protected boolean onKeyLeft(int key, int modifiers) {
        return this.store.pressKey(key, modifiers);
    }

    @Override
    public void removed() {
        this.onClosed.run();
        super.removed();
    }
}
