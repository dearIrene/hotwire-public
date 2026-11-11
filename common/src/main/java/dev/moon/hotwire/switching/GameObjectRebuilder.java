package dev.moon.hotwire.switching;

import java.util.Set;

@FunctionalInterface
public interface GameObjectRebuilder {
    void rebuild(Set<String> newlyBuilt, Set<String> rebuilt, Set<String> reloadFor);

    default void closeBuilt(Set<String> newlyBuilt) {
    }

    default void announce(Set<String> newlyBuilt, Set<String> reloadFor) {
    }
}
