package dev.moon.hotwire.ui;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

public final class Root {
    private final Element base = new Element();
    private final Element menus = new Element();
    private final Element modal = new Element();
    private final FrameClock clock = new FrameClock();
    private List<Element> hoverPath = List.of();
    private @Nullable Element pressed;
    private @Nullable Element focus;

    public Element getBase() {
        return this.base;
    }

    Element getMenus() {
        return this.menus;
    }

    Element getModal() {
        return this.modal;
    }

    public void resize(int width, int height) {
        this.base.setBounds(0, 0, width, height);
        this.menus.setBounds(0, 0, width, height);
        this.modal.setBounds(0, 0, width, height);
    }

    public void drawFrame(Canvas canvas, long nowNanos, double mouseX, double mouseY) {
        this.hover(mouseX, mouseY);
        float dt = this.clock.tick(nowNanos);
        this.base.step(dt);
        this.menus.step(dt);
        this.modal.step(dt);
        this.base.draw(canvas, 1);
        this.menus.draw(canvas, 1);
        this.modal.draw(canvas, 1);
    }

    boolean isModalOpen() {
        return isAnyOpen(this.modal);
    }

    boolean isMenuOpen() {
        return isAnyOpen(this.menus);
    }

    void hover(double x, double y) {
        List<Element> path = new ArrayList<>();
        if (this.isModalOpen()) {
            this.modal.collectUnder(x, y, path);
        } else {
            this.menus.collectUnder(x, y, path);
            if (path.size() <= 1) {
                path.clear();
                this.base.collectUnder(x, y, path);
            }
        }
        this.hoverPath.forEach(element -> element.setHovered(false));
        path.forEach(element -> {
            element.setHovered(true);
            element.setPointer(x, y);
        });
        this.hoverPath = path;
    }

    public boolean click(double x, double y, int button) {
        return this.click(x, y, button, 0);
    }

    public boolean click(double x, double y, int button, int modifiers) {
        if (this.isModalOpen()) {
            this.pressed = this.modal.press(x, y, button, modifiers);
            return true;
        }
        this.pressed = this.menus.press(x, y, button, modifiers);
        if (this.pressed != null) {
            return true;
        }
        List<Element> wereOpen = this.menus.getChildren().stream().filter(Element::open).toList();
        this.pressed = this.base.press(x, y, button, modifiers);
        wereOpen.stream().filter(Element::open).forEach(Element::dismiss);
        return this.pressed != null;
    }

    public boolean drag(double x, double y, int button) {
        return this.pressed != null && this.pressed.onDrag(x, y, button);
    }

    public boolean release(double x, double y, int button) {
        Element released = this.pressed;
        this.pressed = null;
        return released != null && released.onRelease(x, y, button);
    }

    public boolean scroll(double x, double y, double amount) {
        if (this.isModalOpen()) {
            return this.modal.scroll(x, y, amount);
        }
        return this.menus.scroll(x, y, amount) || this.base.scroll(x, y, amount);
    }

    void focus(@Nullable Element element) {
        if (this.focus != null) {
            this.focus.setFocused(false);
        }
        this.focus = element;
        if (element != null) {
            element.setFocused(true);
        }
    }

    public boolean pressKey(int key, int modifiers) {
        if (this.isModalOpen()) {
            return this.modal.pressKey(key, modifiers);
        }
        if (this.focus != null && this.focus.open() && this.focus.onKey(key, modifiers)) {
            return true;
        }
        return this.menus.pressKey(key, modifiers) || this.base.pressKey(key, modifiers);
    }

    public boolean typeChar(int codePoint) {
        if (this.isModalOpen()) {
            return this.modal.typeChar(codePoint);
        }
        if (this.focus != null && this.focus.open() && this.focus.onTyped(codePoint)) {
            return true;
        }
        return this.menus.typeChar(codePoint) || this.base.typeChar(codePoint);
    }

    private static boolean isAnyOpen(Element layer) {
        return layer.getChildren().stream().anyMatch(Element::open);
    }
}
