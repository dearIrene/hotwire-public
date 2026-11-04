package dev.moon.hotwire.redefine;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SkippedConstructorCalls {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkippedConstructorCalls.class);

    private static final int CAP = 1 << 17;
    private static final int PURGE_EVERY = 1 << 12;
    private static final MethodHandle RECORD;
    private static final Map<String, List<Call>> CALLS = new ConcurrentHashMap<>();

    static {
        try {
            RECORD = MethodHandles.lookup().findStatic(SkippedConstructorCalls.class, "record",
                    MethodType.methodType(void.class, String.class, MethodHandle.class, Object[].class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private SkippedConstructorCalls() {
    }

    private record Call(MethodHandle handler, WeakReference<Object> object, List<@Nullable Object> arguments) {
    }

    static MethodHandle makeStandIn(String mixin, MethodHandle handler, MethodType type) {
        return MethodHandles.insertArguments(RECORD, 0, mixin, handler)
                .asCollector(Object[].class, type.parameterCount()).asType(type);
    }

    @SuppressWarnings("UnusedMethod")
    private static void record(String mixin, MethodHandle handler, Object[] arguments) {
        List<Call> calls = CALLS.computeIfAbsent(mixin, any -> new ArrayList<>());
        synchronized (calls) {
            if (calls.size() >= CAP) {
                return;
            }

            if (calls.size() % PURGE_EVERY == PURGE_EVERY - 1) {
                calls.removeIf(call -> call.object().refersTo(null));
            }
            List<@Nullable Object> rest = new ArrayList<>(Arrays.asList(arguments).subList(1, arguments.length));
            calls.add(new Call(handler, new WeakReference<>(arguments[0]), rest));
        }
    }

    static void replay(Set<String> mixins) {
        for (String mixin : mixins) {
            List<Call> calls = CALLS.remove(mixin);
            if (calls == null) {
                continue;
            }
            int run = 0;
            int failed = 0;
            synchronized (calls) {
                for (Call call : calls) {
                    Object object = call.object().get();
                    if (object == null) {
                        continue;
                    }
                    List<@Nullable Object> arguments = new ArrayList<>(call.arguments().size() + 1);
                    arguments.add(object);
                    arguments.addAll(call.arguments());
                    try {
                        call.handler().invokeWithArguments(arguments);
                        run++;
                    } catch (Throwable e) {
                        if (failed++ == 0) {
                            LOGGER.warn("[Hotwire] {}'s constructor handler could not run for an object made while"
                                    + " it was off", mixin, e);
                        }
                    }
                }
            }
            if (run + failed > 0) {
                LOGGER.info("[Hotwire] {}'s constructor handler ran for {} objects made while it was off, {} failed",
                        mixin, run, failed);
            }
        }
    }
}
