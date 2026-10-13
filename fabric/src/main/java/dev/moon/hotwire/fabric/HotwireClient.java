package dev.moon.hotwire.fabric;

import java.io.IOException;
import java.io.UncheckedIOException;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HotwireClient implements ClientModInitializer {
    public static final String ID = "hotwire";
    private static final Logger LOGGER = LoggerFactory.getLogger(HotwireClient.class);

    private static @Nullable HotwireEngine engine;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[Hotwire] {} loaded; H or /hotwire opens it, and its Report button writes what went wrong to"
                + " hotwire/reports", FabricLoader.getInstance().getModContainer(ID)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("unknown"));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> HotwireCommands.register(dispatcher,
                HotwireClient::openSoon));

        OpenKey.register(HotwireClient::openSoon);

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> client.execute(HotwireClient::getEngine));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            HotwireEngine started = engine;
            if (started != null) {
                started.close();
            }
        });
    }

    private static void openSoon() {
        Minecraft client = Minecraft.getInstance();
        //? if >=1.21.2 {
        client.schedule(HotwireClient::open);
        //?} else {
        /*client.tell(HotwireClient::open);
        *///?}
    }

    private static void open() {
        try {
            getEngine().open();
        } catch (UncheckedIOException e) {
            LOGGER.error("[Hotwire] The mods folder could not be read", e);
        }
    }

    public static synchronized HotwireEngine getEngine() {
        HotwireEngine started = engine;
        if (started == null) {
            try {
                started = HotwireEngine.start();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            engine = started;
        }
        return started;
    }
}
