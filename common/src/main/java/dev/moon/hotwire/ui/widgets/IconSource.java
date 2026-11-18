package dev.moon.hotwire.ui.widgets;

import java.nio.file.Path;
import java.util.Collection;
import org.jspecify.annotations.Nullable;

public interface IconSource extends ImageDecoder {
    Canvas.@Nullable Image of(String id);

    Canvas.@Nullable Image ofJar(Path jar);

    void readAhead(Collection<String> ids);

    void close();
}
