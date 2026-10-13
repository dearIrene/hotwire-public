package dev.moon.hotwire.redefine;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

final class BodyMarks {
    private static final String LINKER = Linker.class.getName().replace('.', '/');
    private static final String HOLDERS = FieldHolders.class.getName().replace('.', '/');
    private static final String CALLBACK_INFO = "org/spongepowered/asm/mixin/injection/callback/CallbackInfo";
    private static final String CALLBACK_INFO_RETURNABLE = CALLBACK_INFO + "Returnable";

    private BodyMarks() {
    }

    static boolean injectCallback(MethodNode method) {
        if (Type.getReturnType(method.desc).getSort() != Type.VOID) {
            return false;
        }
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            if (argument.getSort() == Type.OBJECT && (argument.getInternalName().equals(CALLBACK_INFO)
                    || argument.getInternalName().equals(CALLBACK_INFO_RETURNABLE))) {
                return true;
            }
        }
        return false;
    }

    static void prependReachNotice(MethodNode body, String owner, String mixin) {
        InsnList call = new InsnList();
        call.add(new LdcInsnNode(owner + "." + body.name + body.desc));
        call.add(new LdcInsnNode(mixin));
        call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LINKER, "onReached",
                "(Ljava/lang/String;Ljava/lang/String;)V", false));
        body.instructions.insert(call);
        body.maxStack = Math.max(body.maxStack, 2);
    }

    static void prependSkipNotice(MethodNode body, String owner) {
        InsnList call = new InsnList();
        call.add(new LdcInsnNode(owner + "." + body.name + body.desc));
        call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LINKER, "onSkippedCallback", "(Ljava/lang/String;)V", false));
        body.instructions.insert(call);
        body.maxStack = Math.max(body.maxStack, 1);
    }

    static void markMade(MethodNode constructor, ClassNode running) {
        for (AbstractInsnNode insn : constructor.instructions) {
            if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && call.name.equals("<init>")
                    && (call.owner.equals(running.name) || call.owner.equals(running.superName))) {
                InsnList made = new InsnList();
                made.add(new LdcInsnNode(Type.getObjectType(running.name)));
                made.add(new VarInsnNode(Opcodes.ALOAD, 0));
                made.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOLDERS, "markMade",
                        "(Ljava/lang/Class;Ljava/lang/Object;)V", false));
                constructor.instructions.insert(call, made);
                return;
            }
        }
    }
}
