package dev.moon.hotwire.switching;

public final class BetweenFrames {
    private static volatile Runnable drawer = () -> {
    };

    private BetweenFrames() {
    }

    public static void drawWith(Runnable draws) {
        drawer = draws;
    }

    public static void draw() {
        drawer.run();
    }
}
