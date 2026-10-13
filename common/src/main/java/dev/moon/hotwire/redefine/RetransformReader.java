package dev.moon.hotwire.redefine;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedSet;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class RetransformReader {
    private static final Logger LOGGER = LoggerFactory.getLogger(RetransformReader.class);

    private RetransformReader() {
    }

    static void capture(Instrumentation instrumentation, Map<String, Class<?>> byName, Map<String, byte[]> current,
            Map<String, String> refused) throws RebuildException {
        Thread reader = Thread.currentThread();
        List<String> handed = new ArrayList<>();
        ClassFileTransformer capture = new ClassFileTransformer() {
            @Override
            public byte @Nullable [] transform(@Nullable ClassLoader loader, @Nullable String name,
                    @Nullable Class<?> redefining, @Nullable ProtectionDomain domain, byte[] bytes) {
                if (name != null && redefining != null && byName.get(name) == redefining
                        && Thread.currentThread().equals(reader)) {
                    current.put(name, bytes.clone());
                    handed.add(name);
                }
                return null;
            }
        };
        instrumentation.addTransformer(capture, true);
        try {
            SequencedSet<String> left = new LinkedHashSet<>(byName.keySet());
            int batches = 0;
            for (Throwable refusal = findRefusal(instrumentation, left, byName, handed); refusal != null;
                    refusal = findRefusal(instrumentation, left, byName, handed)) {
                batches++;
                if (handed.isEmpty() && left.size() > 1) {
                    int before = left.size();
                    batches += findRefusedAlone(instrumentation, List.copyOf(left), byName, handed, current, refused);
                    left.removeIf(name -> current.containsKey(name) || refused.containsKey(name));
                    handed.clear();
                    if (left.size() < before) {
                        continue;
                    }
                }
                String last = handed.isEmpty() ? left.getFirst() : handed.getLast();
                handed.forEach(left::remove);
                left.remove(last);
                Throwable alone = handed.isEmpty() ? refusal
                        : findRefusal(instrumentation, List.of(last), byName, handed);
                if (alone != null) {
                    RebuildEvidence.dumpReadBack(last, current.remove(last));
                    refused.put(last, alone + "; modifiable: " + instrumentation.isModifiableClass(byName.get(last)));
                }
            }
            if (batches > 0) {
                LOGGER.info("[Hotwire] the JVM read {} classes back in {} batches, refusing {}", byName.size(),
                        batches + 1, refused.size());
            }
        } catch (UnmodifiableClassException | UnsupportedOperationException e) {
            throw new RebuildException("could not read the loaded bytes of " + byName.keySet() + ": " + e, e);
        } finally {
            instrumentation.removeTransformer(capture);
        }
    }

    private static int findRefusedAlone(Instrumentation instrumentation, List<String> names,
            Map<String, Class<?>> byName, List<String> handed, Map<String, byte[]> current,
            Map<String, String> refused) throws UnmodifiableClassException {
        int asked = 0;
        int half = names.size() / 2;
        for (List<String> part : List.of(names.subList(0, half), names.subList(half, names.size()))) {
            asked++;
            Throwable refusal = findRefusal(instrumentation, part, byName, handed);
            if (refusal == null) {
                continue;
            }
            if (part.size() == 1) {
                current.remove(part.getFirst());
                refused.put(part.getFirst(), refusal + "; modifiable: "
                        + instrumentation.isModifiableClass(byName.get(part.getFirst())));
            } else if (handed.isEmpty()) {
                asked += findRefusedAlone(instrumentation, part, byName, handed, current, refused);
            } else {
                handed.forEach(current::remove);
            }
        }
        return asked;
    }

    private static @Nullable Throwable findRefusal(Instrumentation instrumentation, Collection<String> names,
            Map<String, Class<?>> byName, List<String> handed) throws UnmodifiableClassException {
        handed.clear();
        if (names.isEmpty()) {
            return null;
        }
        try {
            Class<?>[] types = new Class<?>[names.size()];
            int at = 0;
            for (String name : names) {
                types[at++] = Objects.requireNonNull(byName.get(name));
            }
            instrumentation.retransformClasses(types);
            return null;
        } catch (InternalError | LinkageError e) {
            return e;
        }
    }
}
