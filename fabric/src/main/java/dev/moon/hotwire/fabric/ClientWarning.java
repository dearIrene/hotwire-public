package dev.moon.hotwire.fabric;

import dev.moon.hotwire.ui.ModifiedClients;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ClientWarning {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientWarning.class);

    private ClientWarning() {
    }

    static void register() {
        FabricLoader loader = FabricLoader.getInstance();
        String paths = loader.getGameDir().toAbsolutePath() + "/" + System.getProperty("java.class.path", "") + "/";
        String flat = paths.replace(';', '/').replace(':', '/');
        String client = ModifiedClients.isDawn(loader::isModLoaded, flat,
                file -> ClientWarning.class.getClassLoader().getResource(file) != null) ? ModifiedClients.DAWN
                : ModifiedClients.find(loader::isModLoaded, flat);
        if (client == null) {
            return;
        }
        LOGGER.warn("[Hotwire] running in a modified client: {}", client);
        ModifiedClients.setCurrent(client);
        ClientLifecycleEvents.CLIENT_STARTED.register(game -> game.execute(() -> openWhenReady(game)));
    }

    private static void openWhenReady(Minecraft game) {
        if (ModViews.openOverlay() != null || ModViews.openScreen() == null) {
            game.execute(() -> openWhenReady(game));
            return;
        }
        HotwireClient.getEngine().open();
    }
}
