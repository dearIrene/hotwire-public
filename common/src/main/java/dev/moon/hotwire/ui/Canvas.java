package dev.moon.hotwire.ui;

public interface Canvas {
    void fill(int x0, int y0, int x1, int y1, int argb);

    void fillAcross(int x0, int y0, int x1, int y1, int leftArgb, int rightArgb);

    void fillDown(int x0, int y0, int x1, int y1, int topArgb, int bottomArgb);

    void drawText(String text, int x, int y, int argb);

    int measureText(String text);

    String clip(String text, int width);

    boolean drawImage(Image image, int x, int y, int width, int height, float alpha);

    void pushClip(int x0, int y0, int x1, int y1);

    void popClip();

    void pushScale(float scale, float centerX, float centerY);

    void popScale();

    default void pushShift(float dx, float dy) {
    }

    default void popShift() {
    }

    interface Image {
    }
}
