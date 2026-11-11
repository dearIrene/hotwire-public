package dev.moon.hotwire.fabric.registration;

import it.unimi.dsi.fastutil.objects.Reference2ReferenceMap;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import java.lang.reflect.Field;
import java.util.Set;
import net.fabricmc.fabric.impl.client.rendering.ColorResolverRegistryImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ColorResolver;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WorldTintCaches {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldTintCaches.class);
    private static @Nullable Field caches;
    private static boolean looked;

    private WorldTintCaches() {
    }

    @SuppressWarnings("unchecked")
    public static void tick(Minecraft game) {
        ClientLevel level = game.level;
        Field field = find();
        if (level == null || field == null) {
            return;
        }
        try {
            Reference2ReferenceMap<ColorResolver, BlockTintCache> had =
                    (Reference2ReferenceMap<ColorResolver, BlockTintCache>) field.get(level);
            Set<ColorResolver> resolvers = ColorResolverRegistryImpl.getCustomResolvers();
            if (had == null || had.size() == resolvers.size()) {
                return;
            }

            Reference2ReferenceMap<ColorResolver, BlockTintCache> now = new Reference2ReferenceOpenHashMap<>();
            for (ColorResolver resolver : resolvers) {
                BlockTintCache cache = had.get(resolver);
                now.put(resolver, cache != null ? cache
                        : new BlockTintCache(pos -> level.calculateBlockTint(pos, resolver)));
            }
            field.set(level, now);
            LOGGER.info("[Hotwire] the open world's tint caches follow the {} color resolvers mods register now (it had"
                    + " {})", now.size(), had.size());
        } catch (IllegalAccessException | RuntimeException e) {
            LOGGER.warn("[Hotwire] the open world's tint caches could not follow the color resolvers", e);
            caches = null;
        }
    }

    private static @Nullable Field find() {
        if (!looked) {
            looked = true;
            for (Field field : ClientLevel.class.getDeclaredFields()) {
                if (field.getName().endsWith("customColorCache")
                        && Reference2ReferenceMap.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    caches = field;
                }
            }
        }
        return caches;
    }
}
