package dev.moon.hotwire.fabric;

import dev.moon.hotwire.ui.ModifiedClients;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ClientWarning {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientWarning.class);
    private static boolean opened;

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
        ClientTickEvents.END_CLIENT_TICK.register(ClientWarning::openWhenReady);
    }

    private static void openWhenReady(Minecraft game) {
        if (opened || ModViews.openOverlay() != null || ModViews.openScreen() == null) {
            return;
        }
        opened = true;
        HotwireClient.getEngine().open();
    }
}
