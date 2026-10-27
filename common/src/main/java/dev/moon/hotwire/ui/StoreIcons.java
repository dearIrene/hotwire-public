package dev.moon.hotwire.ui;
import org.jspecify.annotations.Nullable;

public interface StoreIcons {
    record PagePicture(Canvas.Image image, int width, int height) {
    }

    Canvas.@Nullable Image find(String projectId, String url);

    default @Nullable PagePicture findPicture(String url) {
        return null;
    }

    StoreIcons NONE = new StoreIcons() {
        @Override
        public Canvas.@Nullable Image find(String projectId, String url) {
            return null;
        }
    };
}
