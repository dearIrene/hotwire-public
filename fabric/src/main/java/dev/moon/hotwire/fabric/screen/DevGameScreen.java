package dev.moon.hotwire.fabric.screen;

import com.mojang.blaze3d.platform.Window;
import dev.moon.hotwire.ui.DevWindow;
import dev.moon.hotwire.ui.NoticeLayer;
import dev.moon.hotwire.ui.Root;
import net.minecraft.client.Minecraft;
//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
*///?}

public final class DevGameScreen extends RootScreen {
    private final Root root = new Root();
    private final DevWindow window;

    private final NoticeLayer notices = new NoticeLayer();

    private float factor = 1;

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
        this.factor = this.findFactor();
        int w = Math.round(width / this.factor);
        int h = Math.round(height / this.factor);
        this.root.resize(w, h);
        this.window.setScreen(w, h);
    }

    @Override
    protected double toRoot(double screenUnits) {
        return screenUnits / this.factor;
    }

    private float findFactor() {
        Window shown = Minecraft.getInstance().getWindow();
        int game = Math.max(1, (int) Math.floor(shown.getGuiScale() + 0.5));
        int most = Math.max(1, Math.min(shown.getWidth() / 320, shown.getHeight() / 240));
        this.window.setScaleRange(game, most);
        int own = this.window.getScale();
        return own == 0 ? 1 : (float) Math.min(own, most) / game;
    }

    @Override
    protected void drawFrame(GuiCanvas canvas, long nowNanos, int mouseX, int mouseY) {
        if (this.findFactor() != this.factor) {
            this.onResized(this.width, this.height);
        }
        float f = this.factor;
        canvas.pushScale(f, 0, 0);
        this.root.drawFrame(canvas, nowNanos, mouseX < 0 ? mouseX : Math.round(mouseX / f),
                mouseY < 0 ? mouseY : Math.round(mouseY / f));
        canvas.popScale();
        this.notices.draw(canvas, this.width, this.height, Math.round(this.window.findCardsTop(Math.round(
                this.width / f)) * f), nowNanos);
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
