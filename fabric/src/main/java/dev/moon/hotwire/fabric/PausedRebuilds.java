package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.registration.GameHold;
import dev.moon.hotwire.switching.GameObjectRebuilder;
import dev.moon.hotwire.switching.SwitchTimes;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class PausedRebuilds implements GameObjectRebuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger(PausedRebuilds.class);

    @Override
    public void rebuild(Set<String> newlyBuilt, Set<String> rebuilt, boolean reload) {
        ResourceReload.rebuildRenderers(newlyBuilt, rebuilt, reload);
    }

    @Override
    public void announce(Set<String> newlyBuilt, boolean reload) {
        String words = ResourceReload.describePaused(newlyBuilt, reload);
        if (words == null) {
            return;
        }
        SwitchTimes.tellWords(words);
        LOGGER.info("[Hotwire] said before the game stands still: {}", words);

        GameHold.onGameAndWait(() -> { });
        GameHold.onGameAndWait(() -> { });
    }
}
