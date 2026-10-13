package dev.moon.hotwire.mixin_copy.copy;

import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.service.IGlobalPropertyService;
import org.spongepowered.asm.service.IPropertyKey;

public final class CopyPropertyService implements IGlobalPropertyService {
    private final Map<String, Object> values = new HashMap<>();

    private record Key(String name) implements IPropertyKey {
    }

    @Override
    public IPropertyKey resolveKey(String name) {
        return new Key(name);
    }

    @Override
    @SuppressWarnings({"unchecked", "TypeParameterUnusedInFormals"})
    public <T> @Nullable T getProperty(IPropertyKey key) {
        return (T) this.values.get(((Key) key).name());
    }

    @Override
    public void setProperty(IPropertyKey key, Object value) {
        this.values.put(((Key) key).name(), value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getProperty(IPropertyKey key, T defaultValue) {
        return (T) this.values.getOrDefault(((Key) key).name(), defaultValue);
    }

    @Override
    public String getPropertyString(IPropertyKey key, String defaultValue) {
        Object value = this.values.get(((Key) key).name());
        return value != null ? value.toString() : defaultValue;
    }
}
