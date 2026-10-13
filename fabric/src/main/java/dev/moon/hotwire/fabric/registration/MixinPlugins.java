package dev.moon.hotwire.fabric.registration;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public final class MixinPlugins {
    private static final Logger LOGGER = LoggerFactory.getLogger(MixinPlugins.class);

    private record SwappedPlugin(Object handle, Field field, IMixinConfigPlugin plugin) {
    }

    private record Extension(Object extension, int at, boolean active) {
    }

    private final Supplier<List<Object>> configs;
    private final Object processor;
    private final Map<String, List<SwappedPlugin>> removed = new HashMap<>();
    private final Map<String, List<Extension>> removedExtensions = new HashMap<>();

    public MixinPlugins(Supplier<List<Object>> configs, Object processor) {
        this.configs = configs;
        this.processor = processor;
    }

    public synchronized void takeOut(Collection<String> mods) {
        mods.forEach(this::removeExtensions);
        for (String mod : mods) {
            if (this.removed.containsKey(mod)) {
                continue;
            }
            List<SwappedPlugin> mine = new ArrayList<>();
            for (Object config : this.configs.get()) {
                Object handle = read(config, "plugin");
                if (handle == null) {
                    continue;
                }
                Field field = findField(handle.getClass(), "plugin");
                Object plugin = field == null ? null : get(field, handle);
                if (field != null && plugin instanceof IMixinConfigPlugin own
                        && mod.equals(GameRegistrations.findModOf(own.getClass()))) {
                    try {
                        field.set(handle, new EmptyPlugin());
                        mine.add(new SwappedPlugin(handle, field, own));
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        LOGGER.warn("[Hotwire] {}'s Mixin plugin stays in the game's Mixin: {}", mod, e.toString());
                    }
                }
            }
            if (!mine.isEmpty()) {
                this.removed.put(mod, mine);
            }
        }
    }

    public synchronized void putBack(Collection<String> mods) {
        mods.forEach(this::restoreExtensions);
        for (String mod : mods) {
            for (SwappedPlugin one : this.removed.getOrDefault(mod, List.of())) {
                try {
                    one.field().set(one.handle(), one.plugin());
                } catch (ReflectiveOperationException | RuntimeException e) {
                    LOGGER.warn("[Hotwire] {}'s Mixin plugin could not go back into the game's Mixin", mod, e);
                }
            }
            this.removed.remove(mod);
        }
    }

    private void removeExtensions(String mod) {
        Object extensions = read(this.processor, "extensions");
        if (extensions == null || this.removedExtensions.containsKey(mod)) {
            return;
        }
        synchronized (this.processor) {
            List<Object> all = list(extensions, "extensions");
            List<Object> active = list(extensions, "activeExtensions");
            if (all == null || active == null) {
                return;
            }
            List<Extension> mine = new ArrayList<>();
            for (int at = 0; at < all.size(); at++) {
                Object extension = all.get(at);
                if (mod.equals(GameRegistrations.findModOf(extension.getClass()))) {
                    mine.add(new Extension(extension, at, active.contains(extension)));
                }
            }
            if (mine.isEmpty()) {
                return;
            }
            mine.reversed().forEach(one -> all.remove(one.at()));
            List<Object> gone = mine.stream().map(Extension::extension).toList();
            writeActive(extensions, active.stream().filter(one -> !gone.contains(one)).toList());
            this.removedExtensions.put(mod, mine);
            LOGGER.info("[Hotwire] {}'s Mixin extensions are out of the game's Mixin: {}", mod,
                    gone.stream().map(one -> one.getClass().getName()).toList());
        }
    }

    private void restoreExtensions(String mod) {
        List<Extension> mine = this.removedExtensions.remove(mod);
        Object extensions = read(this.processor, "extensions");
        if (mine == null || extensions == null) {
            return;
        }
        synchronized (this.processor) {
            List<Object> all = list(extensions, "extensions");
            List<Object> active = list(extensions, "activeExtensions");
            if (all == null || active == null) {
                return;
            }
            List<Object> on = new ArrayList<>(active);
            for (Extension one : mine) {
                all.add(Math.min(one.at(), all.size()), one.extension());
                if (one.active()) {
                    on.add(one.extension());
                }
            }
            writeActive(extensions, all.stream().filter(on::contains).toList());
        }
    }

    @SuppressWarnings("unchecked")
    private static @Nullable List<Object> list(Object holder, String name) {
        return read(holder, name) instanceof List<?> list ? (List<Object>) list : null;
    }

    private static void writeActive(Object extensions, List<Object> active) {
        Field field = findField(extensions.getClass(), "activeExtensions");
        try {
            if (field != null) {
                field.set(extensions, active);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("[Hotwire] the game's Mixin keeps its active extensions as they were", e);
        }
    }

    private static final class EmptyPlugin implements IMixinConfigPlugin {
        @Override
        public void onLoad(String mixinPackage) {
        }

        @Override
        public @Nullable String getRefMapperConfig() {
            return null;
        }

        @Override
        public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
            return true;
        }

        @Override
        public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        }

        @Override
        public @Nullable List<String> getMixins() {
            return null;
        }

        @Override
        public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                IMixinInfo mixinInfo) {
        }

        @Override
        public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                IMixinInfo mixinInfo) {
        }
    }

    private static @Nullable Object read(Object holder, String name) {
        Field field = findField(holder.getClass(), name);
        return field == null ? null : get(field, holder);
    }

    private static @Nullable Field findField(Class<?> type, String name) {
        for (Class<?> at = type; at != null; at = at.getSuperclass()) {
            try {
                Field field = at.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    private static @Nullable Object get(Field field, Object holder) {
        try {
            return field.get(holder);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
