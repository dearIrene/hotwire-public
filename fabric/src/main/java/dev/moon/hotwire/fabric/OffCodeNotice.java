package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.add.WorldReopen;
import dev.moon.hotwire.fabric.registration.GameRegistrations;
import dev.moon.hotwire.redefine.Tripwire;
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
            current.offerReopen(new ReopenPanel.Offer(true, List.of(), installed.nameOf(mod)
                    + " is off, yet this world's objects still call it", "They were made while it was on;"
                    + " made again, they are made without it."));
            return;
        }
        engine.showMessage(installed.nameOf(mod) + " is off, yet its code just ran" + (first.size() > 1
                ? " (reached from " + first.get(1) + ")" : "") + ": Hotwire could not take all of it out. "
                + (fromWorld ? "Saving and reopening the world clears what the world holds of it; a restart"
                + " clears all." : "A restart clears it."), true);
    }
}
