package dev.moon.hotwire.fabric.mixin.core.client;

import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Minecraft.class)
public interface MinecraftAccessor {
    @Accessor("pendingReload")
    @Nullable CompletableFuture<Void> hotwire$getPendingReload();
}
