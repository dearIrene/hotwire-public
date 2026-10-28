package dev.moon.hotwire.ui;

import java.util.List;
import org.jspecify.annotations.Nullable;

public interface CacheHost {
    CacheHost NONE = new CacheHost() {
    };

    default @Nullable CacheFill getCacheFill() {
        return null;
    }

    default void fillCache(boolean go) {
    }

    default List<CacheVersion> getCacheVersions() {
        return List.of();
    }

    default void clearCache(@Nullable String version) {
    }

    default void setCacheLimit(int megabytes) {
    }

    default StoreCacheView getStoreCache() {
        return StoreCacheView.NONE;
    }
}
