package dev.moon.hotwire.redefine;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ShutOverrides {
    private record Shut(String owner, String method, String mod, String overriding) {
    }

    private static final String SHUT = ShutOverrides.class.getName().replace('.', '/');
    private static final String RENAMED = "hotwire$override$";
    private static final String ORIGINAL = "hotwire$original$";
    private static final Logger LOGGER = LoggerFactory.getLogger(ShutOverrides.class);

    private static final Map<String, List<Shut>> BY_OVERRIDING = new ConcurrentHashMap<>();
    private static final ClassValue<Map<String, Optional<MethodHandle>>> FOUND = new ClassValue<>() {
        @Override
        protected Map<String, Optional<MethodHandle>> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    private ShutOverrides() {
    }

    public static void register(String mod, String owner, String method, String overriding) {
        BY_OVERRIDING.computeIfAbsent(overriding, key -> new CopyOnWriteArrayList<>())
                .add(new Shut(owner, method, mod, overriding));
    }

    private static List<Shut> findAll() {
        List<Shut> all = new ArrayList<>();
        BY_OVERRIDING.values().forEach(all::addAll);
        return all;
    }

    static void clear() {
        BY_OVERRIDING.clear();
    }

    public static Set<String> findOwnersFor(Set<String> mods) {
        Set<String> owners = new HashSet<>();
        for (Shut shut : findAll()) {
            if (mods.contains(shut.mod())) {
                owners.add(shut.owner());
            }
        }
        return owners;
    }

    public static byte[] renameOverrides(byte[] classBytes) {
        if (BY_OVERRIDING.isEmpty()) {
            return classBytes;
        }

        List<Shut> own = BY_OVERRIDING.get(new ClassReader(classBytes).getClassName());
        if (own == null) {
            return classBytes;
        }
        Map<String, Shut> overridden = new HashMap<>();
        own.forEach(shut -> overridden.put(shut.method(), shut));
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        for (MethodNode method : node.methods) {
            if (overridden.containsKey(method.name + method.desc)) {
                method.name = RENAMED + method.name;
            }
            for (AbstractInsnNode insn : method.instructions) {
                if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESPECIAL
                        || call.owner.equals(node.name)) {
                    continue;
                }
                Shut shut = overridden.get(call.name + call.desc);
                if (shut != null) {
                    method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, shut.owner(),
                            ORIGINAL + call.name, call.desc, false));
                }
            }
        }
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    static byte[] addDispatch(String name, byte[] mixed, Predicate<String> on) {
        Set<String> methods = new HashSet<>();
        for (Shut one : findAll()) {
            if (one.owner().equals(name) && on.test(one.mod())) {
                methods.add(one.method());
            }
        }
        if (methods.isEmpty()) {
            return mixed;
        }
        ClassNode node = new ClassNode();
        new ClassReader(mixed).accept(node, ClassReader.EXPAND_FRAMES);
        for (String shut : methods) {
            MethodNode method = findMethod(node, shut);
            if (method == null || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC)) != 0) {
                continue;
            }
            int flags = method.access & (Opcodes.ACC_VARARGS | Opcodes.ACC_STRICT);
            MethodNode original = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC | flags,
                    ORIGINAL + method.name, method.desc, null, method.exceptions.toArray(String[]::new));
            method.accept(original);
            node.methods.add(original);
            method.instructions = makeDispatch(node.name, method);
            method.tryCatchBlocks.clear();
            method.localVariables = null;
            method.visibleLocalVariableAnnotations = null;
            method.invisibleLocalVariableAnnotations = null;
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static @Nullable MethodNode findMethod(ClassNode node, String method) {
        for (MethodNode one : node.methods) {
            if ((one.name + one.desc).equals(method)) {
                return one;
            }
        }
        return null;
    }

    private static InsnList makeDispatch(String owner, MethodNode method) {
        Type[] arguments = Type.getArgumentTypes(method.desc);
        Type returned = Type.getReturnType(method.desc);
        int slot = 1;
        for (Type argument : arguments) {
            slot += argument.getSize();
        }
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;",
                false));
        code.add(new LdcInsnNode(method.name + method.desc));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SHUT, "find",
                "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/invoke/MethodHandle;", false));
        code.add(new VarInsnNode(Opcodes.ASTORE, slot));
        code.add(new VarInsnNode(Opcodes.ALOAD, slot));
        LabelNode own = new LabelNode();
        code.add(new JumpInsnNode(Opcodes.IFNULL, own));
        code.add(new VarInsnNode(Opcodes.ALOAD, slot));
        loadArguments(code, arguments);
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "invokeExact",
                "(L" + owner + ";" + method.desc.substring(1), false));
        code.add(new InsnNode(returned.getOpcode(Opcodes.IRETURN)));
        code.add(own);
        Object[] locals = new Object[arguments.length + 2];
        locals[0] = owner;
        for (int i = 0; i < arguments.length; i++) {
            locals[i + 1] = toFrameType(arguments[i]);
        }
        locals[arguments.length + 1] = "java/lang/invoke/MethodHandle";
        code.add(new FrameNode(Opcodes.F_NEW, locals.length, locals, 0, new Object[0]));
        loadArguments(code, arguments);
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner, ORIGINAL + method.name, method.desc, false));
        code.add(new InsnNode(returned.getOpcode(Opcodes.IRETURN)));
        return code;
    }

    private static void loadArguments(InsnList code, Type[] arguments) {
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        int slot = 1;
        for (Type argument : arguments) {
            code.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), slot));
            slot += argument.getSize();
        }
    }

    private static Object toFrameType(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.INTEGER;
            case Type.FLOAT -> Opcodes.FLOAT;
            case Type.LONG -> Opcodes.LONG;
            case Type.DOUBLE -> Opcodes.DOUBLE;
            default -> type.getSort() == Type.ARRAY ? type.getDescriptor() : type.getInternalName();
        };
    }

    public static @Nullable MethodHandle find(Class<?> type, String method) {
        return FOUND.get(type).computeIfAbsent(method, key -> Optional.ofNullable(findOverride(type, key)))
                .orElse(null);
    }

    private static @Nullable Shut findShut(String method, String overriding) {
        for (Shut shut : BY_OVERRIDING.getOrDefault(overriding, List.of())) {
            if (shut.method().equals(method)) {
                return shut;
            }
        }
        return null;
    }

    private static @Nullable MethodHandle findOverride(Class<?> type, String method) {
        int open = method.indexOf('(');
        String name = method.substring(0, open);
        for (Class<?> at = type; at != null; at = at.getSuperclass()) {
            Shut shut = findShut(method, at.getName().replace('.', '/'));
            if (shut == null) {
                continue;
            }
            try {
                MethodType shape = MethodType.fromMethodDescriptorString(method.substring(open), at.getClassLoader());
                Class<?> owner = Class.forName(shut.owner().replace('/', '.'), false, at.getClassLoader());
                return MethodHandles.privateLookupIn(at, MethodHandles.lookup()).findVirtual(at, RENAMED + name, shape)
                        .asType(shape.insertParameterTypes(0, owner));
            } catch (ReflectiveOperationException e) {
                LOGGER.error("[Hotwire] {}'s override of {}.{} in {} was not found; the game's own runs", shut.mod(),
                        shut.owner(), method, at.getName(), e);
                return null;
            }
        }
        return null;
    }
}
