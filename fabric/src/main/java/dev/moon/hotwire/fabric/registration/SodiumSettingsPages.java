package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.fabric.add.LoaderModList;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SodiumSettingsPages {
    private static final Logger LOGGER = LoggerFactory.getLogger(SodiumSettingsPages.class);
    private static final String ROOT = "net.caffeinemc.mods.sodium.";
    private static final String MANAGER = ROOT + "client.config.ConfigManager";
    private static final AtomicBoolean BUILD_DUE = new AtomicBoolean();

    private record PagesOut(String mod) implements OffRemovals.Entry {
        @Override
        public void restore() {
            if (BUILD_DUE.compareAndSet(false, true)) {
                Minecraft.getInstance().execute(() -> {
                    BUILD_DUE.set(false);
                    buildAgain(Set.of());
                });
            }
        }

        @Override
        public String describe() {
            return "Sodium's settings pages of " + this.mod;
        }

        @Override
        public OffRemovals.Where getWhere() {
            return OffRemovals.Where.GAME_BEFORE_FRAME;
        }
    }

    private SodiumSettingsPages() {
    }

    public static void takeOut(Collection<String> mods) {
        OffRemovals.onGame(() -> takeOutNow(mods), "settings pages left out");
    }

    private static void takeOutNow(Collection<String> mods) {
        Class<?> manager = findManager();
        if (manager == null) {
            return;
        }
        List<String> theirs = new ArrayList<>();
        try {
            for (Object user : readUsers(manager)) {
                String id = readModId(user);
                if (mods.contains(id)) {
                    theirs.add(id);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.info("[Hotwire] Sodium's settings could not be read: {}", e.toString());
            return;
        }
        if (!theirs.isEmpty() && buildAgain(Set.copyOf(mods))) {
            theirs.forEach(mod -> OffRemovals.add(mod, new PagesOut(mod)));
            LOGGER.info("[Hotwire] Sodium's settings were built again without {}", theirs);
        }
    }

    private static @Nullable Class<?> findManager() {
        try {
            return Class.forName(MANAGER, false, SodiumSettingsPages.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    private static List<Object> readUsers(Class<?> manager) throws ReflectiveOperationException {
        Field users = manager.getDeclaredField("configUsers");
        users.setAccessible(true);
        List<Object> all = new ArrayList<>();
        for (Object user : (Collection<?>) users.get(null)) {
            if (user != null) {
                all.add(user);
            }
        }
        return all;
    }

    private static String readModId(Object user) throws ReflectiveOperationException {
        Field id = user.getClass().getDeclaredField("modId");
        id.setAccessible(true);
        return (String) id.get(user);
    }

    @SuppressWarnings("unchecked")
    private static boolean buildAgain(Set<String> going) {
        Class<?> manager = findManager();
        if (manager == null) {
            return false;
        }
        try {
            ClassLoader loader = manager.getClassLoader();
            Set<String> off = new HashSet<>(LoaderModList.getHidden());
            off.addAll(going);
            Field infoField = manager.getDeclaredField("modInfoFunction");
            infoField.setAccessible(true);
            Object info = infoField.get(null);
            Class<?> builderType = Class.forName(ROOT + "client.config.builder.ConfigBuilderImpl", false, loader);
            Constructor<?> makeBuilder = builderType.getConstructor(Function.class, String.class);
            Method build = builderType.getMethod("build");
            Class<?> pointType = Class.forName(ROOT + "api.config.ConfigEntryPoint", false, loader);
            Method late = findLate(pointType);
            Class<?> optionsType = Class.forName(ROOT + "client.config.structure.ModOptions", false, loader);
            Method configId = optionsType.getMethod("configId");
            Method name = optionsType.getMethod("name");
            List<Object> pages = new ArrayList<>();
            Object sodium = null;
            for (Object user : readUsers(manager)) {
                String id = readModId(user);
                if (off.contains(id)) {
                    continue;
                }
                Field entry = user.getClass().getDeclaredField("configEntrypoint");
                entry.setAccessible(true);
                Object builder = makeBuilder.newInstance(info, id);
                late.invoke(((Supplier<Object>) entry.get(user)).get(), builder);
                for (Object options : (Collection<?>) build.invoke(builder)) {
                    if ("sodium".equals(configId.invoke(options))) {
                        sodium = options;
                    } else {
                        pages.add(options);
                    }
                }
            }
            if (sodium == null) {
                return false;
            }
            pages.sort(Comparator.comparing(options -> {
                try {
                    return (String) name.invoke(options);
                } catch (ReflectiveOperationException e) {
                    return "";
                }
            }));
            pages.addFirst(sodium);
            Object config = Class.forName(ROOT + "client.config.structure.Config", false, loader)
                    .getConstructor(List.class).newInstance(pages);
            manager.getField("CONFIG").set(null, config);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.warn("[Hotwire] Sodium's settings stay as they were built: {}", e.toString());
            return false;
        }
    }

    private static Method findLate(Class<?> pointType) throws NoSuchMethodException {
        for (Method method : pointType.getMethods()) {
            if (method.getName().equals("registerConfigLate") && method.getParameterCount() == 1) {
                return method;
            }
        }
        throw new NoSuchMethodException("registerConfigLate");
    }
}
