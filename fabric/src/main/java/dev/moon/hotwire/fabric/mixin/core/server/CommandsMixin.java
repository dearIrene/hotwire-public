package dev.moon.hotwire.fabric.mixin.core.server;

import dev.moon.hotwire.fabric.registration.OffModCommands;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Commands.class)
public abstract class CommandsMixin {
    @Inject(method = "sendCommands", at = @At("HEAD"))
    private void hotwire$takeOffModsOut(ServerPlayer player, CallbackInfo ci) {
        OffModCommands.takeOutOf(((Commands) (Object) this).getDispatcher(), false);
    }
}
