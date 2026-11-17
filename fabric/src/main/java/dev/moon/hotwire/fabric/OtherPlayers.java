package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.registration.WorldContent;
import dev.moon.hotwire.registration.GameRegistrations;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OtherPlayers {
    private static final Logger LOGGER = LoggerFactory.getLogger(OtherPlayers.class);

    private OtherPlayers() {
    }

    public static boolean isShared() {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        return server != null && (server.isPublished() || count() > 0);
    }

    public static int count() {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        return server == null ? 0 : Math.max(0, server.getPlayerCount() - 1);
    }

    public static boolean isReachedBy(String mod) {
        return !WorldContent.find(List.of(mod)).isEmpty()
                || GameRegistrations.getJournal().countByKind(mod).containsKey("packet receiver");
    }

    public static void tell(String line) {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server != null) {
            server.execute(() -> find(server).forEach(player -> player.sendSystemMessage(Component.literal(line))));
        }
    }

    public static void sendAway(IntegratedServer server, String reason) throws InterruptedException {
        try {
            server.submit(() -> find(server).forEach(player -> player.connection.disconnect(
                    Component.literal(reason)))).get(10, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.warn("[Hotwire] the other players were not told why the world closes: {}", e.toString());
        }
    }

    private static List<ServerPlayer> find(IntegratedServer server) {
        UUID host = Minecraft.getInstance().getUser().getProfileId();
        return server.getPlayerList().getPlayers().stream().filter(player -> !player.getUUID().equals(host))
                .toList();
    }
}
