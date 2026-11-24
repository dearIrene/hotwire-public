package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.fabric.GameParts;
import dev.moon.hotwire.fabric.mixin.core.client.OptionsAccessor;
import dev.moon.hotwire.registration.RecorderSupport;
import dev.moon.hotwire.registration.Registration;
import dev.moon.hotwire.registration.SequenceOrder;
import java.util.Arrays;
import java.util.List;
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

    @Override
    public void remove() {
        List<KeyMapping> modded = KeyBindingRecorder.getModded();
        synchronized (modded) {
            if (modded.remove(this.binding)) {
                GameParts.getOffKeys().add(this.binding);
                dropFromGameKeys(this.binding);
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
                GameParts.getOffKeys().remove(this.binding);
                rebuildGameKeys();
            }
        }
    }

    @SuppressWarnings("ReferenceEquality")
    private static void dropFromGameKeys(KeyMapping binding) {
        Options options = Minecraft.getInstance().options;
        if (options != null) {
            OptionsAccessor access = (OptionsAccessor) options;
            access.hotwire$setKeyMappings(Arrays.stream(access.hotwire$getKeyMappings())
                    .filter(key -> key != binding).toArray(KeyMapping[]::new));
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
