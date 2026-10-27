package dev.moon.hotwire.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

final class ManageCachePanel extends Panel {
    static final int WIDTH = 420;
    static final int ROW = 24;
    private static final int CLEAR_W = 64;

    private static final float CONFIRM_SECONDS = 3;

    private final Supplier<List<CacheVersion>> versions;
    private final Consumer<@Nullable String> onClear;
    private List<CacheVersion> shown = List.of();
    private final List<Button> clears = new ArrayList<>();

    private @Nullable String asked;
    private boolean askedAll;
    private float confirming;

    ManageCachePanel(Supplier<List<CacheVersion>> versions, Consumer<@Nullable String> onClear, Runnable onDone) {
        super(Lang.get("hotwire.button.done"), onDone);
        this.versions = versions;
        this.onClear = onClear;
        this.setPrimary(Lang.get("hotwire.cache.clear_all"), this::clearAll);
        this.setPrimaryWidth(80);
    }

    void present() {
        this.confirming = 0;
        this.asked = null;
        this.askedAll = false;
        this.show();
    }

    private void clearAll() {
        if (this.askedAll && this.confirming > 0) {
            this.confirming = 0;
            this.askedAll = false;
            this.onClear.accept(null);
        } else {
            this.asked = null;
            this.askedAll = true;
            this.confirming = CONFIRM_SECONDS;
        }
    }

    private void clear(String version) {
        if (version.equals(this.asked) && this.confirming > 0) {
            this.confirming = 0;
            this.asked = null;
            this.onClear.accept(version);
        } else {
            this.askedAll = false;
            this.asked = version;
            this.confirming = CONFIRM_SECONDS;
        }
    }

    private void showVersions(List<CacheVersion> now) {
        if (now.equals(this.shown)) {
            return;
        }
        this.shown = List.copyOf(now);
        this.clears.forEach(this::remove);
        this.clears.clear();
        for (CacheVersion one : this.shown) {
            this.clears.add(this.add(new Button(Lang.get("hotwire.cache.clear_one"), false,
                    () -> this.clear(one.version()))));
        }
    }

    @Override
    public void step(float dt) {
        this.showVersions(this.versions.get());
        this.size(WIDTH, CONTENT_Y + ROW * Math.max(1, this.shown.size()) + 14 + 46);
        this.confirming = Math.max(0, this.confirming - dt);
        this.setPrimaryLabel(Lang.get(this.askedAll && this.confirming > 0 ? "hotwire.cache.clear_sure"
                : "hotwire.cache.clear_all"));
        this.setPrimaryActive(!this.shown.isEmpty());
        super.step(dt);
        int x = this.getBoxX();
        int w = this.getBoxWidth();
        float s = this.getBoxShown();
        for (int row = 0; row < this.clears.size(); row++) {
            CacheVersion one = this.shown.get(row);
            Button button = this.clears.get(row);
            button.label(Lang.get(one.version().equals(this.asked) && this.confirming > 0 ? "hotwire.cache.clear_sure"
                    : "hotwire.cache.clear_one"));
            button.setBounds(x + w - 14 - CLEAR_W, this.getBoxY() + CONTENT_Y + ROW * row - 5, CLEAR_W, 18);
            button.setAlpha(s);
            button.setVisible(!one.inUse());
            button.setActive(this.open() && s > 0.9f, true);
        }
    }

    @Override
    protected void drawContent(Canvas canvas, float alpha, int x, int y, int w, int h) {
        canvas.drawText(Lang.get("hotwire.cache.manage_title"), x + 14, y + 16, Colors.fade(Palette.TEXT, alpha));
        if (this.shown.isEmpty()) {
            canvas.drawText(Lang.get("hotwire.cache.none"), x + 14, y + CONTENT_Y, Colors.fade(Palette.DIM, alpha));
            return;
        }
        long total = 0;
        for (int row = 0; row < this.shown.size(); row++) {
            CacheVersion one = this.shown.get(row);
            int rowY = y + CONTENT_Y + ROW * row;
            String name = one.current() ? Lang.get("hotwire.cache.version_current", one.version()) : one.version();
            canvas.drawText(name, x + 14, rowY, Colors.fade(Palette.TEXT, alpha));
            String size = CacheFill.describeBytes(one.bytes());
            int sizeX = x + w - 14 - CLEAR_W - 12 - canvas.measureText(size);
            canvas.drawText(size, sizeX, rowY, Colors.fade(Palette.DIM, alpha));
            if (one.inUse()) {
                String busy = Lang.get("hotwire.cache.version_in_use");
                canvas.drawText(canvas.clip(busy, CLEAR_W + 40), x + w - 14 - canvas.measureText(busy), rowY,
                        Colors.fade(Palette.FAINT, alpha));
            }
            total += one.bytes();
        }
        canvas.drawText(Lang.get("hotwire.cache.total", CacheFill.describeBytes(total)), x + 14,
                y + CONTENT_Y + ROW * this.shown.size() + 2, Colors.fade(Palette.DIM, alpha));
    }
}
