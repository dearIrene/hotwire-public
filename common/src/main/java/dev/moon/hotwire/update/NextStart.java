package dev.moon.hotwire.update;

import dev.moon.hotwire.jar.ModManifest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public final class NextStart {
    public interface Game {
        @Nullable String findVersionOf(String id);

        boolean satisfies(String version, List<String> ranges);

        String resolve(String id);

        Collection<ModManifest> getInstalled();
    }

    public record Unmet(String entry, ModManifest mod, String other, List<String> ranges, @Nullable String has,
            boolean breaks, boolean asking) {
    }

    private NextStart() {
    }

    public static @Nullable Unmet findUnmet(Map<String, Map<String, ModManifest>> waiting, Game game) {
        for (Map.Entry<String, Map<String, ModManifest>> entry : waiting.entrySet()) {
            for (ModManifest mod : entry.getValue().values()) {
                Unmet own = findOwn(entry.getKey(), mod, waiting, game);

                if (own != null && !mod.id().equals(entry.getKey()) && !isAsked(mod, waiting, game)) {
                    continue;
                }
                Unmet unmet = own != null ? own : findAsked(entry.getKey(), mod, entry.getValue(), waiting, game);
                if (unmet != null) {
                    return unmet;
                }
            }
        }
        return null;
    }

    private static @Nullable Unmet findOwn(String entry, ModManifest mod, Map<String, Map<String, ModManifest>> waiting,
            Game game) {
        for (Map.Entry<String, List<String>> wants : mod.getRelation(ModManifest.Relation.DEPENDS).entrySet()) {
            String has = findVersion(wants.getKey(), waiting, game);

            if (has == null || (!wants.getValue().isEmpty() && !game.satisfies(has, wants.getValue()))) {
                return new Unmet(entry, mod, wants.getKey(), wants.getValue(), has, false, false);
            }
        }
        for (Map.Entry<String, List<String>> breaks : mod.getRelation(ModManifest.Relation.BREAKS).entrySet()) {
            String has = findVersion(breaks.getKey(), waiting, game);
            if (has != null && game.satisfies(has, breaks.getValue())) {
                return new Unmet(entry, mod, breaks.getKey(), breaks.getValue(), has, true, false);
            }
        }
        return null;
    }

    private static @Nullable Unmet findAsked(String entry, ModManifest mod, Map<String, ModManifest> beside,
            Map<String, Map<String, ModManifest>> waiting, Game game) {
        for (ModManifest installed : game.getInstalled()) {
            ModManifest other = findComing(installed.id(), waiting);
            other = other != null ? other : installed;
            if (beside.containsKey(other.id())) {
                continue;
            }
            for (Map.Entry<String, List<String>> wants : other.getRelation(ModManifest.Relation.DEPENDS).entrySet()) {
                if (isNamed(wants.getKey(), mod, game) && !wants.getValue().isEmpty()
                        && !game.satisfies(mod.version(), wants.getValue())) {
                    return new Unmet(entry, mod, other.id(), wants.getValue(), other.version(), false, true);
                }
            }
            for (Map.Entry<String, List<String>> breaks : other.getRelation(ModManifest.Relation.BREAKS).entrySet()) {
                if (isNamed(breaks.getKey(), mod, game) && game.satisfies(mod.version(), breaks.getValue())) {
                    return new Unmet(entry, mod, other.id(), breaks.getValue(), other.version(), true, true);
                }
            }
        }
        return null;
    }

    private static boolean isAsked(ModManifest mod, Map<String, Map<String, ModManifest>> waiting, Game game) {
        List<ModManifest> others = new ArrayList<>(game.getInstalled());
        waiting.values().forEach(brought -> others.addAll(brought.values()));
        for (ModManifest other : others) {
            if (other.id().equals(mod.id())) {
                continue;
            }
            for (String id : other.getRelation(ModManifest.Relation.DEPENDS).keySet()) {
                if (isNamed(id, mod, game)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isNamed(String id, ModManifest mod, Game game) {
        return id.equals(mod.id()) || mod.provides().contains(id) || game.resolve(id).equals(mod.id());
    }

    private static @Nullable String findVersion(String id, Map<String, Map<String, ModManifest>> waiting, Game game) {
        ModManifest coming = findComing(id, waiting);
        coming = coming != null ? coming : findComing(game.resolve(id), waiting);
        return coming != null ? coming.version() : game.findVersionOf(id);
    }

    private static @Nullable ModManifest findComing(String id, Map<String, Map<String, ModManifest>> waiting) {
        for (Map<String, ModManifest> brought : waiting.values()) {
            ModManifest named = brought.get(id);
            if (named != null) {
                return named;
            }
            for (ModManifest mod : brought.values()) {
                if (mod.provides().contains(id)) {
                    return mod;
                }
            }
        }
        return null;
    }
}
