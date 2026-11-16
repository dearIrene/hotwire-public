package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.registration.RegistrationJournal;
import dev.moon.hotwire.update.LiveNames;
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

    private record Card(Object registry, Object card, String id) implements RegistrationJournal.Take {
        @Override
        public void restore() {
            try {
                this.registry.getClass().getMethod("register", LiveNames.findBeside(CARD, this.registry.getClass()))
                        .invoke(this.registry, this.card);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("OneConfig's register", e);
            }
        }

        @Override
        public String describe() {
            return "OneConfig's mod card " + this.id;
        }

        @Override
        public RegistrationJournal.Where getWhere() {
            return RegistrationJournal.Where.GAME_BEFORE_FRAME;
        }
    }

    private OneConfigModCards() {
    }

    public static void takeOut(Collection<String> mods) {
        GameHold.onGameBeforeFrameAndWait(() -> takeOutNow(mods), "mod cards taken out");
    }

    private static void takeOutNow(Collection<String> mods) {
        Object registry = findRegistry();
        if (registry == null) {
            return;
        }
        try {
            List<?> cards = new ArrayList<>((List<?>) Objects.requireNonNull(registry.getClass()
                    .getMethod("getConfigs").invoke(registry)));

            Method idOf = LiveNames.findBeside(CARD, registry.getClass()).getMethod("getId");
            for (Object card : cards) {
                Objects.requireNonNull(card);
                String id = (String) idOf.invoke(card);
                Object tree = registry.getClass().getMethod("findTree", String.class).invoke(registry, id);
                String mod = tree == null ? null : findModOf(tree);
                if (mod != null && mods.contains(mod)) {
                    GameRegistrations.getJournal().addTake(mod, new Card(registry, card, id));
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
            Class<?> registry = LiveNames.findRunning(REGISTRY, OneConfigModCards.class.getClassLoader());

            return Owners.isOfOffMod(registry) ? null : registry.getField("INSTANCE").get(null);
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
            String type = LiveNames.findOwnName(node.getClass().getName());
            if ("org.polyfrost.oneconfig.api.config.v1.Property$Field".equals(type)) {
                Object owner = read(node, node.getClass().getName(), "owner");
                mod = owner == null ? null : Owners.findModOf(owner.getClass());
            } else if ("org.polyfrost.oneconfig.api.config.v1.Tree".equals(type)) {
                mod = findModOf(node);
            }
            if (mod != null) {
                return mod;
            }
        }
        return null;
    }

    private static @Nullable Object read(Object of, String type, String name) throws ReflectiveOperationException {
        Field field = LiveNames.findBeside(type, of.getClass()).getDeclaredField(name);
        field.setAccessible(true);
        return field.get(of);
    }
}
