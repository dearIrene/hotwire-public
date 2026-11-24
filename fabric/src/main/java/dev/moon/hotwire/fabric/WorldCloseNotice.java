package dev.moon.hotwire.fabric;

import dev.moon.hotwire.engine.HotwireEngine;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.notices.Notice;
import dev.moon.hotwire.ui.notices.Notices;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WorldCloseNotice {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldCloseNotice.class);
    private static final List<String> ERROR_WORDS = List.of("exception", "error", "failed", "could not",
            "couldn't", "invalid", "unknown custom packet", "decode", "encode");

    private static final Set<String> TOLD = ConcurrentHashMap.newKeySet();

    private WorldCloseNotice() {
    }

    static boolean isError(String reason) {
        String lower = reason.toLowerCase(Locale.ROOT);
        return ERROR_WORDS.stream().anyMatch(lower::contains);
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    public static void onDisconnect(String reason) {
        if (!isError(reason) || !TOLD.add(reason)) {
            return;
        }
        HotwireEngine engine = HotwireClient.findEngine();
        LOGGER.warn("[Hotwire] the world closed with an error: {}", reason);
        if (engine == null) {
            return;
        }
        engine.getTasks().submit("report of a world that closed with an error",
                () -> engine.writeReport("world closed", "The world closed with an error: " + reason),
                where -> Notices.show(Notice.ofBad(Lang.get("hotwire.world_closed.title"),
                        Lang.get("hotwire.world_closed.detail", reason))));
    }
}
