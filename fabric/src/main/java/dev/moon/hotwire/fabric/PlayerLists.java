package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.version.Spellings;
import dev.moon.hotwire.seam.PlayerListReader;
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

    private static void writeMine(FriendlyByteBuf to, Mine mine) {
        to.writeBoolean(mine.sharing());
        writeMods(to, mine.mods());
    }

    private static Mine readMine(FriendlyByteBuf from) {
        try {
            List<SharedMod> mods = PlayerListReader.readShared(wrap(from));
            return mods == null ? new Mine(false, List.of()) : new Mine(true, mods);
        } finally {
            from.skipBytes(from.readableBytes());
        }
    }

    private static void writeEveryone(FriendlyByteBuf to, Everyone everyone) {
        to.writeVarInt(everyone.players().size());
        for (PlayerMods player : everyone.players()) {
            to.writeUUID(player.player());
            to.writeUtf(player.name(), PlayerMods.LONGEST_NAME);
            to.writeBoolean(player.sharing());
            writeMods(to, player.mods());
        }
    }

    private static Everyone readEveryone(FriendlyByteBuf from) {
        try {
            return new Everyone(PlayerListReader.readPlayers(wrap(from)));
        } finally {
            from.skipBytes(from.readableBytes());
        }
    }

    private static PlayerListReader.Source wrap(FriendlyByteBuf from) {
        return new PlayerListReader.Source() {
            @Override
            public int readVarInt() {
                return from.readVarInt();
            }

            @Override
            public boolean readBoolean() {
                return from.readBoolean();
            }

            @Override
            public String readUtf(int longest) {
                return from.readUtf(longest);
            }

            @Override
            public UUID readUuid() {
                return from.readUUID();
            }

            @Override
            public boolean isReadable() {
                return from.isReadable();
            }
        };
    }
}
