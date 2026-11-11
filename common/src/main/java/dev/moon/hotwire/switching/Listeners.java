package dev.moon.hotwire.switching;

import dev.moon.hotwire.api.Registration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Listeners<T> {
    private static final Logger LOGGER = LoggerFactory.getLogger(Listeners.class);
    private final List<Entry<T>> entries = new CopyOnWriteArrayList<>();

    private static final class Entry<T> {
        private final T listener;
        private final @Nullable String mod;

        Entry(T listener, @Nullable String mod) {
            this.listener = listener;
            this.mod = mod;
        }
    }

    public Registration add(T listener, @Nullable String mod) {
        Entry<T> entry = new Entry<>(listener, mod);
        this.entries.add(entry);
        return () -> this.entries.remove(entry);
    }

    public boolean isEmpty() {
        return this.entries.isEmpty();
    }

    public boolean isListening(@Nullable String mod) {
        return mod != null && this.entries.stream().anyMatch(entry -> mod.equals(entry.mod));
    }

    public void tell(String what, Predicate<String> on, Consumer<T> call) {
        for (Entry<T> entry : this.entries) {
            if (entry.mod != null && !on.test(entry.mod)) {
                continue;
            }
            try {
                call.accept(entry.listener);
            } catch (RuntimeException | LinkageError e) {
                LOGGER.warn("[Hotwire] {}'s listener threw in {}", entry.mod == null ? "a" : entry.mod, what, e);
            }
        }
    }
}
