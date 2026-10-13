package dev.moon.hotwire.redefine;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class InitialValues {
    private InitialValues() {
    }

    public static Optional<Object> ofStatic(Class<?> owner, byte[] original, String name, String descriptor)
            throws ReflectiveOperationException {
        ClassNode node = RetainedInitializers.read(original);
        InsnList slice = FieldInitializers.findStaticSlices(node.name, node, Set.of(name + descriptor))
                .get(name + descriptor);
        if (slice == null) {
            return Optional.empty();
        }
        ClassNode made = new ClassNode();
        made.visit(Math.max(node.version, Opcodes.V1_8), Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                node.name + "$HotwireDefault", null, "java/lang/Object", null);
        MethodNode value = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "get", "()Ljava/lang/Object;",
                null, null);
        for (AbstractInsnNode insn : slice) {
            if (!(insn instanceof FieldInsnNode store && store.getOpcode() == Opcodes.PUTSTATIC)) {
                value.instructions.add(insn.clone(Map.of()));
            }
        }
        value.instructions.add(new InsnNode(Opcodes.ARETURN));
        made.methods.add(value);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        made.accept(writer);
        MethodHandles.Lookup nest = MethodHandles.privateLookupIn(owner, MethodHandles.lookup())
                .defineHiddenClass(writer.toByteArray(), true, MethodHandles.Lookup.ClassOption.NESTMATE);
        MethodHandle get = nest.findStatic(nest.lookupClass(), "get", MethodType.methodType(Object.class));
        return Optional.ofNullable(asSupplier(get).get());
    }

    public static Optional<Object> ofInstance(Class<?> owner, byte[] original, Object self, String name,
            String descriptor) throws ReflectiveOperationException {
        ClassNode node = RetainedInitializers.read(original);
        InsnList slice = FieldInitializers.findInstanceSlicesBranching(node.name, node, Set.of(name + descriptor))
                .get(name + descriptor);
        if (slice == null || slice.size() < 2) {
            return Optional.empty();
        }
        ClassNode made = new ClassNode();
        made.visit(Math.max(node.version, Opcodes.V1_8), Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                node.name + "$HotwireInitial", null, "java/lang/Object", null);
        MethodNode value = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "apply",
                "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);

        value.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        value.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, node.name));
        value.instructions.add(new VarInsnNode(Opcodes.ASTORE, 0));
        AbstractInsnNode[] insns = slice.toArray();
        Map<LabelNode, LabelNode> labels = new HashMap<>();
        for (AbstractInsnNode insn : insns) {
            if (insn instanceof LabelNode label) {
                labels.put(label, new LabelNode());
            }
        }
        for (int i = 1; i < insns.length - 1; i++) {
            if (insns[i] instanceof FrameNode frame && frame.stack != null && !frame.stack.isEmpty()) {
                List<Object> stack = frame.stack.subList(1, frame.stack.size());
                value.instructions.add(new FrameNode(Opcodes.F_NEW, frame.local.size(), frame.local.toArray(),
                        stack.size(), stack.toArray()));
            } else {
                value.instructions.add(insns[i].clone(labels));
            }
        }
        value.instructions.add(new InsnNode(Opcodes.ARETURN));
        made.methods.add(value);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        made.accept(writer);
        MethodHandles.Lookup nest = MethodHandles.privateLookupIn(owner, MethodHandles.lookup())
                .defineHiddenClass(writer.toByteArray(), true, MethodHandles.Lookup.ClassOption.NESTMATE);
        MethodHandle apply = nest.findStatic(nest.lookupClass(), "apply",
                MethodType.methodType(Object.class, Object.class));
        return Optional.ofNullable(asFunction(apply).apply(self));
    }

    @SuppressWarnings("unchecked")
    private static Function<Object, Object> asFunction(MethodHandle apply) {
        return MethodHandleProxies.asInterfaceInstance(Function.class, apply);
    }

    @SuppressWarnings("unchecked")
    private static Supplier<Object> asSupplier(MethodHandle get) {
        return MethodHandleProxies.asInterfaceInstance(Supplier.class, get);
    }
}
