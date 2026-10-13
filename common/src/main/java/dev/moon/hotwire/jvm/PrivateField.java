package dev.moon.hotwire.jvm;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;

public final class PrivateField {
    private final VarHandle handle;
    private final String name;

    private PrivateField(VarHandle handle, String name) {
        this.handle = handle;
        this.name = name;
    }

    public static PrivateField of(Class<?> owner, String field) {
        String name = owner.getName() + "." + field;
        try {
            Field declared = owner.getDeclaredField(field);
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(owner, MethodHandles.lookup());
            return new PrivateField(lookup.unreflectVarHandle(declared), name);
        } catch (NoSuchFieldException | IllegalAccessException | SecurityException e) {
            throw new IllegalStateException("[Hotwire] cannot reach " + name + ": " + e, e);
        }
    }

    public <T> T get(Object owner, Class<T> type) {
        Object value = this.handle.get(owner);
        if (!type.isInstance(value)) {
            throw new IllegalStateException("[Hotwire] " + this.name + " holds " + value + ", not a " + type.getName());
        }
        return type.cast(value);
    }

    public String getName() {
        return this.name;
    }
}
