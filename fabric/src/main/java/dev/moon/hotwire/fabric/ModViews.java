package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.mixin.core.client.gui.components.toasts.ToastInstanceAccessor;
import dev.moon.hotwire.fabric.mixin.core.client.gui.components.toasts.ToastManagerAccessor;
import dev.moon.hotwire.fabric.registration.GameHold;
import dev.moon.hotwire.fabric.screen.HotwireScreen;
import dev.moon.hotwire.fabric.version.Spellings;
import dev.moon.hotwire.redefine.FieldSlots;
import dev.moon.hotwire.registration.Fields;
import dev.moon.hotwire.registration.Owners;
import dev.moon.hotwire.ui.Notices;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.particle.Particle;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ModViews {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModViews.class);

    private ModViews() {
    }

    public static @Nullable Screen openScreen() {
        return Spellings.openScreen();
    }

    public static @Nullable Overlay openOverlay() {
        return Spellings.openOverlay();
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
                String mod = particle instanceof Particle ? Owners.findModOf(particle.getClass()) : null;
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
        Object holder = Spellings.getScreenHolder();
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
        Spellings.showOverlay(overlay);
    }

    public static void rebuild() {
        Screen screen = openScreen();
        if (screen == null || screen instanceof HotwireScreen) {
            return;
        }
        Spellings.resize(screen);
    }

    static void reopenScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        Screen screen = openScreen();
        if (screen == null) {
            return;
        }
        if (screen instanceof HotwireScreen hotwire) {
            show(hotwire.makeAgain());
            return;
        }
        show(minecraft.level == null ? new TitleScreen() : null);
    }

    static void showBack(@Nullable Screen back, Collection<String> off) {
        if (back != null && isShownBy(back, off)) {
            show(Minecraft.getInstance().level == null ? new TitleScreen() : null);
        } else {
            show(back);
        }
    }

    public static void show(@Nullable Screen screen) {
        Spellings.show(screen);
    }

    static List<String> close(Collection<String> ids) {
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
        ToastManagerAccessor toasts = Spellings.getToasts();
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
                Notices.show("Hotwire", line);
                return;
            }
            MutableComponent text = Component.literal("[Hotwire] " + line)
                    .withStyle(bad ? ChatFormatting.RED : ChatFormatting.GRAY);
            if (!hint.isEmpty()) {
                text = text.append(Component.literal(" " + hint).withStyle(ChatFormatting.RED));
            }
            Spellings.tellPlayer(minecraft.player, text);
        });
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
        String owner = Owners.findModOf(shown.getClass());
        return owner != null && ids.contains(owner);
    }

    public static void runSoon(Runnable opening) {
        Spellings.runSoon(opening);
    }

    static List<KeyMapping> registerKeys(Map<String, Integer> defaults) {
        Identifier id = Identifier.fromNamespaceAndPath(HotwireClient.ID, HotwireClient.ID);
        return Spellings.registerKeys(id, defaults);
    }

    static void setKey(KeyMapping mapping, int code) {
        Spellings.setKey(mapping, code);
    }
}
