package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.fabric.version.Spellings;
import dev.moon.hotwire.redefine.Tripwire;
import dev.moon.hotwire.registration.Owners;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.Map;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OffEntries {
    private static final Logger LOGGER = LoggerFactory.getLogger(OffEntries.class);

    private OffEntries() {
    }

    public static void leaveOut(@Nullable Map<Identifier, Object2IntMap<Identifier>> sent) {
        if (sent == null || !Tripwire.isAnyWatched()) {
            return;
        }
        int left = 0;
        for (Registry<?> registry : BuiltInRegistries.REGISTRY) {
            Object2IntMap<Identifier> entries = sent.get(Spellings.getKeyId(registry.key()));
            if (entries != null) {
                left += leaveOut(registry, entries);
            }
        }
        if (left > 0) {
            LOGGER.info("[Hotwire] {} registry entries of off mods were left out of what a joining game is told",
                    left);
        }
    }

    private static <T> int leaveOut(Registry<T> registry, Object2IntMap<Identifier> entries) {
        int before = entries.size();
        entries.keySet().removeIf(id -> {
            T value = Spellings.findRegistryValue(registry, ResourceKey.create(registry.key(), id));
            return value != null && Owners.isOfOffMod(value.getClass());
        });
        return before - entries.size();
    }
}
