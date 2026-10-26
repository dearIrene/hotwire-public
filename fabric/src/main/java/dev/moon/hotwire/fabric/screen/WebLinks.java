package dev.moon.hotwire.fabric.screen;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
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
        if (System.getProperty("hotwire.oneclient") != null) {
            return;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String address = uri.toASCIIString();
        List<String> command = os.contains("win") ? List.of("rundll32", "url.dll,FileProtocolHandler", address)
                : os.contains("mac") ? List.of("open", address) : List.of("xdg-open", address);
        try {
            new ProcessBuilder(command).start();
        } catch (IOException e) {
            LOGGER.info("[Hotwire] the link could not be opened: {}", e.toString());
        }
    }
}
