package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ModSounds {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModSounds.class);

    private ModSounds() {
    }

    public static void takeOut(Collection<String> mods) {
        Minecraft game = Minecraft.getInstance();
        game.execute(() -> {
            SoundManager manager = game.getSoundManager();
            List<SoundInstance> theirs = new ArrayList<>();
            for (Object engine : Fields.findBelow(manager, 1, type -> type.getName().startsWith("net.minecraft."))) {
                for (FieldSlots.Slot field : Fields.getDeclared(engine.getClass())) {
                    try {
                        if (!field.isStatic() && field.get(engine) instanceof Map<?, ?> playing) {
                            for (Object key : List.copyOf(playing.keySet())) {
                                if (key instanceof SoundInstance sound
                                        && mods.contains(GameRegistrations.findModOf(sound.getClass()))) {
                                    theirs.add(sound);
                                }
                            }
                        }
                    } catch (IllegalAccessException | RuntimeException e) {
                    }
                }
            }
            theirs.forEach(manager::stop);
            if (!theirs.isEmpty()) {
                LOGGER.info("[Hotwire] {} sounds of {} were stopped", theirs.size(), mods);
            }
        });
    }
}
