package dev.moon.hotwire.ui;

import java.util.List;
import org.jspecify.annotations.Nullable;

public record AddPreview(String name, String version, Canvas.@Nullable Image icon,
        AddMode mode, List<String> lines, List<String> lateReach) {
    public AddPreview {
        lines = List.copyOf(lines);
        lateReach = List.copyOf(lateReach);
    }

    public AddPreview withIcon(Canvas.@Nullable Image icon) {
        return new AddPreview(this.name, this.version, icon, this.mode, this.lines, this.lateReach);
    }

    public static AddPreview ofRefusal(String name, String reason) {
        return new AddPreview(name, "", null, AddMode.REFUSED, List.of(reason), List.of());
    }

    public static AddPreview ofLater(String name, String version, List<String> lines) {
        return new AddPreview(name, version, null, AddMode.NEXT_START, lines, List.of());
    }

    public static AddPreview reopen(String name, String version, List<String> lines) {
        return new AddPreview(name, version, null, AddMode.SAVE_AND_REOPEN, lines, List.of());
    }

    public static AddPreview ofReady(String name, String version, List<String> lines, List<String> lateReach) {
        return new AddPreview(name, version, null, AddMode.NOW, lines, lateReach);
    }
}
