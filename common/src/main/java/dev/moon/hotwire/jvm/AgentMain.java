package dev.moon.hotwire.jvm;

import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

public final class AgentMain {
    private static volatile @Nullable Instrumentation instrumentation;
    private static volatile @Nullable Function<String, @Nullable URL> resources;
    private static volatile @Nullable Predicate<String> absent;

    private AgentMain() {
    }

    public static void agentmain(String options, Instrumentation instrumentation) {
        AgentMain.instrumentation = instrumentation;
    }

    public static @Nullable Instrumentation getInstrumentation() {
        return instrumentation;
    }

    public static void setResources(@Nullable Function<String, @Nullable URL> finder,
            @Nullable Predicate<String> nowhere) {
        resources = finder;
        absent = nowhere;
    }

    public static @Nullable URL findResource(String name) {
        Function<String, @Nullable URL> finder = resources;
        return finder == null ? null : finder.apply(name);
    }

    public static boolean isAbsent(String name) {
        Predicate<String> nowhere = absent;
        return nowhere != null && nowhere.test(name);
    }
}
