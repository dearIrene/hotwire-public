package dev.moon.hotwire.registration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;

public final class SequenceOrder {
    private SequenceOrder() {
    }

    @SuppressWarnings("ReferenceEquality")
    public static <T> List<T> insert(List<T> items, T item, ToLongFunction<T> sequenceOf) {
        long sequence = sequenceOf.applyAsLong(item);
        List<T> out = new ArrayList<>(items.size() + 1);
        boolean placed = false;
        for (T existing : items) {
            if (existing == item) {
                return List.copyOf(items);
            }
            if (!placed && sequenceOf.applyAsLong(existing) > sequence) {
                out.add(item);
                placed = true;
            }
            out.add(existing);
        }
        if (!placed) {
            out.add(item);
        }
        return out;
    }
}
