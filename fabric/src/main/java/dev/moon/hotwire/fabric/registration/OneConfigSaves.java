package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.api.ModLifecycleListener;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OneConfigSaves implements ModLifecycleListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(OneConfigSaves.class);
    private static final String MANAGER = "org.polyfrost.oneconfig.api.config.v1.ConfigManager";
    private static final String TREE = "org.polyfrost.oneconfig.api.config.v1.Tree";

    private final Predicate<@Nullable String> listening;

    public OneConfigSaves(Predicate<@Nullable String> listening) {
        this.listening = listening;
    }

    @Override
    public void prepareOff(List<String> ids) {
        Class<?> manager = findManager();
        if (manager == null || this.listening.test(GameRegistrations.findModOrShipper(manager))) {
            return;
        }
        int saved = 0;
        try {
            Object active = manager.getMethod("active").invoke(null);
            Method save = manager.getMethod("save", Class.forName(TREE, false, manager.getClassLoader()));
            Method idOf = Class.forName(TREE, false, manager.getClassLoader()).getMethod("getID");
            Map<?, ?> configs = (Map<?, ?>) read(manager, "initializedConfigs");
            for (Object tree : List.copyOf((Collection<?>) manager.getMethod("trees").invoke(active))) {
                if (tree == null) {
                    continue;
                }
                String owner = findOwner(tree, (String) idOf.invoke(tree), configs);
                if (owner != null && ids.contains(owner) && Boolean.TRUE.equals(save.invoke(active, tree))) {
                    saved++;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.warn("[Hotwire] OneConfig's settings of {} could not be saved before their off", ids, e);
            return;
        }
        if (saved > 0) {
            LOGGER.info("[Hotwire] {} OneConfig settings files of {} saved before their off", saved, ids);
        }
    }

    private static @Nullable String findOwner(Object tree, @Nullable String id, @Nullable Map<?, ?> configs)
            throws ReflectiveOperationException {
        Object config = configs == null || id == null ? null : configs.get(id);
        String mod = config == null ? null : GameRegistrations.findModOf(config.getClass());
        if (mod == null) {
            mod = OneConfigModCards.findModOf(tree);
        }
        return mod != null ? mod : id;
    }

    private static @Nullable Class<?> findManager() {
        try {
            return Class.forName(MANAGER, false, OneConfigSaves.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    private static @Nullable Object read(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
