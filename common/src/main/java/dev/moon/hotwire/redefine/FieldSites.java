package dev.moon.hotwire.redefine;

import dev.moon.hotwire.classfile.ClassHeader;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class FieldSites {
    static final String INIT = "$hotwire$init";
    private static final Logger LOGGER = LoggerFactory.getLogger(FieldSites.class);

    private static final Map<String, List<Init>> INITS = new ConcurrentHashMap<>();

    private static final Map<String, Set<String>> UNSITED = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> SITED = new ConcurrentHashMap<>();

    private record Init(String className, MethodHandle setUp) {
    }

    private FieldSites() {
    }

    static void make(String internalName, ClassNode node, Set<String> merged, Supplier<@Nullable ClassNode> shipped,
            Set<String> guarded) {
        ClassNode original = shipped.get();
        if (merged.isEmpty() || original == null) {
            return;
        }
        String dumpFor = System.getProperty("hotwire_field_sites_dump");
        if (dumpFor != null && internalName.equals(dumpFor)) {
            try {
                org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
                node.accept(writer);
                java.nio.file.Path file = java.nio.file.Path.of("hotwire", "dump", internalName.replace('/', '.') + ".fs.class");
                java.nio.file.Files.createDirectories(file.getParent());
                java.nio.file.Files.write(file, writer.toByteArray());
                byte[] shippedBytes = HandlerGuards.originals.apply(internalName);
                if (shippedBytes != null) {
                    java.nio.file.Files.write(file.resolveSibling(internalName.replace('/', '.') + ".shipped.class"), shippedBytes);
                }
                LOGGER.info("[Hotwire] field sites wrote {} and skip it", file);
            } catch (java.io.IOException e) {
                LOGGER.warn("[Hotwire] field sites could not write {}", internalName, e);
            }
            return;
        }
        Set<String> own = new HashSet<>();
        original.fields.forEach(field -> own.add(field.name + field.desc));

        Set<String> stored = new HashSet<>();
        for (MethodNode method : node.methods) {
            if (!method.name.equals("<init>")) {
                continue;
            }
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode store && store.getOpcode() == Opcodes.PUTFIELD
                        && store.owner.equals(internalName)) {
                    stored.add(store.name + store.desc);
                }
            }
        }
        Map<String, String> added = new HashMap<>();
        for (FieldNode field : node.fields) {
            if ((field.access & Opcodes.ACC_STATIC) == 0 && !own.contains(field.name + field.desc)
                    && stored.contains(field.name + field.desc)) {
                added.put(field.name + field.desc, field.name);
            }
        }
        if (added.isEmpty()) {
            return;
        }
        Set<String> methods = new HashSet<>();
        node.methods.forEach(method -> methods.add(method.name + method.desc));
        Map<String, String> modOfMixin = new HashMap<>();
        for (String mixin : merged) {
            String mod = TripwireSites.findModOfClass(mixin.replace('.', '/'));
            if (mod != null) {
                modOfMixin.put(mixin, mod);
            }
        }
        Map<String, InsnList> slices = FieldInitializers.findInstanceSlices(internalName, node, added.keySet());
        Set<String> unsited = new HashSet<>();
        List<String> why = new ArrayList<>();
        Set<String> sitedMixins = new HashSet<>();
        List<String> sited = new ArrayList<>();
        for (Map.Entry<String, String> field : added.entrySet()) {
            InsnList slice = slices.get(field.getKey());
            if (slice == null) {
                unsited.addAll(merged);
                why.add(field.getValue() + ": no plain setup");
                continue;
            }
            if (RetainedInitializers.isPure(slice, internalName, methods)) {
                continue;
            }
            String mixin = findDeclaring(field.getKey(), merged);
            if (mixin == null) {
                mixin = findMixin(slice, internalName, modOfMixin);
            }
            if (mixin == null) {
                unsited.addAll(merged);
                why.add(field.getValue() + ": no mixin of the mod");
                continue;
            }
            if (!moveToMethod(internalName, node, field.getKey(), field.getValue(), slice, mixin)) {
                unsited.add(mixin);
                why.add(field.getValue() + ": " + lastRefusal);
                continue;
            }
            guarded.add(mixin);
            sitedMixins.add(mixin);
            sited.add(field.getValue());
        }
        sitedMixins.removeAll(unsited);
        if (unsited.isEmpty()) {
            UNSITED.remove(internalName);
        } else {
            UNSITED.put(internalName, Set.copyOf(unsited));
            LOGGER.info("[Hotwire] {} sets up fields of {} that no site switches: it rebuilds for them ({})",
                    internalName, unsited, why);
        }
        if (sitedMixins.isEmpty()) {
            SITED.remove(internalName);
        } else {
            SITED.put(internalName, Set.copyOf(sitedMixins));
            LOGGER.info("[Hotwire] {} sets up {} through sites", internalName, sited);
        }
    }

    static boolean isSitedFor(String internalName, Set<String> owns) {
        Set<String> sitedHere = SITED.getOrDefault(internalName, Set.of());
        Set<String> unsitedHere = UNSITED.getOrDefault(internalName, Set.of());
        boolean any = false;
        for (String mixin : sitedHere) {
            any |= owns.contains(mixin.replace('.', '/'));
        }
        for (String mixin : unsitedHere) {
            if (owns.contains(mixin.replace('.', '/'))) {
                return false;
            }
        }
        return any;
    }

    private static final Map<String, Set<String>> MIXIN_FIELDS = new ConcurrentHashMap<>();

    private static @Nullable String findDeclaring(String key, Set<String> merged) {
        for (String mixin : merged) {
            Set<String> fields = MIXIN_FIELDS.computeIfAbsent(mixin, FieldSites::readFields);
            if (fields.contains(key)) {
                return mixin;
            }
        }
        return null;
    }

    private static Set<String> readFields(String mixin) {
        byte[] bytes = HandlerGuards.originals.apply(mixin.replace('.', '/'));
        if (bytes == null) {
            return Set.of();
        }
        Set<String> fields = new HashSet<>();
        for (ClassHeader.Member field : ClassHeader.of(bytes).fields()) {
            fields.add(field.name() + field.desc());
        }
        return Set.copyOf(fields);
    }

    private static @Nullable String findMixin(InsnList slice, String self, Map<String, String> modOfMixin) {
        for (AbstractInsnNode insn : slice) {
            String owner = switch (insn) {
                case MethodInsnNode call -> call.owner;
                case FieldInsnNode field -> field.owner;
                case TypeInsnNode type -> type.desc;
                default -> null;
            };
            if (owner == null || owner.equals(self) || owner.startsWith("[")) {
                continue;
            }
            String mod = TripwireSites.findModOfClass(owner);
            if (mod == null) {
                continue;
            }
            for (Map.Entry<String, String> mixin : modOfMixin.entrySet()) {
                if (mixin.getValue().equals(mod)) {
                    return mixin.getKey();
                }
            }
        }
        return null;
    }

    private static String lastRefusal = "";

    @SuppressWarnings("ReferenceEquality")
    private static boolean moveToMethod(String internalName, ClassNode node, String key, String name,
            InsnList slice, String mixin) {
        List<AbstractInsnNode> sliceInsns = realInsns(slice);
        lastRefusal = "";

        for (AbstractInsnNode insn : sliceInsns) {
            if ((insn instanceof VarInsnNode local && (local.var != 0 || local.getOpcode() != Opcodes.ALOAD))
                    || insn instanceof IincInsnNode) {
                lastRefusal = "the setup uses a local";
                return false;
            }
        }
        List<AbstractInsnNode[]> ranges = new ArrayList<>();
        List<MethodNode> constructors = new ArrayList<>();
        for (MethodNode method : node.methods) {
            if (!method.name.equals("<init>")) {
                continue;
            }
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof FieldInsnNode store) || store.getOpcode() != Opcodes.PUTFIELD
                        || !store.owner.equals(internalName) || !(store.name + store.desc).equals(key)) {
                    continue;
                }
                AbstractInsnNode start = store;
                for (int left = sliceInsns.size() - 1; left > 0; left--) {
                    start = start.getPrevious();
                    while (start != null && !isReal(start)) {
                        start = start.getPrevious();
                    }
                    if (start == null) {
                        lastRefusal = "the constructor's store does not read as the setup";
                        return false;
                    }
                }
                if (!(start instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != 0
                        || !sameOpcodes(start, store, sliceInsns)) {
                    lastRefusal = "the constructor's store does not read as the setup";
                    return false;
                }
                ranges.add(new AbstractInsnNode[] {start, store});
                constructors.add(method);
            }
        }
        if (ranges.isEmpty()) {
            lastRefusal = "no constructor stores it";
            return false;
        }
        MethodNode setUp = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC, name + INIT, "()V", null,
                null);
        for (AbstractInsnNode insn : sliceInsns) {
            setUp.instructions.add(insn.clone(Map.of()));
        }
        setUp.instructions.add(new InsnNode(Opcodes.RETURN));

        setUp.maxLocals = 1;
        setUp.maxStack = constructors.getFirst().maxStack;
        node.methods.add(setUp);
        for (int at = 0; at < ranges.size(); at++) {
            MethodNode method = constructors.get(at);
            AbstractInsnNode start = ranges.get(at)[0];
            AbstractInsnNode end = ranges.get(at)[1];
            InsnList call = new InsnList();
            call.add(new VarInsnNode(Opcodes.ALOAD, 0));
            call.add(new InvokeDynamicInsnNode(name + INIT, "(L" + internalName + ";)V", HandlerGuards.BOOTSTRAP_FIELD,
                    mixin));
            method.instructions.insertBefore(start, call);
            AbstractInsnNode insn = start;
            while (insn != null) {
                AbstractInsnNode next = insn.getNext();
                method.instructions.remove(insn);
                if (insn == end) {
                    break;
                }
                insn = next;
            }
        }
        for (FieldNode field : node.fields) {
            if ((field.name + field.desc).equals(key)) {
                field.access &= ~Opcodes.ACC_FINAL;
            }
        }
        return true;
    }

    private static List<AbstractInsnNode> realInsns(InsnList list) {
        List<AbstractInsnNode> real = new ArrayList<>();
        for (AbstractInsnNode insn : list) {
            if (isReal(insn)) {
                real.add(insn);
            }
        }
        return real;
    }

    private static boolean isReal(AbstractInsnNode insn) {
        return !(insn instanceof LabelNode || insn instanceof LineNumberNode || insn instanceof FrameNode);
    }

    @SuppressWarnings("ReferenceEquality")
    private static boolean sameOpcodes(AbstractInsnNode start, AbstractInsnNode end, List<AbstractInsnNode> slice) {
        int at = 0;
        for (AbstractInsnNode insn = start; insn != null; insn = insn.getNext()) {
            if (!isReal(insn)) {
                continue;
            }
            if (at >= slice.size() || insn.getOpcode() != slice.get(at).getOpcode()) {
                return false;
            }
            at++;
            if (insn == end) {
                return at == slice.size();
            }
        }
        return false;
    }

    static void note(String mixin, String className, MethodHandle setUp) {
        INITS.computeIfAbsent(mixin, any -> new ArrayList<>()).add(new Init(className, setUp));
    }

    static void replay(Set<String> mixins) {
        for (String mixin : mixins) {
            for (Init init : INITS.getOrDefault(mixin, List.of())) {
                List<Object> made = OffWindowObjects.getLive(init.className());
                int failed = 0;
                for (Object object : made) {
                    try {
                        init.setUp().invoke(object);
                    } catch (Throwable e) {
                        if (failed++ == 0) {
                            LOGGER.warn("[Hotwire] {}'s setup could not be given an object made while {} was off",
                                    init.className(), mixin, e);
                        }
                    }
                }
                if (!made.isEmpty()) {
                    LOGGER.info("[Hotwire] {} objects of {} made while {} was off were given its setup, {} failed",
                            made.size(), init.className(), mixin, failed);
                    OffWindowObjects.forget(init.className());
                }
            }
        }
    }
}
