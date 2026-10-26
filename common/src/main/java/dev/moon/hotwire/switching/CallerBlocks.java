package dev.moon.hotwire.switching;

import dev.moon.hotwire.plan.SwitchPlan;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

public final class CallerBlocks {
    private CallerBlocks() {
    }

    public static String describeRow(String id, boolean switchable, @Nullable String caller, Set<String> needed,
            Function<String, String> nameOf) {
        if (!switchable) {
            return "Hotwire never turns it off.";
        }
        if (caller == null) {
            return "";
        }
        if (id.equals(caller)) {
            return "It can't turn itself off.";
        }
        return needed.contains(id) ? nameOf.apply(caller) + " needs it." : "";
    }

    public static @Nullable String findRefusal(SwitchPlan plan, List<String> asked, @Nullable String caller,
            Function<String, String> nameOf) {
        if (caller == null || plan.direction() != SwitchPlan.Direction.OFF || !plan.order().contains(caller)) {
            return null;
        }
        String name = nameOf.apply(caller);
        return asked.contains(caller) ? name + " can't turn itself off." : "Turning off "
                + PlayerMessages.joinNames(asked, nameOf) + " would turn off " + name + " too, so " + name
                + " can't do it.";
    }
}
