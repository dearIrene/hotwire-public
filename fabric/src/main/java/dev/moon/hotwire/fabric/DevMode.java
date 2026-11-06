package dev.moon.hotwire.fabric;

import dev.moon.hotwire.concurrent.Tasks;
import dev.moon.hotwire.config.DevSetup;
import dev.moon.hotwire.config.HotwireSettings;
import dev.moon.hotwire.fabric.dev.DevSession;
import dev.moon.hotwire.fabric.screen.DevGameScreen;
import dev.moon.hotwire.fabric.screen.WebLinks;
import dev.moon.hotwire.ui.DevWindow;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.Notice;
import dev.moon.hotwire.ui.Notices;
import java.io.IOException;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class DevMode implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(DevMode.class);
    private final @Nullable DevSession session;
    private final HotwireSettings settings;
    private @Nullable DevWindow window;

    DevMode(HotwireSettings settings, Tasks tasks, DevSession.Engine engine) {
        this.settings = settings;
        DevSetup setup = DevSetups.get();
        DevSession.useFullRedefinition(setup.isFull());
        if (setup.inSetup()) {
            LOGGER.info("[Hotwire] developer mode on {} {}: {}", System.getProperty("java.vendor"),
                    System.getProperty("java.version"), setup.jvm());
        }
        this.session = settings.isDevReloadOn(setup.inSetup())
                ? DevSession.start(DevSetups.getBuildFolder(), tasks, engine) : null;
    }

    void openWindow() {
        DevSession running = this.session;
        if (running == null) {
            Notices.show(Notice.ofAsk(Lang.get("hotwire.dev.window.title"), Lang.get("hotwire.dev.window.off")));
            return;
        }
        DevWindow shown = this.window;
        if (shown == null) {
            DevSetup setup = DevSetups.get();
            shown = new DevWindow(running, Lang.get(switch (setup.jvm()) {
                case FULL -> "hotwire.settings.developer.full";
                case JETBRAINS_WITHOUT_FLAG -> "hotwire.settings.developer.no_flag";
                case OTHER -> "hotwire.settings.developer.other_java";
            }), setup.isFull(), this.settings.getDevWindow(), this.settings::setDevWindow,
                    this.settings.getDevScale(), this.settings::setDevScale, () -> ModViews.show(null));
            shown.setMcp(FabricLoader.getInstance().isModLoaded("hotwire-mcp"),
                    () -> WebLinks.open("https://github.com/moonshee/hotwire/releases"));
            this.window = shown;
        }
        ModViews.show(new DevGameScreen(shown));
    }

    @Override
    public void close() {
        if (this.session != null) {
            try {
                this.session.close();
            } catch (IOException e) {
                LOGGER.debug("[Hotwire] the build folders' watch did not close", e);
            }
        }
    }
}
