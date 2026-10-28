package dev.moon.hotwire.fabric.registration;

import com.mojang.serialization.Decoder;
import com.mojang.serialization.Encoder;
import com.mojang.serialization.MapDecoder;
import com.mojang.serialization.MapEncoder;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.StreamDecoder;
import net.minecraft.network.codec.StreamEncoder;
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
        for (Registry<?> registry : BuiltInRegistries.REGISTRY) {
            int count = 0;
            int idle = 0;
            for (Object value : registry) {
                if (value == null || !mod.equals(GameRegistrations.findModOf(value.getClass()))) {
                    continue;
                }
                if (passive.computeIfAbsent(value.getClass(), type -> isPassive(type, mod))) {
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

    private static boolean isPassive(Class<?> type, String mod) {
        for (Class<?> at = type; at != null && mod.equals(GameRegistrations.findModOf(at)); at = at.getSuperclass()) {
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
