package dev.moon.hotwire.fabric.add;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class HostLists {
    private static final Logger LOGGER = LoggerFactory.getLogger(HostLists.class);
    private static final String MENU = "com.terraformersmc.modmenu.ModMenu";
    private static final String MENU_MOD = "com.terraformersmc.modmenu.util.mod.fabric.FabricMod";
    private static final String MENU_API = "com.terraformersmc.modmenu.api.ModMenuApi";
    private static final String MULTIMAP = "com.google.common.collect.Multimap";
    private static final String SODIUM = "net.caffeinemc.mods.sodium.client.config.ConfigManager";
    private static final String SODIUM_KEY = "sodium:config_api_user";
    private static final String SODIUM_BUILDER = "net.caffeinemc.mods.sodium.client.config.builder.ConfigBuilderImpl";
    private static final String SODIUM_USER = "net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint";
    private static final String SODIUM_BUILDER_API = "net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder";
    private static final String SODIUM_CONFIG = "net.caffeinemc.mods.sodium.client.config.structure.Config";
    private static final String SODIUM_PAGE = "net.caffeinemc.mods.sodium.client.config.structure.ModOptions";
    private static final String VOICE_PLUGINS = "de.maxhenkel.voicechat.plugins.PluginManager";
    private static final String VOICE_PLUGIN = "de.maxhenkel.voicechat.api.VoicechatPlugin";
    private static final String VOICE_API = "de.maxhenkel.voicechat.api.VoicechatApi";
    private static final String VOICE_SERVER_API = "de.maxhenkel.voicechat.plugins.impl.VoicechatServerApiImpl";
    private static final String LIBJF_REGISTERED = "dev.jfronny.libjf.config.impl.entrypoint.JfConfigSafe";
    private static final String LIBJF_DSL = "dev.jfronny.libjf.config.api.v2.dsl.DSL";
    private static final String LIBJF_CONFIG = "dev.jfronny.libjf.config.api.v2.JfCustomConfig";
    private static final String LIBJF_NAMINGS = "dev.jfronny.libjf.config.impl.io.Namings";
    private static final String LIBJF_NAMING = "dev.jfronny.libjf.config.api.v2.Naming$Custom";

    private HostLists() {
    }

    static void join(List<String> ids) {
        ClassLoader game = HostLists.class.getClassLoader();
        for (String id : ids.reversed()) {
            FabricLoader.getInstance().getModContainer(id).ifPresent(container -> listInMenu(game, id, container));
        }
        ids.forEach(id -> addSodiumPages(game, id));
        ids.forEach(id -> addLibJfConfigs(game, id));
        ids.forEach(id -> addVoicePlugins(game, id));
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
            Object parent = parentId == null || id.equals(parentId) ? null : mods.get(String.valueOf(parentId));
            if (parent == null) {
                readMap(menu, "ROOT_MODS").put(id, mod);
            } else {
                Class.forName(MULTIMAP, true, game).getMethod("put", Object.class, Object.class)
                        .invoke(read(menu, "PARENT_MAP"), parent, mod);
            }
            boolean screen = addScreens(game, menu, id);
            menu.getMethod("clearModCountCache").invoke(null);
            LOGGER.info("[Hotwire] {} is listed in Mod Menu{}", id, screen ? ", with its config screen" : "");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.warn("[Hotwire] {} could not be listed in Mod Menu; it shows there after a restart", id, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean addScreens(ClassLoader game, Class<?> menu, String id) throws ReflectiveOperationException {
        Class<?> api = Class.forName(MENU_API, true, game);
        Map<String, Object> factories = readMap(menu, "configScreenFactories");
        List<Object> apis = (List<Object>) read(menu, "apiImplementations");
        boolean own = false;
        for (EntrypointContainer<Object> entry : findEntrypoints("modmenu", id)) {
            Object made = entry.getEntrypoint();
            Object factory = api.getMethod("getModConfigScreenFactory").invoke(made);
            if (factory != null) {
                factories.put(id, factory);
                own = true;
            }
            if (api.getMethod("getProvidedConfigScreenFactories").invoke(made) instanceof Map<?, ?> more) {
                more.forEach((key, value) -> factories.put(String.valueOf(key), value));
            }
            apis.add(made);
        }
        return own;
    }

    private static void addSodiumPages(ClassLoader game, String id) {
        Class<?> manager = find(game, SODIUM);
        if (manager == null) {
            return;
        }
        for (EntrypointContainer<Object> entry : findEntrypoints(SODIUM_KEY, id)) {
            try {
                Object built = manager.getField("CONFIG").get(null);
                if (built == null) {
                    return;
                }
                Object made = entry.getEntrypoint();
                Class<?> builderType = Class.forName(SODIUM_BUILDER, true, game);
                Object builder = builderType.getConstructor(Function.class, String.class)
                        .newInstance(read(manager, "modInfoFunction"), id);
                Class.forName(SODIUM_USER, true, game)
                        .getMethod("registerConfigLate", Class.forName(SODIUM_BUILDER_API, true, game))
                        .invoke(made, builder);
                Class<?> config = Class.forName(SODIUM_CONFIG, true, game);
                Method configId = Class.forName(SODIUM_PAGE, true, game).getMethod("configId");
                Method name = Class.forName(SODIUM_PAGE, true, game).getMethod("name");
                List<Object> pages = new ArrayList<>((List<?>) config.getMethod("getModOptions").invoke(built));
                List<Object> ids = new ArrayList<>();
                for (Object page : pages) {
                    ids.add(configId.invoke(page));
                }
                for (Object page : (Collection<?>) builderType.getMethod("build").invoke(builder)) {
                    if (!ids.contains(configId.invoke(page))) {
                        pages.add(page);
                    }
                }
                List<Object> rest = new ArrayList<>(pages.subList(1, pages.size()));
                rest.sort(Comparator.comparing(page -> String.valueOf(invoke(name, page))));
                rest.addFirst(pages.getFirst());
                Object settings = config.getConstructor(List.class).newInstance(rest);
                manager.getMethod("registerConfigEntryPoint", Supplier.class, String.class)
                        .invoke(null, (Supplier<Object>) () -> made, id);
                manager.getField("CONFIG").set(null, settings);
                LOGGER.info("[Hotwire] {}'s page is in Sodium's video settings", id);
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

    private static @Nullable Object invoke(Method method, Object on) {
        try {
            return method.invoke(on);
        } catch (ReflectiveOperationException e) {
            return "";
        }
    }
}
