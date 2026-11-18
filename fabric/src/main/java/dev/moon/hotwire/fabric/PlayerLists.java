package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.version.Spellings;
import dev.moon.hotwire.seam.PlayerMods;
import dev.moon.hotwire.seam.SharedMod;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

public final class PlayerLists {
    private static final int EVERY = 40;

    private record Mine(boolean sharing, List<SharedMod> mods) implements CustomPacketPayload {
        private static final Type<Mine> TYPE = new Type<>(Identifier.fromNamespaceAndPath("hotwire", "my_mods"));

        @Override
        public Type<Mine> type() {
            return TYPE;
        }
    }

    private record Everyone(List<PlayerMods> players) implements CustomPacketPayload {
        private static final Type<Everyone> TYPE = new Type<>(Identifier.fromNamespaceAndPath("hotwire", "players"));

        @Override
        public Type<Everyone> type() {
            return TYPE;
        }
    }

    public interface Own {
        boolean isSharing();

        List<SharedMod> findMods();
    }

    private final Supplier<@Nullable Own> own;
    private final Consumer<List<PlayerMods>> heard;

    private final Map<UUID, Mine> told = new HashMap<>();

    private @Nullable Mine sent;
    private int ticks;

    public PlayerLists(Supplier<@Nullable Own> own, Consumer<List<PlayerMods>> heard) {
        this.own = own;
        this.heard = heard;
    }

    public void register() {
        Spellings.findToServerRegistry().register(Mine.TYPE, StreamCodec.of(PlayerLists::writeMine,
                PlayerLists::readMine));
        Spellings.findToGameRegistry().register(Everyone.TYPE, StreamCodec.of(PlayerLists::writeEveryone,
                PlayerLists::readEveryone));

        ServerPlayNetworking.registerGlobalReceiver(Mine.TYPE, (mine, context) -> {
            this.told.put(context.player().getUUID(), mine);
            this.tellAll(context.player().level().getServer());
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (this.told.remove(handler.getPlayer().getUUID()) != null) {
                this.tellAll(server);
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(Everyone.TYPE, (everyone, context) -> {
            UUID self = Minecraft.getInstance().getUser().getProfileId();
            this.heard.accept(everyone.players().stream().filter(player -> !player.player().equals(self)).toList());
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            this.sent = null;
            this.heard.accept(List.of());
        });
    }

    public void tick() {
        Minecraft game = Minecraft.getInstance();
        if (game.getConnection() == null || ++this.ticks < EVERY) {
            return;
        }
        this.ticks = 0;
        Own mine = this.own.get();
        if (mine == null) {
            return;
        }
        boolean sharing = mine.isSharing();
        Mine now = new Mine(sharing, sharing ? trimToMost(mine.findMods()) : List.of());
        if (now.equals(this.sent)) {
            return;
        }
        IntegratedServer server = game.getSingleplayerServer();
        if (server != null) {
            this.sent = now;
            UUID self = game.getUser().getProfileId();
            server.execute(() -> {
                this.told.put(self, now);
                this.tellAll(server);
            });
        } else if (ClientPlayNetworking.canSend(Mine.TYPE)) {
            this.sent = now;
            ClientPlayNetworking.send(now);
        }
    }

    private static List<SharedMod> trimToMost(List<SharedMod> mods) {
        return List.copyOf(mods.subList(0, Math.min(mods.size(), PlayerMods.MOST_MODS)));
    }

    private void tellAll(@Nullable MinecraftServer server) {
        if (server == null) {
            return;
        }
        List<PlayerMods> players = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Mine mine = this.told.get(player.getUUID());
            PlayerMods mods = mine == null ? null : PlayerMods.of(player.getUUID(), player.getName().getString(),
                    mine.sharing(), mine.mods());
            if (mods != null && players.size() < PlayerMods.MOST_PLAYERS) {
                players.add(mods);
            }
        }
        Everyone everyone = new Everyone(List.copyOf(players));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (ServerPlayNetworking.canSend(player, Everyone.TYPE)) {
                ServerPlayNetworking.send(player, everyone);
            }
        }
    }

    private static void writeMods(FriendlyByteBuf to, List<SharedMod> mods) {
        to.writeVarInt(mods.size());
        for (SharedMod mod : mods) {
            to.writeUtf(mod.mod(), SharedMod.LONGEST);
            to.writeBoolean(mod.on());
            to.writeUtf(mod.version(), SharedMod.LONGEST);
            to.writeUtf(mod.project(), SharedMod.LONGEST);
        }
    }

    private static @Nullable List<SharedMod> readMods(FriendlyByteBuf from) {
        int count = from.readVarInt();
        if (count < 0 || count > PlayerMods.MOST_MODS) {
            return null;
        }
        List<SharedMod> mods = new ArrayList<>();
        for (int at = 0; at < count; at++) {
            SharedMod mod = SharedMod.of(from.readUtf(SharedMod.LONGEST), from.readBoolean(),
                    from.readUtf(SharedMod.LONGEST), from.readUtf(SharedMod.LONGEST));
            if (mod == null) {
                return null;
            }
            mods.add(mod);
        }
        return mods;
    }

    private static void writeMine(FriendlyByteBuf to, Mine mine) {
        to.writeBoolean(mine.sharing());
        writeMods(to, mine.mods());
    }

    private static Mine readMine(FriendlyByteBuf from) {
        try {
            boolean sharing = from.readBoolean();
            List<SharedMod> mods = readMods(from);
            return mods == null || from.isReadable() || (!sharing && !mods.isEmpty()) ? new Mine(false, List.of())
                    : new Mine(sharing, mods);
        } catch (RuntimeException e) {
            return new Mine(false, List.of());
        } finally {
            from.skipBytes(from.readableBytes());
        }
    }

    private static void writeEveryone(FriendlyByteBuf to, Everyone everyone) {
        to.writeVarInt(everyone.players().size());
        for (PlayerMods player : everyone.players()) {
            to.writeUUID(player.player());
            to.writeUtf(player.name(), 16);
            to.writeBoolean(player.sharing());
            writeMods(to, player.mods());
        }
    }

    private static Everyone readEveryone(FriendlyByteBuf from) {
        try {
            int count = from.readVarInt();
            if (count < 0 || count > PlayerMods.MOST_PLAYERS) {
                return new Everyone(List.of());
            }
            List<PlayerMods> players = new ArrayList<>();
            for (int at = 0; at < count; at++) {
                UUID player = from.readUUID();
                String name = from.readUtf(16);
                boolean sharing = from.readBoolean();
                List<SharedMod> mods = readMods(from);
                PlayerMods one = mods == null ? null : PlayerMods.of(player, name, sharing, mods);
                if (one == null) {
                    return new Everyone(List.of());
                }
                players.add(one);
            }
            return new Everyone(from.isReadable() ? List.of() : List.copyOf(players));
        } catch (RuntimeException e) {
            return new Everyone(List.of());
        } finally {
            from.skipBytes(from.readableBytes());
        }
    }
}
