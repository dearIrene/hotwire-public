package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.mixin.core.client.gui.components.toasts.ToastInstanceAccessor;
import dev.moon.hotwire.fabric.mixin.core.client.gui.components.toasts.ToastManagerAccessor;
import dev.moon.hotwire.fabric.registration.Fields;
import dev.moon.hotwire.fabric.registration.GameHold;
import dev.moon.hotwire.fabric.registration.GameRegistrations;
import dev.moon.hotwire.fabric.screen.HotwireScreen;
import dev.moon.hotwire.redefine.FieldSlots;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.particle.Particle;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ModViews {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModViews.class);

    private ModViews() {
    }

    public static @Nullable Screen openScreen() {
        //? if >=26.2 {
        return Minecraft.getInstance().gui.screen();
        //?} else {
        /*return Minecraft.getInstance().screen;
        *///?}
    }

    public static @Nullable Overlay openOverlay() {
        //? if >=26.2 {
        return Minecraft.getInstance().gui.overlay();
        //?} else {
        /*return Minecraft.getInstance().getOverlay();
        *///?}
    }

    public static void clearParticlesOf(Collection<String> mods) {
        Minecraft game = Minecraft.getInstance();
        if (game.level == null || !hasParticlesOf(game.particleEngine, Set.copyOf(mods))) {
            return;
        }
        GameHold.onGameAndWait(() -> game.particleEngine.setLevel(game.level));
        LOGGER.info("[Hotwire] the particles were cleared: some were {}'s", mods);
    }

    private static boolean hasParticlesOf(Object engine, Set<String> mods) {
        for (Object item : findContentsOf(engine)) {
            List<Object> particles = item instanceof Particle ? List.of(item)
                    : item instanceof Collection<?> collection ? List.copyOf(collection) : findContentsOf(item);
            for (Object particle : particles) {
                String mod = particle instanceof Particle ? GameRegistrations.findModOf(particle.getClass()) : null;
                if (mod != null && mods.contains(mod)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<Object> findContentsOf(Object holder) {
        List<Object> found = new ArrayList<>();
        for (Class<?> at = holder.getClass(); at != null && at != Object.class; at = at.getSuperclass()) {
            for (FieldSlots.Slot field : Fields.getDeclared(at)) {
                try {
                    Object value = field.isStatic() ? null : field.get(holder);
                    Collection<?> items = value instanceof Map<?, ?> map ? map.values()
                            : value instanceof Collection<?> collection ? collection : List.of();
                    items.stream().filter(Objects::nonNull).forEach(found::add);
                } catch (IllegalAccessException | RuntimeException e) {
                }
            }
        }
        return found;
    }

    public static @Nullable LoadingOverlay findLoadingOverlay() {
        Minecraft game = Minecraft.getInstance();
        //? if >=26.2 {
        Object holder = game.gui;
        //?} else {
        /*Object holder = game;
        *///?}
        for (FieldSlots.Slot field : Fields.getDeclared(holder.getClass())) {
            try {
                if (!field.isStatic() && Overlay.class.isAssignableFrom(field.getType())) {
                    return field.get(holder) instanceof LoadingOverlay loading ? loading : null;
                }
            } catch (IllegalAccessException e) {
                return openOverlay() instanceof LoadingOverlay loading ? loading : null;
            }
        }
        return openOverlay() instanceof LoadingOverlay loading ? loading : null;
    }

    public static void closeOverlay() {
        showOverlay(null);
    }

    public static void showOverlay(@Nullable Overlay overlay) {
        //? if >=26.2 {
        Minecraft.getInstance().gui.setOverlay(overlay);
        //?} else {
        /*Minecraft.getInstance().setOverlay(overlay);
        *///?}
    }

    public static void rebuild() {
        Screen screen = openScreen();
        if (screen == null || screen instanceof HotwireScreen) {
            return;
        }
        //? if >=1.21.11 {
        screen.resize(screen.width, screen.height);
        //?} else {
        /*screen.resize(Minecraft.getInstance(), screen.width, screen.height);
        *///?}
    }

    static void reopenScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        Screen screen = openScreen();
        if (screen == null) {
            return;
        }
        if (screen instanceof HotwireScreen) {
            HotwireClient.getEngine().open();
            return;
        }
        show(minecraft.level == null ? new TitleScreen() : null);
    }

    public static void show(@Nullable Screen screen) {
        //? if >=26.2 {
        Minecraft.getInstance().gui.setScreen(screen);
        //?} else {
        /*Minecraft.getInstance().setScreen(screen);
        *///?}
    }

    static List<String> close(Collection<String> ids) {
        Minecraft minecraft = Minecraft.getInstance();
        List<String> closed = new ArrayList<>();
        Screen screen = openScreen();
        if (screen != null && isShownBy(screen, ids)) {
            closed.add("the screen " + screen.getClass().getSimpleName());
            show(null);
        }
        Overlay overlay = openOverlay();
        if (overlay != null && isShownBy(overlay, ids)) {
            closed.add("the overlay " + overlay.getClass().getSimpleName());
            closeOverlay();
        }
        //? if >=26.2 {
        ToastManagerAccessor toasts = (ToastManagerAccessor) minecraft.gui.toastManager();
        //?} else if >=1.21.4 {
        /*ToastManagerAccessor toasts = (ToastManagerAccessor) minecraft.getToastManager();
        *///?} else {
        /*ToastManagerAccessor toasts = (ToastManagerAccessor) minecraft.getToasts();
        *///?}
        int gone = 0;
        gone += remove(toasts.hotwire$getVisible(), ids, entry -> ((ToastInstanceAccessor) entry).hotwire$getToast());
        gone += remove(toasts.hotwire$getQueued(), ids, toast -> toast);
        if (gone > 0) {
            closed.add(gone == 1 ? "1 toast" : gone + " toasts");
        }
        return closed;
    }

    static boolean isHotwireShown() {
        return openScreen() instanceof HotwireScreen;
    }

    static void showChat(String line, boolean bad) {
        showChat(line, bad, "");
    }

    static void showChat(String line, boolean bad, String hint) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.player == null) {
                showToast("Hotwire", line);
                return;
            }
            MutableComponent text = Component.literal("[Hotwire] " + line)
                    .withStyle(bad ? ChatFormatting.RED : ChatFormatting.GRAY);
            if (!hint.isEmpty()) {
                text = text.append(Component.literal(" " + hint).withStyle(ChatFormatting.RED));
            }
            //? if >=26.1 {
            minecraft.player.sendSystemMessage(text);
            //?} else {
            /*minecraft.player.displayClientMessage(text, false);
            *///?}
        });
    }

    public static void showToast(String title, String line) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> SystemToast.addOrUpdate(
                //? if >=26.2 {
                minecraft.gui.toastManager(),
                //?} else if >=1.21.4 {
                /*minecraft.getToastManager(),
                *///?} else {
                /*minecraft.getToasts(),
                *///?}
                SystemToast.SystemToastId.PERIODIC_NOTIFICATION, Component.literal(title), Component.literal(line)));
    }

    private static <T> int remove(Collection<T> entries, Collection<String> ids, Function<T, Object> toastOf) {
        int gone = 0;
        for (T entry : List.copyOf(entries)) {
            if (isShownBy(toastOf.apply(entry), ids)) {
                entries.remove(entry);
                gone++;
            }
        }
        return gone;
    }

    private static boolean isShownBy(Object shown, Collection<String> ids) {
        String owner = GameRegistrations.findModOf(shown.getClass());
        return owner != null && ids.contains(owner);
    }
}
