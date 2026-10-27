package dev.moon.hotwire.mixin_copy;

import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

public final class PluginShape {
    private static final String CLASS_NODE = "Lorg/objectweb/asm/tree/ClassNode;";

    private PluginShape() {
    }

    private static final Set<String> REGISTRIES = Set.of("org/spongepowered/asm/mixin/injection/InjectionPoint",
            "org/spongepowered/asm/mixin/injection/selectors/TargetSelector",
            "org/spongepowered/asm/mixin/injection/struct/InjectionInfo");

    public static boolean transforms(String plugin, Function<String, byte @Nullable []> bytesOf) {
        String at = plugin.replace('.', '/');
        for (int depth = 0; depth < 8 && at != null && !at.startsWith("java/")
                && !at.startsWith("org/spongepowered/"); depth++) {
            byte[] bytes = bytesOf.apply(at);
            if (bytes == null) {
                return false;
            }
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            for (MethodNode method : node.methods) {
                if ((method.name.equals("preApply") || method.name.equals("postApply"))
                        && method.desc.contains(CLASS_NODE) && hasBody(method)) {
                    return true;
                }
                if (isRegisteringWithMixin(method)) {
                    return true;
                }
            }
            at = node.superName;
        }
        return false;
    }

    public static boolean isReshapingFirstOnly(String plugin, Function<String, byte @Nullable []> bytesOf) {
        boolean first = false;
        String at = plugin.replace('.', '/');
        for (int depth = 0; depth < 8 && at != null && !at.startsWith("java/")
                && !at.startsWith("org/spongepowered/"); depth++) {
            byte[] bytes = bytesOf.apply(at);
            if (bytes == null) {
                return false;
            }
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            for (MethodNode method : node.methods) {
                boolean shapes = method.desc.contains(CLASS_NODE) && hasBody(method);
                if (isRegisteringWithMixin(method) || (shapes && method.name.equals("postApply"))) {
                    return false;
                }
                first |= shapes && method.name.equals("preApply");
            }
            at = node.superName;
        }
        return first;
    }

    private static boolean isRegisteringWithMixin(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.name.equals("register")
                    && REGISTRIES.contains(call.owner)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasBody(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() >= 0 && insn.getOpcode() != Opcodes.RETURN) {
                return true;
            }
        }
        return false;
    }
}
