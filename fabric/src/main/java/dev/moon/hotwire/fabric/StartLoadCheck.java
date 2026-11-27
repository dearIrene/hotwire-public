package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.version.Spellings;
import net.minecraft.client.Minecraft;

final class StartLoadCheck {
    static volatile boolean made;

    private StartLoadCheck() {
    }

    static boolean isLoaded() {
        return made && Minecraft.getInstance() != null && Spellings.openOverlay() == null
                && (Spellings.openScreen() != null || Minecraft.getInstance().level != null);
    }
}
