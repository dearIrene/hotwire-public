package dev.moon.hotwire.fabric.add;

import dev.moon.hotwire.fabric.ModViews;
import dev.moon.hotwire.fabric.ResourceReload;
import dev.moon.hotwire.fabric.mixin.core.network.ConnectionAccessor;
import dev.moon.hotwire.fabric.mixin.core.util.thread.BlockableEventLoopAccessor;
import dev.moon.hotwire.jvm.JarChannels;
import dev.moon.hotwire.ui.Lang;
import io.netty.channel.Channel;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
//? if <1.21.8 {
/*import net.minecraft.client.gui.screens.TitleScreen;
*///?}
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WorldReopen {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldReopen.class);
    private static final Duration WAIT = Duration.ofMinutes(2);

    private static final Duration SLOW = Duration.ofSeconds(30);

    private static final Duration SHUTDOWN_WAIT = Duration.ofMinutes(10);
    private static final Duration RELEASED_GRACE = Duration.ofSeconds(2);

    private record QueueAtLeave(Queue<Runnable> queue, Set<Runnable> earlier) {
    }

    private static volatile @Nullable QueueAtLeave queueAtLeave;

    private WorldReopen() {
    }

    private static volatile Consumer<Boolean> closedFor = closed -> { };

    public static void tellClosedTo(Consumer<Boolean> listener) {
        closedFor = listener;
    }

    public static Optional<String> findRefusal() {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            return Optional.of(Lang.get("hotwire.reopen.single_player_only"));
        }
        if (server.isPublished()) {
            return Optional.of(Lang.get("hotwire.reopen.lan"));
        }
        return Optional.empty();
    }

    public static boolean isLeftOnly() {
        Minecraft client = Minecraft.getInstance();
        return client.level != null && client.getSingleplayerServer() == null;
    }

    public static ModAdder.Result add(Supplier<ModAdder.Result> add) {
        Optional<String> cannot = findRefusal();
        if (cannot.isPresent()) {
            return new ModAdder.Result.Refused(cannot.get());
        }
        Minecraft client = Minecraft.getInstance();
        try {
            String level = getFolderOf(Objects.requireNonNull(client.getSingleplayerServer()));
            leave(client);
            closedFor.accept(true);
            try {
                ModAdder.Result result = add.get();
                if (result instanceof ModAdder.Result.Failed failed && failed.leftBehind()) {
                    return new ModAdder.Result.Failed(failed.reason() + " The world stays closed until then.",
                            failed.cause(), true);
                }
                openOrSay(client, level);
                return result;
            } finally {
                closedFor.accept(false);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ModAdder.Result.Refused("the add was interrupted while the world was closing or opening");
        } catch (IllegalStateException e) {
            LOGGER.warn("[Hotwire] Save and add could not finish", e);
            return new ModAdder.Result.Failed(Lang.get("hotwire.reopen.add_failed", e.getMessage()), e);
        }
    }

    public static <T> T runAround(Supplier<T> add) throws InterruptedException {
        Minecraft client = Minecraft.getInstance();
        if (isLeftOnly()) {
            leave(client);
            return add.get();
        }
        IntegratedServer server = client.getSingleplayerServer();
        if (server == null) {
            throw new IllegalStateException("no single-player world is open");
        }
        String level = getFolderOf(server);
        leave(client);
        closedFor.accept(true);
        try {
            T result;
            try {
                result = add.get();
            } catch (RuntimeException e) {
                openOrSay(client, level);
                throw e;
            }
            openOrSay(client, level);
            return result;
        } finally {
            closedFor.accept(false);
        }
    }

    private static void openOrSay(Minecraft client, String level) throws InterruptedException {
        try {
            open(client, level);
        } catch (IllegalStateException e) {
            LOGGER.warn("[Hotwire] the world {} did not open again: {}", level, e.getMessage());
            ModViews.showNotice("Hotwire", Lang.get("hotwire.reopen.not_reopened", e.getMessage()));
        }
    }

    private static String getFolderOf(IntegratedServer server) {
        Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        return String.valueOf(root.getFileName());
    }

    public static void leave(Minecraft client) throws InterruptedException {
        JarChannels.makeUninterruptible();
        IntegratedServer leaving = client.getSingleplayerServer();
        ClientPacketListener listener = client.getConnection();
        Channel channel = listener == null ? null
                : ((ConnectionAccessor) listener.getConnection()).hotwire$getChannel();
        TickEnd.run(() -> {
            Queue<Runnable> queue = ((BlockableEventLoopAccessor) client).hotwire$getPending();
            Set<Runnable> earlier = Collections.newSetFromMap(new IdentityHashMap<>());
            earlier.addAll(queue);
            queueAtLeave = new QueueAtLeave(queue, earlier);

            ResourceReload.layOutChat();

            try {
                //? if >=1.21.10 {
                client.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
                //?} elif >=1.21.8 {
                /*ClientLevel level = client.level;
                if (level != null) {
                    level.disconnect(ClientLevel.DEFAULT_QUIT_MESSAGE);
                }
                client.disconnectWithSavingScreen();
                *///?} else {
                /*ClientLevel level = client.level;
                if (level != null) {
                    level.disconnect();
                }
                client.disconnect(new TitleScreen());
                *///?}
            } finally {
                queueAtLeave = null;
            }
        });
        long deadline = System.nanoTime() + WAIT.toNanos();
        long slow = System.nanoTime() + SLOW.toNanos();
        long released = 0;
        boolean asked = false;

        Thread serverThread = leaving == null ? null : leaving.getRunningThread();
        while (client.level != null || client.hasSingleplayerServer()
                || (serverThread != null && serverThread.isAlive())) {
            if (released == 0 && leaving != null && !client.hasSingleplayerServer()) {
                released = System.nanoTime();
            }
            if (!asked && leaving != null && released != 0
                    && System.nanoTime() - released > RELEASED_GRACE.toNanos() && leaving.isRunning()) {
                asked = true;
                leaving.halt(false);
            }
            if (slow != 0 && System.nanoTime() > slow) {
                slow = 0;
                Thread.getAllStackTraces().forEach((thread, stack) -> LOGGER.info("[Hotwire] the close is slow; {} {}"
                        + " at {}", thread.getName(), thread.getState(), Arrays.stream(stack).limit(12)
                        .map(String::valueOf).collect(Collectors.joining(" < "))));
            }
            boolean shuttingDown = client.level == null && !client.hasSingleplayerServer() && leaving != null
                    && !leaving.isRunning();
            if (System.nanoTime() > deadline + (shuttingDown ? SHUTDOWN_WAIT.minus(WAIT).toNanos() : 0)) {
                throw new IllegalStateException("the world or its server did not close in "
                        + (shuttingDown ? SHUTDOWN_WAIT : WAIT).toSeconds() + " s"
                        + (leaving == null ? "" : "; its thread is at " + describeWhere(leaving.getRunningThread())));
            }
            Thread.sleep(200);
        }
        awaitClosed(channel);
    }

    @SuppressWarnings("ReferenceEquality")
    public static boolean dropEarlierTasks(Queue<?> queue) {
        QueueAtLeave at = queueAtLeave;
        if (at == null || at.queue() != queue) {
            return false;
        }
        queue.removeIf(at.earlier()::contains);
        return true;
    }

    private static void awaitClosed(@Nullable Channel channel) throws InterruptedException {
        if (channel == null || channel.eventLoop().inEventLoop()) {
            return;
        }
        try {
            if (channel.closeFuture().await(WAIT.toMillis())) {
                for (int round = 0; round < 2; round++) {
                    channel.eventLoop().submit(() -> { }).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
                }
            }
        } catch (ExecutionException | TimeoutException | RejectedExecutionException e) {
            LOGGER.warn("[Hotwire] the old connection's last events were not waited for: {}", e.toString());
        }
    }

    private static String describeWhere(Thread thread) {
        StringBuilder out = new StringBuilder(thread.getState().toString());
        StackTraceElement[] stack = thread.getStackTrace();
        for (int at = 0; at < Math.min(14, stack.length); at++) {
            out.append(" < ").append(stack[at]);
        }
        return out.toString();
    }

    public static void open(Minecraft client, String level) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (ModViews.openOverlay() != null) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("a reload held the game for " + WAIT.toSeconds() + " s, so the world "
                        + level + " was not opened");
            }
            Thread.sleep(200);
        }
        awaitUnlocked(client, level, deadline);
        boolean[] refused = new boolean[1];
        client.execute(() -> client.createWorldOpenFlows().openWorld(level, () -> refused[0] = true));
        while (client.player == null || client.level == null) {
            if (refused[0]) {
                throw new IllegalStateException("the game would not open the world " + level + " again");
            }
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("the world " + level + " did not open again in " + WAIT.toSeconds()
                        + " s");
            }
            Thread.sleep(200);
        }
    }

    private static void awaitUnlocked(Minecraft client, String level, long deadline) throws InterruptedException {
        Path lock = client.getLevelSource().getBaseDir().resolve(level).resolve("session.lock");
        while (isLocked(lock)) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("the world " + level + " was still being saved after "
                        + WAIT.toSeconds() + " s");
            }
            Thread.sleep(200);
        }
    }

    private static boolean isLocked(Path lock) {
        if (!Files.exists(lock)) {
            return false;
        }
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE)) {
            FileLock free = channel.tryLock();
            if (free == null) {
                return true;
            }
            free.release();
            return false;
        } catch (OverlappingFileLockException | IOException e) {
            return true;
        }
    }
}
