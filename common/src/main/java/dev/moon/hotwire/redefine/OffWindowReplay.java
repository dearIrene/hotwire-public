package dev.moon.hotwire.redefine;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OffWindowReplay {
    private static final Logger LOGGER = LoggerFactory.getLogger(OffWindowReplay.class);
    static final String METHOD = "hotwire$replayMade";
    private static final String RECORD = OffWindowObjects.class.getName().replace('.', '/');

    static final class Addition {
        private final byte[] bytes;
        private final Set<String> fields;

        Addition(byte[] bytes, Set<String> fields) {
            this.bytes = bytes;
            this.fields = Set.copyOf(fields);
        }

        byte[] getBytes() {
            return this.bytes;
        }

        Set<String> getFields() {
            return this.fields;
        }
    }

    private OffWindowReplay() {
    }

    static Addition add(String name, byte[] mixed, byte[] running, byte @Nullable [] original, boolean handlers) {
        OffWindowObjects.markRecording(name, isRecording(mixed));
        if (original == null || OffWindowObjects.countAlive(name) == 0) {
            return new Addition(mixed, Set.of());
        }
        ClassNode now = RetainedInitializers.read(mixed);
        Set<String> own = new HashSet<>();
        RetainedInitializers.read(original).fields.forEach(field -> own.add(field.name + field.desc));
        Set<String> setNow = ConstructedFields.of(mixed);
        Set<String> setThen = ConstructedFields.of(running);
        Map<String, String> lost = new HashMap<>();

        boolean record = "java/lang/Record".equals(now.superName);
        now.fields.forEach(field -> {
            if ((field.access & Opcodes.ACC_STATIC) == 0 && setNow.contains(field.name)
                    && !setThen.contains(field.name) && !own.contains(field.name + field.desc)
                    && !(record && (field.access & Opcodes.ACC_FINAL) != 0)) {
                lost.put(field.name + field.desc, field.name);
            }
        });
        Map<String, InsnList> slices = new LinkedHashMap<>(lost.isEmpty() ? Map.of()
                : FieldInitializers.findInstanceSlicesBranching(now.name, now, lost.keySet()));

        Set<String> called = new HashSet<>();
        for (String field : lost.keySet()) {
            HandlerReplay.Call call = slices.containsKey(field) || !handlers ? null
                    : HandlerReplay.of(now, field, lost.keySet());
            if (call != null) {
                slices.put(field, called.add(call.handler()) ? call.insns() : new InsnList());
            }
        }
        if (slices.size() < lost.size()) {
            Set<String> missing = new TreeSet<>();
            lost.forEach((key, field) -> {
                if (!slices.containsKey(key)) {
                    missing.add(field);
                }
            });
            LOGGER.info("[Hotwire] {} objects of {} made while their mod was off cannot be given {} again; the"
                    + " live-object check weighs them", OffWindowObjects.countAlive(name), name, missing);
        }
        if (slices.isEmpty()) {
            return new Addition(mixed, Set.of());
        }
        MethodNode replay = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC, METHOD, "()V", null, null);
        Set<String> fields = new TreeSet<>();
        slices.forEach((field, slice) -> {
            replay.instructions.add(slice);
            fields.add(lost.get(field));
        });
        replay.instructions.add(new InsnNode(Opcodes.RETURN));
        now.methods.add(replay);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        now.accept(writer);
        return new Addition(writer.toByteArray(), fields);
    }

    static boolean isRecording(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1).contains(RECORD);
    }

    @SuppressWarnings("unchecked")
    private static Consumer<Object> asConsumer(MethodHandle method) {
        return MethodHandleProxies.asInterfaceInstance(Consumer.class, method);
    }

    public static int run(Class<?> type, Set<String> fields) {
        String name = type.getName().replace('.', '/');
        MethodHandles.Lookup side = Linker.findCurrentLookup(type);
        List<Object> live = OffWindowObjects.getLive(name);
        if (side == null || live.isEmpty()) {
            OffWindowObjects.forget(name);
            return 0;
        }
        Consumer<Object> replay;
        try {
            MethodHandle method = side.findStatic(side.lookupClass(), METHOD, MethodType.methodType(void.class, type));
            replay = asConsumer(method.asType(MethodType.methodType(void.class, Object.class)));
        } catch (ReflectiveOperationException e) {
            LOGGER.error("[Hotwire] {} has no replay of {} in its sidecar", name, fields, e);
            return live.size();
        }
        int failed = 0;
        for (Object made : live) {
            try {
                replay.accept(made);
            } catch (RuntimeException | LinkageError e) {
                if (failed++ == 0) {
                    LOGGER.error("[Hotwire] setting up {} again on {} made while its mod was off failed", fields,
                            name, e);
                }
            }
        }
        if (failed == 0) {
            OffWindowObjects.forget(name);
        }
        LOGGER.info("[Hotwire] {} of {} objects of {} made while their mod was off got {}", live.size() - failed,
                live.size(), name, fields);
        return failed;
    }
}
