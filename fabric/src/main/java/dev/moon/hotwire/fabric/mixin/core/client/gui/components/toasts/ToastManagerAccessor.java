package dev.moon.hotwire.fabric.mixin.core.client.gui.components.toasts;

import java.util.Deque;
import java.util.List;
import net.minecraft.client.gui.components.toasts.Toast;
//? if >=1.21.4 {
import net.minecraft.client.gui.components.toasts.ToastManager;
//?} else {
/*import net.minecraft.client.gui.components.toasts.ToastComponent;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

//? if >=1.21.4 {
@Mixin(ToastManager.class)
//?} else {
/*@Mixin(ToastComponent.class)
*///?}
public interface ToastManagerAccessor {
    //? if >=1.21.4 {
    @Accessor("visibleToasts")
    //?} else {
    /*@Accessor("visible")
    *///?}
    List<Object> hotwire$getVisible();

    @Accessor("queued")
    Deque<Toast> hotwire$getQueued();
}
