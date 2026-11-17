package dev.moon.hotwire.switching;

import dev.moon.hotwire.report.Report;
import java.util.List;

public record CheckedResult(ModSwitcher.Result result, Report.OffCheck check, List<Report.Failure> faults) {
}
