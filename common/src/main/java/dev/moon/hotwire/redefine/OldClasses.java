package dev.moon.hotwire.redefine;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

final class OldClasses {
    private OldClasses() {
    }

    static byte[] write(ClassNode node, int newest, @Nullable ClassReader pool, int flags,
            Function<String, byte @Nullable []> classBytes) {
        int own = node.version & 0xFFFF;
        int version = Math.max(own, newest & 0xFFFF);
        if (version < Opcodes.V1_7 && usesJava7(node)) {
            version = Opcodes.V1_7;
        }
        boolean framesAnew = own < Opcodes.V1_7 && version >= Opcodes.V1_7;
        node.version = version == own ? node.version : version;
        if (version < Opcodes.V1_6) {
            node.methods.forEach(OldClasses::dropFrames);
        }
        ClassWriter writer = framesAnew ? new FrameWriter(pool, classBytes)
                : pool == null ? new ClassWriter(flags) : new ClassWriter(pool, flags);
        node.accept(writer);
        return writer.toByteArray();
    }

    static boolean usesJava7(ClassNode node) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof InvokeDynamicInsnNode) {
                    return true;
                }
                if (insn instanceof LdcInsnNode ldc && (ldc.cst instanceof Handle
                        || (ldc.cst instanceof Type type && type.getSort() == Type.METHOD))) {
                    return true;
                }
                if (insn instanceof MethodInsnNode call && (call.owner.equals("java/lang/invoke/MethodHandle")
                        || call.owner.equals("java/lang/invoke/VarHandle"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void dropFrames(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof FrameNode) {
                method.instructions.remove(insn);
            }
        }
    }

    private static final class FrameWriter extends ClassWriter {
        private static final String OBJECT = "java/lang/Object";
        private final Function<String, byte @Nullable []> classBytes;

        FrameWriter(@Nullable ClassReader pool, Function<String, byte @Nullable []> classBytes) {
            super(pool, COMPUTE_FRAMES);
            this.classBytes = classBytes;
        }

        @Override
        protected String getCommonSuperClass(String first, String second) {
            List<String> above = this.findChain(first);
            if (above.contains(second)) {
                return second;
            }
            List<String> other = this.findChain(second);
            if (other.contains(first)) {
                return first;
            }
            if (this.isInterface(first) || this.isInterface(second)) {
                return OBJECT;
            }
            for (String parent : above) {
                if (other.contains(parent)) {
                    return parent;
                }
            }
            return OBJECT;
        }

        private List<String> findChain(String name) {
            List<String> chain = new ArrayList<>();
            for (String at = name; at != null && !chain.contains(at); at = this.findParent(at)) {
                chain.add(at);
            }
            return chain;
        }

        private @Nullable String findParent(String name) {
            if (name.equals(OBJECT)) {
                return null;
            }
            byte[] bytes = this.read(name);
            String parent = bytes == null ? null : new ClassReader(bytes).getSuperName();
            return parent == null ? OBJECT : parent;
        }

        private boolean isInterface(String name) {
            byte[] bytes = this.read(name);
            return bytes != null && (new ClassReader(bytes).getAccess() & Opcodes.ACC_INTERFACE) != 0;
        }

        private byte @Nullable [] read(String name) {
            byte[] given = this.classBytes.apply(name);
            if (given != null) {
                return given;
            }
            for (ClassLoader loader : new ClassLoader[] {OldClasses.class.getClassLoader(),
                    ClassLoader.getSystemClassLoader()}) {
                try (InputStream in = loader.getResourceAsStream(name + ".class")) {
                    if (in != null) {
                        return in.readAllBytes();
                    }
                } catch (IOException e) {
                    return null;
                }
            }
            return null;
        }
    }
}
