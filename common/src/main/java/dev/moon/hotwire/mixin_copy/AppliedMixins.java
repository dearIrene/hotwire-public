package dev.moon.hotwire.mixin_copy;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

public final class AppliedMixins {
    private static final String MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";

    private AppliedMixins() {
    }

    public static Set<String> of(byte[] classBytes, byte @Nullable [] original) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, ClassReader.SKIP_CODE);
        Set<String> mixins = new HashSet<>();
        Set<String> own = new HashSet<>();
        if (original != null) {
            own.addAll(Arrays.asList(new ClassReader(original).getInterfaces()));
        }
        for (String face : node.interfaces) {
            if (!own.contains(face)) {
                mixins.add(face.replace('/', '.'));
            }
        }
        for (MethodNode method : node.methods) {
            collect(method.visibleAnnotations, mixins);
        }
        for (FieldNode field : node.fields) {
            collect(field.visibleAnnotations, mixins);
        }
        return mixins;
    }

    private static void collect(@Nullable List<AnnotationNode> annotations, Set<String> into) {
        if (annotations == null) {
            return;
        }
        for (AnnotationNode annotation : annotations) {
            if (!annotation.desc.equals(MERGED) || annotation.values == null) {
                continue;
            }
            for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                if ("mixin".equals(annotation.values.get(i)) && annotation.values.get(i + 1) instanceof String name) {
                    into.add(name);
                }
            }
        }
    }
}
