package dev.moon.hotwire.fabric;

import dev.moon.hotwire.dev.DevCompare;
import dev.moon.hotwire.dev.DevExtras;
import dev.moon.hotwire.fabric.screen.DevGameScreen;
import dev.moon.hotwire.fabric.screen.GuiCanvas;
import dev.moon.hotwire.fabric.screen.HotwireScreen;
import dev.moon.hotwire.fabric.version.Bands;
import dev.moon.hotwire.fabric.version.Spellings;
import dev.moon.hotwire.ui.dev.BuildMark;
import dev.moon.hotwire.ui.dev.CompareHud;
import dev.moon.hotwire.ui.notices.NoticeLayer;
import dev.moon.hotwire.ui.notices.Notices;
import java.util.List;
import java.util.function.Supplier;
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
        Spellings.runSoon(() -> {
            if (!added) {
                added = true;
                addLayer();
            }
        });
    }

    public static void draw(GuiCanvas canvas) {
        Minecraft minecraft = Minecraft.getInstance();
        Screen screen = ModViews.openScreen();
        if (screen instanceof HotwireScreen || screen instanceof DevGameScreen) {
            if (Notices.getShowing().isEmpty()) {
                Spellings.runSoon(NoticeHud::takeOut);
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
            Spellings.runSoon(NoticeHud::takeOut);
        }
    }

    private static void takeOut() {
        if (added && Notices.getShowing().isEmpty() && !marking) {
            removeLayer();
            added = false;
        }
    }

    private static void addLayer() {
        Bands.addHudLayer(HotwireClient.NOTICES, NoticeHud::draw);
    }

    private static void removeLayer() {
        Bands.removeHudLayer(HotwireClient.NOTICES);
    }

    public static boolean isAdded() {
        return added;
    }
}
