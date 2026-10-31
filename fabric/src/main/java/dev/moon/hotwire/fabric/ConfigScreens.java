package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.registration.GameRegistrations;
import dev.moon.hotwire.ui.Lang;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ConfigScreens {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigScreens.class);
    private static final String MENU_API = "com.terraformersmc.modmenu.api.ModMenuApi";
    private static final String MENU_FACTORY = "com.terraformersmc.modmenu.api.ConfigScreenFactory";
    private static final String ONECONFIG_MANAGER = "org.polyfrost.oneconfig.api.config.v1.ConfigManager";
    private static final String ONECONFIG_CONFIG = "org.polyfrost.oneconfig.api.config.v1.Config";
    private static final String ONECONFIG_NODE = "org.polyfrost.oneconfig.api.config.v1.Node";
    private static final String ONECONFIG_UI = "org.polyfrost.oneconfig.api.ui.v1.OneConfigUI";
    private static final String ONECONFIG_ROUTE = "org.polyfrost.oneconfig.internal.ui.navigation.graph.ModConfigRoute";

    @FunctionalInterface
    private interface ScreenFactory {
        @Nullable Object create(Screen parent) throws ReflectiveOperationException;
    }

    private record SettingsScreen(ScreenFactory factory, List<String> runs) {
    }

    private final Predicate<String> on;
    private final Function<String, String> names;
    private final Consumer<String> sayBad;

    private final Set<String> empty = new HashSet<>();

    private final Set<String> unreadable = new HashSet<>();

    ConfigScreens(Predicate<String> on, Function<String, String> names, Consumer<String> sayBad) {
        this.on = on;
        this.names = names;
        this.sayBad = sayBad;
    }

    Set<String> findOpenable() {
        return Set.copyOf(this.findScreens().keySet());
    }

    boolean open(String id) {
        Screen hotwire = ModViews.openScreen();
        SettingsScreen settings = this.findScreens().get(id);
        if (hotwire == null || settings == null) {
            return false;
        }
        Object made;
        try {
            made = settings.factory().create(hotwire);
        } catch (InvocationTargetException e) {
            return this.refuse(id, e.getCause() == null ? e : e.getCause());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return this.refuse(id, e);
        }
        if (!(made instanceof Screen screen)) {
            this.empty.add(id);
            this.sayBad.accept(Lang.get("hotwire.mod_settings.none", this.names.apply(id)));
            return false;
        }
        ModViews.show(screen);
        return true;
    }

    private Map<String, SettingsScreen> findScreens() {
        ClassLoader game = ConfigScreens.class.getClassLoader();
        Map<String, SettingsScreen> found = new LinkedHashMap<>();
        Map<String, SettingsScreen> provided = new LinkedHashMap<>();
        this.addMenuScreens(game, found, provided);
        this.addOneConfigScreens(game, found);
        provided.forEach(found::putIfAbsent);
        found.entrySet().removeIf(entry -> !this.isOpenable(entry.getKey(), entry.getValue()));
        return found;
    }

    private boolean isOpenable(String id, SettingsScreen settings) {
        return !id.equals(HotwireClient.ID) && !this.empty.contains(id)
                && FabricLoader.getInstance().getModContainer(id).isPresent() && this.on.test(id)
                && settings.runs().stream().allMatch(this.on);
    }

    private void addMenuScreens(ClassLoader game, Map<String, SettingsScreen> own,
            Map<String, SettingsScreen> provided) {
        Class<?> api;
        Method create;
        Method factoryOf;
        Method provides;
        try {
            api = Class.forName(MENU_API, false, game);
            create = Class.forName(MENU_FACTORY, false, game).getMethod("create", Screen.class);
            factoryOf = api.getMethod("getModConfigScreenFactory");
            provides = api.getMethod("getProvidedConfigScreenFactories");
        } catch (ClassNotFoundException | NoSuchMethodException | LinkageError e) {
            return;
        }
        String menu = GameRegistrations.findModOrShipper(api);
        if (menu != null && !this.on.test(menu)) {
            return;
        }
        for (EntrypointContainer<Object> entry : FabricLoader.getInstance().getEntrypointContainers("modmenu",
                Object.class)) {
            String id = entry.getProvider().getMetadata().getId();
            if (!this.on.test(id)) {
                continue;
            }
            List<String> runs = menu == null ? List.of(id) : List.of(id, menu);
            try {
                Object made = entry.getEntrypoint();
                if (!api.isInstance(made)) {
                    continue;
                }

                boolean overrides = made.getClass().getMethod(factoryOf.getName()).getDeclaringClass() != api;
                Object factory = overrides ? factoryOf.invoke(made) : null;
                if (factory != null) {
                    own.putIfAbsent(id, new SettingsScreen(parent -> create.invoke(factory, parent), runs));
                }
                if (provides.invoke(made) instanceof Map<?, ?> more) {
                    for (Map.Entry<?, ?> other : more.entrySet()) {
                        Object offered = other.getValue();
                        if (offered != null) {
                            provided.putIfAbsent(String.valueOf(other.getKey()),
                                    new SettingsScreen(parent -> create.invoke(offered, parent), runs));
                        }
                    }
                }
            } catch (InvocationTargetException e) {
                this.noteUnreadable(id, e.getCause() == null ? e : e.getCause());
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                this.noteUnreadable(id, e);
            }
        }
    }

    private void addOneConfigScreens(ClassLoader game, Map<String, SettingsScreen> into) {
        Class<?> manager;
        Field configs;
        Field tree;
        Field page;
        Method metadata;
        Method create;
        Constructor<?> route;
        try {
            manager = Class.forName(ONECONFIG_MANAGER, false, game);
            Class<?> config = Class.forName(ONECONFIG_CONFIG, false, game);
            configs = manager.getDeclaredField("initializedConfigs");
            configs.setAccessible(true);
            tree = config.getDeclaredField("tree");
            tree.setAccessible(true);
            page = config.getField("id");
            metadata = Class.forName(ONECONFIG_NODE, false, game).getMethod("getMetadata", String.class);
            create = Class.forName(ONECONFIG_UI, false, game).getMethod("createScreen", Object.class);
            route = Class.forName(ONECONFIG_ROUTE, false, game).getConstructor(String.class, String.class);
        } catch (ClassNotFoundException | NoSuchFieldException | NoSuchMethodException | LinkageError
                | RuntimeException e) {
            return;
        }
        String oneConfig = GameRegistrations.findModOrShipper(create.getDeclaringClass());
        if (oneConfig != null && !this.on.test(oneConfig)) {
            return;
        }
        List<Object> set;
        try {
            synchronized (manager) {
                set = List.<Object>copyOf(((Map<?, ?>) configs.get(null)).values());
            }
        } catch (IllegalAccessException | RuntimeException e) {
            LOGGER.warn("[Hotwire] OneConfig's configs could not be read for their settings pages", e);
            return;
        }
        for (Object config : set) {
            String id = GameRegistrations.findModOrShipper(config.getClass());
            if (id == null || into.containsKey(id) || !this.on.test(id)) {
                continue;
            }
            try {
                Object node = tree.get(config);
                if (node == null || metadata.invoke(node, "hidden") != null) {
                    continue;
                }
                Object shown = page.get(config);
                List<String> runs = oneConfig == null ? List.of(id) : List.of(id, oneConfig);
                into.put(id, new SettingsScreen(parent -> create.invoke(null, route.newInstance(shown, null)), runs));
            } catch (InvocationTargetException e) {
                this.noteUnreadable(id, e.getCause() == null ? e : e.getCause());
            } catch (ReflectiveOperationException | RuntimeException e) {
                this.noteUnreadable(id, e);
            }
        }
    }

    private void noteUnreadable(String id, Throwable cause) {
        if (this.unreadable.add(id)) {
            LOGGER.warn("[Hotwire] {}'s settings screen could not be read; its row has no Settings button", id, cause);
        }
    }

    private boolean refuse(String id, Throwable cause) {
        LOGGER.warn("[Hotwire] {}'s settings screen threw as it was made", id, cause);
        String name = this.names.apply(id);
        String message = cause.getMessage();
        if (cause instanceof NoClassDefFoundError && message != null) {
            this.sayBad.accept(Lang.get("hotwire.mod_settings.missing", name, message.replace('/', '.')));
        } else {
            this.sayBad.accept(Lang.get("hotwire.mod_settings.failed", name, message == null
                    ? cause.getClass().getSimpleName() : message));
        }
        return false;
    }
}
