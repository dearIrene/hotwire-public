package dev.moon.hotwire.redefine;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

final class SplitRewriter {
    private final ClassSplit owner;

    SplitRewriter(ClassSplit owner) {
        this.owner = owner;
    }

    void rewrite(MethodNode method, boolean inSidecar) {
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            switch (insn) {
                case FieldInsnNode field -> {
                    if (!field.owner.equals(this.owner.target)
                            && this.owner.addedElsewhere.apply(field.owner).contains(field.name + field.desc)) {
                        method.instructions.set(field, ForeignFields.makeAccess(field));
                    } else if (field.owner.equals(this.owner.target)
                            && this.owner.addedFields.contains(field.name + field.desc)
                            && !(!inSidecar && this.owner.isOwnInitialization(method, field))) {
                        method.instructions.set(field, this.makeFieldAccess(field));
                    } else if (!inSidecar && field.getOpcode() == Opcodes.PUTFIELD
                            && this.owner.isOwnInitialization(method, field)) {
                        this.copyToHolder(method, field);
                    } else if (field.owner.equals(this.owner.target) && field.getOpcode() == Opcodes.PUTSTATIC
                            && this.owner.writtenInPlace.contains(field.name + field.desc)
                            && (inSidecar || !method.name.equals("<clinit>"))) {
                        method.instructions.set(field, new InvokeDynamicInsnNode(field.name, "(" + field.desc + ")V",
                                ClassSplit.FIELD, Linker.PUT_FINAL, field.desc));
                    } else if (field.owner.equals(this.owner.target) && field.getOpcode() == Opcodes.PUTFIELD
                            && this.owner.stillFinal.contains(field.name + field.desc)
                            && (inSidecar || !method.name.equals("<init>"))) {
                        method.instructions.set(field, new InvokeDynamicInsnNode(field.name,
                                "(L" + this.owner.target + ";" + field.desc + ")V", ClassSplit.FIELD,
                                        Linker.PUT_FINAL, field.desc));
                    } else if (inSidecar && this.owner.isReachedAsInherited(field.owner, field.name + field.desc)
                            && (field.getOpcode() == Opcodes.GETFIELD || field.getOpcode() == Opcodes.PUTFIELD)) {
                        boolean get = field.getOpcode() == Opcodes.GETFIELD;
                        String owner = "L" + field.owner + ";";
                        method.instructions.set(field, new InvokeDynamicInsnNode(field.name,
                                get ? "(" + owner + ")" + field.desc : "(" + owner + field.desc + ")V",
                                        ClassSplit.INHERITED,
                                Type.getObjectType(field.owner), get ? Linker.GET_FIELD : Linker.PUT_FIELD));
                    }
                }
                case MethodInsnNode call -> {
                    if (inSidecar && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                            && this.owner.isReachedAsInherited(call.owner, call.name + call.desc)
                            && !this.owner.addedMethods.containsKey(call.name + call.desc)) {
                        method.instructions.set(call, new InvokeDynamicInsnNode(call.name,
                                "(L" + call.owner + ";" + call.desc.substring(1), ClassSplit.INHERITED,
                                Type.getObjectType(call.owner), Linker.VIRTUAL));
                    } else if (call.owner.equals(this.owner.target)) {
                        this.rewriteCall(method, call, inSidecar);
                    } else if (inSidecar && call.getOpcode() == Opcodes.INVOKESPECIAL && !call.name.equals("<init>")) {
                        String withReceiver = "(L" + this.owner.target + ";" + call.desc.substring(1);
                        method.instructions.set(call,
                                new InvokeDynamicInsnNode(call.name, withReceiver, ClassSplit.SUPER,
                                Type.getObjectType(call.owner)));
                    }
                }
                case InvokeDynamicInsnNode indy -> {
                    this.rewriteLambda(method, indy);
                    if (inSidecar) {
                        for (int i = 0; i < indy.bsmArgs.length; i++) {
                            if (indy.bsmArgs[i] instanceof Handle handle) {
                                indy.bsmArgs[i] = this.makeNestmateHandle(handle);
                            }
                        }
                    }
                }
                case LdcInsnNode ldc when inSidecar && ldc.cst instanceof Handle handle -> {
                    ldc.cst = this.makeNestmateHandle(handle);
                }
                default -> {
                }
            }
        }
    }

    void copyToHolder(MethodNode method, FieldInsnNode field) {
        Type type = Type.getType(field.desc);
        int local = method.maxLocals;
        method.maxLocals += type.getSize();
        InsnList before = new InsnList();
        before.add(new VarInsnNode(type.getOpcode(Opcodes.ISTORE), local));
        before.add(new InsnNode(Opcodes.DUP));
        before.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), local));
        method.instructions.insertBefore(field, before);
        InsnList after = new InsnList();
        after.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), local));
        after.add(this.makeFieldAccess(new FieldInsnNode(Opcodes.PUTFIELD, field.owner, field.name, field.desc)));
        method.instructions.insert(field, after);
    }

    Handle makeNestmateHandle(Handle handle) {
        if (handle.getTag() == Opcodes.H_INVOKESPECIAL && handle.getOwner().equals(this.owner.target)
                && !handle.getName().equals("<init>")) {
            return new Handle(Opcodes.H_INVOKEVIRTUAL, handle.getOwner(), handle.getName(), handle.getDesc(),
                    handle.isInterface());
        }
        return handle;
    }

    void holdLock(MethodNode method, boolean isStatic) {
        InsnList lock = new InsnList();
        this.owner.pushLock(lock, isStatic);
        lock.add(new InsnNode(Opcodes.MONITORENTER));
        LabelNode start = new LabelNode();
        lock.add(start);
        method.instructions.insert(lock);
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            int op = insn.getOpcode();
            if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) {
                InsnList release = new InsnList();
                this.owner.pushLock(release, isStatic);
                release.add(new InsnNode(Opcodes.MONITOREXIT));
                method.instructions.insertBefore(insn, release);
            }
        }
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        method.instructions.add(end);
        method.instructions.add(handler);
        Object[] locals = isStatic ? new Object[0] : new Object[] {this.owner.target};
        method.instructions.add(new FrameNode(Opcodes.F_FULL, locals.length, locals, 1,
                new Object[] {"java/lang/Throwable"}));
        this.owner.pushLock(method.instructions, isStatic);
        method.instructions.add(new InsnNode(Opcodes.MONITOREXIT));
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
    }

    void rewriteCall(MethodNode method, MethodInsnNode call, boolean inSidecar) {
        MethodNode added = this.owner.addedMethods.get(call.name + call.desc);
        if (added != null) {
            String descriptor = this.owner.makeStaticDescriptor(added);
            method.instructions.set(call, inSidecar
                    ? new MethodInsnNode(Opcodes.INVOKESTATIC, this.owner.sidecar, call.name, descriptor, false)
                    : new InvokeDynamicInsnNode(call.name, descriptor, ClassSplit.METHOD));
        } else if (inSidecar && call.getOpcode() == Opcodes.INVOKESPECIAL && !call.name.equals("<init>")) {
            call.setOpcode(Opcodes.INVOKEVIRTUAL);
        }
    }

    void rewriteLambda(MethodNode method, InvokeDynamicInsnNode indy) {
        if (!indy.bsm.getOwner().equals(ClassSplit.METAFACTORY) || !indy.bsm.getName().equals("metafactory")
                || !(indy.bsmArgs[1] instanceof Handle impl) || !impl.getOwner().equals(this.owner.target)) {
            return;
        }
        MethodNode added = this.owner.addedMethods.get(impl.getName() + impl.getDesc());
        if (added != null) {
            method.instructions.set(indy, new InvokeDynamicInsnNode(indy.name, indy.desc, ClassSplit.LAMBDA,
                    indy.bsmArgs[0], impl.getName(), this.owner.makeStaticDescriptor(added), indy.bsmArgs[2]));
        }
    }

    AbstractInsnNode makeFieldAccess(FieldInsnNode field) {
        String self = "L" + this.owner.target + ";";
        return switch (field.getOpcode()) {
            case Opcodes.GETFIELD -> new InvokeDynamicInsnNode(field.name, "(" + self + ")" + field.desc,
                    ClassSplit.FIELD,
                    Linker.GET_FIELD, field.desc);
            case Opcodes.PUTFIELD -> new InvokeDynamicInsnNode(field.name, "(" + self + field.desc + ")V",
                    ClassSplit.FIELD,
                    Linker.PUT_FIELD, field.desc);
            case Opcodes.GETSTATIC -> new InvokeDynamicInsnNode(field.name, "()" + field.desc, ClassSplit.FIELD,
                    Linker.GET_STATIC, field.desc);
            default -> new InvokeDynamicInsnNode(field.name, "(" + field.desc + ")V", ClassSplit.FIELD,
                    Linker.PUT_STATIC, field.desc);
        };
    }
}
