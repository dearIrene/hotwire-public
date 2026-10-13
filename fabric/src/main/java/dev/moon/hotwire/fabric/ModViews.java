package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.mixin.core.client.gui.components.toasts.ToastInstanceAccessor;
import dev.moon.hotwire.fabric.mixin.core.client.gui.components.toasts.ToastManagerAccessor;
import dev.moon.hotwire.fabric.registration.GameRegistrations;
import dev.moon.hotwire.fabric.screen.HotwireScreen;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

public final class ModViews {
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

    public static void closeOverlay() {
        //? if >=26.2 {
        Minecraft.getInstance().gui.setOverlay(null);
        //?} else {
        /*Minecraft.getInstance().setOverlay(null);
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
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.player == null) {
                showToast("Hotwire", line);
                return;
            }
            Component text = Component.literal("[Hotwire] " + line)
                    .withStyle(bad ? ChatFormatting.RED : ChatFormatting.GRAY);
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
