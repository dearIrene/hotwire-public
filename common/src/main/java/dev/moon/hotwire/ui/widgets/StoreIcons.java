package dev.moon.hotwire.ui.widgets;
import org.jspecify.annotations.Nullable;

public interface StoreIcons {
    record PagePicture(Canvas.Image image, int width, int height) {
    }

    Canvas.@Nullable Image find(String projectId, String url);

    default @Nullable PagePicture findPicture(String url) {
        return null;
    }
}
