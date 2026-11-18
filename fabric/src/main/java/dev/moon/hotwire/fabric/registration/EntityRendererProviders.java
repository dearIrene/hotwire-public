package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.fabric.mixin.core.client.renderer.entity.EntityRenderersAccessor;
import dev.moon.hotwire.registration.GameRegistrations;
import dev.moon.hotwire.registration.Owners;
import dev.moon.hotwire.registration.RegistrationJournal;
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
    private final AtomicBoolean changed = new AtomicBoolean();

    private record Removal(EntityRendererProviders from, EntityType<?> type, EntityRendererProvider<?> provider)
            implements RegistrationJournal.Take {
        @Override
        @SuppressWarnings("ReferenceEquality")
        public void restore() {
            Map<EntityType<?>, EntityRendererProvider<?>> providers = EntityRenderersAccessor.hotwire$getProviders();
            if (providers.get(this.type) == NOTHING) {
                providers.put(this.type, this.provider);
                this.from.changed.set(true);
            }
        }

        @Override
        public String describe() {
            return "the renderer of " + this.type;
        }
    }

    public void takeOut(Collection<String> mods) {
        Map<EntityType<?>, EntityRendererProvider<?>> providers = EntityRenderersAccessor.hotwire$getProviders();
        boolean any = false;
        for (EntityType<?> type : List.copyOf(providers.keySet())) {
            EntityRendererProvider<?> provider = providers.get(type);
            String mod = provider == null ? null : Owners.findModOf(provider.getClass());
            if (provider != null && mod != null && mods.contains(mod)) {
                GameRegistrations.getJournal().addTake(mod, new Removal(this, type, provider));
                providers.put(type, NOTHING);
                any = true;
            }
        }
        if (any) {
            this.changed.set(true);
        }
    }

    public boolean takeChanged() {
        return this.changed.getAndSet(false);
    }
}
