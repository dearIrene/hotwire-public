package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.registration.OffModClose;
import dev.moon.hotwire.fabric.registration.OffModCommands;
import dev.moon.hotwire.fabric.report.HotwireLog;
import dev.moon.hotwire.modrinth.Downloads;
import dev.moon.hotwire.redefine.FieldHolders;
import dev.moon.hotwire.redefine.InitHookReplay;
import dev.moon.hotwire.redefine.Tripwire;
import dev.moon.hotwire.report.GameLog;
import dev.moon.hotwire.ui.DownloadNotices;
import dev.moon.hotwire.ui.Lang;
import java.io.IOException;
import java.io.UncheckedIOException;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HotwireClient implements ClientModInitializer {
    public static final String ID = "hotwire";

    public static final Identifier NOTICES = Identifier.fromNamespaceAndPath(ID, "notices");
    private static final Logger LOGGER = LoggerFactory.getLogger(HotwireClient.class);

    private static @Nullable HotwireEngine engine;

    @Override
    public void onInitializeClient() {
        HotwireLog.install(FabricLoader.getInstance().getGameDir().resolve("hotwire").resolve("logs"));
        GameLog.LOGGER.info("[Hotwire] {} loaded; H or /hotwire opens it, and its Report button writes what went wrong"
                + " to hotwire/reports", LoadedMods.findVersion(ID, "unknown"));
        NoticeHud.register();
        Downloads.onEveryChange(DownloadNotices::show);

        Lang.useTranslator((key, values) -> {
            Language language = Language.getInstance();
            return language.has(key) ? Lang.fill(language.getOrDefault(key), values) : null;
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) ->
                HotwireCommands.register(dispatcher, HotwireClient::openSoon));

        Identifier afterMods = Identifier.fromNamespaceAndPath(ID, "after_mods");
        ClientCommandRegistrationCallback.EVENT.addPhaseOrdering(Event.DEFAULT_PHASE, afterMods);
        ClientCommandRegistrationCallback.EVENT.register(afterMods, (dispatcher, context) -> {
            HotwireEngine started = engine;
            if (started != null) {
                OffModCommands.readOffFrom(id -> !started.isOn(id) || Tripwire.isWatched(id));
                OffModCommands.takeOutOf(dispatcher, true);
            }
        });

        OpenKey.register(HotwireClient::openSoonFromKey);
        OffTooltips.register();

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> client.execute(HotwireClient::getEngine));

        InitHookReplay.catchUpLaterWhile(() -> Minecraft.getInstance().level == null);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
            FieldHolders.retryCatchUps();
            OpenKey.tellConflicts();
            HotwireEngine started = engine;
            if (started != null) {
                OffModCommands.readOffFrom(id -> !started.isOn(id) || Tripwire.isWatched(id));
                started.lookAheadInWorldSoon();
                started.showUpdateInChat();

                OffModCommands.takeOut(id -> !started.isOn(id));
            }
        }));

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> GameLog.LOGGER.info(
                "[Hotwire] the world's connection closed", new Throwable("closed here")));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            OffModClose.run(client);
            OffModClose.exitWhenStuck();
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
            HotwireEngine made = started;
            OffModCommands.readOffFrom(id -> !made.isOn(id) || Tripwire.isWatched(id));
        }
        return started;
    }
}
