package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.fabric.mixin.core.server.TickTaskAccessor;
import dev.moon.hotwire.fabric.mixin.core.util.thread.BlockableEventLoopAccessor;
import dev.moon.hotwire.redefine.FieldSlots;
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
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GameTasks {
    private static final Logger LOGGER = LoggerFactory.getLogger(GameTasks.class);

    private record Removal(BlockableEventLoop<?> loop, Runnable task) implements TakeoutRecord.Entry {
        @Override
        public void restore() {
            this.loop.execute(this.task);
        }

        @Override
        public String describe() {
            return "the task " + this.task.getClass().getName();
        }

        @Override
        @SuppressWarnings("ReferenceEquality")
        public boolean isStale() {
            return !(this.loop instanceof Minecraft) && this.loop != Minecraft.getInstance().getSingleplayerServer();
        }
    }

    private static final Set<String> MODS_OFF = new HashSet<>();
    private static final Map<String, Integer> WAITING = new HashMap<>();
    private static final int MOST = 1000;

    private static final Set<Class<?>> SAID = new HashSet<>();

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
        String mod = findOwnerOf(task, findDepthFor(loop));
        if (mod == null || !MODS_OFF.contains(mod)) {
            if (LOGGER.isDebugEnabled() && SAID.add(task.getClass())) {
                LOGGER.debug("[Hotwire] a task of {} ({}) runs while mods are off; it holds {}", mod,
                        task.getClass().getName(), describeCaptures(task));
            }
            return false;
        }
        synchronized (GameTasks.class) {
            if (WAITING.merge(mod, 1, Integer::sum) <= MOST) {
                TakeoutRecord.add(mod, new Removal(loop, task));
            }
            if (SAID.add(task.getClass())) {
                LOGGER.info("[Hotwire] {}'s task {} waits for its on; it holds {}", mod, task.getClass().getName(),
                        describeCaptures(task));
            }
        }
        return true;
    }

    private static final int WRAPPED_DEPTH = 6;

    private static final Set<String> KOTLIN_WORK = Set.of("kotlin.Function", "kotlin.coroutines.Continuation",
            "kotlin.coroutines.CoroutineContext");

    private static final ClassValue<Boolean> IS_WORK = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return type.isHidden() || hasWorkInterface(type);
        }
    };

    private static boolean hasWorkInterface(Class<?> type) {
        for (Class<?> at = type; at != null; at = at.getSuperclass()) {
            for (Class<?> one : at.getInterfaces()) {
                if (one.isAnnotationPresent(FunctionalInterface.class) || KOTLIN_WORK.contains(one.getName())
                        || hasWorkInterface(one)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int findDepthFor(BlockableEventLoop<?> loop) {
        return loop instanceof Minecraft ? WRAPPED_DEPTH : 0;
    }

    private static List<String> describeCaptures(Object task) {
        List<String> found = new ArrayList<>();
        for (FieldSlots.Slot field : Fields.getReferenceFields(task.getClass(), false)) {
            try {
                Object captured = field.get(task);
                if (captured != null) {
                    found.add(field.getName() + ": " + captured.getClass().getName());
                }
            } catch (IllegalAccessException | RuntimeException e) {
                found.add(field.getName() + ": ?");
            }
        }
        return found;
    }

    private static @Nullable String findOwnerOf(Object task, int depth) {
        Object submitted = FutureWork.find(task);
        if (submitted != null) {
            return GameRegistrations.findModOrShipper(submitted.getClass());
        }

        String mod = GameRegistrations.findModOrShipper(task.getClass());

        if (depth == 0 || (mod != null && MODS_OFF.contains(mod)) || CollectionParts.isGameObject(task)) {
            return mod;
        }
        for (FieldSlots.Slot field : Fields.getReferenceFields(task.getClass(), false)) {
            Object captured;
            try {
                captured = field.get(task);
            } catch (IllegalAccessException | RuntimeException e) {
                continue;
            }

            String inner = captured == null || CollectionParts.isGameObject(captured)
                    || !IS_WORK.get(captured.getClass()) ? null : findOwnerOf(captured, depth - 1);
            if (inner != null && MODS_OFF.contains(inner)) {
                return inner;
            }
        }
        return mod;
    }

    private static Runnable unwrap(Runnable queued) {
        return queued instanceof TickTask tick ? ((TickTaskAccessor) tick).hotwire$getRunnable() : queued;
    }

    private static void drain(Collection<String> mods) {
        for (BlockableEventLoop<?> loop : findLoops()) {
            Queue<Runnable> pending = ((BlockableEventLoopAccessor) loop).hotwire$getPending();
            for (Runnable queued : List.copyOf(pending)) {
                Runnable task = unwrap(queued);
                String mod = findOwnerOf(task, findDepthFor(loop));
                if (mod != null && mods.contains(mod) && pending.remove(queued)) {
                    TakeoutRecord.add(mod, new Removal(loop, task));
                    WAITING.merge(mod, 1, Integer::sum);
                }
            }
        }
        mods.forEach(mod -> {
            int removed = WAITING.getOrDefault(mod, 0);
            if (removed > 0) {
                LOGGER.info("[Hotwire] {} tasks {} had queued on the game's loops wait until its on", removed, mod);
            }
        });
    }

    public static synchronized void onBack(Collection<String> mods) {
        MODS_OFF.removeAll(mods);
        mods.forEach(WAITING::remove);
        isAnyOff = !MODS_OFF.isEmpty();
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
