package dev.moon.hotwire.ui;

import java.util.List;
import org.jspecify.annotations.Nullable;

public sealed interface UpdatePreview {
    record Ready(String name, Canvas.@Nullable Image icon, String installedVersion, String newVersion,
            String fileName, UpdateMethod method, List<String> lines) implements UpdatePreview {
        public Ready {
            lines = List.copyOf(lines);
        }

        public static Ready of(String name, Canvas.@Nullable Image icon, String installedVersion, String newVersion,
                String fileName, UpdateMethod method, List<String> lines) {
            return new Ready(name, icon, installedVersion, newVersion, fileName, method, lines);
        }
    }

    record SameVersion(String name, String version) implements UpdatePreview {
    }

    record Unreadable(String fileName) implements UpdatePreview {
    }

    record Refused(String name, String reason) implements UpdatePreview {
    }
}
