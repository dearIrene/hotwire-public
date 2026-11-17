package dev.moon.hotwire.fabric.version;

import dev.moon.hotwire.fabric.screen.GuiCanvas;
import java.util.function.Consumer;
import net.minecraft.resources.Identifier;

public final class Bands {
    private Bands() {
    }

    public static void addHudLayer(Identifier id, Consumer<GuiCanvas> drawing) {
        //? if >=1.21.6 {
        HudElements.addLast(id, drawing);
        //?}
    }

    public static void removeHudLayer(Identifier id) {
        //? if >=1.21.6 {
        HudElements.remove(id);
        //?}
    }
}
