package dev.moon.hotwire.ui;

import java.util.ArrayList;
import java.util.List;

final class MessageLine extends Element {
    private String text = "";
    private boolean failure;

    @Override
    protected boolean takesPointer() {
        return false;
    }

    void show(String message, boolean failure) {
        this.text = getFirstSentence(message.replaceAll("\\s+", " ").strip());
        this.failure = failure;
    }

    String getText() {
        return this.text;
    }

    static String getFirstSentence(String flat) {
        int end = flat.indexOf(". ");
        return end > 20 ? flat.substring(0, end + 1) : flat;
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
        List<String> lines = wrap(canvas, this.text, Math.max(120, this.getWidth() - 260), 2);
        int color = Colors.fade(this.failure ? Palette.BAD : Palette.DIM, alpha);
        int n = lines.size();
        for (int i = 0; i < n; i++) {
            canvas.drawText(canvas.clip(lines.get(i), Math.max(120, this.getWidth() - 260)), 24,
                    this.getHeight() - 40 - n * 11 + i * 11, color);
        }
    }
}
