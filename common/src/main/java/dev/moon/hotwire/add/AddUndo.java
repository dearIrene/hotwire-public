package dev.moon.hotwire.add;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AddUndo {
    private static final Logger LOGGER = LoggerFactory.getLogger(AddUndo.class);

    private final List<Runnable> undoable = new ArrayList<>();
    private final List<String> permanent = new ArrayList<>();

    public void addUndo(Runnable undo) {
        this.undoable.add(undo);
    }

    public void addPermanent(String what) {
        this.permanent.add(what);
    }

    public String run() {
        for (int i = this.undoable.size() - 1; i >= 0; i--) {
            try {
                this.undoable.get(i).run();
            } catch (RuntimeException e) {
                LOGGER.warn("[Hotwire] a step of the failed add could not be undone", e);
            }
        }
        this.undoable.clear();

        if (!this.permanent.isEmpty()) {
            LOGGER.info("[Hotwire] until the game restarts: {}", String.join("; ", this.permanent));
        }
        return "";
    }
}
