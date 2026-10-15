package dev.moon.hotwire.fabric.mixin.core.brigadier.tree;

import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import dev.moon.hotwire.fabric.registration.CommandRecorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CommandNode.class, remap = false)
public abstract class CommandNodeMixin<S> {
    @Inject(method = "addChild", at = @At("TAIL"))
    private void hotwire$onChildAdded(CommandNode<S> node, CallbackInfo ci) {
        if ((Object) this instanceof RootCommandNode<?> root) {
            CommandRecorder.onAdded(root, node);
        }
    }
}
