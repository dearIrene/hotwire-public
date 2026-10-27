package dev.moon.hotwire.redefine;

import dev.moon.hotwire.classfile.ClassHeader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

final class BatchShapes {
    private static final String MADE = OffWindowObjects.class.getName().replace('.', '/');
    private static final String LAMBDA_SITES = LambdaSites.class.getName().replace('.', '/');

    private static final Map<byte[], ClassNode> CONSTRUCTORS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Pattern MERGED = Pattern.compile("\\b(?:md|fd)[0-9a-f]{6}\\$");

    private static final Pattern SESSION = Pattern.compile("_\\$(md|fd)\\$[0-9a-f]{6}\\$[0-9a-f]+");
    private static final Pattern HANDLER = Pattern.compile("\\$(?!lambda\\$)[a-z]{3}[0-9a-f]{3}\\$");
    private static final Pattern ARGS = Pattern.compile("Args\\$\\d+");
    private static final Pattern COUNTED = Pattern.compile("(md#\\$[^\\s(;]*\\$(?:#|\\d+))\\$[0-9a-f]+");

    private BatchShapes() {
    }

    private static String describeOperand(AbstractInsnNode insn, boolean masked) {
        return switch (insn) {
            case MethodInsnNode call -> call.owner + "." + (masked ? GeneratedNames.mask(call.name) : call.name)
                    + call.desc;
            case FieldInsnNode field -> field.owner + "." + (masked ? GeneratedNames.mask(field.name) : field.name)
                    + field.desc;
            case TypeInsnNode type -> type.desc;
            case LdcInsnNode ldc -> String.valueOf(ldc.cst);
            case IntInsnNode number -> String.valueOf(number.operand);
            case VarInsnNode local -> String.valueOf(local.var);
            case IincInsnNode increment -> increment.var + "+" + increment.incr;
            case InvokeDynamicInsnNode dynamic -> (masked ? GeneratedNames.mask(dynamic.name) : dynamic.name)
                    + dynamic.desc + (masked && isLambdaLink(dynamic) ? "lambda" : dynamic.bsm);
            case MultiANewArrayInsnNode array -> array.desc + array.dims;
            default -> "";
        };
    }

    static List<String> describeMasked(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        List<String> out = new ArrayList<>();
        node.fields.forEach(field -> out.add(GeneratedNames.mask(field.name) + field.desc + field.access));
        for (MethodNode method : node.methods) {
            StringBuilder text = new StringBuilder(GeneratedNames.mask(method.name)).append(method.desc)
                    .append(method.access);
            for (AbstractInsnNode insn : method.instructions) {
                if (insn.getOpcode() >= 0) {
                    text.append(' ').append(insn.getOpcode()).append(describeOperand(insn, true));
                }
            }
            out.add(text.toString());
        }
        out.addAll(node.interfaces);

        out.replaceAll(text -> HANDLER.matcher(SESSION.matcher(COUNTED.matcher(ARGS.matcher(MERGED.matcher(text)
                .replaceAll("md#\\$")).replaceAll("Args\\$#")).replaceAll("$1\\$#")).replaceAll("_\\$$1\\$#\\$#"))
                .replaceAll("\\$#\\$"));
        Collections.sort(out);
        return out;
    }

    static List<String> findConstructors(byte[] bytes, byte @Nullable [] original) {
        return findConstructors(bytes, original, true);
    }

    static List<String> findConstructors(byte[] bytes, byte @Nullable [] original, boolean masked) {
        ClassNode node = readConstructors(bytes);
        Set<String> own = new HashSet<>();
        if (original != null) {
            Set<String> had = ConstructedFields.readFields(original).keys();
            for (FieldNode field : node.fields) {
                if ((field.access & Opcodes.ACC_STATIC) == 0 && !had.contains(field.name + field.desc)) {
                    own.add(field.name + field.desc);
                }
            }
        }
        List<String> out = new ArrayList<>();
        for (MethodNode method : node.methods) {
            if (method.name.equals("<init>")) {
                Set<AbstractInsnNode> ownSetup = findOwnSetup(node.name, method, own);
                StringBuilder text = new StringBuilder(method.desc);
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn.getOpcode() >= 0 && !ownSetup.contains(insn)) {
                        text.append(' ').append(insn.getOpcode()).append(describeOperand(insn, masked));
                    }
                }
                out.add(text.toString());
            }
        }
        return out;
    }

    private static ClassNode readConstructors(byte[] bytes) {
        ClassNode known = CONSTRUCTORS.get(bytes);
        if (known != null) {
            return known;
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, node) {
            @Override
            public @Nullable MethodVisitor visitMethod(int access, String name, String descriptor,
                    @Nullable String signature, String @Nullable [] exceptions) {
                return name.equals("<init>") ? super.visitMethod(access, name, descriptor, signature, exceptions)
                        : null;
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        CONSTRUCTORS.put(bytes, node);
        return node;
    }

    private static boolean isLambdaLink(InvokeDynamicInsnNode dynamic) {
        String owner = dynamic.bsm.getOwner();
        return owner.equals("java/lang/invoke/LambdaMetafactory") || owner.equals(LAMBDA_SITES);
    }

    static String describeFirstDifference(List<String> was, List<String> now) {
        for (int index = 0; index < Math.max(was.size(), now.size()); index++) {
            String one = index < was.size() ? was.get(index) : "";
            String other = index < now.size() ? now.get(index) : "";
            if (!one.equals(other)) {
                int at = 0;
                while (at < one.length() && at < other.length() && one.charAt(at) == other.charAt(at)) {
                    at++;
                }
                int from = Math.max(0, at - 40);
                return "constructor " + index + ": \"" + one.substring(from, Math.min(one.length(), at + 80))
                        + "\" became \"" + other.substring(from, Math.min(other.length(), at + 80)) + "\"";
            }
        }
        return "no difference";
    }

    private static Set<AbstractInsnNode> findOwnSetup(String owner, MethodNode method, Set<String> own) {
        Set<AbstractInsnNode> setup = Collections.newSetFromMap(new IdentityHashMap<>());
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(MADE) && call.name.equals("onMade")
                    && call.getPrevious() != null && call.getPrevious().getPrevious() != null) {
                setup.add(call);
                setup.add(call.getPrevious());
                setup.add(call.getPrevious().getPrevious());
            }
        }
        if (own.isEmpty()) {
            return setup;
        }
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof FieldInsnNode store && store.getOpcode() == Opcodes.PUTFIELD
                    && store.owner.equals(owner) && own.contains(store.name + store.desc)) {
                AbstractInsnNode start = FieldInitializers.findOwnSetupStart(store, own);
                for (AbstractInsnNode at = start; at != null; at = at.getNext()) {
                    setup.add(at);
                    if (at.equals(store)) {
                        break;
                    }
                }
            }
        }
        return setup;
    }

    static Set<String> findMethodsGained(Map<String, byte[]> current, Map<String, byte[]> mixed) {
        Set<String> gained = new HashSet<>();
        mixed.forEach((name, bytes) -> {
            byte[] running = current.get(name);
            if (running == null) {
                return;
            }
            Set<String> had = new HashSet<>();
            ClassHeader.of(running).methods().forEach(method -> had.add(method.name() + method.desc()));
            for (ClassHeader.Member method : ClassHeader.of(bytes).methods()) {
                String key = method.name() + method.desc();
                if (!had.contains(key) && !method.name().equals("<init>") && !method.name().equals("<clinit>")) {
                    gained.add(key);
                }
            }
        });
        return gained;
    }

    static Map<String, Set<String>> findFieldsGained(Map<String, byte[]> current, Map<String, byte[]> mixed) {
        Map<String, Set<String>> gained = new HashMap<>();
        mixed.forEach((name, bytes) -> {
            byte[] running = current.get(name);
            if (running == null) {
                return;
            }
            Set<String> had = new HashSet<>();
            ClassHeader.of(running).fields().forEach(field -> had.add(field.name() + field.desc()));
            Set<String> added = new HashSet<>();
            for (ClassHeader.Member field : ClassHeader.of(bytes).fields()) {
                if (!had.contains(field.name() + field.desc())) {
                    added.add(field.name() + field.desc());
                }
            }
            if (!added.isEmpty()) {
                gained.put(name, added);
            }
        });
        return gained;
    }

    static Set<String> findDucksGained(Map<String, byte[]> current, Map<String, byte[]> mixed,
            Function<String, byte @Nullable []> classBytes) {
        Set<String> ducks = new HashSet<>();
        mixed.forEach((name, bytes) -> {
            byte[] running = current.get(name);
            if (running != null) {
                ducks.addAll(DuckParents.close(List.of(new ClassReader(bytes).getInterfaces()),
                        Set.of(new ClassReader(running).getInterfaces()), classBytes));
            }
        });
        return ducks;
    }
}
