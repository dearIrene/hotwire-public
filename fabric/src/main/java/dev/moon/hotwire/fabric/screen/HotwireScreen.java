package dev.moon.hotwire.fabric.screen;

import dev.moon.hotwire.ui.ModScreen;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
*///?}
//? if >=1.21.9 {
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
//?}

public final class HotwireScreen extends Screen {
    private final ModScreen screen;
    private final ScreenOpenTimer timer;

    public HotwireScreen(ModScreen screen, ScreenOpenTimer timer) {
        super(Component.literal("Hotwire"));
        this.screen = screen;
        this.timer = timer;
    }

    @Override
    protected void init() {
        this.screen.resize(this.width, this.height);
        this.timer.onSized();
    }

    @Override
    public void onFilesDrop(List<Path> files) {
        this.screen.onFilesDropped(files);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    //? if >=26.1 {
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        this.timer.onFrameBegun();
        this.screen.drawFrame(new GuiCanvas(graphics, this.font), System.nanoTime(), mouseX, mouseY);
        this.timer.onFrameDrawn();
    }
    //?} else {
    /*@Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        this.timer.onFrameBegun();
        this.screen.drawFrame(new GuiCanvas(graphics, this.font), System.nanoTime(), mouseX, mouseY);
        this.timer.onFrameDrawn();
    }
    *///?}

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        return this.screen.getRoot().scroll(x, y, scrollY) || super.mouseScrolled(x, y, scrollX, scrollY);
    }

    //? if >=1.21.9 {
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        return this.screen.getRoot().click(event.x(), event.y(), event.button(), event.modifiers())
                || super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        return this.screen.getRoot().drag(event.x(), event.y(), event.button())
                || super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return this.screen.getRoot().release(event.x(), event.y(), event.button()) || super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return this.screen.getRoot().pressKey(event.key(), event.modifiers()) || super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return this.screen.getRoot().typeChar(event.codepoint()) || super.charTyped(event);
    }
    //?} else {
    /*@Override
    public boolean mouseClicked(double x, double y, int button) {
        int modifiers = (hasShiftDown() ? 1 : 0) | (hasControlDown() ? 2 : 0);
        return this.screen.getRoot().click(x, y, button, modifiers) || super.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dragX, double dragY) {
        return this.screen.getRoot().drag(x, y, button) || super.mouseDragged(x, y, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        return this.screen.getRoot().release(x, y, button) || super.mouseReleased(x, y, button);
    }

    @Override
    public boolean keyPressed(int key, int scancode, int modifiers) {
        return this.screen.getRoot().pressKey(key, modifiers) || super.keyPressed(key, scancode, modifiers);
    }

    @Override
    public boolean charTyped(char typed, int modifiers) {
        return this.screen.getRoot().typeChar(typed) || super.charTyped(typed, modifiers);
    }
    *///?}
}
