package dev.moon.hotwire.switching;

import dev.moon.hotwire.plan.SwitchPlan;
import dev.moon.hotwire.ui.Lang;
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
            return Lang.get("hotwire.api.never");
        }
        if (caller == null) {
            return "";
        }
        if (id.equals(caller)) {
            return Lang.get("hotwire.api.itself");
        }
        return needed.contains(id) ? Lang.get("hotwire.api.needs_it", nameOf.apply(caller)) : "";
    }

    public static @Nullable String findRefusal(SwitchPlan plan, List<String> asked, @Nullable String caller,
            Function<String, String> nameOf) {
        if (caller == null || plan.direction() != SwitchPlan.Direction.OFF || !plan.order().contains(caller)) {
            return null;
        }
        String name = nameOf.apply(caller);
        return asked.contains(caller) ? Lang.get("hotwire.api.cant_itself", name) : Lang.get(
                "hotwire.api.takes_along", PlayerMessages.joinNames(asked, nameOf), name, name);
    }
}
