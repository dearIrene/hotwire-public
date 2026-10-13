package dev.moon.hotwire.jvm;

import java.lang.instrument.Instrumentation;
import org.jspecify.annotations.Nullable;

public final class AgentMain {
    private static volatile @Nullable Instrumentation instrumentation;

    private AgentMain() {
    }

    public static void agentmain(String options, Instrumentation instrumentation) {
        AgentMain.instrumentation = instrumentation;
    }

    public static @Nullable Instrumentation getInstrumentation() {
        return instrumentation;
    }
}
