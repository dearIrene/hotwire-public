package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.add.WorldReopen;
import dev.moon.hotwire.fabric.registration.GameRegistrations;
import dev.moon.hotwire.redefine.Tripwire;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.ModScreen;
import dev.moon.hotwire.ui.ReopenPanel;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class OffCodeNotice {
    private static final Logger LOGGER = LoggerFactory.getLogger(OffCodeNotice.class);

    private OffCodeNotice() {
    }

    static void install(HotwireEngine engine, InstalledMods installed) {
        Tripwire.setOwners(GameRegistrations::findModOrShipper);
        Tripwire.setGameThreads(() -> {
            Minecraft client = Minecraft.getInstance();
            MinecraftServer server = client.getSingleplayerServer();
            return client.isSameThread() || (server != null && server.isSameThread());
        });
        Tripwire.setLibraries(mod -> installed.getGraph().getUses(mod));
        Tripwire.setGameIds(ModPolicy.BUILT_IN);

        //? if >=1.21.2 {
        Tripwire.onFirstCall(mod -> Minecraft.getInstance().schedule(() -> onOffCodeCalled(engine, installed, mod)));
        //?} else {
        /*Tripwire.onFirstCall(mod -> Minecraft.getInstance().tell(() -> onOffCodeCalled(engine, installed, mod)));
        *///?}
    }

    private static void onOffCodeCalled(HotwireEngine engine, InstalledMods installed, String mod) {
        if (engine.isOn(mod)) {
            return;
        }
        List<String> first = Tripwire.peek(mod).first();
        LOGGER.warn("[Hotwire] {} is off, yet its code ran: {}", mod, String.join(" < ", first));
        ModScreen current = engine.findScreen();
        boolean fromWorld = engine.findJvmParts().map(parts -> parts.classes().isReachedFromWorld(mod, first))
                .orElse(false);
        if (current != null && fromWorld && WorldReopen.findRefusal().isEmpty()) {
            current.offerReopen(new ReopenPanel.Offer(true, List.of(), installed.nameOf(mod),
                    Lang.get("hotwire.off_code.world")));
            return;
        }
        String clears = Lang.get(fromWorld ? "hotwire.off_code.reopen" : "hotwire.off_code.restart");
        engine.showMessage(first.size() > 1 ? Lang.get("hotwire.off_code.ran_from", installed.nameOf(mod),
                first.get(1), clears) : Lang.get("hotwire.off_code.ran", installed.nameOf(mod), clears), true);
    }
}
