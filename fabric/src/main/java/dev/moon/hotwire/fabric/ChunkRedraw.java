package dev.moon.hotwire.fabric;

import net.minecraft.client.Minecraft;

final class ChunkRedraw {
    private ChunkRedraw() {
    }

    static void run() {
        Minecraft game = Minecraft.getInstance();
        game.execute(() -> {
            if (game.level != null) {
                //? if >=26.2 {
                game.levelRenderer.invalidateCompiledGeometry(game.level, game.options,
                        game.gameRenderer.mainCamera(), game.getBlockColors());
                //?} else {
                /*game.levelRenderer.allChanged();
                *///?}
            }
        });
    }
}
