package dev.moon.hotwire.switching;

import dev.moon.hotwire.registration.RegistrationJournal;
import dev.moon.hotwire.report.Report;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;

public final class OffChecker {
    private final RegistrationJournal registrations;
    private final Supplier<Function<String, List<String>>> threadsRunning;

    public OffChecker(RegistrationJournal registrations, Supplier<Function<String, List<String>>> threadsRunning) {
        this.registrations = registrations;
        this.threadsRunning = threadsRunning;
    }

    public Report.OffCheck check(Collection<String> off, SwitchApplier.ApplyResult applied) {
        Map<String, List<String>> stillLive = new TreeMap<>();
        Function<String, List<String>> threads = this.threadsRunning.get();
        for (String id : off) {
            List<String> found = new ArrayList<>();
            for (RegistrationJournal.Entry entry : this.registrations.getLive(id)) {
                found.add(entry.registration().getKind() + " " + entry.registration().describe());
            }
            found.addAll(threads.apply(id));
            if (!found.isEmpty()) {
                stillLive.put(id, List.copyOf(found));
            }
        }
        if (!applied.leftBehind().isEmpty()) {
            stillLive.put("classes", applied.leftBehind());
        }
        return stillLive.isEmpty() ? new Report.OffCheck.AllOff() : new Report.OffCheck.PartlyOff(stillLive);
    }
}
