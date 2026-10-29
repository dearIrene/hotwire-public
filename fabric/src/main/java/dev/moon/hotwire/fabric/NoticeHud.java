package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.screen.GuiCanvas;
import dev.moon.hotwire.fabric.screen.HotwireScreen;
import dev.moon.hotwire.fabric.screen.StoreGameScreen;
import dev.moon.hotwire.ui.NoticeLayer;
import dev.moon.hotwire.ui.Notices;
//? if >=1.21.6 {
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
*///?}
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

public final class NoticeHud {
    private static final NoticeLayer LAYER = new NoticeLayer();
    private static boolean added;

    private NoticeHud() {
    }

    public static void register() {
        Notices.onShown(notice -> addWhenShown());
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
        if (screen instanceof HotwireScreen || screen instanceof StoreGameScreen) {
            if (Notices.getShowing().isEmpty()) {
                minecraft.execute(NoticeHud::takeOut);
            }
            return;
        }
        LAYER.draw(canvas, minecraft.getWindow().getGuiScaledWidth(), minecraft.getWindow().getGuiScaledHeight(),
                System.nanoTime());
        if (LAYER.isEmpty()) {
            minecraft.execute(NoticeHud::takeOut);
        }
    }

    private static void takeOut() {
        if (added && Notices.getShowing().isEmpty()) {
            removeLayer();
            added = false;
        }
    }

    //? if >=1.21.6 {
    private static void addLayer() {
        HudElementRegistry.addLast(HotwireClient.NOTICES, (graphics, delta) -> draw(new GuiCanvas(graphics,
                Minecraft.getInstance().font)));
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
