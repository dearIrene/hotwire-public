package dev.moon.hotwire.dev;

import dev.moon.hotwire.seam.Games;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.notices.Notice;
import dev.moon.hotwire.ui.notices.Notices;
import java.io.IOException;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SourceOpener {
    private static final Logger LOGGER = LoggerFactory.getLogger(SourceOpener.class);

    private SourceOpener() {
    }

    static void open(DevMod mod, String type, int line, String label) {
        Path file;
        try {
            file = IdeOpener.findSource(mod.findProjects(), type);
        } catch (IOException e) {
            LOGGER.debug("[Hotwire] the project could not be searched for {}", type, e);
            file = null;
        }
        if (file == null) {
            Notices.show(Notice.ofBad(Lang.get("hotwire.dev.no_source", label), ""));
        } else if (IdeOpener.open(file, line)) {
            Notices.show(Notice.ofDone(Lang.get("hotwire.dev.opened", label), ""));
        } else {
            String place = file + ":" + line;
            Games.getNow().copyToClipboard(place);
            Notices.show(Notice.ofOn(Lang.get("hotwire.dev.copied"), place));
        }
    }
}
