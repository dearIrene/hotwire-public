package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.registration.Registration;
import dev.moon.hotwire.registration.SequenceOrder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class ReloaderRegistration<K, V> implements Registration {
    private final Map<K, V> reloaders;
    private final K id;
    private final V reloader;

    ReloaderRegistration(Map<K, V> reloaders, K id, V reloader) {
        this.reloaders = reloaders;
        this.id = id;
        this.reloader = reloader;
    }

    @Override
    public String getKind() {
        return "resource reloader";
    }

    @Override
    public String describe() {
        return this.id.toString();
    }

    @Override
    public void remove() {
        this.reloaders.remove(this.id, this.reloader);
    }

    @Override
    @SuppressWarnings("ReferenceEquality")
    public void restore() {
        V current = this.reloaders.get(this.id);
        if (current == this.reloader) {
            return;
        }
        if (current != null) {
            throw new IllegalStateException("Another reloader took the id " + this.id + " while it was off.");
        }
        List<K> order = SequenceOrder.insert(List.copyOf(this.reloaders.keySet()), this.id,
                RecorderSupport::getSequenceOf);
        Map<K, V> before = new LinkedHashMap<>(this.reloaders);
        this.reloaders.clear();
        for (K key : order) {
            this.reloaders.put(key, key == this.id ? this.reloader : Objects.requireNonNull(before.get(key)));
        }
    }
}
