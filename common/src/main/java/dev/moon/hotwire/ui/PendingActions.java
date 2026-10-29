package dev.moon.hotwire.ui;

import dev.moon.hotwire.update.PendingChange;
import java.io.IOException;
import java.util.List;
import java.util.function.BiConsumer;

final class PendingActions {
    private final ModScreen.Game game;
    private final BiConsumer<String, Boolean> say;

    PendingActions(ModScreen.Game game, BiConsumer<String, Boolean> say) {
        this.game = game;
        this.say = say;
    }

    List<PendingChange> act(PendingChange change) {
        try {
            switch (change) {
                case PendingChange.Update update -> {
                    this.game.cancelPending(update.modId());
                    this.say.accept(Lang.get("hotwire.update.cancelled", update.name(), update.fromVersion(),
                            update.newJar()), false);
                }
                case PendingChange.Copied copied -> {
                    this.game.removeCopy(copied.modId());
                    this.say.accept(copied.name() + " runs until you close the game, and the next launch is"
                            + " without it:"
                            + " " + copied.jar() + " was taken out of your mods folder.", false);
                }
                case PendingChange.Revertable back -> {
                    this.game.revertAtNextStart(back.modId());
                    this.say.accept(back.name() + " goes back to " + back.oldVersion() + " when you close the game."
                            + " Cancel it any time from Pending tasks.", false);
                }
            }
        } catch (IOException e) {
            this.say.accept(Lang.get("hotwire.failed", e.getMessage()), true);
        }
        return this.game.getPendingChanges();
    }

    List<PendingChange> cleanUp(String modId) {
        try {
            this.game.cleanUpEarlier(modId);
            this.say.accept(Lang.get("hotwire.pending.cleaned"), false);
        } catch (IOException e) {
            this.say.accept(Lang.get("hotwire.failed", e.getMessage()), true);
        }
        return this.game.getPendingChanges();
    }
}
