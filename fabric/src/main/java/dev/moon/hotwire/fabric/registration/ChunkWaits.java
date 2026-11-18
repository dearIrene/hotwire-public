package dev.moon.hotwire.fabric.registration;

import java.lang.reflect.Field;
import java.util.concurrent.Executor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.thread.BlockableEventLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ChunkWaits {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkWaits.class);

    private static final ClassValue<Field> QUEUES = new ClassValue<>() {
        @Override
        protected Field computeValue(Class<?> type) {
            for (Field field : type.getDeclaredFields()) {
                if (BlockableEventLoop.class.isAssignableFrom(field.getType()) && field.trySetAccessible()) {
                    return field;
                }
            }
            throw new IllegalStateException(type.getName() + " has no task queue");
        }
    };

    private ChunkWaits() {
    }

    static boolean isIn(MinecraftServer server) {
        boolean blocked = false;
        for (StackTraceElement frame : server.getRunningThread().getStackTrace()) {
            if (frame.getClassName().equals(BlockableEventLoop.class.getName())) {
                blocked |= frame.getMethodName().startsWith("managedBlock");
            } else if (blocked && frame.getClassName().equals(ServerChunkCache.class.getName())) {
                return true;
            }
        }
        return false;
    }

    static void run(MinecraftServer server, Runnable work) {
        try {
            for (ServerLevel level : server.getAllLevels()) {
                ((Executor) QUEUES.get(ServerChunkCache.class).get(level.getChunkSource())).execute(work);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("[Hotwire] the levels' chunk queues could not be handed the hold: {}", e.toString());
        }
    }
}
