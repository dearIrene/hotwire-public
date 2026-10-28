package dev.moon.hotwire.ui;

import java.util.Map;
import org.jspecify.annotations.Nullable;

public interface StoreCacheView {
    StoreCacheView NONE = new StoreCacheView() {
        @Override
        public Map<String, Long> getSizes() {
            return Map.of();
        }

        @Override
        public void clear(@Nullable String kind) {
        }

        @Override
        public int getLimit() {
            return 0;
        }

        @Override
        public void setLimit(int megabytes) {
        }
    };

    Map<String, Long> getSizes();

    void clear(@Nullable String kind);

    int getLimit();

    void setLimit(int megabytes);
}
