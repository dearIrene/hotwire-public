package dev.moon.hotwire.fabric.registration;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OneConfigModCards {
    private static final Logger LOGGER = LoggerFactory.getLogger(OneConfigModCards.class);
    private static final String REGISTRY = "org.polyfrost.oneconfig.internal.ui.api.ConfigRegistry";
    private static final String CARD = "org.polyfrost.oneconfig.internal.ui.api.ConfigData";

    private record Card(Object registry, Object card, String id) implements OffRemovals.Entry {
        @Override
        public void restore() {
            try {
                this.registry.getClass().getMethod("register", Class.forName(CARD, false, this.registry.getClass()
                        .getClassLoader())).invoke(this.registry, this.card);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("OneConfig's register", e);
            }
        }

        @Override
        public String describe() {
            return "OneConfig's mod card " + this.id;
        }

        @Override
        public OffRemovals.Where getWhere() {
            return OffRemovals.Where.GAME_BEFORE_FRAME;
        }
    }

    private OneConfigModCards() {
    }

    public static void takeOut(Collection<String> mods) {
        OffRemovals.onGame(() -> takeOutNow(mods), "mod cards taken out");
    }

    private static void takeOutNow(Collection<String> mods) {
        Object registry = findRegistry();
        if (registry == null) {
            return;
        }
        try {
            List<?> cards = new ArrayList<>((List<?>) Objects.requireNonNull(registry.getClass()
                    .getMethod("getConfigs").invoke(registry)));

            Method idOf = Class.forName(CARD, false, registry.getClass().getClassLoader()).getMethod("getId");
            for (Object card : cards) {
                Objects.requireNonNull(card);
                String id = (String) idOf.invoke(card);
                Object tree = registry.getClass().getMethod("findTree", String.class).invoke(registry, id);
                String mod = tree == null ? null : findModOf(tree);
                if (mod != null && mods.contains(mod)) {
                    OffRemovals.add(mod, new Card(registry, card, id));
                    registry.getClass().getMethod("unregister", String.class).invoke(registry, id);
                    LOGGER.info("[Hotwire] {}'s OneConfig mod card {} is taken out while it is off", mod, id);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("[Hotwire] OneConfig's mod cards could not be taken out", e);
        }
    }

    private static @Nullable Object findRegistry() {
        try {
            return Class.forName(REGISTRY, false, OneConfigModCards.class.getClassLoader()).getField("INSTANCE")
                    .get(null);
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException | LinkageError e) {
            return null;
        }
    }

    static @Nullable String findModOf(Object tree) throws ReflectiveOperationException {
        Map<?, ?> nodes = (Map<?, ?>) read(tree, "org.polyfrost.oneconfig.api.config.v1.Tree", "theMap");
        if (nodes == null) {
            return null;
        }
        for (Object node : nodes.values()) {
            if (node == null) {
                continue;
            }
            String mod = null;
            if (node.getClass().getName().equals("org.polyfrost.oneconfig.api.config.v1.Property$Field")) {
                Object owner = read(node, node.getClass().getName(), "owner");
                mod = owner == null ? null : GameRegistrations.findModOf(owner.getClass());
            } else if (node.getClass().getName().equals("org.polyfrost.oneconfig.api.config.v1.Tree")) {
                mod = findModOf(node);
            }
            if (mod != null) {
                return mod;
            }
        }
        return null;
    }

    private static @Nullable Object read(Object of, String type, String name) throws ReflectiveOperationException {
        Field field = Class.forName(type, false, of.getClass().getClassLoader()).getDeclaredField(name);
        field.setAccessible(true);
        return field.get(of);
    }
}
