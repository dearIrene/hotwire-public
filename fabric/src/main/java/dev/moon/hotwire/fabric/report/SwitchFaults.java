package dev.moon.hotwire.fabric.report;

import dev.moon.hotwire.report.Report;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;

public final class SwitchFaults {
    private static final int LIMIT = 40;
    private static final List<Report.Failure> NOTED = new CopyOnWriteArrayList<>();

    private SwitchFaults() {
    }

    public static void note(String said, @Nullable Throwable thrown) {
        if (NOTED.size() < LIMIT && NOTED.stream().noneMatch(noted -> noted.step().equals(said))) {
            NOTED.add(thrown == null ? new Report.Failure(said, null, List.of())
                    : Report.Failure.of(said, null, thrown));
        }
    }

    public static List<Report.Failure> take() {
        List<Report.Failure> out = List.copyOf(NOTED);
        NOTED.removeAll(out);
        return out;
    }
}
