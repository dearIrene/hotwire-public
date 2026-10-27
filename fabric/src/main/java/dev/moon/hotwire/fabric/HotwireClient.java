package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.report.HotwireLog;
import dev.moon.hotwire.redefine.FieldHolders;
import dev.moon.hotwire.redefine.InitHookReplay;
import dev.moon.hotwire.report.GameLog;
import dev.moon.hotwire.ui.Lang;
import java.io.IOException;
import java.io.UncheckedIOException;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HotwireClient implements ClientModInitializer {
    public static final String ID = "hotwire";
    private static final Logger LOGGER = LoggerFactory.getLogger(HotwireClient.class);

    private static @Nullable HotwireEngine engine;

    @Override
    public void onInitializeClient() {
        HotwireLog.install(FabricLoader.getInstance().getGameDir().resolve("hotwire").resolve("logs"));
        GameLog.LOGGER.info("[Hotwire] {} loaded; H or /hotwire opens it, and its Report button writes what went wrong"
                + " to hotwire/reports", LoadedMods.findVersion(ID, "unknown"));

        Lang.useTranslator((key, values) -> {
            Language language = Language.getInstance();
            return language.has(key) ? Lang.fill(language.getOrDefault(key), values) : null;
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> HotwireCommands.register(dispatcher,
                HotwireClient::openSoon));

        OpenKey.register(HotwireClient::openSoonFromKey);
        OffTooltips.register();

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> client.execute(HotwireClient::getEngine));

        InitHookReplay.catchUpLaterWhile(() -> Minecraft.getInstance().level == null);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
            FieldHolders.retryCatchUps();
            HotwireEngine started = engine;
            if (started != null) {
                started.lookAheadInWorldSoon();
            }
        }));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            HotwireEngine started = engine;
            if (started != null) {
                started.close();
            }
        });
    }

    private static void openSoon() {
        ModViews.runSoon(HotwireClient::open);
    }

    private static void open() {
        try {
            getEngine().open();
        } catch (UncheckedIOException e) {
            LOGGER.error("[Hotwire] The mods folder could not be read", e);
        }
    }

    private static void openSoonFromKey() {
        ModViews.runSoon(ModViews.isShiftDown() ? HotwireClient::open : () -> getEngine().openChooser());
    }

    public static synchronized @Nullable HotwireEngine findEngine() {
        return engine;
    }

    public static synchronized HotwireEngine getEngine() {
        HotwireEngine started = engine;
        if (started == null) {
            try {
                started = EngineStart.start();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            engine = started;
        }
        return started;
    }
}
