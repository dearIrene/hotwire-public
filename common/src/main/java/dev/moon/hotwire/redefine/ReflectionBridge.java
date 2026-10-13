package dev.moon.hotwire.redefine;

import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

public final class ReflectionBridge {
    private static final String CLASS = "java/lang/Class";
    private static final String METHOD = "java/lang/reflect/Method";
    private static final String FIELD = "java/lang/reflect/Field";
    private static final String BRIDGE = SidecarReflection.class.getName().replace('.', '/');

    static final Map<String, String> CALLS = Map.ofEntries(
            Map.entry(CLASS + ".getDeclaredMethod", "(Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/Class;)"
                    + "Ljava/lang/reflect/Method;"),
            Map.entry(CLASS + ".getMethod", "(Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/Class;)"
                    + "Ljava/lang/reflect/Method;"),
            Map.entry(CLASS + ".getDeclaredMethods", "(Ljava/lang/Class;)[Ljava/lang/reflect/Method;"),
            Map.entry(CLASS + ".getMethods", "(Ljava/lang/Class;)[Ljava/lang/reflect/Method;"),
            Map.entry(CLASS + ".getDeclaredField", "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/reflect/Field;"),
            Map.entry(CLASS + ".getField", "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/reflect/Field;"),
            Map.entry(CLASS + ".getDeclaredFields", "(Ljava/lang/Class;)[Ljava/lang/reflect/Field;"),
            Map.entry(CLASS + ".getFields", "(Ljava/lang/Class;)[Ljava/lang/reflect/Field;"),
            Map.entry(CLASS + ".isInstance", "(Ljava/lang/Class;Ljava/lang/Object;)Z"),
            Map.entry(CLASS + ".cast", "(Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/Object;"),
            Map.entry(CLASS + ".isAssignableFrom", "(Ljava/lang/Class;Ljava/lang/Class;)Z"),
            Map.entry(METHOD + ".invoke", "(Ljava/lang/reflect/Method;Ljava/lang/Object;[Ljava/lang/Object;)"
                    + "Ljava/lang/Object;"),
            Map.entry(FIELD + ".get", "(Ljava/lang/reflect/Field;Ljava/lang/Object;)Ljava/lang/Object;"),
            Map.entry(FIELD + ".set", "(Ljava/lang/reflect/Field;Ljava/lang/Object;Ljava/lang/Object;)V"),
            Map.entry(FIELD + ".getInt", "(Ljava/lang/reflect/Field;Ljava/lang/Object;)I"),
            Map.entry(FIELD + ".getLong", "(Ljava/lang/reflect/Field;Ljava/lang/Object;)J"),
            Map.entry(FIELD + ".getBoolean", "(Ljava/lang/reflect/Field;Ljava/lang/Object;)Z"),
            Map.entry(FIELD + ".getDouble", "(Ljava/lang/reflect/Field;Ljava/lang/Object;)D"),
            Map.entry(FIELD + ".getFloat", "(Ljava/lang/reflect/Field;Ljava/lang/Object;)F"),
            Map.entry(FIELD + ".setInt", "(Ljava/lang/reflect/Field;Ljava/lang/Object;I)V"),
            Map.entry(FIELD + ".setLong", "(Ljava/lang/reflect/Field;Ljava/lang/Object;J)V"),
            Map.entry(FIELD + ".setBoolean", "(Ljava/lang/reflect/Field;Ljava/lang/Object;Z)V"),
            Map.entry(FIELD + ".setDouble", "(Ljava/lang/reflect/Field;Ljava/lang/Object;D)V"),
            Map.entry(FIELD + ".setFloat", "(Ljava/lang/reflect/Field;Ljava/lang/Object;F)V"));
    private static final Set<String> OWNERS = Set.of(CLASS, METHOD, FIELD);

    private ReflectionBridge() {
    }

    static boolean names(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        char[] buffer = new char[reader.getMaxStringLength()];
        for (int i = 1; i < reader.getItemCount(); i++) {
            int offset = reader.getItem(i);
            if (offset > 0 && reader.readByte(offset - 1) == 7 && OWNERS.contains(reader.readUTF8(offset, buffer))) {
                return true;
            }
        }
        return false;
    }

    public static byte[] rewrite(byte[] classBytes) {
        if (!names(classBytes)) {
            return classBytes;
        }
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        if (!DuckBridge.raiseForInvokeDynamic(node) || !rewrite(node)) {
            return classBytes;
        }
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    static boolean rewrite(ClassNode node) {
        if (node.name.equals(BRIDGE)) {
            return false;
        }
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                    String descriptor = CALLS.get(call.owner + "." + call.name);
                    if (descriptor != null && hasSameArgs(descriptor, call)) {
                        method.instructions.set(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, call.name,
                                descriptor, false));
                        changed = true;
                    }
                }
            }
        }
        return changed;
    }

    private static boolean hasSameArgs(String bridge, MethodInsnNode call) {
        String receiver = "L" + call.owner + ";";
        return bridge.equals("(" + receiver + call.desc.substring(1));
    }
}
