package dev.moon.hotwire.switching;

import java.util.Set;

@FunctionalInterface
public interface GameObjectRebuilder {
    void rebuild(Set<String> newlyBuilt, Set<String> rebuilt, boolean reload);

    default void announce(Set<String> newlyBuilt, boolean reload) {
    }
}
