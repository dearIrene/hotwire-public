package dev.moon.hotwire.registration;

import dev.moon.hotwire.redefine.Tripwire;
import dev.moon.hotwire.redefine.TripwireSites;
import dev.moon.hotwire.report.Causes;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.notices.Notices;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OffModSaves {
    private static final Logger LOGGER = LoggerFactory.getLogger(OffModSaves.class);

    private static volatile @Nullable OffModSaves installed;
    private final Function<String, String> nameOf;

    private final Map<String, Object> told = new ConcurrentHashMap<>();

    private OffModSaves(Function<String, String> nameOf) {
        this.nameOf = nameOf;
    }

    public static void install(Function<String, String> nameOf) {
        installed = new OffModSaves(nameOf);
    }

    public static <T> T run(Supplier<T> step) {
        if (!Tripwire.isAnyWatched()) {
            return step.get();
        }
        boolean before = Tripwire.markHotwireWork(true);
        try {
            return step.get();
        } catch (RuntimeException | LinkageError e) {
            if (before) {
                throw e;
            }
            onFailed(e);
        } finally {
            Tripwire.markHotwireWork(before);
        }
        return step.get();
    }

    private static void onFailed(Throwable thrown) {
        String mod = findOffMod(thrown);
        OffModSaves saves = installed;
        if (saves != null && !saves.isFirstFor(mod)) {
            return;
        }
        if (mod == null) {
            LOGGER.warn("[Hotwire] a save or load threw with the code of off mods let in, none of them in its frames;"
                    + " it runs again with their gates closed", thrown);
            return;
        }
        LOGGER.warn("[Hotwire] {} is off and its code threw in a save or load; it runs again with {}'s gates closed,"
                + " so what it holds is saved as it reads while off", mod, mod, thrown);
        if (saves != null) {
            Notices.show(Lang.get("hotwire.off_save.title"), Lang.get("hotwire.off_save.failed",
                    saves.nameOf.apply(mod)));
        }
    }

    @SuppressWarnings("ReferenceEquality")
    private boolean isFirstFor(@Nullable String mod) {
        Object watch = mod == null ? null : Tripwire.findWatch(mod);
        Object now = watch == null ? this : watch;
        return this.told.put(mod == null ? "" : mod, now) != now;
    }

    static @Nullable String findOffMod(Throwable thrown) {
        for (Throwable at : Causes.list(thrown).reversed()) {
            for (StackTraceElement frame : at.getStackTrace()) {
                String mod = TripwireSites.findModOfClass(frame.getClassName().replace('.', '/'));
                if (mod != null && Tripwire.isWatched(mod)) {
                    return mod;
                }
            }
        }
        return null;
    }
}
