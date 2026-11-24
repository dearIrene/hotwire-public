package dev.moon.hotwire.update;

import java.util.ArrayList;
import java.util.List;

public final class SwapProblems {
    private static final List<String> MARKS = List.of(" leaves the queue: its new jar is gone", " waits: ",
            " could not ", "the jars could not be moved");

    private SwapProblems() {
    }

    public static List<String> findInLog(String log) {
        List<String> out = new ArrayList<>();
        for (String line : log.lines().toList()) {
            if (MARKS.stream().anyMatch(line::contains)) {
                out.add(line.strip());
            }
        }
        return out;
    }

    public static List<String> findMissed(List<UpdateQueue.Entry> entries, long startedMillis) {
        List<String> out = new ArrayList<>();
        for (UpdateQueue.Entry entry : entries) {
            if (entry.queuedAtMillis() < startedMillis && entry.reason().isEmpty()) {
                out.add(entry.name() + " " + entry.toVersion());
            }
        }
        return out;
    }
}
