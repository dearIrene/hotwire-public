package dev.moon.hotwire.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import org.jspecify.annotations.Nullable;

final class CachePage {
    private static final int EXPAND_MEGABYTES = 256;
    private static final int LARGE_MEGABYTES = 512;
    private static final int MOST_MEGABYTES = 1024;
    private static final int LEAST_MEGABYTES = 16;

    private static final long SURE_NANOS = 3_000_000_000L;
    private static final long LIST_NANOS = 1_000_000_000L;
    private static final String ALL = "";

    private static final List<String> STORE_KINDS = List.of("search", "page", "versions", "picture");

    private record Row(String key, String name, long bytes, boolean inUse) {
    }

    final Part part;
    private final CacheHost caches;
    private final Text once = Kit.text("", Palette.BAD).wrap();
    private final Text over = Kit.text("", Palette.WARN).wrap();
    private final Push expand;
    private final Push fill = Kit.lead(Lang.get("hotwire.cache.fill_button"), this::toggleFill);
    private final Text status = Kit.dim("");
    private final Text estimate = Kit.dim("");
    private final Tabs which = Kit.tabs(Tabs.Look.BOX, this::pickCache).tab(Lang.get("hotwire.cache.tab.switch"))
            .tab(Lang.get("hotwire.cache.tab.store"));
    private final Line list = Kit.column().gap(4);
    private final Text total = Kit.dim("");
    private final Push clearAll = Kit.push(Lang.get("hotwire.cache.clear_all"), () -> this.clear(ALL));
    private final Limit switchLimit;
    private final Limit storeLimit;
    private boolean storeShown;

    private @Nullable String asked;
    private long askedAt;
    private long listedAt;
    private long now;

    CachePage(CacheHost caches) {
        this.caches = caches;
        StoreCacheView store = caches.getStoreCache();
        this.expand = Kit.push(Lang.get("hotwire.cache.limit_expand"), () -> caches.setCacheLimit(EXPAND_MEGABYTES));
        this.switchLimit = new Limit("hotwire.advanced.cache_limit", "hotwire.cache.limit",
                () -> findMegabytes(caches.getCacheFill()), caches::setCacheLimit);
        this.storeLimit = new Limit("hotwire.advanced.store_limit", "hotwire.cache.store_limit", store::getLimit,
                store::setLimit);
        this.part = Kit.column(
                Kit.text(Lang.get("hotwire.cache.fill_title")).big(),
                Kit.text(Lang.get("hotwire.cache.fill_explains")).wrap(),
                Kit.dim(Lang.get("hotwire.cache.fill_playing")).wrap(), this.once, this.over,
                Kit.row(this.fill, this.expand, this.status, Kit.space()).gap(8),
                Kit.cells(this::findPart), this.estimate,
                Kit.row(Kit.text(Lang.get("hotwire.cache.manage_title")), Kit.rule().grow(1)).gap(6),
                Kit.row(this.which, Kit.space(), this.total, this.clearAll).gap(8),
                Kit.scroll(this.list), this.switchLimit.part, this.storeLimit.part).pad(12, 10).gap(8);
    }

    private static int findMegabytes(@Nullable CacheFill fill) {
        return fill == null || fill.limitBytes() <= 0 ? 0 : (int) (fill.limitBytes() >> 20);
    }

    private double findPart() {
        CacheFill fill = this.caches.getCacheFill();
        return fill == null || fill.of() == 0 ? 0 : fill.done() / (double) fill.of();
    }

    private void toggleFill() {
        CacheFill fill = this.caches.getCacheFill();
        if (fill != null && !fill.isFull()) {
            this.caches.fillCache(!fill.running());
        }
    }

    private void pickCache(int index) {
        this.storeShown = index == 1;
        this.which.choose(index);
        this.asked = null;
        this.listedAt = 0;
    }

    void refresh(long nowNanos) {
        this.now = nowNanos;
        CacheFill fill = this.caches.getCacheFill();
        boolean outdated = fill != null && fill.outdated();
        this.once.set(Lang.get(outdated ? "hotwire.cache.outdated" : "hotwire.cache.fill_once"))
                .ink(outdated ? Palette.WARN : Palette.BAD);
        boolean isOver = fill != null && fill.isOverLimit();
        if (fill != null && isOver) {
            this.over.set(Lang.get("hotwire.cache.limit_over", CacheFill.describeBytes(Math.max(
                    fill.estimatedBytes(), fill.folderBytes())), CacheFill.describeBytes(fill.limitBytes())));
        }
        this.over.show(isOver);
        this.expand.show(fill != null && isOver && fill.limitBytes() < (long) EXPAND_MEGABYTES << 20);
        this.fill.show(fill != null);
        if (fill != null) {
            this.showFill(fill);
        }
        if (this.asked != null && nowNanos - this.askedAt > SURE_NANOS) {
            this.asked = null;
            this.listedAt = 0;
        }
        if (this.listedAt == 0 || nowNanos - this.listedAt > LIST_NANOS) {
            this.listedAt = nowNanos;
            this.fillList();
        }
        this.switchLimit.refresh(!this.storeShown);
        this.storeLimit.refresh(this.storeShown);
    }

    private void showFill(CacheFill fill) {
        String label = "hotwire.cache.fill_button";
        if (fill.isFull()) {
            label = "hotwire.cache.filled_button";
        } else if (fill.running()) {
            label = "hotwire.cache.pause_button";
        } else if (fill.outdated()) {
            label = "hotwire.cache.refill_button";
        } else if (fill.done() > 0) {
            label = "hotwire.cache.resume_button";
        }
        this.fill.label(Lang.get(label)).active(!fill.isFull());
        String left = fill.millisLeft() < 0 || fill.isFull() ? ""
                : Lang.get("hotwire.cache.fill_left", CacheFill.describeMillis(fill.millisLeft()));

        String status = fill.isFull() ? Lang.get("hotwire.cache.fill_full",
                CacheFill.describeBytes(fill.folderBytes())) : Lang.get("hotwire.cache.fill_progress",
                Math.round(this.findPart() * 100), left, CacheFill.describeBytes(fill.savedBytes()));
        this.status.set(fill.running() || fill.done() > 0 ? status : "");
        this.estimate.set(fill.estimatedBytes() < 0 ? Lang.get("hotwire.cache.fill_size_pending")
                : Lang.get("hotwire.cache.fill_size", CacheFill.describeBytes(fill.estimatedBytes())))
                .show(!fill.isFull());
    }

    private void fillList() {
        List<Row> found = new ArrayList<>();
        if (this.storeShown) {
            Map<String, Long> sizes = this.caches.getStoreCache().getSizes();
            for (String kind : STORE_KINDS) {
                long bytes = sizes.getOrDefault(kind, 0L);
                if (bytes > 0) {
                    found.add(new Row(kind, Lang.get("hotwire.cache.store." + kind), bytes, false));
                }
            }
        } else {
            for (CacheVersion one : this.caches.getCacheVersions()) {
                found.add(new Row(one.version(), one.current() ? Lang.get("hotwire.cache.version_current",
                        one.version()) : one.version(), one.bytes(), one.inUse()));
            }
        }
        this.list.clear();
        if (found.isEmpty()) {
            this.list.with(Kit.dim(Lang.get("hotwire.cache.none")));
        }
        for (Row row : found) {
            Part end = row.inUse() ? Kit.dim(Lang.get("hotwire.cache.version_in_use"))
                    : Kit.push(Lang.get(row.key().equals(this.asked) ? "hotwire.cache.clear_sure"
                            : "hotwire.cache.clear_one"), () -> this.clear(row.key()));
            this.list.with(Kit.row(Kit.text(row.name()), Kit.space(),
                    Kit.dim(CacheFill.describeBytes(row.bytes())), end).gap(10));
        }
        this.total.set(Lang.get("hotwire.cache.total", CacheFill.describeBytes(found.stream().mapToLong(Row::bytes)
                .sum()))).show(!found.isEmpty());
        this.clearAll.label(Lang.get(ALL.equals(this.asked) ? "hotwire.cache.clear_sure"
                : "hotwire.cache.clear_all")).show(!found.isEmpty());
    }

    private void clear(String key) {
        if (!key.equals(this.asked)) {
            this.asked = key;
            this.askedAt = this.now;
        } else if (this.storeShown) {
            this.caches.getStoreCache().clear(key.equals(ALL) ? null : key);
            this.asked = null;
        } else {
            this.caches.clearCache(key.equals(ALL) ? null : key);
            this.asked = null;
        }
        this.fillList();
    }

    private static final class Limit {
        private final Part part;
        private final TextField field;
        private final Text note = Kit.dim("");
        private final String noteKey;
        private final IntSupplier limit;

        Limit(String labelKey, String noteKey, IntSupplier limit, IntConsumer onLimit) {
            this.noteKey = noteKey;
            this.limit = limit;
            this.field = new TextField(Lang.get("hotwire.cache.limit_unit"), this::holdToMost, () -> {
                int megabytes = findTyped(this.getTyped());
                if (megabytes >= LEAST_MEGABYTES) {
                    onLimit.accept(megabytes);
                }
                this.stop();
            }, this::stop);
            this.field.acceptOnly(Character::isDigit);
            this.field.typeOnlyOnceClicked();
            this.field.setInk(Limit::findInk);
            this.part = Kit.row(Kit.column(Kit.text(Lang.get(labelKey)), this.note).gap(3), Kit.space(),
                    Kit.fit(this.field).width(48).height(14),
                    Kit.dim(Lang.get("hotwire.cache.limit_unit"))).gap(6);
        }

        private String getTyped() {
            return this.field.getText();
        }

        private void stop() {
            this.field.stopTyping();
        }

        void refresh(boolean shown) {
            this.part.show(shown);
            this.note.set(Lang.get(this.field.isTyping() ? "hotwire.cache.limit_set" : this.noteKey));
            int megabytes = this.limit.getAsInt();
            String text = megabytes <= 0 ? "" : Integer.toString(megabytes);
            if (!this.field.isTyping() && !text.equals(this.field.getText())) {
                this.field.setText(text);
            }
        }

        private void holdToMost(String text) {
            if (findTyped(text) > MOST_MEGABYTES) {
                this.field.setText(Integer.toString(MOST_MEGABYTES));
            }
        }

        private static int findTyped(String text) {
            if (text.isEmpty()) {
                return 0;
            }
            return text.length() > 5 ? Integer.MAX_VALUE : Integer.parseInt(text);
        }

        private static int findInk(String text) {
            int megabytes = findTyped(text);
            if (megabytes >= MOST_MEGABYTES) {
                return Palette.BAD;
            }
            return megabytes > LARGE_MEGABYTES ? Palette.WARN : Palette.TEXT;
        }
    }
}
