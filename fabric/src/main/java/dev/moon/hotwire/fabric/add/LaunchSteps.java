package dev.moon.hotwire.fabric.add;

import dev.moon.hotwire.fabric.registration.GameRegistrations;
import dev.moon.hotwire.fabric.registration.ModLifecycle;
import dev.moon.hotwire.fabric.registration.OffModCommands;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LaunchSteps {
    private static final Logger LOGGER = LoggerFactory.getLogger(LaunchSteps.class);

    public record Step(String name, Runnable run) {
    }

    private LaunchSteps() {
    }

    public static List<Step> afterStart(List<String> ids, Function<String, @Nullable Class<?>> findLoaded,
            Map<String, List<String>> setUp) {
        return List.of(
                new Step("host lists", () -> HostLists.join(ids)),
                new Step("entrypoint lists", () -> EntrypointLists.join(ids, findLoaded)),
                new Step("client commands", OffModCommands::rebuildClientCommands),
                new Step("client-started listeners", () -> {
                    for (String id : ids) {
                        setUp.put(id, ModLifecycle.runSetup(id, GameRegistrations.getJournal(),
                                Minecraft.getInstance()));
                    }
                }));
    }

    public static void run(String what, List<Step> steps, Set<String> skipped) {
        List<String> ran = new ArrayList<>();
        for (Step step : steps) {
            if (skipped.contains(step.name())) {
                continue;
            }
            step.run();
            ran.add(step.name());
        }
        LOGGER.info("[Hotwire] {}: launch steps {}{}", what, ran, skipped.isEmpty() ? "" : ", skipped " + skipped);
    }
}
