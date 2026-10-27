package dev.moon.hotwire.redefine;

import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

public final class DuckBridge {
    private static final String LINKER = Linker.class.getName().replace('.', '/');
    private static final String BOOTSTRAP = "Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
            + "Ljava/lang/invoke/MethodType;";
    private static final Handle IS_INSTANCE = new Handle(Opcodes.H_INVOKESTATIC, LINKER, "bootstrapIsInstance",
            "(" + BOOTSTRAP + "Ljava/lang/String;)Ljava/lang/invoke/CallSite;", false);
    private static final Handle CAST = new Handle(Opcodes.H_INVOKESTATIC, LINKER, "bootstrapCast",
            "(" + BOOTSTRAP + "Ljava/lang/String;)Ljava/lang/invoke/CallSite;", false);
    private static final Handle DUCK = new Handle(Opcodes.H_INVOKESTATIC, LINKER, "bootstrapDuck",
            "(" + BOOTSTRAP + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/invoke/CallSite;",
            false);
    private static final Handle MOVED = new Handle(Opcodes.H_INVOKESTATIC, LINKER, "bootstrapMoved",
            "(" + BOOTSTRAP + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/invoke/CallSite;",
            false);
    private static final Handle DUCK_LAMBDA = new Handle(Opcodes.H_INVOKESTATIC, LINKER, "bootstrapDuckLambda",
            "(" + BOOTSTRAP + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;"
                    + "Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);

    private DuckBridge() {
    }

    public static boolean names(byte[] classBytes, Set<String> ducks) {
        return names(classBytes, ducks, Linker.getMoved());
    }

    public static boolean names(byte[] classBytes, Set<String> ducks, Set<String> moved) {
        if (ducks.isEmpty() && moved.isEmpty()) {
            return false;
        }
        ClassReader reader = new ClassReader(classBytes);
        char[] buffer = new char[reader.getMaxStringLength()];
        for (int i = 1; i < reader.getItemCount(); i++) {
            int offset = reader.getItem(i);
            if (offset <= 0) {
                continue;
            }
            int tag = reader.readByte(offset - 1);
            if (tag == 7 && ducks.contains(reader.readUTF8(offset, buffer))) {
                return true;
            }

            if (tag == 12 && !moved.isEmpty()
                    && moved.contains(reader.readUTF8(offset, buffer) + reader.readUTF8(offset + 2, buffer))) {
                return true;
            }

            if (tag == 16 && !ducks.isEmpty() && namesAny(reader.readUTF8(offset, buffer), ducks)) {
                return true;
            }
        }
        return false;
    }

    private static boolean namesAny(String descriptor, Set<String> classes) {
        for (int from = descriptor.indexOf('L'); from >= 0; from = descriptor.indexOf('L', from + 1)) {
            int end = descriptor.indexOf(';', from);
            if (end < 0) {
                return false;
            }
            if (classes.contains(descriptor.substring(from + 1, end))) {
                return true;
            }
            from = end;
        }
        return false;
    }

    public static byte[] rewrite(byte[] classBytes, Set<String> ducks) {
        return rewrite(classBytes, ducks, Linker.getMoved());
    }

    static byte[] rewrite(byte[] classBytes, Set<String> ducks, Set<String> moved) {
        if (!names(classBytes, ducks, moved)) {
            return classBytes;
        }
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        if (ducks.contains(node.name)) {
            return rewriteDuck(node, ducks, moved);
        }
        rewrite(node, ducks, moved);
        return write(node, 0);
    }

    static void rewrite(ClassNode node, Set<String> ducks) {
        rewrite(node, ducks, Linker.getMoved());
    }

    public static byte[] bridgeInstructions(byte[] classBytes, Set<String> ducks) {
        if (!names(classBytes, ducks)) {
            return classBytes;
        }
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        if (!raiseForInvokeDynamic(node)) {
            return classBytes;
        }
        if (ducks.contains(node.name)) {
            return rewriteDuck(node, ducks, Linker.getMoved());
        }
        rewrite(node, ducks);
        return write(node, 0);
    }

    static boolean raiseForInvokeDynamic(ClassNode node) {
        if ((node.version & 0xFFFF) >= Opcodes.V1_7) {
            return true;
        }
        if ((node.version & 0xFFFF) < Opcodes.V1_6) {
            return false;
        }
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn.getOpcode() == Opcodes.JSR || insn.getOpcode() == Opcodes.RET) {
                    return false;
                }
            }
        }
        node.version = Opcodes.V1_8;
        return true;
    }

    private static byte[] rewriteDuck(ClassNode node, Set<String> ducks, Set<String> moved) {
        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_STATIC) != 0) {
                rewrite(node.name, method, ducks, moved);
            }
        }
        return write(node, 0);
    }

    static void rewrite(ClassNode node, Set<String> ducks, Set<String> moved) {
        for (MethodNode method : node.methods) {
            rewrite(node.name, method, ducks, moved);
        }
    }

    private static void rewrite(String self, MethodNode method, Set<String> ducks, Set<String> moved) {
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            switch (insn) {
                case TypeInsnNode type when ducks.contains(type.desc) -> {
                    if (type.getOpcode() == Opcodes.INSTANCEOF) {
                        method.instructions.set(insn, new InvokeDynamicInsnNode("isInstance",
                                "(Ljava/lang/Object;)Z", IS_INSTANCE, type.desc));
                    } else if (type.getOpcode() == Opcodes.CHECKCAST) {
                        method.instructions.set(insn, new InvokeDynamicInsnNode("cast",
                                "(Ljava/lang/Object;)Ljava/lang/Object;", CAST, type.desc));
                    }
                }
                case MethodInsnNode call when call.getOpcode() == Opcodes.INVOKEINTERFACE
                        && ducks.contains(call.owner) -> {
                    method.instructions.set(insn, new InvokeDynamicInsnNode(call.name,
                            "(Ljava/lang/Object;" + call.desc.substring(1), DUCK, call.owner, call.name, call.desc));
                }
                case InvokeDynamicInsnNode lambda when isDuckLambda(lambda, ducks) -> lambda.bsm = DUCK_LAMBDA;

                case MethodInsnNode call when !self.equals(call.owner)
                        && (call.getOpcode() == Opcodes.INVOKEVIRTUAL || call.getOpcode() == Opcodes.INVOKESTATIC
                        || call.getOpcode() == Opcodes.INVOKEINTERFACE)
                        && moved.contains(call.name + call.desc) -> {
                    method.instructions.set(insn, makeMovedCall(call));
                }
                default -> {
                }
            }
        }
    }

    private static InvokeDynamicInsnNode makeMovedCall(MethodInsnNode call) {
        boolean isStatic = call.getOpcode() == Opcodes.INVOKESTATIC;
        String shape = isStatic ? call.desc : "(Ljava/lang/Object;" + call.desc.substring(1);
        return new InvokeDynamicInsnNode(call.name, shape, MOVED, call.owner, call.name, call.desc, isStatic ? 1 : 0);
    }

    static void rewriteInheritedMoved(ClassNode node, String self, Set<String> moved, Set<String> notMoved) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(self)
                        && (call.getOpcode() == Opcodes.INVOKEVIRTUAL || call.getOpcode() == Opcodes.INVOKESTATIC)
                        && moved.contains(call.name + call.desc) && !notMoved.contains(call.name + call.desc)) {
                    method.instructions.set(call, makeMovedCall(call));
                }
            }
        }
    }

    private static boolean isDuckLambda(InvokeDynamicInsnNode lambda, Set<String> ducks) {
        if (!LambdaSites.isFactory(lambda.bsm) || lambda.bsmArgs.length < 3
                || !(lambda.bsmArgs[1] instanceof Handle target) || !(lambda.bsmArgs[2] instanceof Type instantiated)) {
            return false;
        }
        if (ducks.contains(target.getOwner())) {
            return true;
        }
        for (String duck : ducks) {
            if (instantiated.getDescriptor().contains("L" + duck + ";")) {
                return true;
            }
        }
        return false;
    }

    static byte[] write(ClassNode node, int flags) {
        ClassWriter writer = new ClassWriter(flags);
        node.accept(writer);
        return writer.toByteArray();
    }

    static void eraseFields(ClassNode holder, Set<String> ducks) {
        for (FieldNode field : holder.fields) {
            if (isDuckType(field.desc, ducks)) {
                field.desc = "Ljava/lang/Object;";
                field.signature = null;
            }
        }
        for (MethodNode method : holder.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode access && access.owner.equals(holder.name)
                        && isDuckType(access.desc, ducks)) {
                    access.desc = "Ljava/lang/Object;";
                }
            }
        }
    }

    private static boolean isDuckType(String descriptor, Set<String> ducks) {
        return descriptor.startsWith("L") && descriptor.endsWith(";")
                && ducks.contains(descriptor.substring(1, descriptor.length() - 1));
    }
}
