package dev.moon.hotwire.api;

import java.util.List;

public interface ModLifecycleListener {
    default void prepareOff(List<String> ids) {
    }

    default void finishOn(List<String> ids) {
    }

    default void prepareStart(List<String> ids) {
    }

    default void finishStart(List<String> ids, boolean ok) {
    }

    default void finishUpdate(List<String> ids) {
    }
}
