package dev.moon.hotwire.fabric.mixin.core.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Options.class)
public interface OptionsAccessor {
    @Accessor("keyMappings")
    KeyMapping[] hotwire$getKeyMappings();

    @Mutable
    @Accessor("keyMappings")
    void hotwire$setKeyMappings(KeyMapping[] keyMappings);
}
