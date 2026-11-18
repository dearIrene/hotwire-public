package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.add.HiddenPacks;
import dev.moon.hotwire.fabric.registration.GameTasks;
import dev.moon.hotwire.fabric.registration.ModSounds;
import dev.moon.hotwire.fabric.registration.OffKeyBindings;
import dev.moon.hotwire.fabric.registration.OffModCommands;
import dev.moon.hotwire.fabric.update.OldPayloads;
import dev.moon.hotwire.fabric.version.HudElements;

public final class GameParts {
    private static final GameTasks TASKS = new GameTasks();
    private static final ModSounds SOUNDS = new ModSounds();
    private static final OldPayloads PAYLOADS = new OldPayloads();
    private static final OffKeyBindings OFF_KEYS = new OffKeyBindings();
    private static final OffModCommands COMMANDS = new OffModCommands();
    private static final HiddenPacks PACKS = new HiddenPacks();
    private static final HudElements HUD = new HudElements();

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

    public static OffModCommands getCommands() {
        return COMMANDS;
    }

    public static HiddenPacks getPacks() {
        return PACKS;
    }

    public static HudElements getHud() {
        return HUD;
    }
}
