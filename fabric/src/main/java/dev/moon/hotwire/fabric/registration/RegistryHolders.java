package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import java.lang.instrument.Instrumentation;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

public final class RegistryHolders {
    private final Lambdas owners;

    private final Map<Class<?>, Optional<String>> modOf = new ConcurrentHashMap<>();

    public RegistryHolders(Instrumentation instrumentation) {
        this.owners = new Lambdas(instrumentation);
    }

    public Set<String> findOwnedBy(Set<String> mods) {
        Set<String> found = new TreeSet<>();
        for (Registry<?> registry : BuiltInRegistries.REGISTRY) {
            for (Class<?> at = registry.getClass(); at != null && at != Object.class; at = at.getSuperclass()) {
                for (FieldSlots.Slot field : Fields.getDeclared(at)) {
                    if (field.isStatic() || field.getType().isPrimitive()) {
                        continue;
                    }
                    try {
                        Object value = field.get(registry);
                        String mod = value == null ? null : this.modOf.computeIfAbsent(value.getClass(),
                                type -> Optional.ofNullable(this.owners.findModOf(type))).orElse(null);
                        if (mod != null && mods.contains(mod)) {
                            found.add(mod);
                        }
                    } catch (ReflectiveOperationException | RuntimeException e) {
                    }
                }
            }
        }
        return found;
    }
}
