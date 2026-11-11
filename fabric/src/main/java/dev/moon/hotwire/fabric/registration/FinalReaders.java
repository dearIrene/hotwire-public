package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.fabric.rebuild.ConstantPools;
import dev.moon.hotwire.jvm.LoadedClassList;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class FinalReaders {
    private static final Logger LOGGER = LoggerFactory.getLogger(FinalReaders.class);

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
        try {
            instrumentation.retransformClasses(readers.toArray(Class<?>[]::new));
        } catch (UnmodifiableClassException | RuntimeException | LinkageError e) {
            for (Class<?> reader : readers) {
                try {
                    instrumentation.retransformClasses(reader);
                } catch (UnmodifiableClassException | RuntimeException | LinkageError again) {
                    if (again instanceof ClassFormatError && reader.isRecord()) {
                        LOGGER.info("[Hotwire] the JVM cannot read {} back: {}", reader.getName(), again.toString());
                    } else {
                        LOGGER.warn("[Hotwire] {} keeps the old value of a static it reads compiled in: {}",
                                reader.getName(), again.toString());
                    }
                }
            }
        }
        LOGGER.info("[Hotwire] {} classes reading {} statics made again were read anew in {} ms", readers.size(),
                written.size(), (System.nanoTime() - began) / 1_000_000);
    }
}
