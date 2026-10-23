package dev.moon.hotwire.ui;

import java.util.function.Consumer;

final class TextField extends Element {
    static final int ENTER = 257;
    static final int ESCAPE = 256;
    static final int BACKSPACE = 259;
    static final int DELETE = 261;
    static final int RIGHT = 262;
    static final int LEFT = 263;
    static final int HOME = 268;
    static final int END = 269;
    static final int KEY_A = 65;
    static final int SHIFT = 1;
    static final int CONTROL = 2;
    private static final int EDGE = 0xFFA0A0A0;
    private static final int EDGE_FOCUSED = 0xFFFFFFFF;
    private static final int FILL = 0xFF000000;
    private static final int INK = 0xFFE0E0E0;
    private static final int CURSOR = 0xFFD0D0D0;
    private static final int HINT = 0xFF808080;
    private static final int SELECTION = 0x800000FF;
    private static final float BLINK = 0.3f;

    private final String hint;
    private final Consumer<String> onChange;
    private final Runnable onEnter;
    private final Runnable onEscape;
    private String text = "";
    private int cursor;
    private int anchor;
    private int scrolled;
    private float clock;

    public TextField(String hint, Consumer<String> onChange, Runnable onEnter, Runnable onEscape) {
        this.hint = hint;
        this.onChange = onChange;
        this.onEnter = onEnter;
        this.onEscape = onEscape;
    }

    String getText() {
        return this.text;
    }

    void setText(String text) {
        this.text = text;
        this.cursor = text.length();
        this.anchor = this.cursor;
    }

    @Override
    public void step(float dt) {
        this.clock += dt;
        super.step(dt);
    }

    @Override
    protected boolean onClick(double px, double py, int button) {
        this.clock = 0;
        return true;
    }

    @Override
    protected boolean onTyped(int codePoint) {
        if (Character.isISOControl(codePoint) || codePoint == 0xA7) {
            return false;
        }
        this.replace(new String(Character.toChars(codePoint)));
        return true;
    }

    @Override
    protected boolean onKey(int key, int modifiers) {
        boolean shift = (modifiers & SHIFT) != 0;
        boolean control = (modifiers & CONTROL) != 0;
        this.clock = 0;
        switch (key) {
            case ENTER -> this.onEnter.run();
            case ESCAPE -> this.onEscape.run();
            case BACKSPACE -> this.erase(-1, control);
            case DELETE -> this.erase(1, control);
            case LEFT -> this.move(control ? this.findWordEdge(-1) : this.cursor - 1, shift);
            case RIGHT -> this.move(control ? this.findWordEdge(1) : this.cursor + 1, shift);
            case HOME -> this.move(0, shift);
            case END -> this.move(this.text.length(), shift);
            case KEY_A -> {
                if (!control) {
                    return false;
                }
                this.anchor = 0;
                this.cursor = this.text.length();
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void move(int to, boolean extend) {
        this.cursor = Math.max(0, Math.min(this.text.length(), to));
        if (!extend) {
            this.anchor = this.cursor;
        }
    }

    private int findWordEdge(int direction) {
        int at = this.cursor;
        if (direction < 0) {
            while (at > 0 && this.text.charAt(at - 1) == ' ') {
                at--;
            }
            while (at > 0 && this.text.charAt(at - 1) != ' ') {
                at--;
            }
        } else {
            while (at < this.text.length() && this.text.charAt(at) == ' ') {
                at++;
            }
            while (at < this.text.length() && this.text.charAt(at) != ' ') {
                at++;
            }
        }
        return at;
    }

    private void erase(int direction, boolean wholeWord) {
        if (this.cursor == this.anchor) {
            this.anchor = wholeWord ? this.findWordEdge(direction) : Math.max(0, Math.min(this.text.length(),
                    this.cursor + direction));
        }
        this.replace("");
    }

    private void replace(String with) {
        int from = Math.min(this.cursor, this.anchor);
        int to = Math.max(this.cursor, this.anchor);
        String next = this.text.substring(0, from) + with + this.text.substring(to);
        this.cursor = from + with.length();
        this.anchor = this.cursor;
        if (!next.equals(this.text)) {
            this.text = next;
            this.onChange.accept(next);
        }
    }

    @Override
    protected void paint(Canvas canvas, float alpha) {
        int x = this.getX();
        int y = this.getY();
        int w = this.getWidth();
        int h = this.getHeight();
        canvas.fill(x, y, x + w, y + h, Colors.fade(this.isFocused() ? EDGE_FOCUSED : EDGE, alpha));
        canvas.fill(x + 1, y + 1, x + w - 1, y + h - 1, Colors.fade(FILL, alpha));
        int textY = y + (h - 8) / 2;
        if (this.text.isEmpty() && !this.isFocused()) {
            canvas.drawText(this.hint, x + 4, textY, Colors.fade(HINT, alpha));
            return;
        }
        int room = w - 8;
        this.scrolled = Math.min(this.scrolled, this.cursor);
        while (canvas.measureText(this.text.substring(this.scrolled, this.cursor)) > room) {
            this.scrolled++;
        }
        String shown = canvas.clip(this.text.substring(this.scrolled), room);
        canvas.drawText(shown, x + 4, textY, Colors.fade(INK, alpha));
        int end = this.scrolled + shown.length();
        int from = Math.max(this.scrolled, Math.min(Math.min(this.cursor, this.anchor), end));
        int to = Math.max(this.scrolled, Math.min(Math.max(this.cursor, this.anchor), end));
        if (from < to) {
            canvas.fill(x + 4 + this.measureWidthTo(canvas, from), textY - 1, x + 4 + this.measureWidthTo(canvas, to),
                    textY + 9, Colors.fade(SELECTION, alpha));
        }
        if (this.isFocused() && (int) (this.clock / BLINK) % 2 == 0) {
            int at = x + 4 + this.measureWidthTo(canvas, this.cursor);
            if (this.cursor == this.text.length()) {
                canvas.drawText("_", at, textY, Colors.fade(CURSOR, alpha));
            } else {
                canvas.fill(at, textY - 1, at + 1, textY + 9, Colors.fade(CURSOR, alpha));
            }
        }
    }

    private int measureWidthTo(Canvas canvas, int index) {
        return canvas.measureText(this.text.substring(this.scrolled, index));
    }
}
