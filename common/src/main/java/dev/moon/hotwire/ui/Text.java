package dev.moon.hotwire.ui;

import java.util.ArrayList;
import java.util.List;

public final class Text extends Part {
    static final int HEIGHT = 8;
    static final int LINE = 11;
    static final String ELLIPSIS = "…";

    private String text;
    private int ink;
    private boolean wrapped;
    private int scale = 1;
    private int most = Integer.MAX_VALUE;

    private List<String> lines = List.of();

    Text(String text, int ink) {
        this.text = text;
        this.ink = ink;
    }

    public Text set(String text) {
        if (!text.equals(this.text)) {
            this.text = text;
            markChanged();
        }
        return this;
    }

    public Text ink(int ink) {
        this.ink = ink;
        return this;
    }

    public Text wrap() {
        this.wrapped = true;
        return this;
    }

    public Text lines(int most) {
        this.most = most;
        return this.wrap();
    }

    public Text big() {
        this.scale = 2;
        return this;
    }

    public String get() {
        return this.text;
    }

    @Override
    protected void measure(Canvas canvas, int room) {
        if (!this.wrapped) {
            this.hugWidth = canvas.measureText(this.text) * this.scale;
            this.hugHeight = HEIGHT * this.scale;
            return;
        }
        this.lines = findLines(canvas, this.text, room, true);
        if (this.lines.size() > this.most) {
            this.lines = new ArrayList<>(this.lines.subList(0, this.most));
            String last = this.lines.removeLast();
            this.lines.add(canvas.clip(last, room - canvas.measureText(ELLIPSIS)) + ELLIPSIS);
        }
        this.hugWidth = this.lines.stream().mapToInt(canvas::measureText).max().orElse(0);
        this.hugHeight = Math.max(1, this.lines.size()) * LINE - (LINE - HEIGHT);
    }

    @Override
    protected void place(Canvas canvas) {
        if (!this.wrapped) {
            String shown = this.text;
            if (this.getWidth() < this.hugWidth) {
                shown = canvas.clip(shown, this.getWidth() / this.scale - canvas.measureText(ELLIPSIS)) + ELLIPSIS;
            }
            this.lines = List.of(shown);
        }
    }

    static List<String> findLines(Canvas canvas, String text, int room) {
        return findLines(canvas, text, room, false);
    }

    private static List<String> findLines(Canvas canvas, String text, int room, boolean broken) {
        List<String> lines = new ArrayList<>();
        String line = "";
        for (String word : text.split(" ", -1)) {
            String longer = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && canvas.measureText(longer) > room) {
                lines.add(line);
                line = word;
            } else {
                line = longer;
            }
            while (broken && room > 0 && canvas.measureText(line) > room) {
                String head = canvas.clip(line, room);
                int folder = Math.max(head.lastIndexOf('\\'), head.lastIndexOf('/')) + 1;
                if (folder > 0) {
                    head = head.substring(0, folder);
                }
                if (head.isEmpty() || head.length() >= line.length()) {
                    break;
                }
                lines.add(head);
                line = line.substring(head.length());
            }
        }
        if (!line.isEmpty()) {
            lines.add(line);
        }
        return lines;
    }

    @Override
    protected void paint(Canvas canvas, float alpha) {
        int y = this.getY() + (this.wrapped ? 0 : (this.getHeight() - HEIGHT * this.scale) / 2);
        if (this.scale > 1) {
            canvas.pushScale(this.scale, this.getX(), y);
        }
        for (int i = 0; i < this.lines.size(); i++) {
            canvas.drawText(this.lines.get(i), this.getX(), y + i * LINE, Colors.fade(this.ink, alpha));
        }
        if (this.scale > 1) {
            canvas.popScale();
        }
    }

    @Override
    protected boolean takesPointer() {
        return false;
    }
}
