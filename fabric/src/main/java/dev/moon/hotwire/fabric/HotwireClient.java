package dev.moon.hotwire.fabric;

//? if >=26.1 {
import com.mojang.blaze3d.platform.InputConstants;
//?}
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
//? if >=1.21.9 && <26.1 {
/*import org.lwjgl.glfw.GLFW;
*///?}
//? if <1.21.9 {
/*import net.minecraft.client.gui.screens.Screen;
*///?}
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

    private static void openSoonFromKey() {
        Minecraft client = Minecraft.getInstance();
        //? if >=26.3 {
        boolean shift = InputConstants.isKeyDown(InputConstants.KEY_LSHIFT)
                || InputConstants.isKeyDown(InputConstants.KEY_RSHIFT);
        //?} elif >=26.1 {
        /*boolean shift = InputConstants.isKeyDown(client.getWindow(), InputConstants.KEY_LSHIFT)
                || InputConstants.isKeyDown(client.getWindow(), InputConstants.KEY_RSHIFT);
        *///?} elif >=1.21.9 {
        /*long window = client.getWindow().handle();
        boolean shift = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
        *///?} else {
        /*boolean shift = Screen.hasShiftDown();
        *///?}
        Runnable opening = shift ? HotwireClient::open : () -> getEngine().openChooser();
        //? if >=1.21.2 {
        client.schedule(opening);
        //?} else {
        /*client.tell(opening);
        *///?}
    }

    public static synchronized @Nullable HotwireEngine findEngine() {
        return engine;
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
