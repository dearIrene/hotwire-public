package dev.moon.hotwire.fabric.screen;

import dev.moon.hotwire.ui.DevWindow;
import dev.moon.hotwire.ui.NoticeLayer;
import dev.moon.hotwire.ui.Root;
//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
*///?}

public final class DevGameScreen extends RootScreen {
    private final Root root = new Root();
    private final DevWindow window;

    private final NoticeLayer notices = new NoticeLayer();

    public DevGameScreen(DevWindow window) {
        super("Hotwire developer");
        this.window = window;
        this.root.getBase().add(window);
    }

    @Override
    protected Root getRoot() {
        return this.root;
    }

    public Root getRootForTests() {
        return this.root;
    }

    public DevWindow getWindow() {
        return this.window;
    }

    @Override
    protected void onResized(int width, int height) {
        this.root.resize(width, height);
        this.window.setScreen(width, height);
    }

    @Override
    protected void drawFrame(GuiCanvas canvas, long nowNanos, int mouseX, int mouseY) {
        this.root.drawFrame(canvas, nowNanos, mouseX, mouseY);
        this.notices.draw(canvas, this.width, this.height, this.window.findCardsTop(this.width), nowNanos);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    //? if >=26.1 {
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    }
    //?} else {
    /*@Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }
    *///?}
}
