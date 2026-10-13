package dev.moon.hotwire.redefine;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

final class FrameLocks {
    private static final Map<String, Boolean> FREE = new ConcurrentHashMap<>();

    private FrameLocks() {
    }

    static boolean isLockFree(StackWalker.StackFrame frame) {
        Class<?> type = frame.getDeclaringClass();
        String name = frame.getMethodName();
        if (name.equals("<clinit>")) {
            return false;
        }
        String key = type.getName() + '.' + name + frame.getDescriptor();
        Boolean free = FREE.get(key);
        if (free == null) {
            free = readFree(type, name, frame.getDescriptor());
            FREE.putIfAbsent(key, free);
        }
        return free;
    }

    private static boolean readFree(Class<?> type, String name, String descriptor) {
        try (InputStream in = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            if (in == null) {
                return false;
            }
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            for (MethodNode method : node.methods) {
                if (method.name.equals(name) && method.desc.equals(descriptor)) {
                    return (method.access & Opcodes.ACC_SYNCHRONIZED) == 0 && !takesLock(method);
                }
            }
            return false;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean takesLock(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == Opcodes.MONITORENTER
                    || (instruction instanceof MethodInsnNode call && isLockCall(call))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLockCall(MethodInsnNode call) {
        String name = call.name.toLowerCase(Locale.ROOT);
        boolean lockType = call.owner.startsWith("java/util/concurrent/locks/")
                || call.owner.equals("java/util/concurrent/Semaphore")
                || call.owner.equals("jdk/internal/misc/InternalLock");
        return lockType && !name.startsWith("unlock") && (name.contains("lock") || name.contains("acquire"));
    }
}
