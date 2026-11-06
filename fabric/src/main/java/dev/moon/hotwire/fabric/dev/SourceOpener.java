package dev.moon.hotwire.fabric.dev;

import dev.moon.hotwire.dev.IdeOpener;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.Notice;
import dev.moon.hotwire.ui.Notices;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SourceOpener {
    private static final Logger LOGGER = LoggerFactory.getLogger(SourceOpener.class);

    private SourceOpener() {
    }

    static void open(DevMod mod, String type, int line, String label) {
        List<Path> projects = new ArrayList<>();
        for (Path folder : mod.folders()) {
            Path project = DevMod.findProject(folder);
            if (project != null && !projects.contains(project)) {
                projects.add(project);
            }
        }
        Path file;
        try {
            file = IdeOpener.findSource(projects, type);
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
            Minecraft game = Minecraft.getInstance();
            game.execute(() -> game.keyboardHandler.setClipboard(place));
            Notices.show(Notice.ofOn(Lang.get("hotwire.dev.copied"), place));
        }
    }
}
