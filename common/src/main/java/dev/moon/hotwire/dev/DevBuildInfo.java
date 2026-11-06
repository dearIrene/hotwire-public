package dev.moon.hotwire.dev;

import java.util.List;

public record DevBuildInfo(int number, int madeOn, long at, List<DevClassChange> classes, List<String> addedClasses,
        List<String> removedClasses, List<String> files, List<String> notes, List<Integer> merged) {
    public DevBuildInfo {
        merged = List.copyOf(merged);
        classes = List.copyOf(classes);
        addedClasses = List.copyOf(addedClasses);
        removedClasses = List.copyOf(removedClasses);
        files = List.copyOf(files);
        notes = List.copyOf(notes);
    }
}
