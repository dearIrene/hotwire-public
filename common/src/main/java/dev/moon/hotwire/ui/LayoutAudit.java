package dev.moon.hotwire.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.ToIntFunction;

public final class LayoutAudit implements Canvas {
    private static final int LINE = 8;

    private static final int LEAST_ALPHA = 0x60;
    private static final float SLACK = 0.5f;
    private static final int MOST = 60;

    private record Box(float left, float top, float right, float bottom) {
        Box cut(Box by) {
            return new Box(Math.max(this.left, by.left), Math.max(this.top, by.top), Math.min(this.right, by.right),
                    Math.min(this.bottom, by.bottom));
        }

        boolean isEmpty() {
            return this.right - this.left < 1 || this.bottom - this.top < 1;
        }
    }

    private record Words(String text, Box shown) {
    }

    private final ToIntFunction<String> measure;
    private final BiFunction<String, Integer, String> clipper;
    private final Box screen;
    private final Deque<float[]> scales = new ArrayDeque<>();
    private final Deque<Box> clips = new ArrayDeque<>();
    private final List<Words> drawn = new ArrayList<>();
    private final Set<String> findings = new LinkedHashSet<>();
    private float scale = 1;
    private float shiftX;
    private float shiftY;

    public LayoutAudit(ToIntFunction<String> measure, BiFunction<String, Integer, String> clipper, int width,
            int height) {
        this.measure = measure;
        this.clipper = clipper;
        this.screen = new Box(0, 0, width, height);
    }

    public List<String> getFindings() {
        return this.findings.stream().limit(MOST).toList();
    }

    public int countWords() {
        return this.drawn.size();
    }

    private Box place(float x0, float y0, float x1, float y1) {
        return new Box(x0 * this.scale + this.shiftX, y0 * this.scale + this.shiftY, x1 * this.scale + this.shiftX,
                y1 * this.scale + this.shiftY);
    }

    @Override
    public void drawText(String text, int x, int y, int argb) {
        if (argb >>> 24 < LEAST_ALPHA || text.isBlank()) {
            return;
        }
        Box whole = this.place(x, y, x + this.measure.applyAsInt(text), y + LINE);
        Box frame = this.clips.isEmpty() ? this.screen : this.clips.peek().cut(this.screen);
        Box shown = whole.cut(frame);
        if (shown.isEmpty()) {
            return;
        }
        if (whole.left < this.screen.left - SLACK || whole.right > this.screen.right + SLACK) {
            this.findings.add("past the screen's edge: " + text);
        } else if (whole.left < frame.left - SLACK || whole.right > frame.right + SLACK) {
            this.findings.add("cut sideways by its frame: " + text);
        }
        for (Words other : this.drawn) {
            if (!other.text.equals(text) && !other.shown.cut(shown).isEmpty()) {
                this.findings.add("over other words: " + text + " | " + other.text);
                break;
            }
        }
        this.drawn.add(new Words(text, shown));
    }

    @Override
    public int measureText(String text) {
        return this.measure.applyAsInt(text);
    }

    @Override
    public String clip(String text, int width) {
        return this.clipper.apply(text, width);
    }

    @Override
    public void pushClip(int x0, int y0, int x1, int y1) {
        Box asked = this.place(x0, y0, x1, y1);
        this.clips.push(this.clips.isEmpty() ? asked : asked.cut(this.clips.peek()));
    }

    @Override
    public void popClip() {
        this.clips.pop();
    }

    @Override
    public void pushScale(float by, float centerX, float centerY) {
        this.scales.push(new float[] {this.scale, this.shiftX, this.shiftY});
        this.shiftX += centerX * (1 - by) * this.scale;
        this.shiftY += centerY * (1 - by) * this.scale;
        this.scale *= by;
    }

    @Override
    public void popScale() {
        float[] was = this.scales.pop();
        this.scale = was[0];
        this.shiftX = was[1];
        this.shiftY = was[2];
    }

    @Override
    public void fill(int x0, int y0, int x1, int y1, int argb) {
    }

    @Override
    public void fillAcross(int x0, int y0, int x1, int y1, int leftArgb, int rightArgb) {
    }

    @Override
    public void fillDown(int x0, int y0, int x1, int y1, int topArgb, int bottomArgb) {
    }

    @Override
    public boolean drawImage(Image image, int x, int y, int width, int height, float alpha) {
        return true;
    }
}
