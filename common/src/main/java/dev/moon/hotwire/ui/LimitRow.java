package dev.moon.hotwire.ui;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import org.jspecify.annotations.Nullable;

final class LimitRow {
    static final int FIELD_W = 48;
    static final int HEIGHT = 36;
    private static final int LARGE_MEGABYTES = 512;
    static final int MOST_MEGABYTES = 1024;
    static final int LEAST_MEGABYTES = 16;

    private final TextField field;
    private final IntSupplier limit;
    private final IntConsumer onLimit;
    private final String labelKey;
    private final String noteKey;

    LimitRow(Element parent, IntSupplier limit, IntConsumer onLimit, String labelKey, String noteKey) {
        this.limit = limit;
        this.onLimit = onLimit;
        this.labelKey = labelKey;
        this.noteKey = noteKey;
        this.field = parent.add(new TextField(Lang.get("hotwire.cache.limit_unit"), this::onTyped, this::set,
                this::show));
        this.field.acceptOnly(Character::isDigit);
        this.field.typeOnlyOnceClicked();
        this.field.setInk(LimitRow::findInk);
    }

    static int findMegabytes(@Nullable CacheFill fill) {
        return fill == null || fill.limitBytes() <= 0 ? 0 : (int) (fill.limitBytes() >> 20);
    }

    void show() {
        int now = this.limit.getAsInt();
        this.field.setText(now <= 0 ? "" : Integer.toString(now));
    }

    void stopTyping() {
        this.field.stopTyping();
    }

    void setVisible(boolean visible) {
        this.field.setVisible(visible);
    }

    private void onTyped(String text) {
        if (findMegabytes(text) > MOST_MEGABYTES) {
            this.field.setText(Integer.toString(MOST_MEGABYTES));
        }
    }

    private void set() {
        int megabytes = findMegabytes(this.field.getText());
        if (megabytes >= LEAST_MEGABYTES) {
            this.onLimit.accept(megabytes);
        } else {
            this.show();
        }
    }

    private static int findMegabytes(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        return text.length() > 5 ? Integer.MAX_VALUE : Integer.parseInt(text);
    }

    private static int findInk(String text) {
        int megabytes = findMegabytes(text);
        if (megabytes >= MOST_MEGABYTES) {
            return Palette.BAD;
        }
        return megabytes > LARGE_MEGABYTES ? Palette.WARN : Palette.TEXT;
    }

    void draw(Canvas canvas, int x, int rowY, int w, float alpha) {
        String unit = Lang.get("hotwire.cache.limit_unit");
        int fieldX = x + w - 14 - canvas.measureText(unit) - 6 - FIELD_W;
        this.field.setBounds(fieldX, rowY - 2, FIELD_W, 14);
        canvas.drawText(unit, fieldX + FIELD_W + 6, rowY + 1, Colors.fade(Palette.DIM, alpha));
        canvas.drawText(canvas.clip(Lang.get(this.labelKey), fieldX - x - 20), x + 14, rowY,
                Colors.fade(Palette.TEXT, alpha));
        String explains = Lang.get(this.field.isTyping() ? "hotwire.cache.limit_set" : this.noteKey);
        canvas.drawText(canvas.clip(explains, w - 28), x + 14, rowY + 20, Colors.fade(Palette.DIM, alpha));
    }
}
