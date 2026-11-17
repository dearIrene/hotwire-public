package dev.moon.hotwire.ui;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

public interface IconSource extends ImageDecoder {
    Canvas.@Nullable Image of(String id);

    Canvas.@Nullable Image ofJar(Path jar);
}
