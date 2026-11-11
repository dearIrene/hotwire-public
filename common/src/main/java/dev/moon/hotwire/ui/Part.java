package dev.moon.hotwire.ui;

import org.jspecify.annotations.Nullable;

public class Part extends Element {
    static final int HUG = -1;
    private static int changes;

    private int wantWidth = HUG;
    private int wantHeight = HUG;
    private float grow;
    private int most = Integer.MAX_VALUE;
    private boolean spare;

    boolean left;
    private @Nullable Press onPress;

    protected int hugWidth;
    protected int hugHeight;

    private int placedAt = -1;
    private int placedWidth;
    private int placedHeight;

    static int findChanges() {
        return changes;
    }

    protected static void markChanged() {
        changes++;
    }

    public Part width(int width) {
        this.wantWidth = width;
        markChanged();
        return this;
    }

    public Part height(int height) {
        this.wantHeight = height;
        markChanged();
        return this;
    }

    public Part grow(float share) {
        this.grow = share;
        markChanged();
        return this;
    }

    public Part most(int most) {
        this.most = most;
        return this;
    }

    public Part spare() {
        this.spare = true;
        return this;
    }

    final int getMost() {
        return this.most;
    }

    final boolean isSpare() {
        return this.spare;
    }

    public Part show(boolean shown) {
        if (shown != this.isVisible()) {
            this.setVisible(shown);
            markChanged();
        }
        return this;
    }

    public interface Press {
        void press(int button, int modifiers);

        default void drag(double x, double y) {
        }
    }

    public Part press(Press onPress) {
        this.onPress = onPress;
        return this;
    }

    @Override
    protected boolean onClick(double px, double py, int button, int modifiers) {
        if (this.onPress == null) {
            return super.onClick(px, py, button, modifiers);
        }
        this.onPress.press(button, modifiers);
        return true;
    }

    @Override
    protected boolean onDrag(double px, double py, int button) {
        if (this.onPress != null && button == 0) {
            this.onPress.drag(px, py);
        }
        return this.onPress != null;
    }

    final float getGrow() {
        return this.grow;
    }

    final int findWidth() {
        return this.wantWidth == HUG ? this.hugWidth : this.wantWidth;
    }

    final int findHeight() {
        return this.wantHeight == HUG ? this.hugHeight : this.wantHeight;
    }

    protected void measure(Canvas canvas, int room) {
    }

    protected void place(Canvas canvas) {
    }

    final void lay(Canvas canvas, int x, int y, int width, int height) {
        this.setBounds(x, y, width, height);
        this.place(canvas);
    }

    public final void layIfChanged(Canvas canvas, int x, int y, int width, int height) {
        if (this.placedAt != changes || this.placedWidth != width || this.placedHeight != height) {
            this.placedWidth = width;
            this.placedHeight = height;
            this.measure(canvas, width);
            this.lay(canvas, x, y, width, height);

            this.placedAt = changes;
        }
    }
}
