package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.version.Spellings;
import net.minecraft.client.Minecraft;

final class ChunkRedraw {
    private ChunkRedraw() {
    }

    static void run() {
        Minecraft game = Minecraft.getInstance();
        game.execute(() -> {
            if (game.level != null) {
                Spellings.redrawChunks(game, game.level);
            }
        });
    }
}
