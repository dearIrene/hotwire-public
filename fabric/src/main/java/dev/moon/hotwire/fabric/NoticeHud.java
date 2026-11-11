package dev.moon.hotwire.fabric;

import dev.moon.hotwire.dev.DevCompare;
import dev.moon.hotwire.dev.DevExtras;
import dev.moon.hotwire.fabric.screen.DevGameScreen;
import dev.moon.hotwire.fabric.screen.GuiCanvas;
import dev.moon.hotwire.fabric.screen.HotwireScreen;
import dev.moon.hotwire.ui.BuildMark;
import dev.moon.hotwire.ui.CompareHud;
import dev.moon.hotwire.ui.NoticeLayer;
import dev.moon.hotwire.ui.Notices;
import java.util.List;
import java.util.function.Supplier;
//? if >=1.21.6 {
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
*///?}
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

public final class NoticeHud {
    private static final NoticeLayer LAYER = new NoticeLayer();
    private static boolean added;

    private static volatile Supplier<List<String>> markLines = List::of;
    private static volatile Supplier<double @Nullable []> markAt = () -> null;
    private static volatile boolean marking;

    private static volatile Supplier<@Nullable DevCompare> compare = () -> null;
    private static volatile Supplier<String> compareKey = () -> "";
    private static volatile Supplier<DevExtras> compareExtras = () -> DevExtras.ALL;

    private NoticeHud() {
    }

    public static void register() {
        Notices.onShown(notice -> addWhenShown());
    }

    public static void markWith(Supplier<List<String>> lines, Supplier<double @Nullable []> at) {
        markLines = lines;
        markAt = at;
        marking = true;
        addWhenShown();
    }

    public static void compareWith(Supplier<@Nullable DevCompare> running, Supplier<String> key,
            Supplier<DevExtras> extras) {
        compare = running;
        compareKey = key;
        compareExtras = extras;
        marking = true;
        addWhenShown();
    }

    private static void addWhenShown() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (!added) {
                added = true;
                addLayer();
            }
        });
    }

    private static void draw(GuiCanvas canvas) {
        Minecraft minecraft = Minecraft.getInstance();
        Screen screen = ModViews.openScreen();
        if (screen instanceof HotwireScreen || screen instanceof DevGameScreen) {
            if (Notices.getShowing().isEmpty()) {
                minecraft.execute(NoticeHud::takeOut);
            }
            return;
        }
        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();
        if (marking) {
            BuildMark.paintOn(canvas, width, height, markLines.get(), markAt.get(), 1);
            CompareHud.paintOn(canvas, width, compare.get(), compareKey.get(), compareExtras.get(), 1);
        }
        LAYER.draw(canvas, width, height, System.nanoTime());
        if (LAYER.isEmpty() && !marking) {
            minecraft.execute(NoticeHud::takeOut);
        }
    }

    private static void takeOut() {
        if (added && Notices.getShowing().isEmpty() && !marking) {
            removeLayer();
            added = false;
        }
    }

    //? if >=1.21.6 {
    private static void addLayer() {
        try {
            HudElementRegistry.addLast(HotwireClient.NOTICES, (graphics, delta) -> draw(new GuiCanvas(graphics,
                    Minecraft.getInstance().font)));
        } catch (IllegalArgumentException e) {
        }
    }

    private static void removeLayer() {
        HudElementRegistry.removeElement(HotwireClient.NOTICES);
    }
    //?} else {
    /*private static void addLayer() {
    }

    private static void removeLayer() {
    }

    public static void drawOver(GuiGraphics graphics) {
        if (added) {
            draw(new GuiCanvas(graphics, Minecraft.getInstance().font));
        }
    }
    *///?}
}
