package dev.moon.hotwire.ui;

public final class ScreenScale {
    private static final int WIDE = 640;
    private static final int TALL = 360;

    private ScreenScale() {
    }

    public static int find(int game, int wide, int tall) {
        int fits = Math.min(wide / WIDE, tall / TALL);
        int least = (game * 7 + 9) / 10;
        return Math.max(1, Math.min(game, Math.max(fits, least)));
    }
}
