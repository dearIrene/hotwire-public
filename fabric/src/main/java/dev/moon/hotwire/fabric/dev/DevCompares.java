package dev.moon.hotwire.fabric.dev;

import dev.moon.hotwire.concurrent.Tasks;
import dev.moon.hotwire.dev.DevCompare;
import dev.moon.hotwire.fabric.CrashGuard;
import dev.moon.hotwire.ui.Lang;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

final class DevCompares {
    private final Tasks tasks;

    private final BiPredicate<SessionMod, Integer> putIn;

    private final Function<Set<String>, List<Class<?>>> loaded;

    DevCompares(Tasks tasks, BiPredicate<SessionMod, Integer> putIn,
            Function<Set<String>, List<Class<?>>> loaded) {
        this.tasks = tasks;
        this.putIn = putIn;
        this.loaded = loaded;
    }

    private @Nullable String findMixin(String id, String type, String method) {
        List<Class<?>> found = this.loaded.apply(Set.of(type));
        return found.isEmpty() ? null : CrashGuard.findMixinOf(found.getFirst(), method, id);
    }

    void start(SessionMod mod, int b) {
        int a = mod.runningNumber;
        if (a == b) {
            return;
        }
        CompareWindow was = mod.comparing;
        if (was != null) {
            was.stop();
        }
        mod.pairA = a;
        mod.pairB = b;
        String id = mod.mod.id();
        mod.comparing = new CompareWindow(a, b, () -> mod.runningNumber, () -> mod.running.getClassNames(),
                (type, method) -> this.findMixin(id, type, method));
        mod.compare = null;
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    void flip(SessionMod mod) {
        CompareWindow window = mod.comparing;
        if (window == null) {
            return;
        }
        int other = mod.runningNumber == window.getA() ? window.getB() : window.getA();
        this.tasks.submit("switching " + mod.mod.id() + " to build " + other + " for an A/B", () -> {
            synchronized (mod.taking) {
                window.onSwitch();
                if (!this.putIn.test(mod, other)) {
                    window.fail(Lang.get("hotwire.dev.compare.no_flip", other));
                }
            }
            return null;
        });
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    void stop(SessionMod mod) {
        CompareWindow window = mod.comparing;
        if (window == null) {
            return;
        }
        window.stop();
        mod.compare = window.read(true);
        mod.comparing = null;

        this.tasks.submit("back to the code's build of " + mod.mod.id() + " after an A/B", () -> {
            synchronized (mod.taking) {
                if (mod.codeNumber >= 0 && mod.codeNumber != mod.runningNumber
                        && !this.putIn.test(mod, mod.codeNumber)) {
                    window.fail(Lang.get("hotwire.dev.compare.no_flip", mod.codeNumber));
                    mod.compare = window.read(true);
                }
            }
            return null;
        });
    }

    static @Nullable DevCompare findRunning(Collection<SessionMod> mods) {
        for (SessionMod mod : mods) {
            CompareWindow window = mod.comparing;
            if (window != null) {
                return window.read(false);
            }
        }
        return null;
    }

    static @Nullable DevCompare find(SessionMod mod) {
        CompareWindow window = mod.comparing;
        return window != null ? window.read(false) : mod.compare;
    }
}
