package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldHolders;
import dev.moon.hotwire.redefine.OffWindowObjects;
import java.lang.ref.WeakReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ClosedWorldHolders {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClosedWorldHolders.class);
    private static WeakReference<@Nullable ClientLevel> last = new WeakReference<>(null);

    private ClosedWorldHolders() {
    }

    public static void tick(Minecraft game) {
        if (last.refersTo(game.level)) {
            return;
        }
        last = new WeakReference<>(game.level);
        int gone = FieldHolders.forgetInstances(ClosedWorldHolders::isOfClosedWorld);
        if (gone > 0) {
            LOGGER.debug("[Hotwire] {} objects of a closed world let go of with their added fields", gone);
        }
    }

    private static boolean isOfClosedWorld(Object made) {
        return switch (made) {
            case BlockEntity block -> block.getLevel() != null && OffWindowObjects.isOfClosedWorld(block.getLevel());
            case LevelChunk chunk -> OffWindowObjects.isOfClosedWorld(chunk.getLevel());
            default -> OffWindowObjects.isOfClosedWorld(made);
        };
    }
}
