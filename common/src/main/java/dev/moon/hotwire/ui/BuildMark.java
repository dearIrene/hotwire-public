package dev.moon.hotwire.ui;

import java.util.List;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

public final class BuildMark extends Element {
    private static final int PAD = 4;
    private static final int LINE = 11;
    private static final int EDGE = 4;

    private final Supplier<List<String>> lines;
    private final Supplier<double @Nullable []> at;
    private final Placer onPlaced;
    private int screenW;
    private int screenH;
    private double grabX;
    private double grabY;
    private boolean dragging;

    private int measured = 120;

    public interface Placer {
        void place(double x, double y);
    }

    public BuildMark(Supplier<List<String>> lines, Supplier<double @Nullable []> at, Placer onPlaced) {
        this.lines = lines;
        this.at = at;
        this.onPlaced = onPlaced;
    }

    public static String describe(String mod, int build, @Nullable String named) {
        String which = build == 0 ? Lang.get("hotwire.dev.mark.start") : Lang.get("hotwire.dev.mark.build", build);
        return named == null ? Lang.get("hotwire.dev.mark", mod, which)
                : Lang.get("hotwire.dev.mark.named", mod, which, named);
    }

    public static void paintOn(Canvas canvas, int screenW, int screenH, List<String> lines, double @Nullable [] at,
            float alpha) {
        if (lines.isEmpty()) {
            return;
        }
        int w = findWidth(canvas, lines);
        int h = findHeight(lines);
        paintBox(canvas, place(screenW, w, at == null ? null : at[0]), place(screenH, h, at == null ? null : at[1]), w,
                h, lines, alpha);
    }

    private static int place(int screen, int size, @Nullable Double share) {
        return share == null ? Math.max(0, screen - size - EDGE) : (int) Math.round(share * Math.max(0, screen - size));
    }

    private static int findWidth(Canvas canvas, List<String> lines) {
        int widest = 0;
        for (String line : lines) {
            widest = Math.max(widest, canvas.measureText(line));
        }
        return widest + 2 * PAD;
    }

    private static int findHeight(List<String> lines) {
        return lines.size() * LINE + 2 * PAD - 2;
    }

    private static void paintBox(Canvas canvas, int x, int y, int w, int h, List<String> lines, float alpha) {
        canvas.fill(x, y, x + w, y + h, Colors.fade(0xA0140F1E, alpha));
        canvas.fill(x, y, x + 1, y + h, Colors.fade(Palette.ON, alpha));
        for (int i = 0; i < lines.size(); i++) {
            canvas.drawText(lines.get(i), x + PAD, y + PAD + i * LINE, Colors.fade(i == 0 ? Palette.TEXT : Palette.DIM,
                    alpha));
        }
    }

    public int[] findBounds() {
        return new int[] {this.getX(), this.getY(), this.getWidth(), this.getHeight()};
    }

    public void setScreen(int width, int height) {
        this.screenW = width;
        this.screenH = height;
    }

    @Override
    public void step(float dt) {
        List<String> shown = this.lines.get();
        if (shown.isEmpty()) {
            this.setBounds(0, 0, 0, 0);
            super.step(dt);
            return;
        }
        if (this.dragging) {
            super.step(dt);
            return;
        }
        int w = this.measured;
        int h = findHeight(shown);
        double[] share = this.at.get();
        this.setBounds(place(this.screenW, w, share == null ? null : share[0]),
                place(this.screenH, h, share == null ? null : share[1]), w, h);
        super.step(dt);
    }

    @Override
    protected void paint(Canvas canvas, float alpha) {
        List<String> shown = this.lines.get();
        if (shown.isEmpty()) {
            return;
        }
        int w = findWidth(canvas, shown);
        if (w != this.measured && !this.dragging) {
            this.measured = w;
            this.setBounds(this.getX(), this.getY(), w, findHeight(shown));
        }
        paintBox(canvas, this.getX(), this.getY(), this.getWidth(), this.getHeight(), shown, alpha);
        if (this.isHovered() || this.dragging) {
            canvas.requestCursor(Canvas.Cursor.RESIZE_ALL);
        }
    }

    @Override
    protected boolean onClick(double px, double py, int button) {
        if (button != 0) {
            return false;
        }
        this.dragging = true;
        this.grabX = px - this.getX();
        this.grabY = py - this.getY();
        return true;
    }

    @Override
    protected boolean onDrag(double px, double py, int button) {
        if (!this.dragging) {
            return false;
        }
        int x = (int) Math.round(Math.clamp(px - this.grabX, 0, Math.max(0, this.screenW - this.getWidth())));
        int y = (int) Math.round(Math.clamp(py - this.grabY, 0, Math.max(0, this.screenH - this.getHeight())));
        this.setBounds(x, y, this.getWidth(), this.getHeight());
        return true;
    }

    @Override
    protected boolean onRelease(double px, double py, int button) {
        if (!this.dragging) {
            return false;
        }
        this.dragging = false;
        double roomX = Math.max(1, this.screenW - this.getWidth());
        double roomY = Math.max(1, this.screenH - this.getHeight());
        this.onPlaced.place(this.getX() / roomX, this.getY() / roomY);
        return true;
    }
}
