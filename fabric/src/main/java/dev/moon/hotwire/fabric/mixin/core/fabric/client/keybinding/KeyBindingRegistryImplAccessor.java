package dev.moon.hotwire.fabric.mixin.core.fabric.client.keybinding;

import java.util.List;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.client.keybinding.KeyBindingRegistryImpl", remap = false)
public interface KeyBindingRegistryImplAccessor {
    @Accessor("MODDED_KEY_BINDINGS")
    static List<KeyMapping> hotwire$getModded() {
        throw new AssertionError("replaced by Mixin");
    }

    @Invoker("process")
    static KeyMapping[] hotwire$process(KeyMapping[] all) {
        throw new AssertionError("replaced by Mixin");
    }
}
