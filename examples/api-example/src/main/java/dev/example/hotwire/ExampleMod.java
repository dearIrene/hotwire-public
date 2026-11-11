package dev.example.hotwire;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;

public final class ExampleMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        boolean hotwire = FabricLoader.getInstance().isModLoaded("hotwire");
        if (hotwire) {
            HotwireCompat.listen();
        }
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registries) -> dispatcher.register(
                ClientCommands.literal("toggle").then(ClientCommands.argument("mod",
                        StringArgumentType.word()).executes(context -> {
                            if (!hotwire) {
                                context.getSource().sendFeedback(Component.literal("Hotwire is not installed."));
                                return 0;
                            }
                            HotwireCompat.toggle(StringArgumentType.getString(context, "mod"),
                                    said -> context.getSource().sendFeedback(Component.literal(said)));
                            return 1;
                        }))));
    }
}
