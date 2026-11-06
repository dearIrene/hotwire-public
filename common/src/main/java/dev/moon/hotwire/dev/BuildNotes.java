package dev.moon.hotwire.dev;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class BuildNotes {
    private static final Set<String> STARTS = Set.of("onInitialize", "onInitializeClient", "onInitializeServer");

    private BuildNotes() {
    }

    public static boolean isWhole(byte @Nullable [] bytes) {
        if (bytes == null || bytes.length < 10) {
            return false;
        }
        try {
            new ClassReader(bytes).accept(new ClassNode(), 0);
            return true;
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            return false;
        }
    }

    public static Set<String> findAddedFields(byte @Nullable [] before, byte @Nullable [] after) {
        Set<String> added = findAnnotatedFields(after);
        added.removeAll(findAnnotatedFields(before));
        return added;
    }

    private static Set<String> findAnnotatedFields(byte @Nullable [] bytes) {
        Set<String> names = new TreeSet<>();
        ClassNode node = read(bytes);
        if (node != null) {
            for (FieldNode field : node.fields) {
                if (field.visibleAnnotations != null && !field.visibleAnnotations.isEmpty()) {
                    names.add(field.name);
                }
            }
        }
        return names;
    }

    public static boolean isStartChanged(byte @Nullable [] before, byte @Nullable [] after) {
        ClassNode was = read(before);
        ClassNode now = read(after);
        if (was == null || now == null) {
            return false;
        }
        for (MethodNode method : now.methods) {
            if (STARTS.contains(method.name) && !readCode(method).equals(readCode(find(was, method)))) {
                return true;
            }
        }
        return false;
    }

    public static SortedSet<String> findChangedMethods(byte @Nullable [] before, byte @Nullable [] after) {
        MethodChanges found = findMethodChanges(before, after);
        SortedSet<String> all = new TreeSet<>(found.changed());
        all.addAll(found.added());
        all.addAll(found.removed());
        return all;
    }

    public record MethodChanges(SortedSet<String> changed, SortedSet<String> added, SortedSet<String> removed,
            @Nullable String file, Map<String, int[]> lines) {
    }

    public static MethodChanges findMethodChanges(byte @Nullable [] before, byte @Nullable [] after) {
        ClassNode was = read(before);
        ClassNode now = read(after);
        MethodChanges found = new MethodChanges(new TreeSet<>(), new TreeSet<>(), new TreeSet<>(),
                now == null ? null : now.sourceFile, new HashMap<>());
        if (was == null || now == null) {
            return found;
        }
        for (MethodNode method : now.methods) {
            MethodNode old = find(was, method);
            boolean added = old == null;
            if (added || !readCode(method).equals(readCode(old))) {
                (added ? found.added() : found.changed()).add(describe(method.name));
                int[] span = findLines(method);
                if (span != null) {
                    found.lines().merge(describe(method.name), span, (a, b) -> new int[] {Math.min(a[0], b[0]),
                        Math.max(a[1], b[1])});
                }
            }
        }
        for (MethodNode method : was.methods) {
            if (find(now, method) == null) {
                found.removed().add(describe(method.name));
            }
        }

        Set<String> both = new TreeSet<>(found.added());
        both.retainAll(found.removed());
        found.added().removeAll(both);
        found.removed().removeAll(both);
        found.changed().addAll(both);
        return found;
    }

    private static String describe(String name) {
        if (name.equals("<init>")) {
            return "constructor";
        }
        if (name.equals("<clinit>")) {
            return "static setup";
        }

        if (name.startsWith("lambda$") && name.lastIndexOf('$') > "lambda$".length()) {
            return "lambda in " + name.substring("lambda$".length(), name.lastIndexOf('$'));
        }
        return name;
    }

    private static @Nullable MethodNode find(ClassNode node, MethodNode like) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(like.name) && method.desc.equals(like.desc)) {
                return method;
            }
        }
        return null;
    }

    private static List<String> readCode(@Nullable MethodNode method) {
        List<String> instructions = new ArrayList<>();
        if (method == null) {
            return instructions;
        }
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() < 0) {
                continue;
            }
            StringBuilder code = new StringBuilder().append(insn.getOpcode());
            switch (insn) {
                case MethodInsnNode call -> code.append(call.owner).append('.').append(call.name).append(call.desc);
                case FieldInsnNode field -> code.append(field.owner).append('.').append(field.name);
                case LdcInsnNode constant -> code.append(constant.cst);
                case TypeInsnNode type -> code.append(type.desc);
                case IntInsnNode value -> code.append(value.operand);
                case VarInsnNode local -> code.append(local.var);
                case IincInsnNode step -> code.append(step.var).append(':').append(step.incr);
                case InvokeDynamicInsnNode dynamic -> code.append(dynamic.name).append(dynamic.desc);
                default -> {
                }
            }
            instructions.add(code.toString());
        }
        return instructions;
    }

    public static Map<String, List<Integer>> findMethodLines(byte @Nullable [] bytes) {
        Map<String, List<Integer>> lines = new HashMap<>();
        ClassNode node = read(bytes);
        if (node == null) {
            return lines;
        }
        for (MethodNode method : node.methods) {
            int[] span = findLines(method);
            if (span != null) {
                lines.merge(describe(method.name), List.of(span[0], span[1]), (x, y) -> List.of(
                        Math.min(x.get(0), y.get(0)), Math.max(x.get(1), y.get(1))));
            }
        }
        return lines;
    }

    private static int @Nullable [] findLines(MethodNode method) {
        int first = Integer.MAX_VALUE;
        int last = -1;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LineNumberNode line) {
                first = Math.min(first, line.line);
                last = Math.max(last, line.line);
            }
        }
        return last < 0 ? null : new int[] {first, last};
    }

    private static @Nullable ClassNode read(byte @Nullable [] bytes) {
        if (!isWhole(bytes)) {
            return null;
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
        return node;
    }
}
