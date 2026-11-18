package dev.moon.hotwire.fabric.registration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.client.KeyMapping;

public final class OffKeyBindings {
    private final Set<KeyMapping> removed = Collections.synchronizedSet(
            Collections.newSetFromMap(new IdentityHashMap<>()));

    void add(KeyMapping binding) {
        this.removed.add(binding);
    }

    void remove(KeyMapping binding) {
        this.removed.remove(binding);
    }

    public KeyMapping[] withRemoved(KeyMapping[] all) {
        List<KeyMapping> out = new ArrayList<>(Arrays.asList(all));
        synchronized (this.removed) {
            out.addAll(this.removed);
        }
        return out.toArray(KeyMapping[]::new);
    }
}
