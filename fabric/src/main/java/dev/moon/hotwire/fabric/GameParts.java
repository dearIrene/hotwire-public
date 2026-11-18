package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.registration.GameTasks;
import dev.moon.hotwire.fabric.registration.ModSounds;
import dev.moon.hotwire.fabric.registration.OffKeyBindings;
import dev.moon.hotwire.fabric.update.OldPayloads;

public final class GameParts {
    private static final GameTasks TASKS = new GameTasks();
    private static final ModSounds SOUNDS = new ModSounds();
    private static final OldPayloads PAYLOADS = new OldPayloads();
    private static final OffKeyBindings OFF_KEYS = new OffKeyBindings();

    private GameParts() {
    }

    public static GameTasks getTasks() {
        return TASKS;
    }

    public static ModSounds getSounds() {
        return SOUNDS;
    }

    public static OldPayloads getPayloads() {
        return PAYLOADS;
    }

    public static OffKeyBindings getOffKeys() {
        return OFF_KEYS;
    }
}
