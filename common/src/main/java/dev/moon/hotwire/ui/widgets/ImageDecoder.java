package dev.moon.hotwire.ui.widgets;

import java.util.Optional;

public interface ImageDecoder {
    Optional<Canvas.Image> decodeIcon(String name, String url, byte[] bytes);

    Optional<StoreIcons.PagePicture> decodePicture(String name, String url, byte[] bytes);
}
