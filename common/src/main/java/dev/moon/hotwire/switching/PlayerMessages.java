package dev.moon.hotwire.switching;

import dev.moon.hotwire.plan.SwitchPlan;
import dev.moon.hotwire.plan.SwitchPlan.Refusal;
import dev.moon.hotwire.report.Report;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

public final class PlayerMessages {
    private PlayerMessages() {
    }

    public static String describe(ModSwitcher.Result result, Function<String, String> nameOf) {
        boolean off = result.plan().direction() == SwitchPlan.Direction.OFF;
        return switch (result) {
            case ModSwitcher.Result.Done done when done.plan().order().isEmpty() -> off ? "Already off."
                    : "Already on.";

            case ModSwitcher.Result.Done done -> (off ? "Turned off " : "Turned on ")
                    + joinNames(done.plan().order(), nameOf) + " in " + formatSeconds(done.millis())
                    + (off ? ", until the game closes." : ".");
            case ModSwitcher.Result.Refused refused -> describeRefusal(refused.plan().refusals().getFirst(), off,
                    nameOf);
            case ModSwitcher.Result.Failed failed -> "Couldn't turn " + (off ? "off " : "on ")
                    + joinNames(failed.plan().order(), nameOf) + ", so nothing changed: "
                    + String.valueOf(failed.cause().getMessage()).replaceFirst("[.]$", "")
                    + ". The log has the details.";
        };
    }

    public static String describe(Report.OffCheck offCheck) {
        return switch (offCheck) {
            case Report.OffCheck.NotRun notRun -> "";
            case Report.OffCheck.AllOff allOff -> " Fully off.";
            case Report.OffCheck.PartlyOff partly -> " Partly off: " + count(partly)
                    + " still live. The report says what.";
        };
    }

    public static String describeFaults(ModSwitcher.Result result, int faults) {
        if (faults == 0 || !(result instanceof ModSwitcher.Result.Done)) {
            return "";
        }
        return (faults == 1 ? " 1 thing" : " " + faults + " things") + " went wrong on the way. The report says what.";
    }

    private static String count(Report.OffCheck.PartlyOff partly) {
        int total = partly.stillLive().values().stream().mapToInt(List::size).sum();
        return total == 1 ? "1 thing is" : total + " things are";
    }

    public static boolean isBad(ModSwitcher.Result result) {
        return !(result instanceof ModSwitcher.Result.Done);
    }

    private static String describeRefusal(Refusal refusal, boolean off, Function<String, String> nameOf) {
        return switch (refusal) {
            case Refusal.NotInstalled missing -> missing.mod() + " is not installed.";
            case Refusal.Protected retained -> nameOf.apply(retained.mod()) + " can't be turned "
                    + (off ? "off" : "on") + ": the game needs it.";
            case Refusal.Breaks breaks -> nameOf.apply(breaks.mod()) + " can't come on while "
                    + nameOf.apply(breaks.other()) + " is on, because it says it breaks it.";
            case Refusal.MissingDependency missing -> nameOf.apply(missing.mod()) + " needs "
                    + missing.dependency() + ", which is not installed.";
            case Refusal.OldDependency old -> nameOf.apply(old.mod()) + " needs " + nameOf.apply(old.dependency())
                    + " " + old.wanted() + ", and the game has " + old.present() + ".";
        };
    }

    static String joinNames(List<String> ids, Function<String, String> nameOf) {
        if (ids.isEmpty()) {
            return "nothing";
        }
        String first = nameOf.apply(ids.getFirst());
        if (ids.size() == 1) {
            return first;
        }
        return ids.size() == 2 ? first + " and " + nameOf.apply(ids.get(1)) : first + " and " + (ids.size() - 1)
                + " more";
    }

    static String formatSeconds(long millis) {
        return millis < 1000 ? millis + " ms" : String.format(Locale.ROOT, "%.1f seconds", millis / 1000.0);
    }
}
