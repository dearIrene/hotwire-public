package dev.moon.hotwire.seam;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public final class PlayerListReader {
    public interface Source {
        int readVarInt();

        boolean readBoolean();

        String readUtf(int longest);

        UUID readUuid();

        boolean isReadable();
    }

    private PlayerListReader() {
    }

    public static @Nullable List<SharedMod> readShared(Source from) {
        try {
            boolean sharing = from.readBoolean();
            List<SharedMod> mods = readMods(from);
            return !sharing || mods == null || from.isReadable() ? null : mods;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static List<PlayerMods> readPlayers(Source from) {
        try {
            int count = from.readVarInt();
            if (count < 0 || count > PlayerMods.MOST_PLAYERS) {
                return List.of();
            }
            List<PlayerMods> players = new ArrayList<>();
            for (int at = 0; at < count; at++) {
                UUID player = from.readUuid();
                String name = from.readUtf(PlayerMods.LONGEST_NAME);
                boolean sharing = from.readBoolean();
                List<SharedMod> mods = readMods(from);
                PlayerMods one = mods == null ? null : PlayerMods.of(player, name, sharing, mods);
                if (one == null) {
                    return List.of();
                }
                players.add(one);
            }
            return from.isReadable() ? List.of() : List.copyOf(players);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static @Nullable List<SharedMod> readMods(Source from) {
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
        return List.copyOf(mods);
    }
}
