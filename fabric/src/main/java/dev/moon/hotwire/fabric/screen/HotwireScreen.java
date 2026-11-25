package dev.moon.hotwire.fabric.screen;

import com.mojang.blaze3d.platform.Window;
import dev.moon.hotwire.fabric.OpenKey;
import dev.moon.hotwire.ui.ModScreen;
import dev.moon.hotwire.ui.Root;
import dev.moon.hotwire.ui.ScreenOpenTimer;
import dev.moon.hotwire.ui.ScreenScale;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

public final class HotwireScreen extends RootScreen {
    private @Nullable ModScreen screen;
    private final ScreenOpenTimer timer;
    private final Supplier<ModScreen> making;
    private float factor = 1;

    public HotwireScreen(ModScreen screen, ScreenOpenTimer timer) {
        this(() -> screen, timer);
    }

    public HotwireScreen(Supplier<ModScreen> making, ScreenOpenTimer timer) {
        super("Hotwire");
        this.making = making;
        this.timer = timer;
    }

    private ModScreen findScreen() {
        ModScreen made = this.screen;
        if (made == null) {
            made = this.making.get();
            made.playEntrance();
            this.screen = made;
        }
        return made;
    }

    public HotwireScreen makeAgain() {
        return new HotwireScreen(this.findScreen(), new ScreenOpenTimer());
    }

    @Override
    protected boolean onKeyLeft(int key, int modifiers) {
        if (modifiers == 0 && !this.getRoot().isTyping() && OpenKey.isOpenKey(key)) {
            this.onClose();
            return true;
        }
        return false;
    }

    @Override
    protected Root getRoot() {
        return this.findScreen().getRoot();
    }

    @Override
    protected void onResized(int width, int height) {
        this.factor = findFactor();
        this.findScreen().resize(Math.round(width / this.factor), Math.round(height / this.factor));
        this.timer.onSized();
    }

    @Override
    protected double toRoot(double screenUnits) {
        return screenUnits / this.factor;
    }

    private static float findFactor() {
        Window shown = Minecraft.getInstance().getWindow();
        int game = Math.max(1, (int) Math.floor(shown.getGuiScale() + 0.5));
        return (float) ScreenScale.find(game, shown.getWidth(), shown.getHeight()) / game;
    }

    @Override
    protected void drawFrame(GuiCanvas canvas, long nowNanos, int mouseX, int mouseY) {
        this.timer.onFrameBegun();
        if (findFactor() != this.factor) {
            this.onResized(this.width, this.height);
        }
        float f = this.factor;
        canvas.pushScale(f, 0, 0);
        this.findScreen().drawFrame(canvas, nowNanos, mouseX < 0 ? mouseX : Math.round(mouseX / f),
                mouseY < 0 ? mouseY : Math.round(mouseY / f));
        canvas.popScale();
        this.timer.onFrameDrawn();
    }

    @Override
    public void onClose() {
        this.findScreen().leave(() -> super.onClose());
    }

    @Override
    protected boolean isBackgroundShown() {
        return Minecraft.getInstance().level == null;
    }

    @Override
    public void onFilesDrop(List<Path> files) {
        this.findScreen().onFilesDropped(files);
    }
}
