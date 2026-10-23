package dev.moon.hotwire.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;

public class Element {
    private final List<Element> children = new ArrayList<>();
    private int x;
    private int y;
    private int width;
    private int height;
    private boolean visible = true;
    private float alpha = 1;
    private boolean hovered;
    private boolean focused;
    private double pointerX;
    private double pointerY;

    public final <T extends Element> T add(T child) {
        this.children.add(child);
        return child;
    }

    public void remove(Element child) {
        this.children.remove(child);
    }

    List<Element> getChildren() {
        return Collections.unmodifiableList(this.children);
    }

    public final void setBounds(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.layOut();
    }

    protected void layOut() {
    }

    int getX() {
        return this.x;
    }

    int getY() {
        return this.y;
    }

    public int getWidth() {
        return this.width;
    }

    public int getHeight() {
        return this.height;
    }

    boolean isVisible() {
        return this.visible;
    }

    final void setVisible(boolean visible) {
        this.visible = visible;
    }

    public boolean open() {
        return this.visible;
    }

    void dismiss() {
        this.visible = false;
    }

    void setAlpha(float alpha) {
        this.alpha = alpha;
    }

    public boolean isHovered() {
        return this.hovered;
    }

    void setHovered(boolean hovered) {
        this.hovered = hovered;
    }

    void setPointer(double x, double y) {
        this.pointerX = x;
        this.pointerY = y;
    }

    void setFocused(boolean focused) {
        this.focused = focused;
    }

    protected final double getPointerX() {
        return this.pointerX;
    }

    protected final double getPointerY() {
        return this.pointerY;
    }

    boolean isFocused() {
        return this.focused;
    }

    boolean contains(double px, double py) {
        return px >= this.x && px < this.x + this.width && py >= this.y && py < this.y + this.height;
    }

    public void step(float dt) {
        for (Element child : this.children) {
            child.step(dt);
        }
    }

    final void draw(Canvas canvas, float parentAlpha) {
        float shown = parentAlpha * this.alpha;
        if (!this.visible || shown <= 0) {
            return;
        }
        this.paint(canvas, shown);
        for (Element child : this.children) {
            child.draw(canvas, shown);
        }
        this.paintOver(canvas, shown);
    }

    protected void paint(Canvas canvas, float alpha) {
    }

    protected void paintOver(Canvas canvas, float alpha) {
    }

    protected boolean onClick(double px, double py, int button, int modifiers) {
        return this.onClick(px, py, button);
    }

    protected boolean onClick(double px, double py, int button) {
        return false;
    }

    protected boolean onDrag(double px, double py, int button) {
        return false;
    }

    protected boolean onRelease(double px, double py, int button) {
        return false;
    }

    protected boolean onScroll(double amount) {
        return false;
    }

    protected boolean onKey(int key, int modifiers) {
        return false;
    }

    protected boolean onTyped(int codePoint) {
        return false;
    }

    final @Nullable Element press(double px, double py, int button) {
        return this.press(px, py, button, 0);
    }

    final @Nullable Element press(double px, double py, int button, int modifiers) {
        if (!this.open() || !this.contains(px, py)) {
            return null;
        }
        for (int i = this.children.size() - 1; i >= 0; i--) {
            Element hit = this.children.get(i).press(px, py, button, modifiers);
            if (hit != null) {
                return hit;
            }
        }
        return this.onClick(px, py, button, modifiers) ? this : null;
    }

    final boolean scroll(double px, double py, double amount) {
        if (!this.open() || !this.contains(px, py)) {
            return false;
        }
        for (int i = this.children.size() - 1; i >= 0; i--) {
            if (this.children.get(i).scroll(px, py, amount)) {
                return true;
            }
        }
        this.pointerX = px;
        this.pointerY = py;
        return this.onScroll(amount);
    }

    final boolean pressKey(int key, int modifiers) {
        if (!this.open()) {
            return false;
        }
        for (int i = this.children.size() - 1; i >= 0; i--) {
            if (this.children.get(i).pressKey(key, modifiers)) {
                return true;
            }
        }
        return this.onKey(key, modifiers);
    }

    final boolean typeChar(int codePoint) {
        if (!this.open()) {
            return false;
        }
        for (int i = this.children.size() - 1; i >= 0; i--) {
            if (this.children.get(i).typeChar(codePoint)) {
                return true;
            }
        }
        return this.onTyped(codePoint);
    }

    protected boolean takesPointer() {
        return true;
    }

    final void collectUnder(double px, double py, List<Element> path) {
        if (!this.open() || !this.takesPointer() || !this.contains(px, py)) {
            return;
        }
        path.add(this);
        for (int i = this.children.size() - 1; i >= 0; i--) {
            int before = path.size();
            this.children.get(i).collectUnder(px, py, path);
            if (path.size() > before) {
                return;
            }
        }
    }
}
