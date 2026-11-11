package dev.moon.hotwire.ui;

import org.jspecify.annotations.Nullable;

final class WindowFrame {
    static final int LEFT = 1;
    static final int RIGHT = 2;
    static final int TOP = 4;
    static final int BOTTOM = 8;

    private static final int EDGE = 4;
    private static final int SNAP = 3;
    private static final long DOUBLE_PRESS_NANOS = 400_000_000L;

    private static final int UNSNAP = 4;
    private static final int MOVE = 16;

    enum Snap { NONE, LEFT, RIGHT, FULL }

    private final int minWidth;
    private final int minHeight;
    private final int titleHeight;
    private final EasedValue drawnX = EasedValue.ofSpeed(18);
    private final EasedValue drawnY = EasedValue.ofSpeed(18);
    private final EasedValue drawnW = EasedValue.ofSpeed(18);
    private final EasedValue drawnH = EasedValue.ofSpeed(18);
    private int screenWidth = 854;
    private int screenHeight = 480;
    private int left;
    private int top;
    private int width;
    private int height;
    private Snap snapped = Snap.NONE;
    private int restoreLeft;
    private int restoreTop;
    private int restoreWidth;
    private int restoreHeight;

    private int holding;
    private double holdX;
    private double holdY;
    private int startLeft;
    private int startTop;
    private int startWidth;
    private int startHeight;
    private Snap preview = Snap.NONE;
    private long lastTitlePress;

    private boolean titlePressed;
    private boolean placed;

    private double @Nullable [] share;

    WindowFrame(int minWidth, int minHeight, int titleHeight) {
        this.minWidth = minWidth;
        this.minHeight = minHeight;
        this.titleHeight = titleHeight;
    }

    void setScreen(int screenWidth, int screenHeight, double @Nullable [] saved, int width, int height, int left,
            int top) {
        int wasWidth = this.screenWidth;
        int wasHeight = this.screenHeight;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        double[] own = this.share;
        if (this.placed && own != null && (wasWidth != screenWidth || wasHeight != screenHeight)) {
            int x = (int) Math.round(own[0] * screenWidth);
            int y = (int) Math.round(own[1] * screenHeight);
            int w = (int) Math.round(own[2] * screenWidth);
            int h = (int) Math.round(own[3] * screenHeight);
            if (this.snapped == Snap.NONE) {
                this.setBounds(x, y, w, h);
                this.clamp();
                this.snapDrawn();
            } else {
                this.restoreLeft = x;
                this.restoreTop = y;
                this.restoreWidth = w;
                this.restoreHeight = h;
            }
        }
        if (!this.placed) {
            this.placed = true;
            if (saved != null && saved.length == 5) {
                this.setBounds((int) Math.round(saved[0] * screenWidth), (int) Math.round(saved[1] * screenHeight),
                        (int) Math.round(saved[2] * screenWidth), (int) Math.round(saved[3] * screenHeight));
                this.clamp();
                if (saved[4] != 0) {
                    this.snapTo(Snap.FULL);
                }
            } else {
                this.setBounds(left, top, width, height);
            }
            this.clamp();
            this.snapDrawn();
            this.saveShare();
            return;
        }
        if (this.snapped != Snap.NONE) {
            this.snapTo(this.snapped);
        }
        this.clamp();
    }

    private void saveShare() {
        double[] saved = this.save();
        this.share = new double[] {saved[0], saved[1], saved[2], saved[3]};
    }

    private void setBounds(int x, int y, int w, int h) {
        this.left = x;
        this.top = y;
        this.width = w;
        this.height = h;
    }

    private void snapDrawn() {
        this.drawnX.snap(this.left);
        this.drawnY.snap(this.top);
        this.drawnW.snap(this.width);
        this.drawnH.snap(this.height);
    }

    private void clamp() {
        this.width = clampTo(this.width, Math.min(this.minWidth, this.screenWidth), Math.max(this.minWidth,
                this.screenWidth));
        this.height = clampTo(this.height, Math.min(this.minHeight, this.screenHeight), Math.max(this.minHeight,
                this.screenHeight));
        this.left = clampTo(this.left, 0, Math.max(0, this.screenWidth - this.width));
        this.top = clampTo(this.top, 0, Math.max(0, this.screenHeight - this.height));
    }

    private static int clampTo(int value, int low, int high) {
        return Math.max(low, Math.min(value, high));
    }

    int getX() {
        return this.drawnX.getRounded();
    }

    int getY() {
        return this.drawnY.getRounded();
    }

    int getWidth() {
        return this.drawnW.getRounded();
    }

    int getHeight() {
        return this.drawnH.getRounded();
    }

    int[] getBounds() {
        return new int[] {this.left, this.top, this.width, this.height};
    }

    boolean isMaximized() {
        return this.snapped != Snap.NONE;
    }

    boolean isGrabbed() {
        return this.holding != 0;
    }

    void step(float dt) {
        this.drawnX.to(this.left);
        this.drawnY.to(this.top);
        this.drawnW.to(this.width);
        this.drawnH.to(this.height);
        if (this.holding != 0) {
            this.snapDrawn();
        } else {
            this.drawnX.step(dt);
            this.drawnY.step(dt);
            this.drawnW.step(dt);
            this.drawnH.step(dt);
        }
    }

    int findEdges(double px, double py) {
        if (this.snapped == Snap.FULL) {
            return 0;
        }
        int edges = 0;
        int x0 = this.left;
        int y0 = this.top;
        int x1 = x0 + this.width;
        int y1 = y0 + this.height;
        if (px < x0 || px >= x1 || py < y0 || py >= y1) {
            return 0;
        }

        boolean nearLeft = px < x0 + EDGE;
        boolean nearRight = px >= x1 - EDGE;
        boolean nearTop = py < y0 + EDGE;
        boolean nearBottom = py >= y1 - EDGE;
        if (nearLeft || (px < x0 + 2 * EDGE && (nearTop || nearBottom))) {
            edges |= LEFT;
        }
        if (nearRight || (px >= x1 - 2 * EDGE && (nearTop || nearBottom))) {
            edges |= RIGHT;
        }
        if (nearTop || (py < y0 + 2 * EDGE && (nearLeft || nearRight))) {
            edges |= TOP;
        }
        if (nearBottom || (py >= y1 - 2 * EDGE && (nearLeft || nearRight))) {
            edges |= BOTTOM;
        }
        return edges;
    }

    Canvas.@Nullable Cursor findCursor(double px, double py, boolean over) {
        int edges;
        if (this.holding != 0) {
            edges = this.holding == MOVE ? 0 : this.holding;
        } else {
            edges = over ? this.findEdges(px, py) : 0;
        }
        boolean across = (edges & (LEFT | RIGHT)) != 0;
        boolean down = (edges & (TOP | BOTTOM)) != 0;
        if (across && down) {
            return Canvas.Cursor.RESIZE_ALL;
        }
        if (across) {
            return Canvas.Cursor.RESIZE_EW;
        }
        return down ? Canvas.Cursor.RESIZE_NS : null;
    }

    boolean press(double px, double py, long nowNanos) {
        int edges = this.findEdges(px, py);
        if (edges != 0) {
            this.hold(edges, px, py);
            return true;
        }
        if (py >= this.top && py < this.top + this.titleHeight && px >= this.left && px < this.left + this.width) {
            if (this.titlePressed && nowNanos - this.lastTitlePress < DOUBLE_PRESS_NANOS) {
                this.titlePressed = false;
                this.toggleMaximized();
                return true;
            }
            this.titlePressed = true;
            this.lastTitlePress = nowNanos;
            this.hold(MOVE, px, py);
            return true;
        }
        return false;
    }

    private void hold(int what, double px, double py) {
        this.holding = what;
        this.holdX = px;
        this.holdY = py;
        this.startLeft = this.left;
        this.startTop = this.top;
        this.startWidth = this.width;
        this.startHeight = this.height;
    }

    void drag(double px, double py) {
        int dx = (int) Math.round(px - this.holdX);
        int dy = (int) Math.round(py - this.holdY);
        if (this.holding == MOVE) {
            this.move(px, py, dx, dy);
        } else if (this.holding != 0) {
            this.size(dx, dy);
        }
    }

    private void move(double px, double py, int dx, int dy) {
        if (this.snapped != Snap.NONE) {
            if (Math.abs(dx) + Math.abs(dy) < UNSNAP) {
                return;
            }

            double along = (this.holdX - this.left) / Math.max(1, this.width);
            this.snapped = Snap.NONE;
            this.width = this.restoreWidth;
            this.height = this.restoreHeight;
            this.left = (int) Math.round(px - along * this.width);
            this.top = (int) Math.round(py - (this.holdY - this.top));
            this.hold(MOVE, px, py);
            this.clamp();
            return;
        }
        this.left = this.startLeft + dx;
        this.top = this.startTop + dy;
        this.clamp();
        if (px <= SNAP) {
            this.preview = Snap.LEFT;
        } else if (px >= this.screenWidth - SNAP - 1) {
            this.preview = Snap.RIGHT;
        } else if (py <= SNAP) {
            this.preview = Snap.FULL;
        } else {
            this.preview = Snap.NONE;
        }
    }

    private void size(int dx, int dy) {
        int x0 = this.startLeft;
        int y0 = this.startTop;
        int x1 = x0 + this.startWidth;
        int y1 = y0 + this.startHeight;

        int leastWidth = Math.min(this.minWidth, this.screenWidth);
        int leastHeight = Math.min(this.minHeight, this.screenHeight);
        if ((this.holding & LEFT) != 0) {
            x0 = clampTo(x0 + dx, 0, x1 - leastWidth);
        }
        if ((this.holding & RIGHT) != 0) {
            x1 = clampTo(x1 + dx, x0 + leastWidth, this.screenWidth);
        }
        if ((this.holding & TOP) != 0) {
            y0 = clampTo(y0 + dy, 0, y1 - leastHeight);
        }
        if ((this.holding & BOTTOM) != 0) {
            y1 = clampTo(y1 + dy, y0 + leastHeight, this.screenHeight);
        }
        this.setBounds(x0, y0, x1 - x0, y1 - y0);
        this.snapped = Snap.NONE;
    }

    boolean release() {
        if (this.holding == 0) {
            return false;
        }
        boolean moved = this.left != this.startLeft || this.top != this.startTop || this.width != this.startWidth
                || this.height != this.startHeight;
        if (this.holding == MOVE && this.preview != Snap.NONE) {
            if (this.snapped == Snap.NONE) {
                this.setBounds(this.startLeft, this.startTop, this.startWidth, this.startHeight);
            }
            this.snapTo(this.preview);
            moved = true;
        }
        this.holding = 0;
        this.preview = Snap.NONE;
        if (moved) {
            this.saveShare();
        }
        return moved;
    }

    void toggleMaximized() {
        if (this.snapped != Snap.NONE) {
            this.snapped = Snap.NONE;
            this.setBounds(this.restoreLeft, this.restoreTop, this.restoreWidth, this.restoreHeight);
            this.clamp();
        } else {
            this.snapTo(Snap.FULL);
        }
    }

    private void snapTo(Snap snap) {
        if (this.snapped == Snap.NONE) {
            this.restoreLeft = this.left;
            this.restoreTop = this.top;
            this.restoreWidth = this.width;
            this.restoreHeight = this.height;
        }
        this.snapped = snap;
        int[] bounds = this.findSnapBounds(snap);
        this.setBounds(bounds[0], bounds[1], bounds[2], bounds[3]);
    }

    private int[] findSnapBounds(Snap snap) {
        int half = this.screenWidth / 2;
        return switch (snap) {
            case LEFT -> new int[] {0, 0, half, this.screenHeight};
            case RIGHT -> new int[] {half, 0, this.screenWidth - half, this.screenHeight};
            case FULL, NONE -> new int[] {0, 0, this.screenWidth, this.screenHeight};
        };
    }

    int @Nullable [] findPreview() {
        return this.holding == MOVE && this.preview != Snap.NONE ? this.findSnapBounds(this.preview) : null;
    }

    double[] save() {
        boolean own = this.snapped == Snap.NONE;
        int x = own ? this.left : this.restoreLeft;
        int y = own ? this.top : this.restoreTop;
        int w = own ? this.width : this.restoreWidth;
        int h = own ? this.height : this.restoreHeight;
        return new double[] {(double) x / this.screenWidth, (double) y / this.screenHeight,
            (double) w / this.screenWidth, (double) h / this.screenHeight, this.snapped == Snap.FULL ? 1 : 0};
    }
}
