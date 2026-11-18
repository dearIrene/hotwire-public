package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.fabric.mixin.core.server.TickTaskAccessor;
import dev.moon.hotwire.fabric.mixin.core.util.thread.BlockableEventLoopAccessor;
import dev.moon.hotwire.redefine.FieldSlots;
import dev.moon.hotwire.registration.CollectionParts;
import dev.moon.hotwire.registration.Fields;
import dev.moon.hotwire.registration.FutureWork;
import dev.moon.hotwire.registration.GameRegistrations;
import dev.moon.hotwire.registration.Owners;
import dev.moon.hotwire.registration.RegistrationJournal;
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

    private record Removal(BlockableEventLoop<?> loop, Runnable task) implements RegistrationJournal.Take {
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

    private static final int MOST = 1000;

    private final Set<String> modsOff = new HashSet<>();
    private final Map<String, Integer> waiting = new HashMap<>();

    private final Set<Class<?>> said = new HashSet<>();

    private volatile boolean isAnyOff;

    public synchronized void takeOut(Collection<String> mods) {
        this.modsOff.addAll(mods);
        this.isAnyOff = true;
        this.drain(mods);
    }

    public synchronized void takeOutAgain(Collection<String> mods) {
        this.drain(mods.stream().filter(this.modsOff::contains).toList());
    }

    public boolean isSetAside(BlockableEventLoop<?> loop, Runnable queued) {
        if (!this.isAnyOff) {
            return false;
        }
        Runnable task = unwrap(queued);
        String mod = this.findOwnerOf(task, findDepthFor(loop));
        if (mod == null || !this.modsOff.contains(mod)) {
            if (LOGGER.isDebugEnabled() && this.said.add(task.getClass())) {
                LOGGER.debug("[Hotwire] a task of {} ({}) runs while mods are off; it holds {}", mod,
                        task.getClass().getName(), describeCaptures(task));
            }
            return false;
        }
        synchronized (this) {
            if (this.waiting.merge(mod, 1, Integer::sum) <= MOST) {
                GameRegistrations.getJournal().addTake(mod, new Removal(loop, task));
            }
            if (this.said.add(task.getClass())) {
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

    private @Nullable String findOwnerOf(Object task, int depth) {
        Object submitted = FutureWork.find(task);
        if (submitted != null) {
            return Owners.findModOrShipper(submitted.getClass());
        }

        String mod = Owners.findModOrShipper(task.getClass());

        if (depth == 0 || (mod != null && this.modsOff.contains(mod)) || CollectionParts.isGameObject(task)) {
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
                    || !IS_WORK.get(captured.getClass()) ? null : this.findOwnerOf(captured, depth - 1);
            if (inner != null && this.modsOff.contains(inner)) {
                return inner;
            }
        }
        return mod;
    }

    private static Runnable unwrap(Runnable queued) {
        return queued instanceof TickTask tick ? ((TickTaskAccessor) tick).hotwire$getRunnable() : queued;
    }

    private void drain(Collection<String> mods) {
        for (BlockableEventLoop<?> loop : findLoops()) {
            Queue<Runnable> pending = ((BlockableEventLoopAccessor) loop).hotwire$getPending();
            for (Runnable queued : List.copyOf(pending)) {
                Runnable task = unwrap(queued);
                String mod = this.findOwnerOf(task, findDepthFor(loop));
                if (mod != null && mods.contains(mod) && pending.remove(queued)) {
                    GameRegistrations.getJournal().addTake(mod, new Removal(loop, task));
                    this.waiting.merge(mod, 1, Integer::sum);
                }
            }
        }
        mods.forEach(mod -> {
            int removed = this.waiting.getOrDefault(mod, 0);
            if (removed > 0) {
                LOGGER.info("[Hotwire] {} tasks {} had queued on the game's loops wait until its on", removed, mod);
            }
        });
    }

    public synchronized void onBack(Collection<String> mods) {
        this.modsOff.removeAll(mods);
        mods.forEach(this.waiting::remove);
        this.isAnyOff = !this.modsOff.isEmpty();
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
