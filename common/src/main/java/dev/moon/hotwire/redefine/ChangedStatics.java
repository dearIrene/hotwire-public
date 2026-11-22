package dev.moon.hotwire.redefine;

import dev.moon.hotwire.classfile.ClassHeader;
import dev.moon.hotwire.classfile.ClassNodes;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ChangedStatics {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChangedStatics.class);
    private static final String PREFIX = "hotwire$passedStatic$";

    private static final String FRESH_PREFIX = "hotwire$freshStatic$";

    private static final String INNER_PREFIX = "hotwire$innerStatic$";

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
            addInner(now, put, slice);
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
        Map<String, String> recomputed = addAfterMembersOnlyIn(running, now, now, true);
        recomputed.putAll(addAfterMembersOnlyIn(now, running, now, false));
        if (!findSetupHandlerMods(now).equals(findSetupHandlerMods(running))) {
            addEmptied(now, recomputed);
        }
        return recomputed;
    }

    private static Map<String, String> addAfterMembersOnlyIn(ClassNode has, ClassNode lacks, ClassNode now,
            boolean dropped) {
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

        Set<String> written = new HashSet<>();
        for (MethodNode method : has.methods) {
            if (!method.name.equals("<clinit>") || only.isEmpty()) {
                continue;
            }
            boolean after = false;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(has.name)
                        && only.contains(call.name + call.desc)) {
                    if (dropped) {
                        addWrites(has, call, written);
                    }
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
        written.removeIf(key -> made.contains(PREFIX + key.substring(0, key.indexOf(':'))));
        Map<String, String> recomputed = new LinkedHashMap<>();
        if (stored.isEmpty() && written.isEmpty()) {
            return recomputed;
        }

        Map<String, InsnList> slicesOfHas = FieldInitializers.findStaticSlices(has.name, has, stored);
        stored.removeIf(key -> {
            InsnList slice = slicesOfHas.get(key);
            if (slice == null) {
                return true;
            }
            for (AbstractInsnNode insn : slice) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(has.name)
                        && only.contains(call.name + call.desc)) {
                    return false;
                }
            }
            return true;
        });
        written.forEach(key -> stored.add(key.replace(":", "")));
        FieldInitializers.findStaticSlices(now.name, now, stored).forEach((field, slice) -> {
            AbstractInsnNode store = slice.getLast();
            if (!(store instanceof FieldInsnNode put) || put.getOpcode() != Opcodes.PUTSTATIC) {
                return;
            }
            addInner(now, put, slice);
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

    private static void addEmptied(ClassNode now, Map<String, String> recomputed) {
        Set<String> made = new HashSet<>();
        for (MethodNode method : now.methods) {
            made.add(method.name);
        }
        Set<String> own = new HashSet<>();
        for (FieldNode field : now.fields) {
            int privateStatic = Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE;
            if ((field.access & privateStatic) == privateStatic && field.desc.startsWith("Ljava/util/")) {
                own.add(field.name + field.desc);
            }
        }
        List<MethodNode> added = new ArrayList<>();
        for (MethodNode method : now.methods) {
            if (!method.name.equals("<clinit>")) {
                continue;
            }
            for (AbstractInsnNode insn : method.instructions) {
                if (!(insn instanceof FieldInsnNode put) || put.getOpcode() != Opcodes.PUTSTATIC
                        || !put.owner.equals(now.name) || !own.contains(put.name + put.desc)
                        || recomputed.containsKey(put.name) || made.contains(PREFIX + put.name)) {
                    continue;
                }
                AbstractInsnNode init = findReal(put.getPrevious());
                AbstractInsnNode dup = init == null ? null : findReal(init.getPrevious());
                AbstractInsnNode create = dup == null ? null : findReal(dup.getPrevious());
                if (init instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
                        && call.name.equals("<init>") && call.desc.equals("()V") && dup != null
                        && dup.getOpcode() == Opcodes.DUP && create instanceof TypeInsnNode type
                        && type.getOpcode() == Opcodes.NEW && type.desc.equals(call.owner)) {
                    MethodNode making = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC
                            | Opcodes.ACC_SYNTHETIC, PREFIX + put.name, "()" + put.desc, null, null);
                    making.instructions.add(new TypeInsnNode(Opcodes.NEW, type.desc));
                    making.instructions.add(new InsnNode(Opcodes.DUP));
                    making.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, type.desc, "<init>", "()V",
                            false));
                    making.instructions.add(new InsnNode(Opcodes.ARETURN));
                    added.add(making);
                    recomputed.put(put.name, put.desc);
                }
            }
        }
        now.methods.addAll(added);
    }

    private static @Nullable AbstractInsnNode findReal(@Nullable AbstractInsnNode insn) {
        AbstractInsnNode at = insn;
        while (at != null && at.getOpcode() < 0) {
            at = at.getPrevious();
        }
        return at;
    }

    private static void addWrites(ClassNode has, MethodInsnNode call, Set<String> into) {
        for (MethodNode handler : has.methods) {
            if (handler.name.equals(call.name) && handler.desc.equals(call.desc)) {
                for (AbstractInsnNode insn : handler.instructions) {
                    if (insn instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTSTATIC
                            && put.owner.equals(has.name)) {
                        into.add(put.name + ":" + put.desc);
                    }
                }
            }
        }
    }

    static Set<String> findSetupHandlerMods(byte[] bytes) {
        return findSetupHandlerMods(ClassNodes.read(bytes, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES));
    }

    private static Set<String> findSetupHandlerMods(ClassNode node) {
        Set<String> mods = new HashSet<>();
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>")) {
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof MethodInsnNode call && call.owner.equals(node.name)) {
                        String mod = HandlerGuards.findModInHandlerName(call.name);
                        if (mod != null) {
                            mods.add(mod);
                        }
                    }
                }
            }
        }
        return mods;
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

                if (!Modifier.isStatic(field.getModifiers())) {
                    LOGGER.debug("[Hotwire] {}.{} is an instance field; no static to make again", type.getName(),
                            entry.getKey());
                    continue;
                }
                field.setAccessible(true);
                Object old = field.get(null);
                boolean changed = hasMethodOf(side, PREFIX + entry.getKey(), field.getType());
                if (changed && old != null && takeInner(side, field, old, replaced)) {
                    LOGGER.info("[Hotwire] {}.{} keeps its object; what it holds took what the setup the mods give"
                            + " it now made", type.getName(), entry.getKey());
                    continue;
                }

                if (!changed && old != null) {
                    continue;
                }

                if (!InitHookReplay.isGameClass(Type.getInternalName(type)) && old != null
                        && Objects.equals(Tripwire.findOwner(old.getClass()), Tripwire.findOwner(type))) {
                    continue;
                }
                MethodHandle making = side.findStatic(side.lookupClass(), (changed ? PREFIX : FRESH_PREFIX)
                        + entry.getKey(), MethodType.methodType(field.getType()));
                Object made = MethodHandleProxies.asInterfaceInstance(Supplier.class, making).get();
                copyElements(old, made);
                if (takeStateOf(field, old, made, replaced)) {
                    LOGGER.info("[Hotwire] {}.{} ({}) took in place what the setup the mods give it now made, so what"
                            + " holds it sees it too", type.getName(), entry.getKey(), old.getClass().getName());
                    continue;
                }
                writer.write(field, made);
                if (old != null) {
                    ReplacedStatics.noteWritten(field, old, made, writer);
                }
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

    private static void addInner(ClassNode now, FieldInsnNode put, InsnList slice) {
        AbstractInsnNode last = put.getPrevious();
        while (last != null && last.getOpcode() < 0) {
            last = last.getPrevious();
        }
        if (!(last instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC) {
            return;
        }
        Type[] args = Type.getArgumentTypes(call.desc);
        if (args.length != 2 || args[0].getSize() != 1 || args[1].getSort() != Type.OBJECT) {
            return;
        }
        Map<LabelNode, LabelNode> labels = new HashMap<>();
        for (AbstractInsnNode insn : slice) {
            if (insn instanceof LabelNode label) {
                labels.put(label, new LabelNode());
            }
        }
        InsnList copy = new InsnList();
        int end = slice.indexOf(call);
        for (int at = 0; at < end; at++) {
            copy.add(slice.get(at).clone(labels));
        }
        copy.add(new InsnNode(Opcodes.SWAP));
        copy.add(new InsnNode(Opcodes.POP));
        copy.add(new InsnNode(Opcodes.ARETURN));
        MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                INNER_PREFIX + put.name, "()" + args[1].getDescriptor(), null, null);
        method.instructions.add(copy);
        now.methods.add(method);
    }

    private static boolean takeInner(MethodHandles.Lookup side, Field field, Object old,
            IdentityHashMap<Object, Object> replaced) throws ReflectiveOperationException {
        Method inner = null;
        for (Method method : side.lookupClass().getDeclaredMethods()) {
            if (method.getName().equals(INNER_PREFIX + field.getName()) && method.getParameterCount() == 0) {
                inner = method;
            }
        }
        if (inner == null) {
            return false;
        }
        MethodHandle making = side.findStatic(side.lookupClass(), inner.getName(),
                MethodType.methodType(inner.getReturnType()));
        Object made = MethodHandleProxies.asInterfaceInstance(Supplier.class, making).get();
        if (made == null) {
            return false;
        }
        for (Class<?> at = old.getClass(); at != Object.class; at = at.getSuperclass()) {
            for (Field part : at.getDeclaredFields()) {
                if (!Modifier.isStatic(part.getModifiers()) && !part.getType().isPrimitive()
                        && part.trySetAccessible()) {
                    Object inside = part.get(old);
                    if (inside != null && inside.getClass() == made.getClass()) {
                        return takeStateOf(field, inside, made, replaced);
                    }
                }
            }
        }
        return false;
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
    private static boolean takeStateOf(Field holder, @Nullable Object old, @Nullable Object made,
            IdentityHashMap<Object, Object> replaced) throws IllegalAccessException {
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
        Map<Field, Object> before = new LinkedHashMap<>();
        for (Field field : fields) {
            before.put(field, field.get(old));
        }
        IdentityHashMap<Object, Object> parts = new IdentityHashMap<>();
        for (Field field : fields) {
            Object was = before.get(field);
            Object now = field.get(made);
            FinalWrites.write(field, old, now);

            if (was != null && now != null && was != now && Tripwire.isOfOffMod(was.getClass())) {
                replaced.put(was, now);
                parts.put(was, now);
            }
        }
        ReplacedStatics.noteInPlace(holder, old, before, parts);
        return true;
    }

    private static void copyElements(@Nullable Object old, @Nullable Object made) {
        if (old != null && made != null && old.getClass().isArray() && old.getClass() == made.getClass()) {
            System.arraycopy(old, 0, made, 0, Math.min(Array.getLength(old), Array.getLength(made)));
        }
    }
}
