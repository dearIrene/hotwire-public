package dev.moon.hotwire.fabric.registration;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

record HandlerMap(WeakReference<Object> map) implements Consumer<String> {
    private static final Logger LOGGER = LoggerFactory.getLogger(HandlerMap.class);

    HandlerMap(Object map) {
        this(new WeakReference<>(map));
    }

    @Override
    public void accept(String mod) {
    }

    static void readAll(CollectionNotes notes, List<String> mods, Consumer<Object> read) {
        long began = System.nanoTime();
        List<Runnable> takes = notes.findTakesFor(mods, false, new ArrayList<>());
        long found = System.nanoTime();
        int maps = 0;
        for (Runnable take : takes) {
            ElementLook.stopIfAsked();
            if (take instanceof ElementTakes.NotedTake filed && filed.take() instanceof HandlerMap handlers) {
                Object map = handlers.map().get();
                if (map != null) {
                    maps++;
                    read.accept(map);
                }
            }
        }
        LOGGER.info("[Hotwire] {} maps of handlers under {} read again in {} ms, found among {} takes in {} ms", maps,
                mods, (System.nanoTime() - found) / 1_000_000, takes.size(), (found - began) / 1_000_000);
    }
}
