package dev.moon.hotwire.fabric;

import dev.moon.hotwire.config.Flags;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinErrorHandler;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public final class LostHooks implements IMixinErrorHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(LostHooks.class);
    private static final String OWN = "dev.moon.hotwire.fabric.mixin.";

    private static volatile @Nullable String lost;

    private static volatile @Nullable Runnable onLost;

    public LostHooks() {
    }

    static void listen() {
        Mixins.registerErrorHandlerClass(LostHooks.class.getName());

        if (Flags.LOST_HOOK != null) {
            note(Flags.LOST_HOOK);
        }
    }

    static @Nullable String findLost() {
        return lost;
    }

    static void setOnLost(Runnable then) {
        onLost = then;
    }

    @Override
    public @Nullable ErrorAction onPrepareError(IMixinConfig config, Throwable failure, IMixinInfo mixin,
            ErrorAction action) {
        note(mixin, failure);
        return null;
    }

    @Override
    public @Nullable ErrorAction onApplyError(String targetClassName, Throwable failure, IMixinInfo mixin,
            ErrorAction action) {
        note(mixin, failure);
        return null;
    }

    private static void note(IMixinInfo mixin, Throwable failure) {
        if (!mixin.getClassName().startsWith(OWN)) {
            return;
        }
        note(mixin.getName() + ": " + failure.getMessage());
    }

    private static void note(String line) {
        LOGGER.warn("[Hotwire] a hook could not be put in, {}", line);
        synchronized (LostHooks.class) {
            String before = lost;
            lost = before == null ? line : before + "\n" + line;
        }
        Runnable then = onLost;
        if (then != null) {
            then.run();
        }
    }
}
