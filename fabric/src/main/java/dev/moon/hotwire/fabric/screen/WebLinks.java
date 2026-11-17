package dev.moon.hotwire.fabric.screen;

import dev.moon.hotwire.config.Flags;
import dev.moon.hotwire.fabric.version.Dialogs;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WebLinks {
    private static final Logger LOGGER = LoggerFactory.getLogger(WebLinks.class);

    private WebLinks() {
    }

    public static void open(String link) {
        URI uri;
        try {
            uri = new URI(link.strip());
        } catch (URISyntaxException e) {
            LOGGER.info("[Hotwire] not a link: {}", link);
            return;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            LOGGER.info("[Hotwire] only web links open: {}", link);
            return;
        }
        LOGGER.info("[Hotwire] opening {}", uri.toASCIIString());
        if (Flags.ONECLIENT) {
            return;
        }
        Dialogs.openUri(uri);
    }

    public static void openFolder(Path folder) {
        if (Flags.ONECLIENT) {
            return;
        }
        Dialogs.openPath(folder);
    }
}
