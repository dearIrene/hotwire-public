package dev.moon.hotwire.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
//? if >=26.1 {
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
//?} else {
/*import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
*///?}

final class HotwireCommands {
    private HotwireCommands() {
    }

    static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, Runnable open) {
        dispatcher.register(makeLiteral(HotwireClient.ID).executes(context -> {
            open.run();
            return 1;
        }));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> makeLiteral(String name) {
        //? if >=26.1 {
        return ClientCommands.literal(name);
        //?} else {
        /*return ClientCommandManager.literal(name);
        *///?}
    }
}
