package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.fabric.mixin.core.fabric.base.event.ArrayBackedEventAccessor;
import dev.moon.hotwire.fabric.mixin.core.fabric.base.event.EventPhaseDataAccessor;
import dev.moon.hotwire.redefine.Tripwire;
import dev.moon.hotwire.registration.Registration;
import dev.moon.hotwire.registration.SequenceOrder;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;

final class EventRegistration implements Registration {
    private final WeakReference<ArrayBackedEventAccessor> event;
    private final WeakReference<EventPhaseDataAccessor> phase;
    private final WeakReference<Object> listener;
    private final String type;

    private @Nullable Object removed;

    EventRegistration(Object event, Object phase, Object listener) {
        this.event = new WeakReference<>((ArrayBackedEventAccessor) event);
        this.phase = new WeakReference<>((EventPhaseDataAccessor) phase);
        this.listener = new WeakReference<>(listener);
        this.type = listener.getClass().getName();
    }

    @Override
    public String getKind() {
        return "event";
    }

    @SuppressWarnings("ReferenceEquality")
    boolean listensTo(Object event) {
        return this.event.get() == event;
    }

    boolean isOutOfEvent() {
        return this.removed != null;
    }

    @Nullable Object findListener() {
        Object removed = this.removed;
        return removed != null ? removed : this.listener.get();
    }

    @Override
    public String describe() {
        return this.type;
    }

    @Override
    public boolean isInGame() {
        return this.removed != null || (this.event.get() != null && this.listener.get() != null);
    }

    @Override
    @SuppressWarnings("ReferenceEquality")
    public void remove() {
        ArrayBackedEventAccessor event = this.event.get();
        EventPhaseDataAccessor phase = this.phase.get();
        Object listener = this.listener.get();
        if (event == null || phase == null || listener == null) {
            return;
        }
        synchronized (event.hotwire$getLock()) {
            Object[] listeners = phase.hotwire$getListeners();
            List<Object> retained = new ArrayList<>();
            for (Object existing : listeners) {
                if (existing != listener) {
                    retained.add(existing);
                }
            }
            if (retained.size() == listeners.length) {
                return;
            }
            phase.hotwire$setListeners(retained.toArray(Arrays.copyOf(listeners, 0)));
            Tripwire.runAsHotwire(() -> event.hotwire$rebuildInvoker(countTotal(event)));
            this.removed = listener;
        }
    }

    @Override
    public void restore() {
        Object listener = this.removed;
        ArrayBackedEventAccessor event = this.event.get();
        EventPhaseDataAccessor phase = this.phase.get();
        this.removed = null;
        if (listener == null || event == null || phase == null) {
            return;
        }
        synchronized (event.hotwire$getLock()) {
            Object[] listeners = phase.hotwire$getListeners();
            List<Object> placed = SequenceOrder.insert(List.of(listeners), listener, RecorderSupport::getSequenceOf);
            if (placed.size() == listeners.length) {
                return;
            }
            phase.hotwire$setListeners(placed.toArray(Arrays.copyOf(listeners, 0)));
            Tripwire.runAsHotwire(() -> event.hotwire$rebuildInvoker(countTotal(event)));
        }
    }

    static int countTotal(ArrayBackedEventAccessor event) {
        int total = 0;
        for (Object phase : event.hotwire$getSortedPhases()) {
            total += ((EventPhaseDataAccessor) phase).hotwire$getListeners().length;
        }
        return total;
    }
}
