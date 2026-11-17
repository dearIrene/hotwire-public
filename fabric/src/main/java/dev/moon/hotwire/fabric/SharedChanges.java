package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.version.Spellings;
import dev.moon.hotwire.seam.SharedChange;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

public final class SharedChanges {
    private SharedChanges() {
    }

    private record Told(List<SharedChange> changes) implements CustomPacketPayload {
        private static final Type<Told> TYPE = new Type<>(Identifier.fromNamespaceAndPath("hotwire", "changes"));

        @Override
        public Type<Told> type() {
            return TYPE;
        }
    }

    public static void register(Consumer<List<SharedChange>> heard) {
        Spellings.findToGameRegistry().register(Told.TYPE, StreamCodec.of(SharedChanges::write,
                SharedChanges::read));
        ClientPlayNetworking.registerGlobalReceiver(Told.TYPE, (told, context) -> {
            if (Minecraft.getInstance().getSingleplayerServer() == null && !told.changes().isEmpty()) {
                heard.accept(told.changes());
            }
        });
    }

    public static void send(List<SharedChange> changes) {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null || changes.isEmpty()) {
            return;
        }
        Told told = new Told(List.copyOf(changes.subList(0, Math.min(changes.size(), SharedChange.MOST))));
        UUID host = Minecraft.getInstance().getUser().getProfileId();
        server.execute(() -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (!player.getUUID().equals(host) && ServerPlayNetworking.canSend(player, Told.TYPE)) {
                    ServerPlayNetworking.send(player, told);
                }
            }
        });
    }

    private static void write(FriendlyByteBuf to, Told told) {
        to.writeByte(told.changes().size());
        for (SharedChange change : told.changes()) {
            to.writeByte(change.kind().getNumber());
            to.writeUtf(change.mod(), SharedChange.LONGEST);
            to.writeUtf(change.project(), SharedChange.LONGEST);
            to.writeUtf(change.version(), SharedChange.LONGEST);
        }
    }

    private static Told read(FriendlyByteBuf from) {
        List<SharedChange> changes = new ArrayList<>();
        try {
            int count = from.readByte();
            boolean whole = count >= 0 && count <= SharedChange.MOST;
            for (int at = 0; whole && at < count; at++) {
                SharedChange change = SharedChange.of(from.readByte(), from.readUtf(SharedChange.LONGEST),
                        from.readUtf(SharedChange.LONGEST), from.readUtf(SharedChange.LONGEST));
                whole = change != null;
                if (change != null) {
                    changes.add(change);
                }
            }
            whole = whole && !from.isReadable();
            return new Told(whole ? changes : List.of());
        } catch (RuntimeException e) {
            return new Told(List.of());
        } finally {
            from.skipBytes(from.readableBytes());
        }
    }
}
