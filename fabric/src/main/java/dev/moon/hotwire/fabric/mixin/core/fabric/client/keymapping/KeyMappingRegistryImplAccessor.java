package dev.moon.hotwire.fabric.mixin.core.fabric.client.keymapping;

import java.util.List;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.client.keymapping.KeyMappingRegistryImpl", remap = false)
public interface KeyMappingRegistryImplAccessor {
    @Accessor("MODDED_KEY_BINDINGS")
    static List<KeyMapping> hotwire$getModded() {
        throw new AssertionError("replaced by Mixin");
    }

    @Invoker("process")
    static KeyMapping[] hotwire$process(KeyMapping[] all) {
        throw new AssertionError("replaced by Mixin");
    }
}
