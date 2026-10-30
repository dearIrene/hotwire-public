package dev.moon.hotwire.redefine;

import dev.moon.hotwire.classfile.ClassHeader;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ChangedStatics {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChangedStatics.class);
    private static final String PREFIX = "hotwire$passedStatic$";

    private static final String FRESH_PREFIX = "hotwire$freshStatic$";

    private static final Pattern MERGED = Pattern.compile("^[A-Za-z]+\\$(?!lambda\\$)[a-z]{3}[0-9a-f]{3}\\$");

    private ChangedStatics() {
    }

    static String makeKey(String member) {
        return MERGED.matcher(member).replaceFirst("");
    }

    static Map<String, String> add(ClassNode now, Set<String> fresh) {
        Map<String, String> recomputed = new LinkedHashMap<>();
        Set<String> stored = new HashSet<>();
        for (MethodNode method : now.methods) {
            if (!method.name.equals("<clinit>")) {
                continue;
            }
            boolean after = false;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(now.name)
                        && fresh.contains(makeKey(call.name) + call.desc)) {
                    after = true;
                } else if (after && insn instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTSTATIC
                        && put.owner.equals(now.name)) {
                    stored.add(put.name + put.desc);
                    after = false;
                }
            }
        }
        if (stored.isEmpty()) {
            return recomputed;
        }
        FieldInitializers.findStaticSlices(now.name, now, stored).forEach((field, slice) -> {
            if (!callsAny(slice, now.name, fresh)) {
                return;
            }
            AbstractInsnNode store = slice.getLast();
            if (!(store instanceof FieldInsnNode put) || put.getOpcode() != Opcodes.PUTSTATIC) {
                return;
            }
            slice.set(store, new InsnNode(Type.getType(put.desc).getOpcode(Opcodes.IRETURN)));
            MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                    PREFIX + put.name, "()" + put.desc, null, null);
            method.instructions.add(slice);
            now.methods.add(method);
            recomputed.put(put.name, put.desc);
        });
        return recomputed;
    }

    static Map<String, String> addSwitched(ClassNode now, ClassNode running) {
        Map<String, String> recomputed = addAfterMembersOnlyIn(running, now, now);
        recomputed.putAll(addAfterMembersOnlyIn(now, running, now));
        return recomputed;
    }

    private static Map<String, String> addAfterMembersOnlyIn(ClassNode has, ClassNode lacks, ClassNode now) {
        Set<String> standing = new HashSet<>();
        for (MethodNode method : lacks.methods) {
            standing.add(makeKey(method.name) + method.desc);
        }
        Set<String> only = new HashSet<>();
        for (MethodNode method : has.methods) {
            String key = makeKey(method.name) + method.desc;
            if (!key.equals(method.name + method.desc) && !standing.contains(key)) {
                only.add(method.name + method.desc);
            }
        }
        Set<String> made = new HashSet<>();
        for (MethodNode method : now.methods) {
            made.add(method.name);
        }
        Set<String> stored = new HashSet<>();
        for (MethodNode method : has.methods) {
            if (!method.name.equals("<clinit>") || only.isEmpty()) {
                continue;
            }
            boolean after = false;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(has.name)
                        && only.contains(call.name + call.desc)) {
                    after = true;
                } else if (after && insn instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTSTATIC
                        && put.owner.equals(has.name)) {
                    if (!made.contains(PREFIX + put.name)) {
                        stored.add(put.name + put.desc);
                    }
                    after = false;
                }
            }
        }
        Map<String, String> recomputed = new LinkedHashMap<>();
        if (stored.isEmpty()) {
            return recomputed;
        }
        FieldInitializers.findStaticSlices(now.name, now, stored).forEach((field, slice) -> {
            AbstractInsnNode store = slice.getLast();
            if (!(store instanceof FieldInsnNode put) || put.getOpcode() != Opcodes.PUTSTATIC) {
                return;
            }
            slice.set(store, new InsnNode(Type.getType(put.desc).getOpcode(Opcodes.IRETURN)));
            MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                    PREFIX + put.name, "()" + put.desc, null, null);
            method.instructions.add(slice);
            now.methods.add(method);
            recomputed.put(put.name, put.desc);
        });
        return recomputed;
    }

    static boolean hasAddedObjectStatics(byte[] mixed, byte[] first, byte[] running) {
        Set<String> added = readObjectStatics(mixed);
        added.removeAll(readObjectStatics(first));
        added.retainAll(readObjectStatics(running));
        return !added.isEmpty();
    }

    private static Set<String> readObjectStatics(byte[] bytes) {
        Set<String> found = new HashSet<>();
        for (ClassHeader.Member field : ClassHeader.of(bytes).fields()) {
            if (field.isStatic() && (field.desc().startsWith("L") || field.desc().startsWith("["))) {
                found.add(field.name() + field.desc());
            }
        }
        return found;
    }

    static Map<String, String> addMadeFresh(ClassNode now, ClassNode first, ClassNode running) {
        Set<String> had = new HashSet<>();
        for (FieldNode field : first.fields) {
            had.add(field.name + field.desc);
        }
        Set<String> runs = new HashSet<>();
        for (FieldNode field : running.fields) {
            runs.add(field.name + field.desc);
        }
        Set<String> made = new HashSet<>();
        for (MethodNode method : now.methods) {
            made.add(method.name);
        }
        Set<String> fresh = new LinkedHashSet<>();
        for (FieldNode field : now.fields) {
            if ((field.access & Opcodes.ACC_STATIC) != 0 && (field.desc.startsWith("L") || field.desc.startsWith("["))
                    && !had.contains(field.name + field.desc) && runs.contains(field.name + field.desc)
                    && !made.contains(PREFIX + field.name)
                    && !made.contains(FRESH_PREFIX + field.name)) {
                fresh.add(field.name + field.desc);
            }
        }
        Map<String, String> recomputed = new LinkedHashMap<>();
        if (fresh.isEmpty()) {
            return recomputed;
        }
        FieldInitializers.findStaticSlices(now.name, now, fresh).forEach((field, slice) -> {
            AbstractInsnNode store = slice.getLast();
            if (!(store instanceof FieldInsnNode put) || put.getOpcode() != Opcodes.PUTSTATIC) {
                return;
            }
            slice.set(store, new InsnNode(Opcodes.ARETURN));
            MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                    FRESH_PREFIX + put.name, "()" + put.desc, null, null);
            method.instructions.add(slice);
            now.methods.add(method);
            recomputed.put(put.name, put.desc);
        });
        return recomputed;
    }

    private static boolean callsAny(InsnList slice, String owner, Set<String> fresh) {
        for (AbstractInsnNode insn : slice) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(owner)
                    && fresh.contains(makeKey(call.name) + call.desc)) {
                return true;
            }
        }
        return false;
    }

    static int run(Class<?> type, Map<String, String> statics, InitHookReplay.StaticWriter writer) {
        if (statics.isEmpty()) {
            return 0;
        }
        MethodHandles.Lookup side = Linker.findCurrentLookup(type);
        if (side == null) {
            LOGGER.error("[Hotwire] {} has no sidecar to make {} again", type.getName(), statics.keySet());
            return statics.size();
        }
        int failed = 0;
        IdentityHashMap<Object, Object> replaced = new IdentityHashMap<>();
        for (Map.Entry<String, String> entry : statics.entrySet()) {
            try {
                Field field = type.getDeclaredField(entry.getKey());
                field.setAccessible(true);
                Object old = field.get(null);
                boolean changed = hasMethodOf(side, PREFIX + entry.getKey(), field.getType());

                if (!changed && old != null) {
                    continue;
                }
                MethodHandle making = side.findStatic(side.lookupClass(), (changed ? PREFIX : FRESH_PREFIX)
                        + entry.getKey(), MethodType.methodType(field.getType()));
                Object made = MethodHandleProxies.asInterfaceInstance(Supplier.class, making).get();
                copyElements(old, made);
                ReplacedStatics.noteRecomputed(type);
                if (takeStateOf(old, made)) {
                    LOGGER.info("[Hotwire] {}.{} took in place what the setup the mods give it now made, so what"
                            + " holds it sees it too", type.getName(), entry.getKey());
                    continue;
                }
                writer.write(field, made);
                if (old != null && made != null) {
                    replaced.put(old, made);
                }
                LOGGER.info("[Hotwire] {}.{} was made again by the setup the mods give it now{}", type.getName(),
                        entry.getKey(), Modifier.isFinal(field.getModifiers()) ? " (a final)" : "");
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                failed++;
                LOGGER.error("[Hotwire] {}.{} could not be made again by its new setup", type.getName(),
                        entry.getKey(), e);
            }
        }
        ReplacedStatics.swap(replaced);
        return failed;
    }

    private static boolean hasMethodOf(MethodHandles.Lookup side, String name, Class<?> valueType) {
        for (Method method : side.lookupClass().getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getReturnType() == valueType
                    && method.getParameterCount() == 0) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("ReferenceEquality")
    private static boolean takeStateOf(@Nullable Object old, @Nullable Object made) throws IllegalAccessException {
        if (old == null || made == null || old == made || old.getClass() != made.getClass()
                || old.getClass().isArray() || old.getClass().isHidden()) {
            return false;
        }
        List<Field> fields = new ArrayList<>();
        for (Class<?> at = old.getClass(); at != Object.class; at = at.getSuperclass()) {
            if (at.getName().startsWith("java.") || at.getName().startsWith("jdk.")) {
                return false;
            }
            for (Field field : at.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    if (!field.trySetAccessible()) {
                        return false;
                    }
                    fields.add(field);
                }
            }
        }
        for (Field field : fields) {
            FinalWrites.write(field, old, field.get(made));
        }
        return true;
    }

    private static void copyElements(@Nullable Object old, @Nullable Object made) {
        if (old != null && made != null && old.getClass().isArray() && old.getClass() == made.getClass()) {
            System.arraycopy(old, 0, made, 0, Math.min(Array.getLength(old), Array.getLength(made)));
        }
    }
}
