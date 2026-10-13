package dev.moon.hotwire.fabric.add;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class AddUndo {
    private static final Logger LOGGER = LoggerFactory.getLogger(AddUndo.class);

    private final List<Runnable> undoable = new ArrayList<>();
    private final List<String> permanent = new ArrayList<>();

    void addUndo(Runnable undo) {
        this.undoable.add(undo);
    }

    void addPermanent(String what) {
        this.permanent.add(what);
    }

    String run() {
        for (int i = this.undoable.size() - 1; i >= 0; i--) {
            try {
                this.undoable.get(i).run();
            } catch (RuntimeException e) {
                LOGGER.warn("[Hotwire] a step of the failed add could not be undone", e);
            }
        }
        this.undoable.clear();
        return this.permanent.isEmpty() ? "" : " Until the game restarts: " + String.join("; ", this.permanent) + ".";
    }
}
