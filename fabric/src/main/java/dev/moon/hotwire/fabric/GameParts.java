package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.add.HiddenPacks;
import dev.moon.hotwire.fabric.registration.GameTasks;
import dev.moon.hotwire.fabric.registration.HoldWork;
import dev.moon.hotwire.fabric.registration.ModLifecycle;
import dev.moon.hotwire.fabric.registration.ModSounds;
import dev.moon.hotwire.fabric.registration.ModelReloadCheck;
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
    private static final ModLifecycle LIFECYCLE = new ModLifecycle();
    private static final OngoingReload ONGOING_RELOAD = new OngoingReload();
    private static final BackgroundReload BACKGROUND_RELOAD = new BackgroundReload();
    private static final ReloadFailures RELOAD_FAILURES = new ReloadFailures();
    private static final ModelReloadCheck MODEL_CHECK = new ModelReloadCheck();
    private static final HoldWork HOLD_WORK = new HoldWork();
    private static final GameThreadDisconnects DISCONNECTS = new GameThreadDisconnects();

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

    public static ModLifecycle getLifecycle() {
        return LIFECYCLE;
    }

    public static OngoingReload getOngoingReload() {
        return ONGOING_RELOAD;
    }

    public static BackgroundReload getBackgroundReload() {
        return BACKGROUND_RELOAD;
    }

    public static ReloadFailures getReloadFailures() {
        return RELOAD_FAILURES;
    }

    public static ModelReloadCheck getModelCheck() {
        return MODEL_CHECK;
    }

    public static HoldWork getHoldWork() {
        return HOLD_WORK;
    }

    public static GameThreadDisconnects getDisconnects() {
        return DISCONNECTS;
    }
}
