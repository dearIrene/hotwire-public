package dev.moon.hotwire.seam;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

public record PlayerMods(UUID player, String name, boolean sharing, List<SharedMod> mods) {
    public static final int MOST_MODS = 1024;
    public static final int MOST_PLAYERS = 32;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    public PlayerMods {
        mods = List.copyOf(mods);
    }

    public int countDiffering(Map<String, SharedMod> own) {
        int count = 0;
        for (SharedMod theirs : this.mods) {
            SharedMod mine = own.get(theirs.mod());
            if (mine == null || mine.on() != theirs.on() || !mine.version().equals(theirs.version())) {
                count++;
            }
        }
        return count;
    }

    public static @Nullable PlayerMods of(UUID player, String name, boolean sharing, List<SharedMod> mods) {
        return NAME.matcher(name).matches() && mods.size() <= MOST_MODS && (sharing || mods.isEmpty())
                ? new PlayerMods(player, name, sharing, mods) : null;
    }
}
