package dev.moon.hotwire.fabric;

import dev.moon.hotwire.concurrent.Tasks;
import dev.moon.hotwire.fabric.registration.GameHold;
import dev.moon.hotwire.fabric.add.WorldReopen;
import dev.moon.hotwire.fabric.registration.GameRegistrations;
import dev.moon.hotwire.redefine.HandlerGuards;
import dev.moon.hotwire.registration.CallerOwner;
import dev.moon.hotwire.report.ActionJournal;
import dev.moon.hotwire.report.Report;
import dev.moon.hotwire.switching.CrashPolicy;
import dev.moon.hotwire.switching.ModSwitcher;
import dev.moon.hotwire.switching.PlayerMessages;
import dev.moon.hotwire.ui.Lang;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CrashGuard {
    private static final Pattern MERGED_LAMBDA = Pattern.compile("^(?:md|fd)[0-9a-f]{6}[$]([^$]+)[$]");
    private static final Logger LOGGER = LoggerFactory.getLogger(CrashGuard.class);
    private static final int FRAMES = 512;
    private static final String MIXIN_MERGED = "org.spongepowered.asm.mixin.transformer.meta.MixinMerged";
    private static volatile @Nullable CrashPolicy policy;

    private static final Set<String> SWITCHED = ConcurrentHashMap.newKeySet();

    private static volatile Function<String, @Nullable String> mixinOwner = name -> null;
    private static final List<String> CAUGHT = new CopyOnWriteArrayList<>();

    private static final int MOST_CAUGHT = 200;

    private CrashGuard() {
    }

    @SuppressWarnings("FutureReturnValueIgnored")

    static void install(InstalledMods installed, Tasks tasks, Supplier<@Nullable SwitchRunner> runner,
            BiConsumer<String, Boolean> say, BiFunction<String, String, String> writeReport,
            BooleanSupplier antiCrash) {
        CrashPolicy[] made = new CrashPolicy[1];
        made[0] = new CrashPolicy(CrashGuard::findModBehind, id -> isTouched(installed, id)
                || antiCrash.getAsBoolean(), caught -> {
            String name = installed.nameOf(caught.mod());
            boolean byAntiCrash = !isTouched(installed, caught.mod());
            LOGGER.error("[Hotwire] {} threw at {} ({}); Hotwire keeps the game going and turns it off", name,
                    caught.site(), caught.mod(), caught.thrown());
            if (CAUGHT.size() >= MOST_CAUGHT) {
                CAUGHT.remove(0);
            }
            CAUGHT.add(caught.mod() + " at " + caught.site() + ": " + caught.thrown());
            SwitchRunner switching = runner.get();
            if (switching == null) {
                made[0].onOffFinished(caught.mod(), false);
                return;
            }
            String why = name + " threw at " + caught.site() + ": " + caught.thrown();
            if (!switching.getSwitcher().isOn(caught.mod())) {
                tasks.submit("report of " + caught.mod() + "'s crash", () -> writeReport.apply(
                        Lang.get("hotwire.report.file.mod_crash", name), why), where -> {
                    String line = Lang.get("hotwire.crash.off_held", name, where);
                    say.accept(line, true);
                    ModViews.showNotice(Lang.get("hotwire.crash.title"), line);
                });
                return;
            }
            endWhenThrown(made[0], caught.mod(), tasks.submit("turning off " + caught.mod() + " after its crash",
                    () -> turnOffAfterCrash(caught.mod(), () -> switching.turnOff(List.of(caught.mod()))), reported -> {
                        if (isWorldBuilt(reported.result())) {
                            endWhenThrown(made[0], caught.mod(), tasks.submit("saving the world and turning off "
                                    + caught.mod() + " after its crash", () -> turnOffAfterCrash(caught.mod(), () -> {
                                        try {
                                            return WorldReopen.runAround(
                                                    () -> switching.turnOff(List.of(caught.mod())));
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                            throw new IllegalStateException("the off was interrupted while the"
                                                    + " world closed or opened", e);
                                        }
                                    }),
                                    reopened -> finish(made[0], caught.mod(), name, reopened.result(), why,
                                            installed, byAntiCrash ? (line, bad) -> { } : say, writeReport,
                                            byAntiCrash)));
                            return;
                        }
                        finish(made[0], caught.mod(), name, reported.result(), why, installed,
                                byAntiCrash ? (line, bad) -> { } : say, writeReport, byAntiCrash);
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

    private static SwitchRunner.CheckedResult turnOffAfterCrash(String mod,
            Supplier<SwitchRunner.CheckedResult> off) {
        ActionJournal.Entry journal = ActionJournal.get().begin(ActionJournal.Action.CRASH_OFF, mod);
        SwitchRunner.CheckedResult result;
        try {
            result = SwitchRunner.runQuietly(() -> GameHold.runUnbudgeted(off));
        } catch (RuntimeException e) {
            journal.endThrown();
            throw e;
        }
        journal.endSwitch(result.result(), result.check() instanceof Report.OffCheck.PartlyOff);
        return result;
    }

    private static boolean isTouched(InstalledMods installed, String mod) {
        return installed.isAddedThisSession(mod) || SWITCHED.contains(mod);
    }

    private static boolean isWorldBuilt(ModSwitcher.Result result) {
        return result instanceof ModSwitcher.Result.Failed failed && failed.cause().isWorldBuilt();
    }

    private static void finish(CrashPolicy policy, String mod, String name, ModSwitcher.Result result, String why,
            InstalledMods installed, BiConsumer<String, Boolean> say,
            BiFunction<String, String, String> writeReport, boolean byAntiCrash) {
        boolean off = !PlayerMessages.isBad(result);
        policy.onOffFinished(mod, off);
        String where = writeReport.apply(Lang.get("hotwire.report.file.mod_crash", name), why);
        if (byAntiCrash) {
            LOGGER.info("[Hotwire] Anti-Crash caught {}'s crash; {}", name, off ? "it is off" : "it could not go off");
            ModViews.showNotice(Lang.get("hotwire.anti_crash.title"), off ? Lang.get("hotwire.anti_crash.caught", name)
                    : Lang.get("hotwire.anti_crash.not_turned_off", name, PlayerMessages.describe(result,
                            installed::nameOf)));
            return;
        }
        String line = off ? Lang.get("hotwire.crash.turned_off", name, where) : Lang.get("hotwire.crash.not_turned_off",
                name, PlayerMessages.describe(result, installed::nameOf), where);
        say.accept(line, true);
        ModViews.showNotice(Lang.get("hotwire.crash.title"), line);
    }

    static void onBackOn(Collection<String> ids) {
        CrashPolicy made = policy;
        if (made != null) {
            ids.forEach(id -> made.onOffFinished(id, true));
        }
    }

    public static void onSwitched(Collection<String> ids) {
        SWITCHED.addAll(ids);
        lastSwitched = List.copyOf(ids);
        lastSwitchedAt = System.nanoTime();
    }

    private static volatile List<String> lastSwitched = List.of();
    private static volatile long lastSwitchedAt;
    private static final long AFTER_SWITCH_NANOS = TimeUnit.SECONDS.toNanos(30);

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
            for (StackTraceElement element : findFramesAbove(at)) {
                try {
                    frames.add(new CallerOwner.Frame(Class.forName(element.getClassName(), false, game),
                            element.getMethodName()));
                } catch (ClassNotFoundException | LinkageError e) {
                }
            }
        }
        CallerOwner.Owner owner = GameRegistrations.findOwnerOf(frames);
        if (owner != null) {
            return owner.mod();
        }
        List<String> recent = lastSwitched;
        if (!recent.isEmpty() && System.nanoTime() - lastSwitchedAt < AFTER_SWITCH_NANOS) {
            LOGGER.warn("[Hotwire] the game's own code threw within 30 s of {}'s switch, with no mod in the trace:"
                    + " taken as that switch's", recent);
            return recent.getFirst();
        }
        return null;
    }

    private static List<StackTraceElement> findFramesAbove(Throwable thrown) {
        StackTraceElement[] frames = thrown.getStackTrace();
        for (int at = 0; at < frames.length; at++) {
            if (frames[at].getMethodName().startsWith("hotwire$catch")) {
                return Arrays.asList(frames).subList(0, at);
            }
        }
        return Arrays.asList(frames);
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
        for (StackTraceElement element : findFramesAbove(thrown)) {
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
        if (mixin != null) {
            return mixinOwner.apply(mixin.replace('.', '/'));
        }
        return findMergedModByName(method.getName());
    }

    public static @Nullable String findMergedModByName(String name) {
        Matcher lambda = MERGED_LAMBDA.matcher(name);
        return lambda.find() ? lambda.group(1) : HandlerGuards.findModInHandlerName(name);
    }

    public static @Nullable String findMergedMod(Field field) {
        String mixin = findMergedFrom(field);
        return mixin == null ? null : mixinOwner.apply(mixin.replace('.', '/'));
    }

    private static @Nullable String findMergedFrom(AnnotatedElement member) {
        for (Annotation annotation : member.getDeclaredAnnotations()) {
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
