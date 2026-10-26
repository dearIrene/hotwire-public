package dev.moon.hotwire.ui;
import org.jspecify.annotations.Nullable;

public interface StoreIcons {
    Canvas.@Nullable Image find(String url);

    void releaseAll();

    StoreIcons NONE = new StoreIcons() {
        @Override
        public Canvas.@Nullable Image find(String url) {
            return null;
        }

        @Override
        public void releaseAll() {
        }
    };
}
