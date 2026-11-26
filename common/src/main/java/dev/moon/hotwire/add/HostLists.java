package dev.moon.hotwire.add;

import dev.moon.hotwire.engine.LifecycleListeners;
import dev.moon.hotwire.registration.SodiumSettingsPages;
import dev.moon.hotwire.update.LiveNames;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HostLists {
    private static final Logger LOGGER = LoggerFactory.getLogger(HostLists.class);
    private static final String MENU = "com.terraformersmc.modmenu.ModMenu";
    private static final String MENU_MOD = "com.terraformersmc.modmenu.util.mod.fabric.FabricMod";
    private static final String MENU_API = "com.terraformersmc.modmenu.api.ModMenuApi";
    private static final String MENU_NO_SCREEN = "com.terraformersmc.modmenu.util.NullScreenFactory";
    private static final String MULTIMAP = "com.google.common.collect.Multimap";
    private static final String SODIUM = "net.caffeinemc.mods.sodium.client.config.ConfigManager";
    private static final String SODIUM_KEY = "sodium:config_api_user";
    private static final String VOICE_PLUGINS = "de.maxhenkel.voicechat.plugins.PluginManager";
    private static final String VOICE_PLUGIN = "de.maxhenkel.voicechat.api.VoicechatPlugin";
    private static final String VOICE_API = "de.maxhenkel.voicechat.api.VoicechatApi";
    private static final String VOICE_SERVER_API = "de.maxhenkel.voicechat.plugins.impl.VoicechatServerApiImpl";
    private static final String LIBJF_REGISTERED = "dev.jfronny.libjf.config.impl.entrypoint.JfConfigSafe";
    private static final String LIBJF_DSL = "dev.jfronny.libjf.config.api.v2.dsl.DSL";
    private static final String LIBJF_CONFIG = "dev.jfronny.libjf.config.api.v2.JfCustomConfig";
    private static final String LIBJF_NAMINGS = "dev.jfronny.libjf.config.impl.io.Namings";
    private static final String ICEBERG_CONFIGS = "com.anthonyhilyard.iceberg.fabric.config.ConfigTracker";
    private static final String LIBJF_NAMING = "dev.jfronny.libjf.config.api.v2.Naming$Custom";

    private HostLists() {
    }

    public static void listMissing(Collection<String> cameOn, Executor game) {
        if (!cameOn.contains("modmenu") || LifecycleListeners.findStarting().contains("modmenu")) {
            return;
        }
        game.execute(() -> {
            ClassLoader loader = HostLists.class.getClassLoader();
            Class<?> menu = find(loader, MENU);
            if (menu == null) {
                return;
            }
            try {
                Map<String, Object> listed = readMap(menu, "MODS");
                FabricLoader.getInstance().getAllMods().stream()
                        .filter(mod -> !listed.containsKey(mod.getMetadata().getId()))
                        .sorted(Comparator.comparingInt(HostLists::countContainers))
                        .forEach(mod -> listInMenu(loader, mod.getMetadata().getId(), mod));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                LOGGER.warn("[Hotwire] the mods added while Mod Menu was off could not be listed in it; they show"
                        + " there after a restart", e);
            }
        });
    }

    private static int countContainers(ModContainer mod) {
        int depth = 0;
        for (ModContainer at = mod; at.getContainingMod().isPresent() && depth < 16; depth++) {
            at = at.getContainingMod().get();
        }
        return depth;
    }

    public static void refreshScreens(List<String> ids) {
        ClassLoader game = HostLists.class.getClassLoader();
        Class<?> menu = find(game, MENU);
        if (menu == null) {
            return;
        }
        for (String id : ids) {
            try {
                if (readMap(menu, "MODS").containsKey(id)) {
                    Class<?> api = Class.forName(MENU_API, true, game);
                    addScreens(menu, api, findNoScreen(game), id, makeEntrypoints("modmenu", id, api));
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                LOGGER.warn("[Hotwire] {}'s Mod Menu screen could not be read again; it is there after a restart", id,
                        e);
            }
        }
    }

    public static void join(List<String> ids) {
        ClassLoader game = HostLists.class.getClassLoader();
        for (String id : ids.reversed()) {
            FabricLoader.getInstance().getModContainer(id).ifPresent(container -> listInMenu(game, id, container));
        }
        ids.forEach(id -> addSodiumPages(game, id));
        ids.forEach(id -> addLibJfConfigs(game, id));
        ids.forEach(id -> addVoicePlugins(game, id));
        loadIcebergConfigs(game);
    }

    private static void loadIcebergConfigs(ClassLoader game) {
        if (!FabricLoader.getInstance().isModLoaded("iceberg")) {
            return;
        }
        try {
            Class<?> tracker = Class.forName(ICEBERG_CONFIGS, true, game);
            Object instance = tracker.getField("INSTANCE").get(null);
            Field files = tracker.getDeclaredField("fileMap");
            files.setAccessible(true);
            for (Object info : new ArrayList<Object>(((Map<?, ?>) files.get(instance)).values())) {
                Method spec = info.getClass().getDeclaredMethod("getSpec");
                spec.setAccessible(true);
                Object config = spec.invoke(info);

                Field values = config.getClass().getDeclaredField("childConfig");
                values.setAccessible(true);
                if (values.get(config) == null) {
                    tracker.getMethod("openConfig", info.getClass(), Path.class).invoke(instance, info,
                            FabricLoader.getInstance().getConfigDir());
                    LOGGER.info("[Hotwire] a config registered with Iceberg since the start is loaded");
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.warn("[Hotwire] Iceberg's configs registered since the start could not be loaded", e);
        }
    }

    private static void addVoicePlugins(ClassLoader game, String id) {
        List<EntrypointContainer<Object>> entries = findEntrypoints("voicechat", id);
        if (entries.isEmpty() || !FabricLoader.getInstance().isModLoaded("voicechat")) {
            return;
        }
        try {
            Class<?> manager = Class.forName(VOICE_PLUGINS, true, game);
            Object plugins = read(manager, "instance");
            Field list = manager.getDeclaredField("plugins");
            list.setAccessible(true);
            if (plugins == null || !(list.get(plugins) instanceof List<?> started)) {
                return;
            }
            List<Object> now = new ArrayList<>(started);
            Method initialize = Class.forName(VOICE_PLUGIN, true, game).getMethod("initialize",
                    Class.forName(VOICE_API, true, game));
            Object api = Class.forName(VOICE_SERVER_API, true, game).getField("INSTANCE").get(null);
            for (EntrypointContainer<Object> entry : entries) {
                Object plugin = entry.getEntrypoint();
                if (!now.contains(plugin)) {
                    now.add(plugin);
                    initialize.invoke(plugin, api);
                }
            }
            list.set(plugins, now);
            Method gather = manager.getDeclaredMethod("gatherEvents");
            gather.setAccessible(true);
            gather.invoke(plugins);
            LOGGER.info("[Hotwire] {}'s voice chat plugin is in Simple Voice Chat", id);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.warn("[Hotwire] {}'s voice chat plugin could not join Simple Voice Chat; it does after a restart",
                    id, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void addLibJfConfigs(ClassLoader game, String id) {
        if (!FabricLoader.getInstance().isModLoaded("libjf-config-core-v2")) {
            return;
        }
        try {
            for (EntrypointContainer<Object> entry : findEntrypoints("libjf:config", id)) {
                if (((Set<String>) Class.forName(LIBJF_REGISTERED, true, game).getField("REGISTERED_MODS").get(null))
                        .add(id)) {
                    Class<?> dsl = Class.forName(LIBJF_DSL, true, game);
                    Class.forName(LIBJF_CONFIG, true, game).getMethod("register", Class.forName(LIBJF_DSL
                            + "$Defaulted", true, game)).invoke(entry.getEntrypoint(), dsl.getMethod("create",
                            String.class).invoke(null, id));
                    LOGGER.info("[Hotwire] {}'s config is registered with LibJF", id);
                }
            }
            for (EntrypointContainer<Object> entry : findEntrypoints("libjf:config_naming", id)) {
                Object naming = entry.getEntrypoint();
                Object name = Class.forName(LIBJF_NAMING, true, game).getMethod("getId").invoke(naming);
                ((Map<Object, Object>) read(Class.forName(LIBJF_NAMINGS, true, game), "NAMINGS")).put(name, naming);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.warn("[Hotwire] {}'s LibJF config could not be registered; it is there after a restart", id, e);
        }
    }

    private static void listInMenu(ClassLoader game, String id, ModContainer container) {
        Class<?> menu = find(game, MENU);
        if (menu == null) {
            return;
        }
        try {
            Map<String, Object> mods = readMap(menu, "MODS");
            if (mods.containsKey(id)) {
                return;
            }
            Object mod = Class.forName(MENU_MOD, true, game).getConstructor(ModContainer.class, Set.class)
                    .newInstance(container, Set.of());
            mods.put(id, mod);
            Object parentId = mod.getClass().getMethod("getParent").invoke(mod);
            String declared = String.valueOf(parentId);
            Object parent = parentId == null || id.equals(parentId) ? null : findParent(mods, declared,
                    LiveNames.findCopiesOf(declared), FabricLoader.getInstance()::isModLoaded);
            if (parent == null) {
                readMap(menu, "ROOT_MODS").put(id, mod);
            } else {
                Class.forName(MULTIMAP, true, game).getMethod("put", Object.class, Object.class)
                        .invoke(read(menu, "PARENT_MAP"), parent, mod);
            }
            Class<?> api = Class.forName(MENU_API, true, game);
            boolean screen = addScreens(menu, api, findNoScreen(game), id, makeEntrypoints("modmenu", id, api));
            menu.getMethod("clearModCountCache").invoke(null);
            LOGGER.info("[Hotwire] {} is listed in Mod Menu{}", id, screen ? ", with its config screen" : "");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.warn("[Hotwire] {} could not be listed in Mod Menu; it shows there after a restart", id, e);
        }
    }

    static @Nullable Object findParent(Map<String, Object> mods, String declared, List<String> copies,
            Predicate<String> isLoaded) {
        for (String copy : copies) {
            Object found = isLoaded.test(copy) ? mods.get(copy) : null;
            if (found != null) {
                return found;
            }
        }
        return mods.get(declared);
    }

    private static @Nullable Class<?> findNoScreen(ClassLoader game) {
        try {
            return Class.forName(MENU_NO_SCREEN, false, game);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    static boolean addScreens(Class<?> menu, Class<?> api, @Nullable Class<?> noScreen, String id, List<Object> made)
            throws ReflectiveOperationException {
        Map<String, Object> factories = readMap(menu, "configScreenFactories");
        List<Object> apis = (List<Object>) read(menu, "apiImplementations");
        boolean own = false;
        for (Object one : made) {
            Object factory = api.getMethod("getModConfigScreenFactory").invoke(one);
            if (factory != null && (noScreen == null || !noScreen.isInstance(factory))) {
                factories.put(id, factory);
                own = true;
            }
            if (api.getMethod("getProvidedConfigScreenFactories").invoke(one) instanceof Map<?, ?> more) {
                more.forEach((key, value) -> factories.putIfAbsent(String.valueOf(key), value));
            }
            if (!apis.contains(one)) {
                apis.add(one);
            }
        }
        return own;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<Object> makeEntrypoints(String key, String id, Class<?> type) {
        List<Object> made = new ArrayList<>();
        for (Object entry : FabricLoader.getInstance().getEntrypointContainers(key, (Class) type)) {
            EntrypointContainer<?> one = (EntrypointContainer<?>) entry;
            if (one.getProvider().getMetadata().getId().equals(id)) {
                made.add(one.getEntrypoint());
            }
        }
        return made;
    }

    private static void addSodiumPages(ClassLoader game, String id) {
        Class<?> manager = find(game, SODIUM);
        if (manager == null) {
            return;
        }
        for (EntrypointContainer<Object> entry : findEntrypoints(SODIUM_KEY, id)) {
            try {
                if (manager.getField("CONFIG").get(null) == null) {
                    return;
                }
                Object made = entry.getEntrypoint();
                manager.getMethod("registerConfigEntryPoint", Supplier.class, String.class)
                        .invoke(null, (Supplier<Object>) () -> made, id);

                Set<String> replaced = FabricLoader.getInstance().getModContainer(id)
                        .map(container -> Set.copyOf(container.getMetadata().getProvides())).orElse(Set.of());
                if (SodiumSettingsPages.buildAgain(replaced)) {
                    LOGGER.info("[Hotwire] {}'s page is in Sodium's video settings", id);
                } else {
                    LOGGER.warn("[Hotwire] {}'s page could not join Sodium's video settings; it shows there after a"
                            + " restart", id);
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                LOGGER.warn("[Hotwire] {}'s page could not join Sodium's video settings; it shows there after a"
                        + " restart", id, e);
            }
        }
    }

    private static List<EntrypointContainer<Object>> findEntrypoints(String key, String id) {
        return FabricLoader.getInstance().getEntrypointContainers(key, Object.class).stream()
                .filter(entry -> entry.getProvider().getMetadata().getId().equals(id)).toList();
    }

    private static @Nullable Class<?> find(ClassLoader game, String name) {
        if (!FabricLoader.getInstance().isModLoaded(name.startsWith("com.terraformersmc") ? "modmenu" : "sodium")) {
            return null;
        }
        try {
            return Class.forName(name, true, game);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    private static Object read(Class<?> owner, String field) throws ReflectiveOperationException {
        Field found = owner.getDeclaredField(field);
        found.setAccessible(true);
        return found.get(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readMap(Class<?> owner, String field) throws ReflectiveOperationException {
        return (Map<String, Object>) read(owner, field);
    }
}
