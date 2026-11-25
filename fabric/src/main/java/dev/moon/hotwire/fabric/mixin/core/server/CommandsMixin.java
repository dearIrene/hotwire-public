package dev.moon.hotwire.fabric.mixin.core.server;

import dev.moon.hotwire.fabric.GameParts;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Commands.class)
public abstract class CommandsMixin {
    @Group(name = "takeOffModsOut")
    @Inject(method = "sendCommands", at = @At("HEAD"))
    private void hotwire$takeOffModsOut(ServerPlayer player, CallbackInfo ci) {
        GameParts.getCommands().takeOutOf(((Commands) (Object) this).getDispatcher(), false);
    }
}
