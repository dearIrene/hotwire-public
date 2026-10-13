package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.fabric.mixin.core.client.OptionsAccessor;
import dev.moon.hotwire.registration.Registration;
import dev.moon.hotwire.registration.SequenceOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

public final class KeyBindingRegistration implements Registration {
    private final KeyMapping binding;

    KeyBindingRegistration(KeyMapping binding) {
        this.binding = binding;
    }

    @Override
    public String getKind() {
        return "key binding";
    }

    @Override
    public String describe() {
        return this.binding.getName();
    }

    private static final Set<KeyMapping> REMOVED = Collections.synchronizedSet(
            Collections.newSetFromMap(new IdentityHashMap<>()));

    public static KeyMapping[] withRemoved(KeyMapping[] all) {
        List<KeyMapping> out = new ArrayList<>(Arrays.asList(all));
        synchronized (REMOVED) {
            out.addAll(REMOVED);
        }
        return out.toArray(KeyMapping[]::new);
    }

    @Override
    public void remove() {
        List<KeyMapping> modded = KeyBindingRecorder.getModded();
        synchronized (modded) {
            if (modded.remove(this.binding)) {
                REMOVED.add(this.binding);
                rebuildGameKeys();
            }
        }
    }

    @Override
    public void restore() {
        List<KeyMapping> modded = KeyBindingRecorder.getModded();
        synchronized (modded) {
            List<KeyMapping> placed = SequenceOrder.insert(modded, this.binding, RecorderSupport::getSequenceOf);
            if (placed.size() != modded.size()) {
                modded.clear();
                modded.addAll(placed);
                REMOVED.remove(this.binding);
                rebuildGameKeys();
            }
        }
    }

    static void rebuildGameKeys() {
        Options options = Minecraft.getInstance().options;
        if (options != null) {
            OptionsAccessor access = (OptionsAccessor) options;
            access.hotwire$setKeyMappings(KeyBindingRecorder.process(access.hotwire$getKeyMappings()));
        }
    }
}
