package dev.moon.hotwire.registration;

import dev.moon.hotwire.update.LiveNames;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import org.jspecify.annotations.Nullable;

final class OneConfigEventRegistration implements Registration {
    private static final String HANDLER = "org.polyfrost.oneconfig.api.event.v1.invoke.EventHandler";
    private static final String INITIALIZATION = "org.polyfrost.oneconfig.api.event.v1.events.InitializationEvent";

    private final Object bus;
    private final Object handler;

    private int copies = 1;

    OneConfigEventRegistration(Object bus, Object handler) {
        this.bus = bus;
        this.handler = handler;
    }

    @Override
    public String getKind() {
        return "OneConfig event";
    }

    @Override
    public String describe() {
        return this.handler.getClass().getName();
    }

    @Override
    public void remove() {
        int removed = 0;
        while (Boolean.TRUE.equals(this.call("unregister")) && removed < 64) {
            removed++;
        }
        this.copies = Math.max(1, removed);
    }

    @Override
    @SuppressWarnings("ReferenceEquality")
    public boolean isInGame() {
        try {
            Field field = this.bus.getClass().getDeclaredField("handlers");
            field.setAccessible(true);
            return ((Map<?, ?>) field.get(this.bus)).values().stream()
                    .anyMatch(listed -> listed instanceof Collection<?> handlers
                            && handlers.stream().anyMatch(one -> one == this.handler));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }

    @Override
    public void restore() {
        for (int put = 0; put < this.copies; put++) {
            this.call("register");
        }
    }

    @Nullable Integer findInitializationPriority() throws ReflectiveOperationException {
        Class<?> base = this.findHandlerType();
        if (base == null || this.findInitialization(base) == null) {
            return null;
        }
        try {
            return base.getMethod("getPriority").invoke(this.handler) instanceof Integer priority ? priority : 0;
        } catch (NoSuchMethodException e) {
            return 0;
        }
    }

    void runInitialization() throws ReflectiveOperationException {
        Class<?> base = this.findHandlerType();
        Class<?> event = base == null ? null : this.findInitialization(base);
        if (base == null || event == null) {
            return;
        }
        Object instance = event.getField("INSTANCE").get(null);
        for (Method method : base.getMethods()) {
            if (method.getName().equals("handle") && method.getParameterCount() == 1) {
                if (Boolean.TRUE.equals(method.invoke(this.handler, instance))) {
                    this.call("unregister");
                }
                return;
            }
        }
    }

    private @Nullable Class<?> findHandlerType() {
        Class<?> base = this.handler.getClass();
        while (base != null && !HANDLER.equals(LiveNames.findOwnName(base.getName()))) {
            base = base.getSuperclass();
        }
        return base;
    }

    private @Nullable Class<?> findInitialization(Class<?> base) throws ReflectiveOperationException {
        return base.getMethod("getEventClass").invoke(this.handler) instanceof Class<?> event
                && INITIALIZATION.equals(LiveNames.findOwnName(event.getName())) ? event : null;
    }

    private @Nullable Object call(String name) {
        try {
            Class<?> handlerType = LiveNames.findBeside(HANDLER, this.bus.getClass());
            Method method = this.bus.getClass().getMethod(name, handlerType);
            return method.invoke(this.bus, this.handler);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException("OneConfig's event bus has no public " + name, e);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("OneConfig's " + name + " threw", e.getCause());
        }
    }
}
