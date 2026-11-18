package dev.moon.hotwire.fabric.registration;

import com.mojang.serialization.Decoder;
import com.mojang.serialization.Encoder;
import com.mojang.serialization.MapDecoder;
import com.mojang.serialization.MapEncoder;
import dev.moon.hotwire.registration.Owners;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.StreamDecoder;
import net.minecraft.network.codec.StreamEncoder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RegistryEntries {
    private static final Logger LOGGER = LoggerFactory.getLogger(RegistryEntries.class);

    private static final List<Class<?>> CODECS = List.of(Encoder.class, Decoder.class, MapEncoder.class,
            MapDecoder.class, StreamEncoder.class, StreamDecoder.class);

    private RegistryEntries() {
    }

    @SuppressWarnings("unchecked")
    public static List<String> describe(String mod) {
        List<String> out = new ArrayList<>();
        Registry<Object> registries = (Registry<Object>) (Registry<?>) BuiltInRegistries.REGISTRY;
        Map<Class<?>, Boolean> passive = new HashMap<>();
        Map<String, Set<MobEffect>> worn = new HashMap<>();
        for (Registry<?> registry : BuiltInRegistries.REGISTRY) {
            int count = 0;
            int idle = 0;
            for (Object value : registry) {
                if (value == null || !mod.equals(Owners.findModOf(value.getClass()))) {
                    continue;
                }
                if (value instanceof MobEffect effect && !worn.computeIfAbsent(mod, any -> findWornEffects())
                        .contains(effect)) {
                    idle++;
                } else if (passive.computeIfAbsent(value.getClass(), type -> isPassive(type, mod))) {
                    idle++;
                } else {
                    count++;
                }
            }
            if (count > 0) {
                out.add("registry entries " + count + " of " + registries.getKey(registry));
            }
            if (idle > 0) {
                LOGGER.info("[Hotwire] {}'s {} entries of {} stay until the game closes; their classes only hand out"
                        + " codecs", mod, idle, registries.getKey(registry));
            }
        }
        return out;
    }

    private static Set<MobEffect> findWornEffects() {
        Set<MobEffect> worn = new HashSet<>();
        try {
            Minecraft client = Minecraft.getInstance();
            MinecraftServer server = client.getSingleplayerServer();
            List<Iterable<? extends Entity>> worlds = new ArrayList<>();
            if (server != null) {
                server.getAllLevels().forEach(level -> worlds.add(level.getAllEntities()));
            }
            ClientLevel level = client.level;
            if (level != null) {
                worlds.add(level.entitiesForRendering());
            }
            for (Iterable<? extends Entity> entities : worlds) {
                for (Entity entity : entities) {
                    if (entity instanceof LivingEntity living) {
                        living.getActiveEffects().forEach(active -> worn.add(active.getEffect().value()));
                    }
                }
            }
        } catch (RuntimeException e) {
            LOGGER.debug("[Hotwire] the entities changed under the effects read; all count as worn", e);
            BuiltInRegistries.MOB_EFFECT.forEach(worn::add);
        }
        return worn;
    }

    private static boolean isPassive(Class<?> type, String mod) {
        for (Class<?> at = type; at != null && mod.equals(Owners.findModOf(at)); at = at.getSuperclass()) {
            for (Method method : at.getDeclaredMethods()) {
                if (!method.isSynthetic() && !Modifier.isStatic(method.getModifiers())
                        && !isCodec(method.getReturnType())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isCodec(Class<?> type) {
        return CODECS.stream().anyMatch(codec -> codec.isAssignableFrom(type));
    }
}
