package dev.moon.hotwire.fabric.add;

import dev.moon.hotwire.jar.ModManifest;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class YumiStarts {
    private static final Logger LOGGER = LoggerFactory.getLogger(YumiStarts.class);
    private static final String YUMI = "yumi_mc_core";
    private static final String MODS = "dev.yumi.mc.core.impl.YumiModsImpl";
    private static final String CONTAINER = "dev.yumi.mc.core.impl.mod.FabricModContainer";
    private static final String YUMI_CONTAINER = "dev.yumi.mc.core.api.ModContainer";
    private static final String INIT = "dev.yumi.mc.core.api.entrypoint.ModInitializer";
    private static final String CLIENT_INIT = "dev.yumi.mc.core.api.entrypoint.client.ClientModInitializer";

    private YumiStarts() {
    }

    static List<Runnable> collect(List<String> ids, ModManifest.Environment side) {
        if (ids.contains(YUMI) || !FabricLoader.getInstance().isModLoaded(YUMI)) {
            return List.of();
        }
        List<Runnable> starts = new ArrayList<>();
        for (String id : ids) {
            starts.addAll(collect(id, "yumi:init", INIT, "onInitialize"));
            if (side == ModManifest.Environment.CLIENT) {
                starts.addAll(collect(id, "yumi:client_init", CLIENT_INIT, "onInitializeClient"));
            }
        }
        return starts;
    }

    private static List<Runnable> collect(String id, String key, String type, String method) {
        List<Runnable> starts = new ArrayList<>();
        FabricLoader loader = FabricLoader.getInstance();
        for (EntrypointContainer<Object> entry : loader.getEntrypointContainers(key, Object.class)) {
            if (!entry.getProvider().getMetadata().getId().equals(id)) {
                continue;
            }
            starts.add(() -> {
                ClassLoader game = YumiStarts.class.getClassLoader();
                try {
                    Object container = findContainer(game, id, entry.getProvider());
                    Class.forName(type, true, game).getMethod(method, Class.forName(YUMI_CONTAINER, true, game))
                            .invoke(entry.getEntrypoint(), container);
                    LOGGER.info("[Hotwire] {} started through Yumi's {}", id, key);
                } catch (ReflectiveOperationException | LinkageError e) {
                    throw new IllegalStateException(id + " could not start through Yumi's " + key + ": " + e, e);
                }
            });
        }
        return starts;
    }

    @SuppressWarnings("unchecked")
    private static Object findContainer(ClassLoader game, String id, ModContainer provider)
            throws ReflectiveOperationException {
        Class<?> mods = Class.forName(MODS, true, game);
        Object yumi = mods.getField("INSTANCE").get(null);
        Map<String, Object> byId = (Map<String, Object>) read(mods, yumi, "modsMap");
        Object own = byId.get(id);
        if (own == null) {
            own = Class.forName(CONTAINER, true, game).getConstructor(ModContainer.class).newInstance(provider);
            byId.put(id, own);
            ((List<Object>) read(mods, yumi, "mods")).add(own);
        }
        return own;
    }

    private static Object read(Class<?> owner, Object on, String field) throws ReflectiveOperationException {
        Field found = owner.getDeclaredField(field);
        found.setAccessible(true);
        return found.get(on);
    }
}
