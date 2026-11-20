package dev.moon.hotwire.fabric.version;

import dev.moon.hotwire.fabric.mixin.core.client.color.block.BlockColorsAccessor;
import dev.moon.hotwire.registration.Owners;
import dev.moon.hotwire.seam.GameTables;
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
//? if <26.1 {
/*import dev.moon.hotwire.fabric.mixin.core.core.IdMapperAccessor;
*///?}

public final class GameColors {
    private GameColors() {
    }

    public static final GameTables TABLES = new GameTables() {
        @Override
        public @Nullable Object findGameDefault(Object container, Object place) {
            return findDefault(container, place);
        }

        @Override
        public boolean isDrawnIntoChunks(Object container) {
            return isTable(container);
        }
    };

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
                if (value instanceof Collection<?> || value instanceof Map<?, ?>) {
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

    @SuppressWarnings("ReferenceEquality")
    public static @Nullable Object findDefault(Object container, Object place) {
        BlockColors running = Minecraft.getInstance().getBlockColors();
        if (running == null) {
            return null;
        }
        //? if >=26.1 {
        if (container != ((BlockColorsAccessor) running).hotwire$getColors()) {
            return null;
        }
        BlockColors fresh = makeDefault();
        List<?> made = fresh == null ? null : ((BlockColorsAccessor) fresh).hotwire$getColors().get(place);
        return made != null && made.stream().allMatch(one -> Owners.isGameOwn(one.getClass())) ? made
                : null;
        //?} else {
        /*if (container != ((IdMapperAccessor) ((BlockColorsAccessor) running).hotwire$getColors()).hotwire$getById()
                || !(place instanceof Integer number)) {
            return null;
        }
        BlockColors fresh = makeDefault();
        Object made = fresh == null ? null : ((BlockColorsAccessor) fresh).hotwire$getColors().byId(number);
        return made != null && Owners.isGameOwn(made.getClass()) ? made : null;
        *///?}
    }
}
