package dev.moon.hotwire.fabric.mixin.core.network;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Connection.class)
public interface ConnectionAccessor {
    @Accessor("channel")
    @Nullable Channel hotwire$getChannel();
}
