package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.registration.Registration;
import dev.moon.hotwire.registration.SequenceOrder;
import java.util.List;
import java.util.Set;

final class ReloadListenerRegistration implements Registration {
    private final Set<Object> factories;
    private final Object factory;

    ReloadListenerRegistration(Set<Object> factories, Object factory) {
        this.factories = factories;
        this.factory = factory;
    }

    @Override
    public String getKind() {
        return "resource reloader";
    }

    @Override
    public String describe() {
        return this.factory.toString();
    }

    @Override
    public void remove() {
        this.factories.remove(this.factory);
    }

    @Override
    public void restore() {
        if (this.factories.contains(this.factory)) {
            return;
        }
        List<Object> order = SequenceOrder.insert(List.copyOf(this.factories), this.factory,
                RecorderSupport::getSequenceOf);
        this.factories.clear();
        this.factories.addAll(order);
    }
}
