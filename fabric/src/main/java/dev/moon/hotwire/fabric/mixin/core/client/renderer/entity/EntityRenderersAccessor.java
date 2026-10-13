package dev.moon.hotwire.fabric.mixin.core.client.renderer.entity;

import java.util.Map;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EntityRenderers.class)
public interface EntityRenderersAccessor {
    @Accessor("PROVIDERS")
    static Map<EntityType<?>, EntityRendererProvider<?>> hotwire$getProviders() {
        throw new AssertionError();
    }
}
