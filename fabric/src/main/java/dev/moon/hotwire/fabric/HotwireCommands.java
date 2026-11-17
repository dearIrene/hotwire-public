package dev.moon.hotwire.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.moon.hotwire.fabric.version.Spellings;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

final class HotwireCommands {
    private HotwireCommands() {
    }

    static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, Runnable open, Runnable report) {
        dispatcher.register(makeLiteral(HotwireClient.ID).executes(context -> {
            open.run();
            return 1;
        }).then(makeLiteral("bug").executes(context -> {
            report.run();
            return 1;
        })));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> makeLiteral(String name) {
        return Spellings.makeCommandLiteral(name);
    }
}
