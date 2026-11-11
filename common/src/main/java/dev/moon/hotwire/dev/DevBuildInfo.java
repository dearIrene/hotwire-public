package dev.moon.hotwire.dev;

import java.util.List;

public record DevBuildInfo(int number, int madeOn, long at, List<DevClassChange> classes, List<String> addedClasses,
        List<String> removedClasses, List<String> files, List<String> notes, List<Integer> merged, String git,
        boolean uncommitted) {
    public DevBuildInfo(int number, int madeOn, long at, List<DevClassChange> classes, List<String> addedClasses,
            List<String> removedClasses, List<String> files, List<String> notes, List<Integer> merged) {
        this(number, madeOn, at, classes, addedClasses, removedClasses, files, notes, merged, "", false);
    }

    public DevBuildInfo {
        git = git == null ? "" : git;
        merged = List.copyOf(merged);
        classes = List.copyOf(classes);
        addedClasses = List.copyOf(addedClasses);
        removedClasses = List.copyOf(removedClasses);
        files = List.copyOf(files);
        notes = List.copyOf(notes);
    }

    public static String findBranchOf(String git) {
        int space = git.indexOf(' ');
        return space < 0 ? git : git.substring(0, space);
    }

    public DevBuildInfo withClasses(List<DevClassChange> found) {
        return new DevBuildInfo(this.number, this.madeOn, this.at, found, this.addedClasses, this.removedClasses,
                this.files, this.notes, this.merged, this.git, this.uncommitted);
    }

    public DevBuildInfo withGit(String at, boolean changed) {
        return new DevBuildInfo(this.number, this.madeOn, this.at, this.classes, this.addedClasses, this.removedClasses,
                this.files, this.notes, this.merged, at, changed);
    }
}
