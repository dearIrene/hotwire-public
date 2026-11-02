package dev.moon.hotwire.fabric.registration;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

record HandlerMap(WeakReference<Object> map) implements Consumer<String> {
    HandlerMap(Object map) {
        this(new WeakReference<>(map));
    }

    @Override
    public void accept(String mod) {
    }

    static void readAll(CollectionNotes notes, List<String> mods, Consumer<Object> read) {
        for (Runnable take : notes.findTakesFor(mods, false, new ArrayList<>())) {
            ElementLook.stopIfAsked();
            if (take instanceof ElementTakes.NotedTake filed && filed.take() instanceof HandlerMap handlers) {
                Object map = handlers.map().get();
                if (map != null) {
                    read.accept(map);
                }
            }
        }
    }
}
