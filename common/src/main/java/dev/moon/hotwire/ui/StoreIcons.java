package dev.moon.hotwire.ui;
import org.jspecify.annotations.Nullable;

public interface StoreIcons {
    Canvas.@Nullable Image find(String url);

    StoreIcons NONE = new StoreIcons() {
        @Override
        public Canvas.@Nullable Image find(String url) {
            return null;
        }
    };
}
