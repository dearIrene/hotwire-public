package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.registration.CallerOwner;
import dev.moon.hotwire.registration.RecorderSupport;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ModGameModes {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModGameModes.class);

    private record Change(String mod, GameType from, GameType to) {
    }

    private static final Changes CHANGES = new Changes();

    private static final class Changes {
        private final Map<UUID, Change> byPlayer = new ConcurrentHashMap<>();
        private volatile boolean restoring;
    }

    private ModGameModes() {
    }

    public static void onAsked(ServerPlayer player, GameType from, GameType to) {
        if (from == to || CHANGES.restoring) {
            return;
        }
        CallerOwner.Owner owner = RecorderSupport.findOwner();
        UUID id = player.getUUID();
        if (owner == null) {
            CHANGES.byPlayer.remove(id);
            return;
        }

        CHANGES.byPlayer.compute(id, (key, was) -> new Change(owner.mod(),
                was != null && was.mod().equals(owner.mod()) ? was.from() : from, to));
    }

    public static void takeOut(Collection<String> mods) {
        if (CHANGES.byPlayer.isEmpty()) {
            return;
        }
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            CHANGES.byPlayer.clear();
            return;
        }
        GameHold.onServerAndWait(server, () -> CHANGES.byPlayer.entrySet().removeIf(entry -> {
            Change change = entry.getValue();
            if (!mods.contains(change.mod())) {
                return false;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null && findMode(player) == change.to()) {
                CHANGES.restoring = true;
                try {
                    player.setGameMode(change.from());
                } finally {
                    CHANGES.restoring = false;
                }
                LOGGER.info("[Hotwire] {} goes back to {} from the {} {} put them in", player.getName().getString(),
                        change.from().getName(), change.to().getName(), change.mod());
            }
            return true;
        }));
    }

    private static GameType findMode(ServerPlayer player) {
        return player.gameMode.getGameModeForPlayer();
    }
}
