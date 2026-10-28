package dev.moon.hotwire.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

final class ManageCachePanel extends Panel {
    static final int WIDTH = 420;
    static final int ROW = 24;
    private static final int CLEAR_W = 64;

    private static final int ROWS_Y = CONTENT_Y + ChoiceBar.HEIGHT + 12;

    private static final float CONFIRM_SECONDS = 3;

    private static final List<String> STORE_KINDS = List.of("search", "page", "versions", "picture");

    private record Row(String key, String name, long bytes, boolean inUse) {
    }

    private final Supplier<List<CacheVersion>> versions;
    private final Consumer<@Nullable String> onClear;
    private final StoreCacheView store;
    private final ChoiceBar caches;
    private final LimitRow switchLimit;
    private final LimitRow storeLimit;
    private List<Row> shown = List.of();
    private final List<Button> clears = new ArrayList<>();

    private @Nullable String asked;
    private boolean askedAll;
    private float confirming;

    ManageCachePanel(Supplier<List<CacheVersion>> versions, Consumer<@Nullable String> onClear,
            IntSupplier switchLimit, IntConsumer onSwitchLimit, StoreCacheView store, Runnable onDone) {
        super(Lang.get("hotwire.button.done"), onDone);
        this.versions = versions;
        this.onClear = onClear;
        this.store = store;
        this.caches = this.add(new ChoiceBar(List.of(ChoiceBar.Choice.of(Lang.get("hotwire.cache.tab.switch")),
                ChoiceBar.Choice.of(Lang.get("hotwire.cache.tab.store"))), 0, this::pickCache));
        this.switchLimit = new LimitRow(this, switchLimit, onSwitchLimit, "hotwire.advanced.cache_limit",
                "hotwire.cache.limit");
        this.storeLimit = new LimitRow(this, store::getLimit, store::setLimit, "hotwire.advanced.store_limit",
                "hotwire.cache.store_limit");
        this.setPrimary(Lang.get("hotwire.cache.clear_all"), this::clearAll);
        this.setPrimaryWidth(80);
    }

    void present() {
        this.confirming = 0;
        this.asked = null;
        this.askedAll = false;
        for (LimitRow row : List.of(this.switchLimit, this.storeLimit)) {
            row.stopTyping();
            row.show();
        }
        this.show();
    }

    private boolean isStore() {
        return this.caches.getChosen() == 1;
    }

    private void pickCache(int index) {
        this.caches.setChosen(index);
        this.confirming = 0;
        this.asked = null;
        this.askedAll = false;
        this.switchLimit.stopTyping();
        this.storeLimit.stopTyping();
    }

    private void clearAll() {
        if (this.askedAll && this.confirming > 0) {
            this.confirming = 0;
            this.askedAll = false;
            if (this.isStore()) {
                this.store.clear(null);
            } else {
                this.onClear.accept(null);
            }
        } else {
            this.asked = null;
            this.askedAll = true;
            this.confirming = CONFIRM_SECONDS;
        }
    }

    private void clear(String key) {
        if (key.equals(this.asked) && this.confirming > 0) {
            this.confirming = 0;
            this.asked = null;
            if (this.isStore()) {
                this.store.clear(key);
            } else {
                this.onClear.accept(key);
            }
        } else {
            this.askedAll = false;
            this.asked = key;
            this.confirming = CONFIRM_SECONDS;
        }
    }

    private List<Row> findRows() {
        List<Row> rows = new ArrayList<>();
        if (this.isStore()) {
            Map<String, Long> sizes = this.store.getSizes();
            for (String kind : STORE_KINDS) {
                long bytes = sizes.getOrDefault(kind, 0L);
                if (bytes > 0) {
                    rows.add(new Row(kind, Lang.get("hotwire.cache.store." + kind), bytes, false));
                }
            }
        } else {
            for (CacheVersion one : this.versions.get()) {
                rows.add(new Row(one.version(), one.current() ? Lang.get("hotwire.cache.version_current",
                        one.version()) : one.version(), one.bytes(), one.inUse()));
            }
        }
        return rows;
    }

    private void showRows(List<Row> now) {
        if (now.equals(this.shown)) {
            return;
        }
        boolean same = now.size() == this.shown.size();
        for (int i = 0; same && i < now.size(); i++) {
            same = now.get(i).key().equals(this.shown.get(i).key());
        }
        this.shown = List.copyOf(now);
        if (same) {
            return;
        }
        this.clears.forEach(this::remove);
        this.clears.clear();
        for (Row one : this.shown) {
            this.clears.add(this.add(new Button(Lang.get("hotwire.cache.clear_one"), false,
                    () -> this.clear(one.key()))));
        }
    }

    private int getRowsHeight() {
        return ROW * Math.max(1, this.shown.size()) + 14;
    }

    @Override
    protected boolean onClick(double px, double py, int button) {
        this.switchLimit.stopTyping();
        this.storeLimit.stopTyping();
        return super.onClick(px, py, button);
    }

    @Override
    public void step(float dt) {
        this.showRows(this.findRows());
        this.size(WIDTH, ROWS_Y + this.getRowsHeight() + LimitRow.HEIGHT + 46);
        this.confirming = Math.max(0, this.confirming - dt);
        this.setPrimaryLabel(Lang.get(this.askedAll && this.confirming > 0 ? "hotwire.cache.clear_sure"
                : "hotwire.cache.clear_all"));
        this.setPrimaryActive(!this.shown.isEmpty());
        super.step(dt);
        int x = this.getBoxX();
        int w = this.getBoxWidth();
        float s = this.getBoxShown();
        this.caches.setBounds(x + 14, this.getBoxY() + CONTENT_Y, w - 28, ChoiceBar.HEIGHT);
        this.caches.setAlpha(s);
        this.switchLimit.setVisible(!this.isStore());
        this.storeLimit.setVisible(this.isStore());
        for (int row = 0; row < this.clears.size() && row < this.shown.size(); row++) {
            Row one = this.shown.get(row);
            Button button = this.clears.get(row);
            button.label(Lang.get(one.key().equals(this.asked) && this.confirming > 0 ? "hotwire.cache.clear_sure"
                    : "hotwire.cache.clear_one"));
            button.setBounds(x + w - 14 - CLEAR_W, this.getBoxY() + ROWS_Y + ROW * row - 5, CLEAR_W, 18);
            button.setAlpha(s);
            button.setVisible(!one.inUse());
            button.setActive(this.open() && s > 0.9f, true);
        }
    }

    @Override
    protected void drawContent(Canvas canvas, float alpha, int x, int y, int w, int h) {
        canvas.drawText(Lang.get("hotwire.cache.manage_title"), x + 14, y + 16, Colors.fade(Palette.TEXT, alpha));
        int limitY = y + ROWS_Y + this.getRowsHeight() + 8;
        (this.isStore() ? this.storeLimit : this.switchLimit).draw(canvas, x, limitY, w, alpha);
        if (this.shown.isEmpty()) {
            canvas.drawText(Lang.get("hotwire.cache.none"), x + 14, y + ROWS_Y, Colors.fade(Palette.DIM, alpha));
            return;
        }
        long total = 0;
        for (int row = 0; row < this.shown.size(); row++) {
            Row one = this.shown.get(row);
            int rowY = y + ROWS_Y + ROW * row;
            canvas.drawText(one.name(), x + 14, rowY, Colors.fade(Palette.TEXT, alpha));
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
                y + ROWS_Y + ROW * this.shown.size() + 2, Colors.fade(Palette.DIM, alpha));
    }
}
