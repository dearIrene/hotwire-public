package dev.moon.hotwire.fabric.version;

import dev.moon.hotwire.fabric.mixin.core.client.color.block.BlockColorsAccessor;
import dev.moon.hotwire.registration.Owners;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import org.jspecify.annotations.Nullable;
//? if >=26.1 {
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.world.level.block.Block;
//?} else {
/*import dev.moon.hotwire.fabric.mixin.core.core.IdMapperAccessor;
*///?}

public final class GameColors {
    private GameColors() {
    }

    private static final ThreadLocal<BlockColors @Nullable []> MAKING = new ThreadLocal<>();

    public static void noteMade(BlockColors made) {
        BlockColors[] into = MAKING.get();
        if (into != null) {
            into[0] = made;
        }
    }

    private static @Nullable BlockColors makeDefault() {
        BlockColors[] made = new BlockColors[1];
        MAKING.set(made);
        try {
            return BlockColors.createDefault();
        } catch (IllegalStateException e) {
            return made[0];
        } finally {
            MAKING.remove();
        }
    }

    private static @Nullable Object foundIn;
    private static Set<Object> tables = Set.of();

    @SuppressWarnings("ReferenceEquality")
    public static synchronized boolean isTable(Object container) {
        BlockColors running = Minecraft.getInstance().getBlockColors();
        if (running == null) {
            return false;
        }
        if (foundIn != running) {
            Set<Object> found = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Object value : readFields(running)) {
                if (value instanceof Map<?, ?> map) {
                    found.add(value);

                    map.values().stream().filter(one -> one instanceof List<?> || one instanceof Object[])
                            .forEach(found::add);
                } else if (value instanceof Collection<?>) {
                    found.add(value);
                } else if (value != null) {
                    readFields(value).stream().filter(one -> one instanceof Collection<?> || one instanceof Map<?, ?>)
                            .forEach(found::add);
                }
            }
            tables = found;
            foundIn = running;
        }
        return tables.contains(container);
    }

    private static List<@Nullable Object> readFields(Object holder) {
        List<@Nullable Object> values = new ArrayList<>();
        for (Field field : holder.getClass().getDeclaredFields()) {
            boolean readable = !Modifier.isStatic(field.getModifiers()) && !field.getType().isPrimitive();
            if (readable && field.trySetAccessible()) {
                try {
                    values.add(field.get(holder));
                } catch (IllegalAccessException e) {
                }
            }
        }
        return values;
    }

    private static @Nullable BlockColors fresh;

    @SuppressWarnings("ReferenceEquality")
    public static synchronized @Nullable Object findDefault(Object container, Object place) {
        BlockColors running = Minecraft.getInstance().getBlockColors();
        if (running == null || !isTable(container)) {
            return null;
        }
        for (int tries = 0; tries < 2; tries++) {
            BlockColors made = fresh;
            if (made == null || tries > 0) {
                made = makeDefault();
                fresh = made;
            }
            Object found = made == null ? null : findIn(running, made, container, place);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @SuppressWarnings("ReferenceEquality")
    private static @Nullable Object findIn(BlockColors running, BlockColors made, Object container, Object place) {
        //? if >=26.1 {
        Map<Block, List<BlockTintSource>> colors = ((BlockColorsAccessor) running).hotwire$getColors();
        Map<Block, List<BlockTintSource>> fromGame = ((BlockColorsAccessor) made).hotwire$getColors();
        if (container == colors) {
            List<?> own = fromGame.get(place);
            return own != null && own.stream().allMatch(one -> Owners.isGameOwn(one.getClass())) ? own : null;
        }

        if (!(place instanceof Integer number)) {
            return null;
        }
        for (Object table : tables) {
            if (!(table instanceof Map<?, ?> map)) {
                continue;
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getValue() == container && entry.getKey() instanceof Block block) {
                    List<?> own = fromGame.get(block);
                    Object one = own == null || number >= own.size() ? null : own.get(number);
                    return one != null && Owners.isGameOwn(one.getClass()) ? one : null;
                }
            }
        }
        return null;
        //?} else {
        /*Object byNumber = ((IdMapperAccessor) ((BlockColorsAccessor) running).hotwire$getColors()).hotwire$getById();
        if (container != byNumber || !(place instanceof Integer number)) {
            return null;
        }
        Object one = ((BlockColorsAccessor) made).hotwire$getColors().byId(number);
        return one != null && Owners.isGameOwn(one.getClass()) ? one : null;
        *///?}
    }
}
