package dev.moon.hotwire.ui;

import java.util.ArrayList;
import java.util.List;

final class MessageLine extends Element {
    private static final float RISE = 5;

    private final EasedValue in = EasedValue.ofSpeed(14);
    private String text = "";
    private String hint = "";
    private boolean failure;

    @Override
    protected boolean takesPointer() {
        return false;
    }

    void show(String message, boolean failure) {
        this.show(message, failure, "");
    }

    void show(String message, boolean failure, String hint) {
        String now = getFirstSentence(message.replaceAll("\\s+", " ").strip());
        if (!now.equals(this.text) || !hint.equals(this.hint)) {
            this.in.snap(0);
        }
        this.text = now;
        this.hint = hint;
        this.failure = failure;
    }

    @Override
    public void step(float dt) {
        this.in.to(this.text.isEmpty() ? 0 : 1);
        this.in.step(dt);
        super.step(dt);
    }

    String getText() {
        return this.text;
    }

    static String getFirstSentence(String flat) {
        int end = flat.indexOf(". ");
        return end > 20 ? flat.substring(0, end + 1) : flat;
    }

    record Line(String text, int color) {
    }

    static void addWrapped(Canvas canvas, List<Line> lines, String text, int color, int width) {
        for (String part : wrap(canvas, text, width, Integer.MAX_VALUE)) {
            lines.add(new Line(part, color));
        }
    }

    static boolean isPath(String word) {
        String bare = word.replaceAll("[.,:;)]+$", "");
        return bare.endsWith(".jar") || bare.endsWith(".zip") || (bare.contains("/") && bare.length() > 1);
    }

    static void drawWords(Canvas canvas, String line, int x, int y, int color, float alpha) {
        int at = x;
        StringBuilder run = new StringBuilder();
        boolean runIsPath = false;
        for (String word : line.split(" ", -1)) {
            boolean path = isPath(word);
            if (!run.isEmpty() && path != runIsPath) {
                String text = run.append(' ').toString();
                canvas.drawText(text, at, y, Colors.fade(runIsPath ? Palette.PATH : color, alpha));
                at += canvas.measureText(text);
                run.setLength(0);
            }
            if (!run.isEmpty()) {
                run.append(' ');
            }
            run.append(word);
            runIsPath = path;
        }
        canvas.drawText(run.toString(), at, y, Colors.fade(runIsPath ? Palette.PATH : color, alpha));
    }

    static List<String> wrap(Canvas canvas, String text, int width, int most) {
        List<String> lines = new ArrayList<>();
        String line = "";
        for (String word : text.split(" ", -1)) {
            String longer = line.isEmpty() ? word : line + " " + word;
            if (line.isEmpty() || canvas.measureText(longer) <= width) {
                line = longer;
                continue;
            }
            lines.add(line);
            line = word;
        }
        if (!line.isEmpty()) {
            lines.add(line);
        }
        if (lines.size() > most) {
            lines = new ArrayList<>(lines.subList(0, most));
        }
        return lines;
    }

    @Override
    protected void paint(Canvas canvas, float alpha) {
        if (this.text.isEmpty()) {
            return;
        }
        int room = Math.max(120, this.getWidth() - 48);
        boolean hinted = !this.hint.isEmpty();
        List<String> lines = new ArrayList<>(wrap(canvas, this.text, room, hinted ? 1 : 2));
        List<Integer> colors = new ArrayList<>();
        lines.forEach(line -> colors.add(this.failure ? Palette.BAD : Palette.ON));
        if (hinted) {
            lines.add(this.hint);
            colors.add(Palette.OFF_TINT);
        }
        float in = this.in.getValue();
        int rise = Math.round((1 - in) * RISE);
        int n = lines.size();
        for (int i = 0; i < n; i++) {
            drawWords(canvas, canvas.clip(lines.get(i), room), 24, this.getHeight() - 38 - n * 11 + i * 11 + rise,
                    colors.get(i), alpha * in);
        }
    }
}
