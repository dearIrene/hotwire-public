package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.fabric.mixin.core.client.renderer.entity.EntityRenderersAccessor;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

public final class EntityRendererProviders {
    private static final EntityRendererProvider<Entity> NOTHING = NoopRenderer::new;
    private static final AtomicBoolean CHANGED = new AtomicBoolean();

    private record Removal(EntityType<?> type, EntityRendererProvider<?> provider) implements TakenOut.Entry {
        @Override
        @SuppressWarnings("ReferenceEquality")
        public void restore() {
            Map<EntityType<?>, EntityRendererProvider<?>> providers = EntityRenderersAccessor.hotwire$getProviders();
            if (providers.get(this.type) == NOTHING) {
                providers.put(this.type, this.provider);
                CHANGED.set(true);
            }
        }

        @Override
        public String describe() {
            return "the renderer of " + this.type;
        }
    }

    private EntityRendererProviders() {
    }

    public static void takeOut(Collection<String> mods) {
        Map<EntityType<?>, EntityRendererProvider<?>> providers = EntityRenderersAccessor.hotwire$getProviders();
        boolean changed = false;
        for (EntityType<?> type : List.copyOf(providers.keySet())) {
            EntityRendererProvider<?> provider = providers.get(type);
            String mod = provider == null ? null : GameRegistrations.findModOf(provider.getClass());
            if (provider != null && mod != null && mods.contains(mod)) {
                TakenOut.add(mod, new Removal(type, provider));
                providers.put(type, NOTHING);
                changed = true;
            }
        }
        if (changed) {
            CHANGED.set(true);
        }
    }

    public static boolean takeChanged() {
        return CHANGED.getAndSet(false);
    }
}
