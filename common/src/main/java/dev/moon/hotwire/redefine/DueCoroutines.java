package dev.moon.hotwire.redefine;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DueCoroutines {
    private static final Logger LOGGER = LoggerFactory.getLogger(DueCoroutines.class);
    private static final String INTERNAL = ".coroutines.jvm.internal.";
    private static final Map<String, List<Due>> DEFERRED = new ConcurrentHashMap<>();

    private static final Map<String, Optional<Object>> SUSPENDED = new ConcurrentHashMap<>();

    private record Due(Object continuation, @Nullable Object result) {
    }

    private DueCoroutines() {
    }

    private static final java.util.Set<String> TOLD = ConcurrentHashMap.newKeySet();

    private static void tell(String mod, String what, Object continuation) {
        if (TOLD.add(mod)) {
            LOGGER.info("[Hotwire] a coroutine of {} came due while it is off and {} ({})", mod, what,
                    continuation.getClass().getName());
        }
    }

    static boolean isBody(@Nullable String superName, String name, String desc) {
        if (superName == null || !name.equals("invokeSuspend")
                || !desc.equals("(Ljava/lang/Object;)Ljava/lang/Object;")) {
            return false;
        }
        int at = superName.indexOf("/coroutines/jvm/internal/");
        String base = at < 0 ? "" : superName.substring(at + "/coroutines/jvm/internal/".length());
        return base.equals("SuspendLambda") || base.equals("RestrictedSuspendLambda")
                || base.equals("ContinuationImpl") || base.equals("RestrictedContinuationImpl");
    }

    public static boolean isBodyClass(Class<?> type) {
        Class<?> parent = type.getSuperclass();
        return parent != null && isBody(parent.getName().replace('.', '/'), "invokeSuspend",
                "(Ljava/lang/Object;)Ljava/lang/Object;");
    }

    public static @Nullable Object addDue(String mod, Object continuation, @Nullable Object result) {
        if (!Tripwire.canWaitForOn(mod)) {
            tell(mod, "runs: " + Thread.currentThread().getName() + " cannot wait for its on", continuation);
            return null;
        }
        Object suspended = findSuspended(continuation.getClass());
        if (suspended == null) {
            tell(mod, "runs: Kotlin's answer for a suspended coroutine was not found", continuation);
            return null;
        }
        tell(mod, "is set aside for its on", continuation);
        DEFERRED.computeIfAbsent(mod, any -> Collections.synchronizedList(new ArrayList<>()))
                .add(new Due(continuation, result));
        return suspended;
    }

    static void resume(String mod) {
        List<Due> due = DEFERRED.remove(mod);
        if (due == null) {
            return;
        }
        List<Due> all;
        synchronized (due) {
            all = List.copyOf(due);
        }
        for (Due one : all) {
            try {
                Object target = one.continuation();
                Method intercepted = findMethod(target.getClass(), "intercepted");
                if (intercepted != null) {
                    intercepted.setAccessible(true);
                    target = intercepted.invoke(target);
                }
                Method resumeWith = findMethod(target.getClass(), "resumeWith", Object.class);
                if (resumeWith == null) {
                    continue;
                }
                resumeWith.setAccessible(true);
                resumeWith.invoke(target, one.result());
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOGGER.warn("[Hotwire] a coroutine of {} set aside through its off could not go on: {}", mod,
                        e.toString());
            }
        }
        LOGGER.info("[Hotwire] {} coroutines of {} that came due while it was off go on now", all.size(), mod);
    }

    private static @Nullable Object findSuspended(Class<?> body) {
        for (Class<?> type = body; type != null; type = type.getSuperclass()) {
            String name = type.getName();
            int at = name.indexOf(INTERNAL);
            if (at >= 0) {
                String kotlin = name.substring(0, at);
                ClassLoader loader = type.getClassLoader();
                return SUSPENDED.computeIfAbsent(kotlin + "@" + System.identityHashCode(loader), key -> {
                    try {
                        Class<?> singletons = Class.forName(kotlin + ".coroutines.intrinsics.CoroutineSingletons",
                                false, loader);
                        return Optional.ofNullable(singletons.getField("COROUTINE_SUSPENDED").get(null));
                    } catch (ReflectiveOperationException | LinkageError e) {
                        return Optional.empty();
                    }
                }).orElse(null);
            }
        }
        return null;
    }

    private static @Nullable Method findMethod(Class<?> type, String name, Class<?>... parameters) {
        for (Class<?> at = type; at != null; at = at.getSuperclass()) {
            try {
                return at.getDeclaredMethod(name, parameters);
            } catch (NoSuchMethodException e) {
            }
        }
        return null;
    }
}
