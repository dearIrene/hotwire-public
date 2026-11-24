package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.version.Spellings;
import dev.moon.hotwire.ui.Lang;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class UnsupportedNotice {
    private static final Logger LOGGER = LoggerFactory.getLogger(UnsupportedNotice.class);

    private UnsupportedNotice() {
    }

    private static String findBridge() {
        return FabricLoader.getInstance().isModLoaded("connector") ? "Sinytra Connector" : "a loader bridge";
    }

    static void tellAtTitle() {
        String bridge = findBridge();
        LOGGER.warn("[Hotwire] Hotwire stays off: it only works on Fabric Loader itself, and this game loads Fabric"
                + " mods through {}", bridge);
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> Spellings.addSystemToast(client,
                Component.literal(Lang.get("hotwire.unsupported.title")),
                Component.literal(Lang.get("hotwire.unsupported.body", bridge))));
    }
}
