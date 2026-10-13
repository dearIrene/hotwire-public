package dev.moon.hotwire.fabric.mixin.core.client.gui.components.toasts;

import net.minecraft.client.gui.components.toasts.Toast;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

//? if >=1.21.4 {
@Mixin(targets = "net.minecraft.client.gui.components.toasts.ToastManager$ToastInstance")
//?} else {
/*@Mixin(targets = "net.minecraft.client.gui.components.toasts.ToastComponent$ToastInstance")
*///?}
public interface ToastInstanceAccessor {
    @Accessor("toast")
    Toast hotwire$getToast();
}
