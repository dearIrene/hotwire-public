package dev.moon.hotwire.fabric.screen;

import dev.moon.hotwire.ui.Root;
import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
*///?}
//? if >=26.3 {
import net.minecraft.client.Minecraft;
//?}
//? if >=1.21.9 {
import com.mojang.blaze3d.platform.InputConstants;
import java.util.Map;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
//?}

public abstract class RootScreen extends Screen {
    //? if >=1.21.9 {
    private static final Map<Integer, Integer> KEYS = Map.ofEntries(Map.entry(InputConstants.KEY_ESCAPE, 256),
            Map.entry(InputConstants.KEY_RETURN, 257), Map.entry(InputConstants.KEY_NUMPADENTER, 335),
            Map.entry(InputConstants.KEY_TAB, 258), Map.entry(InputConstants.KEY_BACKSPACE, 259),
            Map.entry(InputConstants.KEY_DELETE, 261), Map.entry(InputConstants.KEY_RIGHT, 262),
            Map.entry(InputConstants.KEY_LEFT, 263), Map.entry(InputConstants.KEY_DOWN, 264),
            Map.entry(InputConstants.KEY_UP, 265), Map.entry(InputConstants.KEY_PAGEUP, 266),
            Map.entry(InputConstants.KEY_PAGEDOWN, 267), Map.entry(InputConstants.KEY_HOME, 268),
            Map.entry(InputConstants.KEY_END, 269), Map.entry(InputConstants.KEY_A, 65),
            Map.entry(InputConstants.KEY_C, 67), Map.entry(InputConstants.KEY_V, 86),
            Map.entry(InputConstants.KEY_X, 88));
    //?}

    private static volatile BooleanSupplier pausing = () -> false;

    protected RootScreen(String title) {
        super(Component.literal(title));
    }

    protected abstract Root getRoot();

    public static @Nullable Root findRootOf(@Nullable Screen screen) {
        return screen instanceof RootScreen hotwire ? hotwire.getRoot() : null;
    }

    protected abstract void drawFrame(GuiCanvas canvas, long nowNanos, int mouseX, int mouseY);

    protected abstract void onResized(int width, int height);

    protected boolean onKeyLeft(int key, int modifiers) {
        return false;
    }

    @Override
    protected void init() {
        this.onResized(this.width, this.height);
        //? if >=26.3 {
        Minecraft.getInstance().textInputManager().startTextInput(this);
        //?}
    }

    @Override
    public void removed() {
        //? if >=26.3 {
        Minecraft.getInstance().textInputManager().stopTextInput(this);
        //?}
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return pausing.getAsBoolean();
    }

    public static void pauseWith(BooleanSupplier choice) {
        pausing = choice;
    }

    //? if >=26.1 {
    public void drawInHold(GuiGraphicsExtractor graphics) {
        this.drawFrame(new GuiCanvas(graphics, this.font), System.nanoTime(), -1, -1);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        this.drawFrame(new GuiCanvas(graphics, this.font), System.nanoTime(), mouseX, mouseY);
    }
    //?} else {
    /*@Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        this.drawFrame(new GuiCanvas(graphics, this.font), System.nanoTime(), mouseX, mouseY);
    }
    *///?}

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        return this.getRoot().scroll(x, y, scrollY) || super.mouseScrolled(x, y, scrollX, scrollY);
    }

    //? if >=1.21.9 {
    static int toKey(int key) {
        return KEYS.getOrDefault(key, key);
    }

    static int toButton(int button) {
        return switch (button) {
            case InputConstants.MOUSE_BUTTON_LEFT -> 0;
            case InputConstants.MOUSE_BUTTON_RIGHT -> 1;
            case InputConstants.MOUSE_BUTTON_MIDDLE -> 2;
            default -> button;
        };
    }

    private static int toModifiers(InputWithModifiers event) {
        return (event.hasShiftDown() ? 1 : 0) | (event.hasControlDown() ? 2 : 0) | (event.hasAltDown() ? 4 : 0);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        return this.getRoot().click(event.x(), event.y(), toButton(event.button()), toModifiers(event))
                || super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        return this.getRoot().drag(event.x(), event.y(), toButton(event.button()))
                || super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return this.getRoot().release(event.x(), event.y(), toButton(event.button()))
                || super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = toKey(event.key());
        int modifiers = toModifiers(event);
        return this.getRoot().pressKey(key, modifiers) || this.onKeyLeft(key, modifiers)
                || super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return this.getRoot().typeChar(event.codepoint()) || super.charTyped(event);
    }
    //?} else {
    /*@Override
    public boolean mouseClicked(double x, double y, int button) {
        int modifiers = (hasShiftDown() ? 1 : 0) | (hasControlDown() ? 2 : 0);
        return this.getRoot().click(x, y, button, modifiers) || super.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dragX, double dragY) {
        return this.getRoot().drag(x, y, button) || super.mouseDragged(x, y, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        return this.getRoot().release(x, y, button) || super.mouseReleased(x, y, button);
    }

    @Override
    public boolean keyPressed(int key, int scancode, int modifiers) {
        return this.getRoot().pressKey(key, modifiers) || this.onKeyLeft(key, modifiers)
                || super.keyPressed(key, scancode, modifiers);
    }

    @Override
    public boolean charTyped(char typed, int modifiers) {
        return this.getRoot().typeChar(typed) || super.charTyped(typed, modifiers);
    }
    *///?}
}
