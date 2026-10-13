package dev.moon.hotwire.fabric.add;

import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.jar.PluginQuestions;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;

public final class LivePlugins {
    private static final Logger LOGGER = LoggerFactory.getLogger(LivePlugins.class);

    private static final Set<String> RAN = ConcurrentHashMap.newKeySet();

    private static final Map<String, Set<String>> ANSWERED = new ConcurrentHashMap<>();

    private LivePlugins() {
    }

    public static @Nullable Set<String> findAnswers(String config) {
        return ANSWERED.get(config);
    }

    static void run(ModManifest manifest, Path jar) {
        List<PluginQuestions> all;
        try {
            all = PluginQuestions.read(jar, manifest);
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] {}'s mixin plugins could not be read to run in the game", manifest.id(), e);
            return;
        }
        for (PluginQuestions questions : all) {
            if (RAN.add(questions.config())) {
                ask(questions);
            }
        }
    }

    private static void ask(PluginQuestions questions) {
        try {
            IMixinConfigPlugin plugin = Class.forName(questions.plugin(), true, LivePlugins.class.getClassLoader())
                    .asSubclass(IMixinConfigPlugin.class).getDeclaredConstructor().newInstance();
            plugin.onLoad(questions.pkg());
            if (questions.asksRefmap()) {
                plugin.getRefMapperConfig();
            }
            Set<String> mine = new TreeSet<>();
            Set<String> accepted = new TreeSet<>();
            int asked = askAbout(plugin, questions.listed(), questions, mine, accepted);
            List<String> more = plugin.getMixins();
            if (more != null) {
                List<String> added = new ArrayList<>();
                more.forEach(name -> added.add(questions.pkg() + "." + name));
                asked += askAbout(plugin, added, questions, mine, accepted);
            }
            plugin.acceptTargets(Collections.unmodifiableSet(mine), Set.of());
            ANSWERED.put(questions.config(), Set.copyOf(accepted));
            LOGGER.info("[Hotwire] {}'s plugin ran in the game as at a launch, asked about {} mixins",
                    questions.config(), asked);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.warn("[Hotwire] {}'s plugin failed as it ran in the game, so what it sets up for the mod's own code"
                    + " may be missing", questions.config(), e);
        }
    }

    private static int askAbout(IMixinConfigPlugin plugin, List<String> mixins, PluginQuestions questions,
            Set<String> mine, Set<String> accepted) {
        for (String mixin : mixins) {
            Set<String> targets = questions.targets().getOrDefault(mixin, Set.of());
            boolean applies = targets.isEmpty();
            for (String target : targets) {
                applies |= plugin.shouldApplyMixin(target, mixin);
                mine.add(target);
            }
            if (applies) {
                accepted.add(mixin);
            }
        }
        return mixins.size();
    }
}
