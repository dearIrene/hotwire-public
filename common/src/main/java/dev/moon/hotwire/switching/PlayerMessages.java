package dev.moon.hotwire.switching;

import dev.moon.hotwire.plan.SwitchPlan;
import dev.moon.hotwire.plan.SwitchPlan.Refusal;
import dev.moon.hotwire.report.Report;
import dev.moon.hotwire.ui.Lang;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

public final class PlayerMessages {
    private PlayerMessages() {
    }

    public static String describe(ModSwitcher.Result result, Function<String, String> nameOf) {
        return describe(result, nameOf, false);
    }

    public static String describe(ModSwitcher.Result result, Function<String, String> nameOf, boolean kept) {
        boolean off = result.plan().direction() == SwitchPlan.Direction.OFF;
        return switch (result) {
            case ModSwitcher.Result.Done done when done.plan().order().isEmpty() -> Lang.get(off
                    ? "hotwire.switch.already_off" : "hotwire.switch.already_on");
            case ModSwitcher.Result.Done done -> Lang.get(!off ? "hotwire.switch.turned_on"
                    : kept ? "hotwire.switch.turned_off_kept" : "hotwire.switch.turned_off",
                    joinNames(done.plan().order(), nameOf), formatSeconds(done.millis()));
            case ModSwitcher.Result.Refused refused -> describeRefusal(refused.plan().refusals().getFirst(), off,
                    nameOf);
            case ModSwitcher.Result.Failed failed -> Lang.get(off ? "hotwire.switch.failed_off"
                    : "hotwire.switch.failed_on", joinNames(failed.plan().order(), nameOf),
                    String.valueOf(failed.cause().getMessage()).replaceFirst("[.]$", ""));
        };
    }

    public static String describe(Report.OffCheck offCheck) {
        return switch (offCheck) {
            case Report.OffCheck.NotRun notRun -> "";
            case Report.OffCheck.AllOff allOff -> Lang.get("hotwire.switch.fully_off");
            case Report.OffCheck.PartlyOff partly -> Lang.get("hotwire.switch.partly_off", count(partly));
        };
    }

    public static String describeFaults(ModSwitcher.Result result, int faults) {
        if (faults == 0 || !(result instanceof ModSwitcher.Result.Done)) {
            return "";
        }
        return faults == 1 ? Lang.get("hotwire.switch.one_fault") : Lang.get("hotwire.switch.faults", faults);
    }

    private static String count(Report.OffCheck.PartlyOff partly) {
        int total = partly.stillLive().values().stream().mapToInt(List::size).sum();
        return total == 1 ? Lang.get("hotwire.switch.one_live") : Lang.get("hotwire.switch.live", total);
    }

    public static boolean isBad(ModSwitcher.Result result) {
        return !(result instanceof ModSwitcher.Result.Done);
    }

    private static String describeRefusal(Refusal refusal, boolean off, Function<String, String> nameOf) {
        return switch (refusal) {
            case Refusal.NotInstalled missing -> Lang.get("hotwire.refusal.not_installed", missing.mod());
            case Refusal.Protected retained -> Lang.get(off ? "hotwire.refusal.protected_off"
                    : "hotwire.refusal.protected_on", nameOf.apply(retained.mod()));
            case Refusal.Breaks breaks -> Lang.get("hotwire.refusal.breaks", nameOf.apply(breaks.mod()),
                    nameOf.apply(breaks.other()));
            case Refusal.MissingDependency missing -> Lang.get("hotwire.refusal.missing", nameOf.apply(missing.mod()),
                    missing.dependency());
            case Refusal.OldDependency old -> Lang.get("hotwire.refusal.old", nameOf.apply(old.mod()),
                    nameOf.apply(old.dependency()), old.wanted(), old.present());
        };
    }

    public static String joinNames(List<String> ids, Function<String, String> nameOf) {
        if (ids.isEmpty()) {
            return Lang.get("hotwire.names.nothing");
        }
        String first = nameOf.apply(ids.getFirst());
        if (ids.size() == 1) {
            return first;
        }
        return ids.size() == 2 ? Lang.get("hotwire.names.two", first, nameOf.apply(ids.get(1)))
                : Lang.get("hotwire.names.more", first, ids.size() - 1);
    }

    static String formatSeconds(long millis) {
        return millis < 1000 ? Lang.get("hotwire.time.ms", millis) : Lang.get("hotwire.time.seconds",
                String.format(Locale.ROOT, "%.1f", millis / 1000.0));
    }
}
