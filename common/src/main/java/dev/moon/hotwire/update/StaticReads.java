package dev.moon.hotwire.update;

import dev.moon.hotwire.classfile.ClassNodes;
import dev.moon.hotwire.jar.ZipBytes;
import dev.moon.hotwire.update.JarDiff.ClassDiff;
import dev.moon.hotwire.update.JarDiff.ShapeChange;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

final class StaticReads {
    private static final int MOST_STEPS = 200;
    private static final Set<String> TAKES_NULL = Set.of(
            "java/util/Objects.equals(Ljava/lang/Object;Ljava/lang/Object;)Z",
            "java/util/Objects.isNull(Ljava/lang/Object;)Z",
            "java/util/Objects.nonNull(Ljava/lang/Object;)Z",
            "java/util/Objects.hashCode(Ljava/lang/Object;)I",
            "java/lang/String.valueOf(Ljava/lang/Object;)Ljava/lang/String;");

    private final Map<String, Set<String>> watched = new HashMap<>();

    private final Set<String> getters = new HashSet<>();
    private final Set<String> safe = new HashSet<>();

    private StaticReads() {
    }

    static Set<String> findNullSafe(List<ClassDiff> classes, Map<String, byte[]> running,
            Map<String, byte[]> incoming) {
        StaticReads reads = new StaticReads();
        for (ClassDiff diff : classes) {
            byte[] was = running.get(diff.name() + ".class");
            byte[] now = incoming.get(diff.name() + ".class");
            if (diff.shape().contains(ShapeChange.STATIC_SET_ELSEWHERE) && was != null && now != null) {
                reads.watch(ClassNodes.read(was, ClassReader.SKIP_CODE),
                        ClassNodes.read(now, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES));
            }
        }
        if (reads.safe.isEmpty()) {
            return Set.of();
        }
        List<byte[]> all = new ArrayList<>();
        try {
            collectClasses(incoming, all);
        } catch (IOException unread) {
            return Set.of();
        }
        for (byte[] bytes : all) {
            reads.scan(ClassNodes.read(bytes, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES));
        }
        return reads.safe;
    }

    private static void collectClasses(Map<String, byte[]> entries, List<byte[]> into) throws IOException {
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (entry.getKey().endsWith(".class")) {
                into.add(entry.getValue());
            } else if (entry.getKey().endsWith(".jar")) {
                ZipBytes nested = new ZipBytes(entry.getValue());
                Map<String, byte[]> inside = new HashMap<>();
                for (String name : nested.getNames()) {
                    if (name.endsWith(".class") || name.endsWith(".jar")) {
                        inside.put(name, Objects.requireNonNull(nested.read(name)));
                    }
                }
                collectClasses(inside, into);
            }
        }
    }

    private void watch(ClassNode was, ClassNode now) {
        Set<String> fields = ConstructorUses.findStaticsSetElsewhere(was, now);
        if (fields.isEmpty()) {
            return;
        }
        Set<String> marks = new HashSet<>();
        for (FieldNode field : now.fields) {
            if (fields.contains(field.name + field.desc)) {
                if ((field.access & Opcodes.ACC_PRIVATE) == 0) {
                    return;
                }
                marks.add("F " + now.name + "." + field.name + field.desc);
                marks.add("S " + field.name);
            }
        }
        Set<String> returning = new HashSet<>();
        for (MethodNode method : now.methods) {
            AbstractInsnNode first = findReal(method.instructions.getFirst());
            AbstractInsnNode second = first == null ? null : findReal(first.getNext());
            if (first instanceof FieldInsnNode read && read.getOpcode() == Opcodes.GETSTATIC
                    && marks.contains("F " + read.owner + "." + read.name + read.desc)
                    && second != null && second.getOpcode() == Opcodes.ARETURN) {
                if ((method.access & Opcodes.ACC_STATIC) == 0
                        || (method.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC)) == 0) {
                    return;
                }
                returning.add(now.name + "." + method.name + method.desc);
                marks.add("M " + method.name + method.desc);
                marks.add("S " + method.name);
            }
        }
        this.getters.addAll(returning);
        this.safe.add(now.name);
        marks.forEach(mark -> this.watched.computeIfAbsent(mark, key -> new HashSet<>()).add(now.name));
    }

    private void scan(ClassNode node) {
        for (MethodNode method : node.methods) {
            boolean getter = this.getters.contains(node.name + "." + method.name + method.desc);
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode read && read.getOpcode() == Opcodes.GETSTATIC) {
                    if (!getter && this.watched.containsKey("F " + read.owner + "." + read.name + read.desc)
                            && !takesNull(read)) {
                        this.drop("F " + read.owner + "." + read.name + read.desc);
                    }
                } else if (insn instanceof MethodInsnNode call) {
                    if (this.watched.containsKey("M " + call.name + call.desc) && !takesNull(call)) {
                        this.drop("M " + call.name + call.desc);
                    }
                } else if (insn instanceof InvokeDynamicInsnNode dynamic) {
                    this.dropNamed(dynamic.bsm);
                    for (Object argument : dynamic.bsmArgs) {
                        this.dropNamed(argument);
                    }
                } else if (insn instanceof LdcInsnNode constant) {
                    this.dropNamed(constant.cst);
                }
            }
        }
    }

    private void dropNamed(Object constant) {
        if (constant instanceof Handle handle) {
            this.drop("F " + handle.getOwner() + "." + handle.getName() + handle.getDesc());
            this.drop("M " + handle.getName() + handle.getDesc());
        } else if (constant instanceof ConstantDynamic dynamic) {
            this.dropNamed(dynamic.getBootstrapMethod());
            for (int i = 0; i < dynamic.getBootstrapMethodArgumentCount(); i++) {
                this.dropNamed(dynamic.getBootstrapMethodArgument(i));
            }
        } else if (constant instanceof String text) {
            this.drop("S " + text);
        }
    }

    private void drop(String mark) {
        Set<String> owners = this.watched.get(mark);
        if (owners != null) {
            this.safe.removeAll(owners);
        }
    }

    private static boolean takesNull(AbstractInsnNode read) {
        AbstractInsnNode next = findReal(read.getNext());
        if (next == null) {
            return false;
        }
        int opcode = next.getOpcode();
        if (opcode == Opcodes.IF_ACMPEQ || opcode == Opcodes.IF_ACMPNE || opcode == Opcodes.INSTANCEOF) {
            return true;
        }
        if (next instanceof MethodInsnNode call) {
            return opcode == Opcodes.INVOKESTATIC && TAKES_NULL.contains(call.owner + "." + call.name + call.desc);
        }
        if (next instanceof VarInsnNode store && opcode == Opcodes.ASTORE) {
            AbstractInsnNode load = findReal(next.getNext());
            return load instanceof VarInsnNode loaded && load.getOpcode() == Opcodes.ALOAD && loaded.var == store.var
                    && isQuiet(findNullSide(findReal(load.getNext())), store.var);
        }
        if (opcode == Opcodes.DUP) {
            AbstractInsnNode side = findNullSide(findReal(next.getNext()));
            return side != null && side.getOpcode() == Opcodes.POP && isQuiet(findReal(side.getNext()), -1);
        }
        return isQuiet(findNullSide(next), -1);
    }

    private static @Nullable AbstractInsnNode findNullSide(@Nullable AbstractInsnNode test) {
        if (test instanceof JumpInsnNode jump && test.getOpcode() == Opcodes.IFNULL) {
            return findReal(jump.label);
        }
        return test != null && test.getOpcode() == Opcodes.IFNONNULL ? findReal(test.getNext()) : null;
    }

    private static boolean isQuiet(@Nullable AbstractInsnNode start, int slot) {
        AbstractInsnNode at = start;
        for (int steps = 0; at != null && steps < MOST_STEPS; steps++) {
            int opcode = at.getOpcode();
            if (opcode == Opcodes.ATHROW || (at instanceof MethodInsnNode call && (call.name.startsWith("throw")
                    || call.name.startsWith("requireNonNull") || call.name.startsWith("checkNotNull")))) {
                return false;
            }
            if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
                return true;
            }
            boolean branch = at instanceof JumpInsnNode || at instanceof TableSwitchInsnNode
                    || at instanceof LookupSwitchInsnNode;
            if (slot < 0) {
                if (branch || (opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE) || opcode == Opcodes.PUTSTATIC
                        || opcode == Opcodes.PUTFIELD) {
                    return true;
                }
            } else if (at instanceof VarInsnNode local && local.var == slot) {
                return opcode == Opcodes.ASTORE;
            } else if (branch) {
                return false;
            }
            at = findReal(at.getNext());
        }
        return false;
    }

    private static @Nullable AbstractInsnNode findReal(@Nullable AbstractInsnNode from) {
        AbstractInsnNode at = from;
        while (at != null && at.getOpcode() < 0) {
            at = at.getNext();
        }
        return at;
    }
}
