package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.fabric.mixin.core.client.renderer.entity.EntityRenderersAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

public final class EntityRendererProviders {
    private record Removal(EntityType<?> type, EntityRendererProvider<?> provider) {
    }

    private static final Map<String, List<Removal>> REMOVED = new ConcurrentHashMap<>();
    private static final EntityRendererProvider<Entity> NOTHING = NoopRenderer::new;
    private static final AtomicBoolean CHANGED = new AtomicBoolean();

    private EntityRendererProviders() {
    }

    public static void takeOut(Collection<String> mods) {
        Map<EntityType<?>, EntityRendererProvider<?>> providers = EntityRenderersAccessor.hotwire$getProviders();
        boolean changed = false;
        for (EntityType<?> type : List.copyOf(providers.keySet())) {
            EntityRendererProvider<?> provider = providers.get(type);
            String mod = provider == null ? null : GameRegistrations.findModOf(provider.getClass());
            if (provider != null && mod != null && mods.contains(mod)) {
                REMOVED.computeIfAbsent(mod, any -> new ArrayList<>()).add(new Removal(type, provider));
                providers.put(type, NOTHING);
                changed = true;
            }
        }
        if (changed) {
            CHANGED.set(true);
        }
    }

    @SuppressWarnings("ReferenceEquality")
    public static void putBack(Collection<String> mods) {
        Map<EntityType<?>, EntityRendererProvider<?>> providers = EntityRenderersAccessor.hotwire$getProviders();
        boolean changed = false;
        for (String mod : mods) {
            List<Removal> removed = REMOVED.remove(mod);
            for (Removal one : removed == null ? List.<Removal>of() : removed) {
                if (providers.get(one.type()) == NOTHING) {
                    providers.put(one.type(), one.provider());
                    changed = true;
                }
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
