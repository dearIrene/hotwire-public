package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.registration.Registration;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.jspecify.annotations.Nullable;

final class ArchitecturyEventRegistration implements Registration {
    private static final String EVENT = "dev.architectury.event.Event";
    private static final String PRIORITY = "dev.architectury.event.EventPriority";

    private final Object event;
    private final @Nullable Object priority;
    private final Object listener;

    ArchitecturyEventRegistration(Object event, @Nullable Object priority, Object listener) {
        this.event = event;
        this.priority = priority;
        this.listener = listener;
    }

    @SuppressWarnings("ReferenceEquality")
    boolean listensTo(Object event) {
        return this.event == event;
    }

    void call(Object... arguments) {
        for (Class<?> face = this.listener.getClass(); face != null; face = face.getSuperclass()) {
            for (Class<?> declared : face.getInterfaces()) {
                Method method = findAbstractMethodOf(declared, arguments.length);
                if (method != null) {
                    try {
                        method.invoke(this.listener, arguments);
                        return;
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException("the listener's " + method.getName() + " is not public", e);
                    } catch (InvocationTargetException e) {
                        throw new IllegalStateException("the listener threw", e.getCause());
                    }
                }
            }
        }
        throw new IllegalStateException("the listener " + this.describe() + " has no method for its event");
    }

    private static @Nullable Method findAbstractMethodOf(Class<?> face, int parameters) {
        for (Method method : face.getMethods()) {
            if (Modifier.isAbstract(method.getModifiers()) && method.getParameterCount() == parameters) {
                return method;
            }
        }
        return null;
    }

    static @Nullable Object findLifecycleEvent(String name) {
        try {
            return Class.forName("dev.architectury.event.events.common.LifecycleEvent", true,
                    ArchitecturyEventRegistration.class.getClassLoader()).getField(name).get(null);
        } catch (ClassNotFoundException | NoSuchFieldException absent) {
            return null;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Architectury's LifecycleEvent." + name + " is not public", e);
        }
    }

    @Override
    public String getKind() {
        return "architectury event";
    }

    @Override
    public String describe() {
        Class<?>[] faces = this.listener.getClass().getInterfaces();
        return faces.length == 0 ? this.listener.getClass().getName() : faces[0].getName();
    }

    @Override
    public void remove() {
        this.call("unregister", new Class<?>[] {Object.class}, this.listener);
    }

    @Override
    public boolean isInGame() {
        try {
            Object registered = this.loadApi(EVENT).getMethod("isRegistered", Object.class).invoke(this.event,
                    this.listener);
            return !Boolean.FALSE.equals(registered);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }

    @Override
    public void restore() {
        if (this.priority == null) {
            this.call("register", new Class<?>[] {Object.class}, this.listener);
        } else {
            this.call("register", new Class<?>[] {this.loadApi(PRIORITY), Object.class}, this.priority, this.listener);
        }
    }

    private Class<?> loadApi(String name) {
        try {
            return Class.forName(name, false, this.event.getClass().getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Architectury has no " + name, e);
        }
    }

    private void call(String name, Class<?>[] parameters, Object... arguments) {
        try {
            Method method = this.loadApi(EVENT).getMethod(name, parameters);
            method.invoke(this.event, arguments);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException("Architectury's Event has no public " + name, e);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("Architectury's " + name + " threw", e.getCause());
        }
    }
}
