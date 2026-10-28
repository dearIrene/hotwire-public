package dev.moon.hotwire.fabric.screen;

import dev.moon.hotwire.ui.ModScreen;
import dev.moon.hotwire.ui.Root;
import java.nio.file.Path;
import java.util.List;

public final class HotwireScreen extends RootScreen {
    private final ModScreen screen;
    private final ScreenOpenTimer timer;

    public HotwireScreen(ModScreen screen, ScreenOpenTimer timer) {
        super("Hotwire");
        this.screen = screen;
        this.timer = timer;
    }

    public HotwireScreen makeAgain() {
        return new HotwireScreen(this.screen, new ScreenOpenTimer());
    }

    @Override
    protected Root getRoot() {
        return this.screen.getRoot();
    }

    @Override
    protected void onResized(int width, int height) {
        this.screen.resize(width, height);
        this.timer.onSized();
    }

    @Override
    protected void drawFrame(GuiCanvas canvas, long nowNanos, int mouseX, int mouseY) {
        this.timer.onFrameBegun();
        this.screen.drawFrame(canvas, nowNanos, mouseX, mouseY);
        this.timer.onFrameDrawn();
    }

    @Override
    public void onFilesDrop(List<Path> files) {
        this.screen.onFilesDropped(files);
    }
}
