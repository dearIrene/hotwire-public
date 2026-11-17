package dev.moon.hotwire.seam;

import org.jspecify.annotations.Nullable;

public final class Games {
    private static volatile @Nullable Game game;

    private Games() {
    }

    public static void start(Game side) {
        game = side;
    }

    public static boolean isStarted() {
        return game != null;
    }

    public static Game getNow() {
        Game side = game;
        if (side == null) {
            throw new IllegalStateException("the game was asked before it started");
        }
        return side;
    }
}
