package dev.moon.hotwire.fabric.screen;

//? if >=26.3 {
import com.mojang.blaze3d.Blaze3D;
//?} else if >=1.21.11 {
/*import net.minecraft.util.Util;
*///?} else {
/*import net.minecraft.Util;
*///?}
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
        if (System.getProperty("hotwire.oneclient") != null) {
            return;
        }
        //? if >=26.3 {
        Blaze3D.openUri(uri);
        //?} else {
        /*Util.getPlatform().openUri(uri);
        *///?}
    }

    public static void openFolder(Path folder) {
        if (System.getProperty("hotwire.oneclient") != null) {
            return;
        }
        //? if >=26.3 {
        Blaze3D.openPath(folder);
        //?} else {
        /*Util.getPlatform().openPath(folder);
        *///?}
    }
}
