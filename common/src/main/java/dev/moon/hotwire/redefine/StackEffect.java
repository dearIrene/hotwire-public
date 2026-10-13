package dev.moon.hotwire.redefine;

import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;

final class StackEffect {
    private StackEffect() {
    }

    static @Nullable Integer of(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        if (opcode < 0) {
            return 0;
        }
        return switch (insn.getType()) {
            case AbstractInsnNode.METHOD_INSN -> {
                MethodInsnNode call = (MethodInsnNode) insn;
                int args = Type.getArgumentCount(call.desc) + (opcode == Opcodes.INVOKESTATIC ? 0 : 1);
                yield (Type.getReturnType(call.desc).getSort() == Type.VOID ? 0 : 1) - args;
            }
            case AbstractInsnNode.INVOKE_DYNAMIC_INSN -> {
                InvokeDynamicInsnNode call = (InvokeDynamicInsnNode) insn;
                yield (Type.getReturnType(call.desc).getSort() == Type.VOID ? 0 : 1) - Type.getArgumentCount(call.desc);
            }
            case AbstractInsnNode.FIELD_INSN -> switch (opcode) {
                case Opcodes.GETSTATIC -> 1;
                case Opcodes.PUTSTATIC -> -1;
                case Opcodes.GETFIELD -> 0;
                case Opcodes.PUTFIELD -> -2;
                default -> null;
            };
            case AbstractInsnNode.LDC_INSN -> ((LdcInsnNode) insn).cst != null ? 1 : null;
            case AbstractInsnNode.MULTIANEWARRAY_INSN -> 1 - ((MultiANewArrayInsnNode) insn).dims;
            case AbstractInsnNode.JUMP_INSN -> findJumpEffect(opcode);
            case AbstractInsnNode.TABLESWITCH_INSN, AbstractInsnNode.LOOKUPSWITCH_INSN -> null;
            default -> findPlainEffect(opcode);
        };
    }

    private static @Nullable Integer findJumpEffect(int opcode) {
        return switch (opcode) {
            case Opcodes.GOTO -> 0;
            case Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE, Opcodes.IFNULL,
                    Opcodes.IFNONNULL -> -1;
            case Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT,
                    Opcodes.IF_ICMPLE, Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE -> -2;
            default -> null;
        };
    }

    private static @Nullable Integer findPlainEffect(int opcode) {
        return switch (opcode) {
            case Opcodes.NOP, Opcodes.INEG, Opcodes.LNEG, Opcodes.FNEG, Opcodes.DNEG, Opcodes.I2L, Opcodes.I2F,
                    Opcodes.I2D, Opcodes.L2I, Opcodes.L2F, Opcodes.L2D, Opcodes.F2I, Opcodes.F2L, Opcodes.F2D,
                    Opcodes.D2I, Opcodes.D2L, Opcodes.D2F, Opcodes.I2B, Opcodes.I2C, Opcodes.I2S, Opcodes.CHECKCAST,
                    Opcodes.INSTANCEOF, Opcodes.ARRAYLENGTH, Opcodes.NEWARRAY, Opcodes.ANEWARRAY, Opcodes.SWAP,
                    Opcodes.IINC -> 0;
            case Opcodes.ACONST_NULL, Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2,
                    Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5, Opcodes.LCONST_0, Opcodes.LCONST_1,
                    Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2, Opcodes.DCONST_0, Opcodes.DCONST_1,
                    Opcodes.BIPUSH, Opcodes.SIPUSH, Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD,
                    Opcodes.ALOAD, Opcodes.NEW, Opcodes.DUP -> 1;
            case Opcodes.DUP2 -> 2;
            case Opcodes.DUP_X1, Opcodes.DUP_X2 -> 1;
            case Opcodes.IADD, Opcodes.LADD, Opcodes.FADD, Opcodes.DADD, Opcodes.ISUB, Opcodes.LSUB, Opcodes.FSUB,
                    Opcodes.DSUB, Opcodes.IMUL, Opcodes.LMUL, Opcodes.FMUL, Opcodes.DMUL, Opcodes.IDIV, Opcodes.LDIV,
                    Opcodes.FDIV, Opcodes.DDIV, Opcodes.IREM, Opcodes.LREM, Opcodes.FREM, Opcodes.DREM, Opcodes.ISHL,
                    Opcodes.LSHL, Opcodes.ISHR, Opcodes.LSHR, Opcodes.IUSHR, Opcodes.LUSHR, Opcodes.IAND, Opcodes.LAND,
                    Opcodes.IOR, Opcodes.LOR, Opcodes.IXOR, Opcodes.LXOR, Opcodes.LCMP, Opcodes.FCMPL, Opcodes.FCMPG,
                    Opcodes.DCMPL, Opcodes.DCMPG, Opcodes.POP, Opcodes.IALOAD, Opcodes.LALOAD, Opcodes.FALOAD,
                    Opcodes.DALOAD, Opcodes.AALOAD, Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD,
                            Opcodes.MONITORENTER,
                    Opcodes.MONITOREXIT, Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE,
                    Opcodes.ASTORE -> -1;
            case Opcodes.POP2 -> -2;
            case Opcodes.IASTORE, Opcodes.LASTORE, Opcodes.FASTORE, Opcodes.DASTORE, Opcodes.AASTORE, Opcodes.BASTORE,
                    Opcodes.CASTORE, Opcodes.SASTORE -> -3;
            default -> null;
        };
    }
}
