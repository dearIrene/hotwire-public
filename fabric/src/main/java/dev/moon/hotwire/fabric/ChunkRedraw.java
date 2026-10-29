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
                /*int reach = (game.options.getEffectiveRenderDistance() + 1) * 16;
                if (game.player == null) {
                    return;
                }
                int x = game.player.getBlockX();
                int z = game.player.getBlockZ();
                int bottom = game.level.dimensionType().minY();
                game.levelRenderer.setBlocksDirty(x - reach, bottom, z - reach, x + reach,
                        bottom + game.level.dimensionType().height() - 1, z + reach);
                *///?}
            }
        });
    }
}
