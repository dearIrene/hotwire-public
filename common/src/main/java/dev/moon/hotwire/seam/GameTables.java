package dev.moon.hotwire.seam;

import org.jspecify.annotations.Nullable;

public interface GameTables {
    GameTables NONE = new GameTables() {
    };

    default @Nullable Object findGameDefault(Object container, Object place) {
        return null;
    }

    default boolean isDrawnIntoChunks(Object container) {
        return false;
    }

    default void redrawChunks() {
    }
}
