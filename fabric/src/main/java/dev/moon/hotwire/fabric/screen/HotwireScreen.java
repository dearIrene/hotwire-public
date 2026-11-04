package dev.moon.hotwire.fabric.screen;

import dev.moon.hotwire.ui.ModScreen;
import dev.moon.hotwire.ui.Root;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

public final class HotwireScreen extends RootScreen {
    private @Nullable ModScreen screen;
    private final ScreenOpenTimer timer;
    private final Supplier<ModScreen> making;

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
            this.screen = made;
        }
        return made;
    }

    public HotwireScreen makeAgain() {
        return new HotwireScreen(this.findScreen(), new ScreenOpenTimer());
    }

    @Override
    protected Root getRoot() {
        return this.findScreen().getRoot();
    }

    @Override
    protected void onResized(int width, int height) {
        this.findScreen().resize(width, height);
        this.timer.onSized();
    }

    @Override
    protected void drawFrame(GuiCanvas canvas, long nowNanos, int mouseX, int mouseY) {
        this.timer.onFrameBegun();
        this.findScreen().drawFrame(canvas, nowNanos, mouseX, mouseY);
        this.timer.onFrameDrawn();
    }

    @Override
    public void onFilesDrop(List<Path> files) {
        this.findScreen().onFilesDropped(files);
    }
}
