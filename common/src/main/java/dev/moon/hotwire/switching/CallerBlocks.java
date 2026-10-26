package dev.moon.hotwire.switching;

import dev.moon.hotwire.plan.SwitchPlan;
import dev.moon.hotwire.ui.Lang;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

public final class CallerBlocks {
    private CallerBlocks() {
    }

    public record Callers(List<String> mods, boolean known) {
        public Callers {
            mods = List.copyOf(mods);
        }

        public @Nullable String findAsker() {
            return this.mods.isEmpty() ? null : this.mods.getFirst();
        }
    }

    public static String describeRow(String id, boolean switchable, Callers callers, Map<String, String> neededBy,
            Function<String, String> nameOf) {
        if (!switchable) {
            return Lang.get("hotwire.api.never");
        }
        if (!callers.known()) {
            return Lang.get("hotwire.api.unknown_row");
        }
        if (id.equals(callers.findAsker())) {
            return Lang.get("hotwire.api.itself");
        }
        if (callers.mods().contains(id)) {
            return Lang.get("hotwire.api.in_request_row");
        }
        String needer = neededBy.get(id);
        return needer == null ? "" : Lang.get("hotwire.api.needs_it", nameOf.apply(needer));
    }

    public static @Nullable String findRefusal(SwitchPlan plan, List<String> asked, Callers callers,
            Function<String, String> nameOf) {
        if (plan.direction() != SwitchPlan.Direction.OFF) {
            return null;
        }
        if (!callers.known()) {
            return Lang.get("hotwire.api.unknown");
        }
        for (String caller : callers.mods()) {
            if (!plan.order().contains(caller)) {
                continue;
            }
            String name = nameOf.apply(caller);
            if (!caller.equals(callers.findAsker())) {
                return Lang.get("hotwire.api.in_request", name);
            }
            return asked.contains(caller) ? Lang.get("hotwire.api.cant_itself", name) : Lang.get(
                    "hotwire.api.takes_along", PlayerMessages.joinNames(asked, nameOf), name, name);
        }
        return null;
    }
}
