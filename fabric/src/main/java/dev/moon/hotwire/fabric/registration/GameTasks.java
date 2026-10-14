package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.fabric.mixin.core.server.TickTaskAccessor;
import dev.moon.hotwire.fabric.mixin.core.util.thread.BlockableEventLoopAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.TickTask;
import net.minecraft.util.thread.BlockableEventLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GameTasks {
    private static final Logger LOGGER = LoggerFactory.getLogger(GameTasks.class);

    private record Removal(BlockableEventLoop<?> loop, Runnable task) {
    }

    private static final Map<String, List<Removal>> REMOVED = new HashMap<>();
    private static final Set<String> MODS_OFF = new HashSet<>();

    private static volatile boolean isAnyOff;

    private GameTasks() {
    }

    public static synchronized void takeOut(Collection<String> mods) {
        MODS_OFF.addAll(mods);
        isAnyOff = true;
        drain(mods);
    }

    public static synchronized void takeOutAgain(Collection<String> mods) {
        drain(mods.stream().filter(MODS_OFF::contains).toList());
    }

    public static boolean isSetAside(BlockableEventLoop<?> loop, Runnable queued) {
        if (!isAnyOff) {
            return false;
        }
        Runnable task = unwrap(queued);
        String mod = GameRegistrations.findModOf(task.getClass());
        if (mod == null) {
            return false;
        }
        synchronized (GameTasks.class) {
            if (!MODS_OFF.contains(mod)) {
                return false;
            }
            REMOVED.computeIfAbsent(mod, any -> new ArrayList<>()).add(new Removal(loop, task));
        }
        return true;
    }

    private static Runnable unwrap(Runnable queued) {
        return queued instanceof TickTask tick ? ((TickTaskAccessor) tick).hotwire$getRunnable() : queued;
    }

    private static void drain(Collection<String> mods) {
        for (BlockableEventLoop<?> loop : findLoops()) {
            Queue<Runnable> pending = ((BlockableEventLoopAccessor) loop).hotwire$getPending();
            for (Runnable queued : List.copyOf(pending)) {
                Runnable task = unwrap(queued);
                String mod = GameRegistrations.findModOf(task.getClass());
                if (mod != null && mods.contains(mod) && pending.remove(queued)) {
                    REMOVED.computeIfAbsent(mod, any -> new ArrayList<>()).add(new Removal(loop, task));
                }
            }
        }
        mods.forEach(mod -> {
            int removed = REMOVED.getOrDefault(mod, List.of()).size();
            if (removed > 0) {
                LOGGER.info("[Hotwire] {} tasks {} had queued on the game's loops wait until its on", removed, mod);
            }
        });
    }

    public static synchronized void putBack(Collection<String> mods) {
        MODS_OFF.removeAll(mods);
        isAnyOff = !MODS_OFF.isEmpty();
        for (String mod : mods) {
            List<Removal> removed = REMOVED.remove(mod);
            if (removed != null) {
                removed.forEach(one -> one.loop().execute(one.task()));
            }
        }
    }

    private static List<BlockableEventLoop<?>> findLoops() {
        List<BlockableEventLoop<?>> loops = new ArrayList<>();
        Minecraft client = Minecraft.getInstance();
        loops.add(client);
        IntegratedServer server = client.getSingleplayerServer();
        if (server != null) {
            loops.add(server);
        }
        return loops;
    }
}
