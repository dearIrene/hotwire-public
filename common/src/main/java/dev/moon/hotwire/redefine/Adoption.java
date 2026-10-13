package dev.moon.hotwire.redefine;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LabelNode;
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

    private Adoption() {
    }

    public static byte[] rewrite(byte[] bytes, Set<String> adopted) {
        if (adopted.isEmpty()) {
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
            changed |= rewriteNews(method, adopted);
        }
        if (!changed) {
            return bytes;
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
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

    private static boolean rewriteNews(MethodNode method, Set<String> adopted) {
        boolean changed = false;
        List<TypeInsnNode> pending = new ArrayList<>();
        Map<LabelNode, Boolean> made = new HashMap<>();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW) {
                pending.add(type);
            } else if (insn instanceof MethodInsnNode call && call.name.equals("<init>") && !pending.isEmpty()) {
                TypeInsnNode type = pending.removeLast();
                if (!adopted.contains(call.owner) || !type.desc.equals(call.owner)
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
                MethodInsnNode construct = new MethodInsnNode(Opcodes.INVOKESTATIC, call.owner, CONSTRUCT,
                        "(Ljava/lang/Object;" + call.desc.substring(1, call.desc.lastIndexOf(')')) + ")L" + call.owner
                                + ";", false);
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

    private static void replaceMade(List<Object> types, Map<LabelNode, Boolean> made) {
        for (int i = 0; i < types.size(); i++) {
            if (types.get(i) instanceof LabelNode label && made.containsKey(label)) {
                types.set(i, Opcodes.NULL);
            }
        }
    }

    public static CallSite bootstrap(MethodHandles.Lookup caller, String name, MethodType type)
            throws ReflectiveOperationException {
        Class<?> made = caller.lookupClass();
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
