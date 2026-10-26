package dev.moon.hotwire.fabric;

import dev.moon.hotwire.concurrent.Tasks;
import dev.moon.hotwire.fabric.add.WorldReopen;
import dev.moon.hotwire.fabric.registration.GameRegistrations;
import dev.moon.hotwire.registration.CallerOwner;
import dev.moon.hotwire.switching.CrashPolicy;
import dev.moon.hotwire.switching.ModSwitcher;
import dev.moon.hotwire.switching.PlayerMessages;
import dev.moon.hotwire.ui.Lang;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CrashGuard {
    private static final Logger LOGGER = LoggerFactory.getLogger(CrashGuard.class);
    private static final int FRAMES = 512;
    private static final String MIXIN_MERGED = "org.spongepowered.asm.mixin.transformer.meta.MixinMerged";
    private static volatile @Nullable CrashPolicy policy;

    private static final Set<String> SWITCHED = ConcurrentHashMap.newKeySet();

    private static volatile Function<String, @Nullable String> mixinOwner = name -> null;
    private static final List<String> CAUGHT = new CopyOnWriteArrayList<>();

    private CrashGuard() {
    }

    @SuppressWarnings("FutureReturnValueIgnored")

    static void install(InstalledMods installed, Tasks tasks, Supplier<@Nullable SwitchRunner> runner,
            BiConsumer<String, Boolean> say, Function<String, String> writeReport) {
        CrashPolicy[] made = new CrashPolicy[1];
        made[0] = new CrashPolicy(CrashGuard::findModBehind, id -> installed.isAddedThisSession(id)
                || SWITCHED.contains(id), caught -> {
            String name = installed.nameOf(caught.mod());
            LOGGER.error("[Hotwire] {} threw at {} ({}); Hotwire keeps the game going and turns it off", name,
                    caught.site(), caught.mod(), caught.thrown());
            CAUGHT.add(caught.mod() + " at " + caught.site() + ": " + caught.thrown());
            SwitchRunner switching = runner.get();
            if (switching == null) {
                made[0].onOffFinished(caught.mod(), false);
                return;
            }
            String why = name + " threw at " + caught.site() + ": " + caught.thrown();
            if (!switching.getSwitcher().isOn(caught.mod())) {
                tasks.submit("report of " + caught.mod() + "'s crash", () -> writeReport.apply(why), where -> {
                    String line = Lang.get("hotwire.crash.off_held", name, where);
                    say.accept(line, true);
                    ModViews.showToast(Lang.get("hotwire.crash.title"), line);
                });
                return;
            }
            endWhenThrown(made[0], caught.mod(), tasks.submit("turning off " + caught.mod() + " after its crash",
                    () -> switching.turnOff(List.of(caught.mod())), reported -> {
                        if (isWorldBuilt(reported.result())) {
                            endWhenThrown(made[0], caught.mod(), tasks.submit("saving the world and turning off "
                                    + caught.mod() + " after its crash", () -> {
                                        try {
                                            return WorldReopen.runAround(
                                                    () -> switching.turnOff(List.of(caught.mod())));
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                            throw new IllegalStateException("the off was interrupted while the"
                                                    + " world closed or opened", e);
                                        }
                                    },
                                    reopened -> finish(made[0], caught.mod(), name, reopened.result(), why,
                                            installed, say, writeReport)));
                            return;
                        }
                        finish(made[0], caught.mod(), name, reported.result(), why, installed, say, writeReport);
                    }));
        });
        policy = made[0];
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private static void endWhenThrown(CrashPolicy policy, String mod, CompletableFuture<Void> off) {
        off.whenComplete((done, thrown) -> {
            if (thrown != null) {
                policy.onOffFinished(mod, false);
            }
        });
    }

    private static boolean isWorldBuilt(ModSwitcher.Result result) {
        return result instanceof ModSwitcher.Result.Failed failed && failed.cause().isWorldBuilt();
    }

    private static void finish(CrashPolicy policy, String mod, String name, ModSwitcher.Result result, String why,
            InstalledMods installed, BiConsumer<String, Boolean> say, Function<String, String> writeReport) {
        boolean off = !PlayerMessages.isBad(result);
        policy.onOffFinished(mod, off);
        String where = writeReport.apply(why);
        String line = off ? Lang.get("hotwire.crash.turned_off", name, where) : Lang.get("hotwire.crash.not_turned_off",
                name, PlayerMessages.describe(result, installed::nameOf), where);
        say.accept(line, true);
        ModViews.showToast(Lang.get("hotwire.crash.title"), line);
    }

    static void onBackOn(Collection<String> ids) {
        CrashPolicy made = policy;
        if (made != null) {
            ids.forEach(id -> made.onOffFinished(id, true));
        }
    }

    public static void onSwitched(Collection<String> ids) {
        SWITCHED.addAll(ids);
    }

    static void setMixinOwners(Function<String, @Nullable String> owner) {
        mixinOwner = owner;
    }

    private static @Nullable String findModBehind(Throwable thrown) {
        String merged = findMergedOwner(thrown);
        if (merged != null) {
            return merged;
        }
        List<CallerOwner.Frame> frames = new ArrayList<>();
        ClassLoader game = CrashGuard.class.getClassLoader();
        int depth = 0;
        for (Throwable at = thrown; at != null && frames.size() < FRAMES && depth < 32; at = at.getCause(), depth++) {
            for (StackTraceElement element : at.getStackTrace()) {
                try {
                    frames.add(new CallerOwner.Frame(Class.forName(element.getClassName(), false, game),
                            element.getMethodName()));
                } catch (ClassNotFoundException | LinkageError e) {
                }
            }
        }
        CallerOwner.Owner owner = GameRegistrations.findOwnerOf(frames);
        return owner == null ? null : owner.mod();
    }

    private static @Nullable String findMergedOwner(Throwable thrown) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable at = thrown; at != null && chain.size() < 32; at = at.getCause()) {
            chain.addFirst(at);
        }

        for (Throwable at : chain) {
            String mod = findMergedOwnerOf(at);
            if (mod != null) {
                return mod.isEmpty() ? null : mod;
            }
        }
        return null;
    }

    private static @Nullable String findMergedOwnerOf(Throwable thrown) {
        ClassLoader game = CrashGuard.class.getClassLoader();
        for (StackTraceElement element : thrown.getStackTrace()) {
            Class<?> type;
            try {
                type = Class.forName(element.getClassName(), false, game);
            } catch (ClassNotFoundException | LinkageError e) {
                continue;
            }
            if (GameRegistrations.isSwitchable(GameRegistrations.findModOf(type))) {
                return "";
            }
            Method[] methods;
            try {
                methods = type.getDeclaredMethods();
            } catch (LinkageError e) {
                continue;
            }
            for (Method method : methods) {
                if (method.getName().equals(element.getMethodName())) {
                    String mod = findMergedMod(method);
                    if (GameRegistrations.isSwitchable(mod)) {
                        return mod;
                    }
                }
            }
        }
        return null;
    }

    public static @Nullable String findMergedMod(Method method) {
        String mixin = findMergedFrom(method);
        return mixin == null ? null : mixinOwner.apply(mixin.replace('.', '/'));
    }

    private static @Nullable String findMergedFrom(Method method) {
        for (Annotation annotation : method.getDeclaredAnnotations()) {
            if (annotation.annotationType().getName().equals(MIXIN_MERGED)) {
                try {
                    return String.valueOf(annotation.annotationType().getMethod("mixin").invoke(annotation));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return null;
                }
            }
        }
        return null;
    }

    public static boolean handle(Throwable thrown, CrashPolicy.Site site) {
        CrashPolicy current = policy;
        return current != null && current.handle(thrown, site);
    }

    public static List<String> getCaught() {
        return List.copyOf(CAUGHT);
    }
}
