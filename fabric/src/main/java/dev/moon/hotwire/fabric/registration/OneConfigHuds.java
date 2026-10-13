package dev.moon.hotwire.fabric.registration;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OneConfigHuds {
    private static final Logger LOGGER = LoggerFactory.getLogger(OneConfigHuds.class);
    private static final String MANAGER = "org.polyfrost.oneconfig.api.hud.v1.HudManager";
    private static final int WAIT_SECONDS = 5;

    private record Removal(List<Map.Entry<Integer, Object>> huds, Map<Object, Object> providers) {
    }

    private static final Map<String, Removal> REMOVED = Collections.synchronizedMap(new LinkedHashMap<>());

    private OneConfigHuds() {
    }

    public static void takeOut(Collection<String> mods) {
        onGame(() -> takeOutNow(mods), "taken out");
    }

    public static void putBack(Collection<String> mods) {
        onGame(() -> putBackNow(mods), "put back");
    }

    private static void onGame(Runnable work, String what) {
        if (findManager() == null) {
            return;
        }
        try {
            GameHold.await(GameHold.onGameBeforeNextFrame(work, Minecraft.getInstance()), WAIT_SECONDS,
                    "Render thread", "OneConfig's HUDs " + what);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.warn("[Hotwire] OneConfig HUDs could not be {}", what, e);
        }
    }

    private static void takeOutNow(Collection<String> mods) {
        Class<?> manager = Objects.requireNonNull(findManager());
        List<Object> huds = findHuds(manager);
        Map<Object, Object> providers = findProviders(manager);
        for (String mod : mods) {
            List<Map.Entry<Integer, Object>> removed = new ArrayList<>();
            for (int at = 0; at < huds.size(); at++) {
                if (mod.equals(GameRegistrations.findModOf(huds.get(at).getClass()))) {
                    removed.add(Map.entry(at, huds.get(at)));
                }
            }
            Map<Object, Object> kinds = new LinkedHashMap<>();
            providers.forEach((kind, provider) -> {
                if (mod.equals(GameRegistrations.findModOf(provider.getClass()))) {
                    kinds.put(kind, provider);
                }
            });
            if (removed.isEmpty() && kinds.isEmpty()) {
                continue;
            }

            REMOVED.put(mod, new Removal(removed, kinds));
            for (int i = removed.size() - 1; i >= 0; i--) {
                huds.remove((int) removed.get(i).getKey());
            }
            kinds.keySet().forEach(providers::remove);
            LOGGER.info("[Hotwire] {}'s {} OneConfig HUDs are taken out while it is off", mod, removed.size());
        }
        invalidate(manager);
    }

    private static void putBackNow(Collection<String> mods) {
        Class<?> manager = Objects.requireNonNull(findManager());
        List<Object> huds = findHuds(manager);
        Map<Object, Object> providers = findProviders(manager);

        List<String> out = new ArrayList<>(REMOVED.keySet());
        out.retainAll(mods);
        for (String mod : out.reversed()) {
            Removal removed = REMOVED.remove(mod);
            if (removed == null) {
                continue;
            }
            removed.huds().forEach(hud -> {
                if (!hasHud(huds, hud.getValue())) {
                    huds.add(Math.min(hud.getKey(), huds.size()), hud.getValue());
                }
            });
            providers.putAll(removed.providers());
        }
        invalidate(manager);
    }

    @SuppressWarnings("ReferenceEquality")
    private static boolean hasHud(List<Object> huds, Object hud) {
        for (Object each : huds) {
            if (each == hud) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable Class<?> findManager() {
        try {
            return Class.forName(MANAGER, false, OneConfigHuds.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> findHuds(Class<?> manager) {
        return (List<Object>) readField(manager, "activeInstances");
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> findProviders(Class<?> manager) {
        return (Map<Object, Object>) readField(manager, "hudProviders");
    }

    private static Object readField(Class<?> manager, String name) {
        try {
            Field field = manager.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("OneConfig's HUD manager has no " + name, e);
        }
    }

    private static void invalidate(Class<?> manager) {
        try {
            manager.getMethod("invalidate").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("[Hotwire] OneConfig's HUD order could not be worked out again", e);
        }
    }
}
