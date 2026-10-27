package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.Tripwire;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OffEntries {
    private static final Logger LOGGER = LoggerFactory.getLogger(OffEntries.class);
    private static final Set<String> SAID = ConcurrentHashMap.newKeySet();

    private OffEntries() {
    }

    public static boolean isLeftOut(String mod, Object key, RuntimeException failure) {
        if (!Tripwire.isWatched(mod)) {
            return false;
        }
        if (SAID.add(mod)) {
            LOGGER.info("[Hotwire] {} is off: components of its entries that need its data are left out (first {}: {})",
                    mod, key, failure.toString());
        }
        return true;
    }
}
