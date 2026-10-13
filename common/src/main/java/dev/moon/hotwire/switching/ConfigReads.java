package dev.moon.hotwire.switching;

import com.google.gson.JsonParseException;
import dev.moon.hotwire.mixin_copy.ConfigTrim;
import dev.moon.hotwire.redefine.ClassRebuilder;
import dev.moon.hotwire.redefine.RebuildException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ConfigReads {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigReads.class);

    private ConfigReads() {
    }

    static Map<String, String> findLateMixins(List<String> configs, Map<String, String> ownerOfConfig,
            Set<String> onAtLaunch, SwitchApplier.Game game) {
        Map<String, String> late = new HashMap<>();
        for (String config : configs) {
            String owner = ownerOfConfig.get(config);
            byte[] bytes = owner == null || onAtLaunch.contains(owner) ? null : game.findConfigBytes(config);
            try {
                if (bytes != null) {
                    ConfigTrim.readListed(bytes, game.getSide()).forEach(mixin -> late.put(mixin.replace('.', '/'),
                            config));
                }
            } catch (JsonParseException | IllegalStateException e) {
                LOGGER.warn("[Hotwire] the mixin config {} is not readable JSON, so its accessors are not written",
                        config, e);
            }
        }
        return Map.copyOf(late);
    }

    static Set<String> findUnreadable(Set<String> names, SwitchApplier.Game game, ClassRebuilder rebuilder) {
        Map<String, Class<?>> loaded = new HashMap<>();
        names.forEach(name -> Optional.ofNullable(game.findLoaded(name)).ifPresent(type -> loaded.put(name, type)));
        try {
            Map<String, byte[]> read = rebuilder.getLoadedBytes().read(loaded.values(), new ArrayList<>());
            return loaded.keySet().stream().filter(name -> !read.containsKey(name)).collect(Collectors.toSet());
        } catch (RebuildException e) {
            return names;
        }
    }
}
