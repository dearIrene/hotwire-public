package dev.moon.hotwire.redefine;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

@SuppressWarnings("ReferenceEquality")
public final class Adoption {
    public static final String CONSTRUCT = "hotwire$construct";
    private static final String BOOTSTRAP = Type.getInternalName(Adoption.class);
    private static final String ADOPT = "adopt";
    private static final String BOOTSTRAP_TYPE = MethodType.methodType(CallSite.class, MethodHandles.Lookup.class,
            String.class, MethodType.class).toMethodDescriptorString();

    private static final ClassValue<Map<Constructor<?>, Constructor<?>>> MAKERS = new ClassValue<>() {
        @Override
        protected Map<Constructor<?>, Constructor<?>> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    private Adoption() {
    }

    public static byte[] rewrite(byte[] bytes, Set<String> adopted) {
        if (adopted.isEmpty() || !namesAny(bytes, adopted)) {
            return bytes;
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        boolean changed = false;
        if (adopted.contains(node.name)) {
            for (FieldNode field : node.fields) {
                if ((field.access & Opcodes.ACC_STATIC) == 0) {
                    field.access &= ~Opcodes.ACC_FINAL;
                }
            }
            List<MethodNode> constructs = new ArrayList<>();
            for (MethodNode method : node.methods) {
                if (method.name.equals("<init>")) {
                    constructs.add(makeConstructMethod(node, method));
                }
            }
            node.methods.addAll(constructs);
            changed = !constructs.isEmpty();
        }
        for (MethodNode method : node.methods) {
            changed |= rewriteNews(method, call -> adopted.contains(call.owner), false);
        }
        if (!changed) {
            return bytes;
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean namesAny(byte[] bytes, Set<String> classes) {
        ClassReader reader = new ClassReader(bytes);
        char[] buffer = new char[reader.getMaxStringLength()];
        for (int i = 1; i < reader.getItemCount(); i++) {
            int offset = reader.getItem(i);
            if (offset > 0 && reader.readByte(offset - 1) == 7 && classes.contains(reader.readUTF8(offset, buffer))) {
                return true;
            }
        }
        return false;
    }

    private static MethodNode makeConstructMethod(ClassNode owner, MethodNode init) {
        String desc = "(Ljava/lang/Object;" + init.desc.substring(1, init.desc.lastIndexOf(')')) + ")L"
                + owner.name + ";";
        MethodNode construct = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                CONSTRUCT, desc, null, null);
        Map<LabelNode, LabelNode> labels = new HashMap<>();
        for (AbstractInsnNode insn : init.instructions) {
            if (insn instanceof LabelNode label) {
                labels.put(label, new LabelNode());
            }
        }
        MethodInsnNode superCall = findSuperCall(owner, init);
        VarInsnNode thisLoad = findThisLoad(superCall);
        boolean before = true;
        for (AbstractInsnNode insn : init.instructions) {
            if (insn == thisLoad) {
                continue;
            }
            if (insn == superCall) {
                MethodInsnNode call = (MethodInsnNode) insn;
                if (call.owner.equals(owner.name)) {
                    construct.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner.name, CONSTRUCT,
                            "(Ljava/lang/Object;" + call.desc.substring(1, call.desc.lastIndexOf(')')) + ")L"
                                    + owner.name + ";", false));
                } else {
                    String adopting = call.desc.substring(0, call.desc.lastIndexOf(')') + 1) + "L" + owner.name + ";";
                    construct.instructions.add(new InvokeDynamicInsnNode(ADOPT, adopting, new Handle(
                            Opcodes.H_INVOKESTATIC, BOOTSTRAP, "bootstrap", BOOTSTRAP_TYPE, false)));
                }
                construct.instructions.add(new VarInsnNode(Opcodes.ASTORE, 0));
                before = false;
                continue;
            }
            if (insn.getOpcode() == Opcodes.RETURN) {
                construct.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                construct.instructions.add(new InsnNode(Opcodes.ARETURN));
                continue;
            }
            if (insn instanceof FrameNode frame) {
                construct.instructions.add(makeConstructFrame(frame, before, labels));
                continue;
            }
            construct.instructions.add(insn.clone(labels));
        }
        init.tryCatchBlocks.forEach(block -> construct.tryCatchBlocks.add(new TryCatchBlockNode(
                labels.get(block.start), labels.get(block.end), labels.get(block.handler), block.type)));
        return construct;
    }

    private static FrameNode makeConstructFrame(FrameNode frame, boolean before, Map<LabelNode, LabelNode> labels) {
        List<Object> locals = new ArrayList<>();
        for (Object local : frame.local) {
            locals.add(Integer.valueOf(Opcodes.UNINITIALIZED_THIS).equals(local) ? "java/lang/Object"
                    : local instanceof LabelNode label ? labels.get(label) : local);
        }
        List<Object> stack = new ArrayList<>();
        for (Object item : frame.stack) {
            if (before && Integer.valueOf(Opcodes.UNINITIALIZED_THIS).equals(item)) {
                continue;
            }
            stack.add(item instanceof LabelNode label ? labels.get(label) : item);
        }
        return new FrameNode(Opcodes.F_NEW, locals.size(), locals.toArray(), stack.size(), stack.toArray());
    }

    private static MethodInsnNode findSuperCall(ClassNode owner, MethodNode init) {
        int pending = 0;
        for (AbstractInsnNode insn : init.instructions) {
            if (insn instanceof TypeInsnNode made && made.getOpcode() == Opcodes.NEW) {
                pending++;
            } else if (insn instanceof MethodInsnNode call && call.name.equals("<init>")) {
                if (pending > 0) {
                    pending--;
                } else if (call.owner.equals(owner.name) || call.owner.equals(owner.superName)) {
                    return call;
                }
            }
        }
        throw new IllegalStateException(owner.name + ".<init>" + init.desc + " calls no constructor of its own");
    }

    private static VarInsnNode findThisLoad(MethodInsnNode superCall) {
        for (AbstractInsnNode at = superCall.getPrevious(); at != null; at = at.getPrevious()) {
            if (at instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == 0) {
                return load;
            }
        }
        throw new IllegalStateException("no load of this before " + superCall.owner + ".<init>");
    }

    static @Nullable MethodNode makeAddedConstruct(ClassNode owner, MethodNode init, Set<String> added) {
        String args = init.desc.substring(1, init.desc.lastIndexOf(')'));
        MethodNode construct = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                CONSTRUCT, "(Ljava/lang/Object;" + args + ")L" + owner.name + ";", null, null);
        Map<LabelNode, LabelNode> labels = new HashMap<>();
        for (AbstractInsnNode insn : init.instructions) {
            if (insn instanceof LabelNode label) {
                labels.put(label, new LabelNode());
            }
        }
        MethodInsnNode superCall;
        VarInsnNode thisLoad;
        try {
            superCall = findSuperCall(owner, init);
            thisLoad = findThisLoad(superCall);
        } catch (IllegalStateException e) {
            return null;
        }
        Set<AbstractInsnNode> early = new HashSet<>();
        List<FieldInsnNode> earlyFields = new ArrayList<>();
        if (!findEarlyFields(owner, init, superCall, early, earlyFields)) {
            return null;
        }
        Map<FieldInsnNode, Integer> earlyLocals = new HashMap<>();
        int free = init.maxLocals;
        for (FieldInsnNode field : earlyFields) {
            earlyLocals.put(field, free);
            free += Type.getType(field.desc).getSize();
        }
        for (AbstractInsnNode insn : init.instructions) {
            if (early.contains(insn)) {
                continue;
            }
            if (insn == thisLoad) {
                construct.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                construct.instructions.add(new LdcInsnNode(Type.getObjectType(owner.name)));
                construct.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BOOTSTRAP, "findMade",
                        "(Ljava/lang/Object;Ljava/lang/Class;)Ljava/lang/Object;", false));
                continue;
            }
            if (insn instanceof FieldInsnNode field && earlyLocals.containsKey(field)) {
                construct.instructions.add(new VarInsnNode(Type.getType(field.desc).getOpcode(Opcodes.ISTORE),
                        Objects.requireNonNull(earlyLocals.get(field))));
                continue;
            }
            if (insn == superCall) {
                addChainedCall(construct, owner, superCall, added);
                construct.instructions.add(new VarInsnNode(Opcodes.ASTORE, 0));
                for (FieldInsnNode field : earlyFields) {
                    construct.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    construct.instructions.add(new VarInsnNode(Type.getType(field.desc).getOpcode(Opcodes.ILOAD),
                            Objects.requireNonNull(earlyLocals.get(field))));
                    construct.instructions.add(field.clone(labels));
                }
                continue;
            }
            if (insn.getOpcode() == Opcodes.RETURN) {
                construct.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                construct.instructions.add(new InsnNode(Opcodes.ARETURN));
                continue;
            }
            if (insn instanceof FrameNode frame) {
                construct.instructions.add(makeAddedFrame(frame, labels));
                continue;
            }
            construct.instructions.add(insn.clone(labels));
        }
        init.tryCatchBlocks.forEach(block -> construct.tryCatchBlocks.add(new TryCatchBlockNode(
                labels.get(block.start), labels.get(block.end), labels.get(block.handler), block.type)));
        construct.maxLocals = free;
        construct.maxStack = init.maxStack + 3;
        return construct;
    }

    private static void addChainedCall(MethodNode construct, ClassNode owner, MethodInsnNode call, Set<String> added) {
        String args = call.desc.substring(1, call.desc.lastIndexOf(')'));
        boolean own = call.owner.equals(owner.name);
        if (added.contains(call.owner + "<init>" + call.desc)) {
            construct.instructions.add(DuckBridge.makeMovedCall(new MethodInsnNode(Opcodes.INVOKESTATIC, call.owner,
                    CONSTRUCT, "(Ljava/lang/Object;" + args + ")L" + call.owner + ";", false)));
            if (!own) {
                construct.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, owner.name));
            }
        } else {
            construct.instructions.add(new InvokeDynamicInsnNode(own ? "own" : "parent",
                    "(Ljava/lang/Object;" + args + ")L" + owner.name + ";",
                            new Handle(Opcodes.H_INVOKESTATIC, BOOTSTRAP,
                    "bootstrapAdded", BOOTSTRAP_TYPE, false)));
        }
    }

    private static FrameNode makeAddedFrame(FrameNode frame, Map<LabelNode, LabelNode> labels) {
        List<Object> locals = new ArrayList<>();
        for (Object local : frame.local) {
            locals.add(Integer.valueOf(Opcodes.UNINITIALIZED_THIS).equals(local) ? "java/lang/Object"
                    : local instanceof LabelNode label ? labels.get(label) : local);
        }
        List<Object> stack = new ArrayList<>();
        for (Object item : frame.stack) {
            stack.add(Integer.valueOf(Opcodes.UNINITIALIZED_THIS).equals(item) ? "java/lang/Object"
                    : item instanceof LabelNode label ? labels.get(label) : item);
        }
        return new FrameNode(Opcodes.F_NEW, locals.size(), locals.toArray(), stack.size(), stack.toArray());
    }

    private static boolean findEarlyFields(ClassNode owner, MethodNode init, MethodInsnNode superCall,
            Set<AbstractInsnNode> early, List<FieldInsnNode> earlyFields) {
        for (AbstractInsnNode insn = init.instructions.getFirst(); insn != null && insn != superCall;
                insn = insn.getNext()) {
            if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                    && field.owner.equals(owner.name)) {
                AbstractInsnNode value = findPreviousCode(field);
                AbstractInsnNode receiver = value == null ? null : findPreviousCode(value);
                if (!(value instanceof VarInsnNode) || !(receiver instanceof VarInsnNode load)
                        || load.getOpcode() != Opcodes.ALOAD || load.var != 0) {
                    return false;
                }
                early.add(receiver);
                earlyFields.add(field);
            } else if (!earlyFields.isEmpty() && insn instanceof FrameNode) {
                return false;
            }
        }
        return true;
    }

    private static @Nullable AbstractInsnNode findPreviousCode(AbstractInsnNode insn) {
        AbstractInsnNode at = insn.getPrevious();
        while (at != null && at.getOpcode() < 0) {
            at = at.getPrevious();
        }
        return at;
    }

    static void rewriteAddedNews(ClassNode node, Set<String> added) {
        for (MethodNode method : node.methods) {
            rewriteNews(method, call -> added.contains(call.owner + "<init>" + call.desc), true);
        }
    }

    public static byte[] rewriteAdded(byte[] bytes, Set<String> added) {
        if (added.isEmpty()) {
            return bytes;
        }
        ClassReader reader = new ClassReader(bytes);
        boolean names = false;
        char[] buffer = new char[reader.getMaxStringLength()];
        for (int i = 1; i < reader.getItemCount() && !names; i++) {
            int offset = reader.getItem(i);
            if (offset > 0 && reader.readByte(offset - 1) == 7) {
                String name = reader.readUTF8(offset, buffer);
                String constructor = name + "<init>";
                for (String one : added) {
                    names |= one.startsWith(constructor);
                }
            }
        }
        if (!names) {
            return bytes;
        }
        ClassNode node = new ClassNode();
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        boolean changed = false;

        List<MethodNode> constructs = new ArrayList<>();
        for (MethodNode method : node.methods) {
            if (method.name.equals("<init>") && added.contains(node.name + "<init>" + method.desc)) {
                MethodNode construct = makeAddedConstruct(node, method, added);
                if (construct != null) {
                    constructs.add(construct);
                }
            }
        }
        if (!constructs.isEmpty()) {
            for (FieldNode field : node.fields) {
                if ((field.access & Opcodes.ACC_STATIC) == 0) {
                    field.access &= ~Opcodes.ACC_FINAL;
                }
            }
            node.methods.addAll(constructs);
            changed = true;
        }
        for (MethodNode method : node.methods) {
            changed |= rewriteNews(method, call -> added.contains(call.owner + "<init>" + call.desc), true);
        }
        if (!changed) {
            return bytes;
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean rewriteNews(MethodNode method, Predicate<MethodInsnNode> adopted, boolean moved) {
        boolean changed = false;
        List<TypeInsnNode> pending = new ArrayList<>();
        Map<LabelNode, Boolean> made = new HashMap<>();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW) {
                pending.add(type);
            } else if (insn instanceof MethodInsnNode call && call.name.equals("<init>") && !pending.isEmpty()) {
                TypeInsnNode type = pending.removeLast();
                if (!adopted.test(call) || !type.desc.equals(call.owner)
                        || type.getNext() == null || type.getNext().getOpcode() != Opcodes.DUP) {
                    continue;
                }

                for (AbstractInsnNode at = type.getPrevious(); at != null && at.getOpcode() < 0;
                        at = at.getPrevious()) {
                    if (at instanceof LabelNode label) {
                        made.put(label, Boolean.TRUE);
                    }
                }

                method.instructions.set(type, new InsnNode(Opcodes.ACONST_NULL));
                MethodInsnNode direct = new MethodInsnNode(Opcodes.INVOKESTATIC, call.owner, CONSTRUCT,
                        "(Ljava/lang/Object;" + call.desc.substring(1, call.desc.lastIndexOf(')')) + ")L" + call.owner
                                + ";", false);
                AbstractInsnNode construct = moved ? DuckBridge.makeMovedCall(direct) : direct;
                method.instructions.set(call, construct);
                method.instructions.insert(construct, new InsnNode(Opcodes.POP));
                method.instructions.insert(construct, new InsnNode(Opcodes.SWAP));
                changed = true;
            }
        }
        if (changed && !made.isEmpty()) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FrameNode frame) {
                    replaceMade(frame.local, made);
                    replaceMade(frame.stack, made);
                }
            }
        }
        return changed;
    }

    private static void replaceMade(@Nullable List<Object> types, Map<LabelNode, Boolean> made) {
        if (types == null) {
            return;
        }
        for (int i = 0; i < types.size(); i++) {
            if (types.get(i) instanceof LabelNode label && made.containsKey(label)) {
                types.set(i, Opcodes.NULL);
            }
        }
    }

    public static CallSite bootstrapOwn(MethodHandles.Lookup caller, String name, MethodType type)
            throws ReflectiveOperationException {
        Class<?> made = type.returnType();
        return new ConstantCallSite(MethodHandles.privateLookupIn(made, MethodHandles.lookup()).findConstructor(made,
                type.changeReturnType(void.class)));
    }

    public static CallSite bootstrapAdded(MethodHandles.Lookup caller, String name, MethodType type)
            throws ReflectiveOperationException {
        Class<?> declaring = type.returnType();
        Class<?>[] params = type.dropParameterTypes(0, 1).parameterArray();
        Constructor<?> theirs = (name.equals("own") ? declaring : declaring.getSuperclass())
                .getDeclaredConstructor(params);
        MethodHandle make = MethodHandles.lookup().findStatic(Adoption.class, "make",
                MethodType.methodType(Object.class,
                Object.class, Constructor.class, Object[].class));
        return new ConstantCallSite(MethodHandles.insertArguments(make, 1, theirs)
                .asCollector(Object[].class, params.length).asType(type));
    }

    public static Object findMade(@Nullable Object given, Class<?> own) {
        return given instanceof Class<?> ? given : own;
    }

    public static Object make(Object made, Constructor<?> theirs, Object[] args) throws Throwable {
        try {
            Class<?> type = (Class<?>) made;
            Constructor<?> factory = MAKERS.get(type).computeIfAbsent(theirs, one -> findFactory(type, one));
            return factory.newInstance(args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static Constructor<?> findFactory(Class<?> type, Constructor<?> theirs) {
        try {
            Class<?> factoryType = Class.forName("sun.reflect.ReflectionFactory");
            Object reflection = factoryType.getMethod("getReflectionFactory").invoke(null);
            Constructor<?> factory = (Constructor<?>) factoryType.getMethod("newConstructorForSerialization",
                    Class.class,
                    Constructor.class).invoke(reflection, type, theirs);
            factory.setAccessible(true);
            return factory;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("no way to make a " + type.getName(), e);
        }
    }

    public static CallSite bootstrap(MethodHandles.Lookup caller, String name, MethodType type)
            throws ReflectiveOperationException {
        Class<?> made = type.returnType();
        Class<?> parent = made.getSuperclass();
        Constructor<?> theirs = parent.getDeclaredConstructor(type.parameterArray());

        Class<?> factoryType = Class.forName("sun.reflect.ReflectionFactory");
        Object factory = factoryType.getMethod("getReflectionFactory").invoke(null);
        Constructor<?> adopted = (Constructor<?>) factoryType.getMethod("newConstructorForSerialization", Class.class,
                Constructor.class).invoke(factory, made, theirs);

        MethodHandle handle = MethodHandles.lookup().findVirtual(Constructor.class, "newInstance",
                MethodType.methodType(Object.class, Object[].class)).bindTo(adopted)
                .asCollector(Object[].class, type.parameterCount());
        return new ConstantCallSite(handle.asType(type));
    }
}
