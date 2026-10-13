package dev.moon.hotwire.fabric.mixin.core.brigadier;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.moon.hotwire.fabric.registration.CommandRecorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CommandDispatcher.class, remap = false)
public abstract class CommandDispatcherMixin<S> {
    @SuppressWarnings("unchecked")
    @Inject(method = "register", at = @At("RETURN"))
    private void hotwire$onRegistered(LiteralArgumentBuilder<S> command,
            CallbackInfoReturnable<LiteralCommandNode<S>> cir) {
        CommandRecorder.onRegistered((CommandDispatcher<S>) (Object) this, cir.getReturnValue());
    }
}
