package dev.moon.hotwire.redefine;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class RebuildHooks {
    public interface AfterMixin {
        byte[] apply(String internal, byte[] mixed, Predicate<String> on);
    }

    private volatile AfterMixin afterMixin = (internal, mixed, on) -> mixed;

    private volatile Predicate<Class<?>> handlersReplayed = type -> true;

    private volatile Consumer<Map<String, byte[]>> generated = classes -> {
    };

    public void setAfterMixin(AfterMixin after) {
        this.afterMixin = after;
    }

    public void sendGeneratedTo(Consumer<Map<String, byte[]>> to) {
        this.generated = to;
    }

    public void replayHandlersWhere(Predicate<Class<?>> where) {
        this.handlersReplayed = where;
    }

    byte[] applyAfterMixin(String internal, byte[] mixed, Predicate<String> on) {
        return this.afterMixin.apply(internal, mixed, on);
    }

    public void onGenerated(Map<String, byte[]> classes) {
        this.generated.accept(classes);
    }

    boolean hasReplayedHandlers(Class<?> type) {
        return this.handlersReplayed.test(type);
    }
}
