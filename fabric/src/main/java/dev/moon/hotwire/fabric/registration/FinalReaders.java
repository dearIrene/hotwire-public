package dev.moon.hotwire.fabric.registration;

import com.google.common.collect.MapMaker;
import dev.moon.hotwire.fabric.rebuild.ConstantPools;
import dev.moon.hotwire.jvm.LoadedClassList;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class FinalReaders {
    private static final Logger LOGGER = LoggerFactory.getLogger(FinalReaders.class);

    private static final Set<Class<?>> REFUSED = Collections.newSetFromMap(new MapMaker().weakKeys().makeMap());

    private FinalReaders() {
    }

    private static boolean isRead(Class<?> type, ClassLoader game, Instrumentation instrumentation) {
        return Objects.equals(type.getClassLoader(), game) && !type.isHidden() && !type.isArray()
                && instrumentation.isModifiableClass(type);
    }

    static int readAhead(Instrumentation instrumentation, @Nullable ConstantPools pools) {
        if (pools == null) {
            return 0;
        }
        ClassLoader game = FinalReaders.class.getClassLoader();
        int read = 0;
        for (Class<?> type : LoadedClassList.of(instrumentation)) {
            if (isRead(type, game, instrumentation)) {
                pools.namesField(type, Map.of());
                read++;
            }
        }
        return read;
    }

    static void refresh(Instrumentation instrumentation, @Nullable ConstantPools pools, Set<Field> written) {
        if (written.isEmpty()) {
            return;
        }
        long began = System.nanoTime();
        Map<String, Set<String>> fields = new HashMap<>();
        Set<Class<?>> readers = new LinkedHashSet<>();
        for (Field field : written) {
            readers.addAll(Arrays.asList(field.getDeclaringClass().getNestMembers()));

            if (!Modifier.isPrivate(field.getModifiers())) {
                fields.computeIfAbsent(field.getDeclaringClass().getName().replace('.', '/'), owner -> new HashSet<>())
                        .add(field.getName());
            }
        }
        ClassLoader game = FinalReaders.class.getClassLoader();
        if (pools != null && !fields.isEmpty()) {
            readers.addAll(Arrays.<Class<?>>stream(LoadedClassList.of(instrumentation)).parallel()
                    .filter(type -> isRead(type, game, instrumentation) && pools.namesField(type, fields))
                    .toList());
        }
        long found = System.nanoTime();
        readers.removeAll(REFUSED);
        readBack(instrumentation, List.copyOf(readers));
        LOGGER.info("[Hotwire] {} classes reading {} statics made again were read anew in {} ms (found in {} ms)",
                readers.size(), written.size(), (System.nanoTime() - began) / 1_000_000, (found - began) / 1_000_000);
    }

    private static void readBack(Instrumentation instrumentation, List<Class<?>> readers) {
        if (readers.isEmpty()) {
            return;
        }
        try {
            instrumentation.retransformClasses(readers.toArray(Class<?>[]::new));
        } catch (UnmodifiableClassException | RuntimeException | LinkageError e) {
            if (readers.size() > 1) {
                int half = readers.size() / 2;
                readBack(instrumentation, readers.subList(0, half));
                readBack(instrumentation, readers.subList(half, readers.size()));
                return;
            }
            Class<?> reader = readers.get(0);
            REFUSED.add(reader);

            if (e instanceof ClassFormatError && reader.isRecord()) {
                LOGGER.info("[Hotwire] the JVM cannot read {} back: {}", reader.getName(), e.toString());
            } else {
                LOGGER.warn("[Hotwire] {} keeps the old value of a static it reads compiled in: {}", reader.getName(),
                        e.toString());
            }
        }
    }
}
