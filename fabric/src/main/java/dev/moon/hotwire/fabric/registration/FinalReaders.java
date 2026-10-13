package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.fabric.rebuild.ConstantPools;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class FinalReaders {
    private static final Logger LOGGER = LoggerFactory.getLogger(FinalReaders.class);

    private FinalReaders() {
    }

    static void refresh(Instrumentation instrumentation, @Nullable ConstantPools pools, Set<Field> written) {
        if (written.isEmpty()) {
            return;
        }
        long began = System.nanoTime();
        Set<String> fields = new HashSet<>();
        Set<Class<?>> readers = new LinkedHashSet<>();
        for (Field field : written) {
            readers.addAll(Arrays.asList(field.getDeclaringClass().getNestMembers()));

            if (!Modifier.isPrivate(field.getModifiers())) {
                fields.add(field.getDeclaringClass().getName().replace('.', '/') + "." + field.getName());
            }
        }
        ClassLoader game = FinalReaders.class.getClassLoader();
        if (pools != null && !fields.isEmpty()) {
            readers.addAll(Arrays.<Class<?>>stream(instrumentation.getAllLoadedClasses()).parallel()
                    .filter(type -> Objects.equals(type.getClassLoader(), game) && !type.isHidden() && !type.isArray()
                            && instrumentation.isModifiableClass(type) && reads(pools, type, fields))
                    .toList());
        }
        try {
            instrumentation.retransformClasses(readers.toArray(Class<?>[]::new));
        } catch (UnmodifiableClassException | RuntimeException | LinkageError e) {
            for (Class<?> reader : readers) {
                try {
                    instrumentation.retransformClasses(reader);
                } catch (UnmodifiableClassException | RuntimeException | LinkageError again) {
                    LOGGER.warn("[Hotwire] {} keeps the old value of a static it reads compiled in: {}",
                            reader.getName(), again.toString());
                }
            }
        }
        LOGGER.info("[Hotwire] {} classes reading {} statics made again were read anew in {} ms", readers.size(),
                written.size(), (System.nanoTime() - began) / 1_000_000);
    }

    private static boolean reads(ConstantPools pools, Class<?> type, Set<String> fields) {
        List<String[]> members = pools.getMembers(type);
        for (String[] member : members) {
            if (fields.contains(member[0] + "." + member[1])) {
                return true;
            }
        }
        return false;
    }
}
