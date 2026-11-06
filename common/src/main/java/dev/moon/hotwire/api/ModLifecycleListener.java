package dev.moon.hotwire.api;

import java.util.List;

public interface ModLifecycleListener {
    default void beforeOff(List<String> ids) {
    }

    default void afterOn(List<String> ids) {
    }

    default void beforeStart(List<String> ids) {
    }

    default void afterStart(List<String> ids, boolean ok) {
    }

    default void afterUpdate(List<String> ids) {
    }
}
