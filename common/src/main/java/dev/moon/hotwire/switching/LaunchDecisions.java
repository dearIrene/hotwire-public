package dev.moon.hotwire.switching;

import com.google.gson.JsonParseException;
import dev.moon.hotwire.mixin_copy.ConfigTrim;
import dev.moon.hotwire.mixin_copy.PluginShape;
import dev.moon.hotwire.redefine.RebuildException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class LaunchDecisions {
    private static final Logger LOGGER = LoggerFactory.getLogger(LaunchDecisions.class);

    private final SwitchApplier.Game game;
    private final Map<String, String> ownerOfConfig;
    private final Set<String> onAtLaunch;
    private final MixinChoice choice;

    private final Map<String, Optional<byte[]>> read = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> listed = new ConcurrentHashMap<>();
    private final Map<String, Boolean> transforms = new ConcurrentHashMap<>();
    private final Map<String, Boolean> firstOnly = new ConcurrentHashMap<>();

    record Result(Map<String, byte[]> configs, Set<String> askedAgain) {
        boolean isAskedAgain(String mixin) {
            return this.askedAgain.contains(mixin);
        }
    }

    LaunchDecisions(SwitchApplier.Game game, Map<String, String> ownerOfConfig, Set<String> onAtLaunch,
            MixinChoice choice) {
        this.game = game;
        this.ownerOfConfig = ownerOfConfig;
        this.onAtLaunch = onAtLaunch;
        this.choice = choice;
    }

    void forget() {
        this.read.clear();
        this.listed.clear();
        this.transforms.clear();
        this.firstOnly.clear();
    }

    private byte @Nullable [] findBytes(String name) {
        return this.read.computeIfAbsent(name, key -> Optional.ofNullable(this.game.findConfigBytes(key))).orElse(null);
    }

    Result of(List<String> configs, Map<String, Set<String>> appliedByClass, Predicate<String> on)
            throws RebuildException {
        Set<String> applied = new HashSet<>();
        appliedByClass.values().forEach(applied::addAll);
        Map<String, byte[]> trimmed = new HashMap<>();
        Map<String, byte[]> readable = new LinkedHashMap<>();
        Map<String, Set<String>> listedBy = new HashMap<>();
        Map<String, Set<String>> answeredBy = new HashMap<>();
        for (String config : configs) {
            String owner = this.ownerOfConfig.get(config);
            if (owner != null && !this.onAtLaunch.contains(owner)) {
                Set<String> answers = this.game.findPluginAnswers(config);
                if (answers == null) {
                    continue;
                }
                answeredBy.put(config, answers);
            }
            byte[] bytes = this.findBytes(config);
            if (bytes == null) {
                LOGGER.warn("[Hotwire] the config {} could not be read from the game, so its plugin decides afresh",
                        config);
                continue;
            }
            readable.put(config, bytes);
            try {
                listedBy.put(config, this.listed.computeIfAbsent(config, key -> ConfigTrim.readListed(bytes,
                        this.game.getSide())));
            } catch (JsonParseException | IllegalStateException e) {
                throw new RebuildException("the mixin config " + config + " is not readable JSON", e);
            }
        }
        Map<String, Integer> listings = new HashMap<>();
        listedBy.values().forEach(names -> names.forEach(name -> listings.merge(name, 1, Integer::sum)));

        for (String config : List.copyOf(this.ownerOfConfig.keySet())) {
            if (!listedBy.containsKey(config)) {
                this.findListedOutside(config).forEach(name -> listings.merge(name, 1, Integer::sum));
            }
        }
        int repeated = 0;
        Set<String> asked = new HashSet<>();
        for (Map.Entry<String, byte[]> entry : readable.entrySet()) {
            String config = entry.getKey();
            byte[] bytes = entry.getValue();
            Set<String> own = listedBy.getOrDefault(config, Set.of());
            Predicate<String> elsewhere = name -> listings.getOrDefault(name, 0) > (own.contains(name) ? 1 : 0);
            Set<String> answers = answeredBy.get(config);
            Set<String> decided = answers != null ? answers : applied;
            UnaryOperator<Set<String>> choose = answers != null ? listed -> findAnswered(listed, answers)
                    : listed -> this.choice.choose(listed, appliedByClass, ConfigTrim.readPluginOf(bytes) != null);
            String plugin = ConfigTrim.readPluginOf(bytes);
            boolean live = plugin != null && this.isRunLive(config, plugin);
            String owner = this.ownerOfConfig.get(config);
            boolean ownerOff = owner != null && !on.test(owner);
            try {
                trimmed.put(config, ConfigTrim.repeatDecisions(bytes, this.game.getSide(), choose, decided, elsewhere,
                        name -> !ownerOff && this.isPluginRetained(name, bytes, own, decided, elsewhere, asked, live),
                        name -> live));
                repeated++;
                String refmap = ConfigTrim.readRefmapOf(bytes);
                byte[] map = refmap == null ? null : this.findBytes(refmap);
                if (map != null) {
                    trimmed.put(refmap, map);
                }
            } catch (JsonParseException | IllegalStateException e) {
                throw new RebuildException("the mixin config " + config + " is not readable JSON", e);
            }
        }
        LOGGER.info("[Hotwire] {} classes carry {} mixins; {} configs repeat the game's decisions ({} of them what an"
                + " added mod's plugin answered in the game), {} are decided afresh; {} interface mixins aimed"
                + " elsewhere, named by those, come along", appliedByClass.size(), applied.size(), repeated,
                answeredBy.size(), configs.size() - repeated, this.choice.takeElsewhere());
        return new Result(trimmed, Set.copyOf(asked));
    }

    private boolean isRunLive(String config, String plugin) {
        return this.game.hasLivePlugin(config) && this.firstOnly.computeIfAbsent(plugin,
                name -> PluginShape.isReshapingFirstOnly(name, type -> this.findBytes(type + ".class")));
    }

    private Set<String> findListedOutside(String config) {
        byte[] bytes = this.findBytes(config);
        if (bytes == null) {
            return Set.of();
        }
        try {
            return this.listed.computeIfAbsent(config, key -> ConfigTrim.readListed(bytes, this.game.getSide()));
        } catch (JsonParseException | IllegalStateException e) {
            return Set.of();
        }
    }

    private static Set<String> findAnswered(Set<String> listed, Set<String> answers) {
        Set<String> answered = new HashSet<>(listed);
        answered.retainAll(answers);
        return answered;
    }

    private boolean isPluginRetained(String plugin, byte[] config, Set<String> listed, Set<String> applied,
            Predicate<String> elsewhere, Set<String> asked, boolean live) {
        boolean retained = this.transforms.computeIfAbsent(plugin,
                name -> PluginShape.transforms(name, type -> this.findBytes(type + ".class")));
        if (retained && !live) {
            asked.addAll(listed);
            String prefix = ConfigTrim.readPackageOf(config);
            if (!prefix.isEmpty()) {
                applied.stream().filter(mixin -> mixin.startsWith(prefix) && !elsewhere.test(mixin))
                        .forEach(asked::add);
            }
        }
        return retained;
    }
}
