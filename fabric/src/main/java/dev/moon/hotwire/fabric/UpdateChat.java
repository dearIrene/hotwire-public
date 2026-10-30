package dev.moon.hotwire.fabric;

import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.Palette;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

public final class UpdateChat {
    private static final int FROM = Palette.ACCENT_SOFT;
    private static final int TO = Palette.ACCENT;

    private UpdateChat() {
    }

    public static void show() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        String text = Lang.get("hotwire.self_update.chat");
        Style style = Style.EMPTY.withClickEvent(openScreen());
        MutableComponent line = Component.empty();
        int last = Math.max(1, text.length() - 1);
        for (int at = 0; at < text.length(); at++) {
            int color = blend(FROM, TO, (float) at / last);
            line.append(Component.literal(String.valueOf(text.charAt(at)))
                    .withStyle(style.withColor(TextColor.fromRgb(color & 0xFFFFFF))));
        }
        //? if >=26.1 {
        minecraft.player.sendSystemMessage(line);
        //?} else {
        /*minecraft.player.displayClientMessage(line, false);
        *///?}
    }

    private static ClickEvent openScreen() {
        //? if >=1.21.5 {
        return new ClickEvent.RunCommand("/hotwire");
        //?} else {
        /*return new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/hotwire");
        *///?}
    }

    private static int blend(int from, int to, float fraction) {
        int r = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * fraction);
        int g = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * fraction);
        int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * fraction);
        return (r << 16) | (g << 8) | b;
    }
}
