package dev.moon.hotwire.fabric.registration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.client.server.IntegratedServer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OffRemovals {
    private static final Logger LOGGER = LoggerFactory.getLogger(OffRemovals.class);
    private static final int WAIT_SECONDS = 5;

    public enum Where { ANY, GAME, SERVER, GAME_BEFORE_FRAME }

    public interface Entry {
        void restore();

        String describe();

        default Where getWhere() {
            return Where.ANY;
        }

        default Set<String> getNeeds() {
            return Set.of();
        }

        default boolean isStale() {
            return false;
        }

        default @Nullable Runnable getAfterBatch() {
            return null;
        }
    }

    private static final Map<String, List<Entry>> BY_MOD = Collections.synchronizedMap(new LinkedHashMap<>());

    private OffRemovals() {
    }

    public static void add(String mod, Entry entry) {
        BY_MOD.computeIfAbsent(mod, any -> Collections.synchronizedList(new ArrayList<>())).add(entry);
    }

    public static void dropStale() {
        synchronized (BY_MOD) {
            BY_MOD.values().forEach(entries -> entries.removeIf(Entry::isStale));
        }
    }

    public static boolean hasAny(String mod, Predicate<Entry> test) {
        List<Entry> entries = BY_MOD.get(mod);
        return entries != null && entries.stream().anyMatch(test);
    }

    public static void restore(Collection<String> mods, Predicate<String> isOn) {
        List<String> order = new ArrayList<>(BY_MOD.keySet());
        order.retainAll(mods);
        Map<Where, List<Entry>> due = new EnumMap<>(Where.class);
        for (String mod : order.reversed()) {
            List<Entry> entries = BY_MOD.remove(mod);
            if (entries == null) {
                continue;
            }
            List<Entry> kept = new ArrayList<>();
            for (Entry entry : entries.reversed()) {
                if (entry.isStale()) {
                    continue;
                }
                if (entry.getNeeds().stream().anyMatch(other -> !mods.contains(other) && !isOn.test(other))) {
                    kept.add(entry);
                    continue;
                }
                due.computeIfAbsent(entry.getWhere(), any -> new ArrayList<>()).add(entry);
            }
            if (!kept.isEmpty()) {
                BY_MOD.put(mod, Collections.synchronizedList(new ArrayList<>(kept.reversed())));
            }
        }

        synchronized (BY_MOD) {
            for (Map.Entry<String, List<Entry>> rest : BY_MOD.entrySet()) {
                if (!isOn.test(rest.getKey()) && !mods.contains(rest.getKey())) {
                    continue;
                }
                for (Entry entry : List.copyOf(rest.getValue())) {
                    if (!entry.getNeeds().isEmpty()
                            && entry.getNeeds().stream().allMatch(other -> mods.contains(other) || isOn.test(other))
                            && rest.getValue().remove(entry) && !entry.isStale()) {
                        due.computeIfAbsent(entry.getWhere(), any -> new ArrayList<>()).add(entry);
                    }
                }
            }
            BY_MOD.values().removeIf(List::isEmpty);
        }
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        due.forEach((where, entries) -> {
            switch (where) {
                case GAME_BEFORE_FRAME -> onGame(() -> restoreAll(entries), entries.size() + " entries put back");
                case GAME -> GameHold.onGameAndWait(() -> restoreAll(entries));
                case SERVER -> {
                    if (server != null) {
                        GameHold.onServerAndWait(server, () -> restoreAll(entries));
                    }
                }
                default -> restoreAll(entries);
            }
        });
    }

    private static void restoreAll(List<Entry> entries) {
        Set<Runnable> after = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Entry entry : entries) {
            try {
                entry.restore();
            } catch (RuntimeException | LinkageError e) {
                LOGGER.warn("[Hotwire] {} could not be put back", entry.describe(), e);
            }
            Runnable once = entry.getAfterBatch();
            if (once != null) {
                after.add(once);
            }
        }
        after.forEach(Runnable::run);
    }

    static void onGame(Runnable work, String what) {
        try {
            GameHold.await(GameHold.onGameBeforeNextFrame(work, Minecraft.getInstance()), WAIT_SECONDS,
                    "Render thread", what);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.warn("[Hotwire] {} could not be done on the game thread", what, e);
        }
    }
}
