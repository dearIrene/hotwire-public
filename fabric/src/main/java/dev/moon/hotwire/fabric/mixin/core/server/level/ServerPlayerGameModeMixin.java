package dev.moon.hotwire.fabric.mixin.core.server.level;

import dev.moon.hotwire.fabric.registration.ModGameModes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerGameModeMixin {
    @Inject(method = "setGameMode", at = @At("HEAD"), require = 0)
    private void hotwire$noteGameMode(GameType to, CallbackInfoReturnable<Boolean> cir) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        ModGameModes.onAsked(player, player.gameMode.getGameModeForPlayer(), to);
    }
}
