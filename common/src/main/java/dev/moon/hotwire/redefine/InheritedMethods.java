package dev.moon.hotwire.redefine;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

final class InheritedMethods {
    private InheritedMethods() {
    }

    static Set<String> find(@Nullable String superName, Function<String, byte @Nullable []> mixedOf) {
        Set<String> found = new HashSet<>();
        ClassVisitor collect = new ClassVisitor(Opcodes.ASM9) {
            @Override
            public @Nullable MethodVisitor visitMethod(int access, String name, String descriptor,
                    @Nullable String signature, String @Nullable [] exceptions) {
                if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0) {
                    found.add(name + descriptor);
                }
                return null;
            }
        };
        String at = superName;
        while (at != null && !at.equals("java/lang/Object")) {
            byte[] bytes = mixedOf.apply(at);
            if (bytes == null) {
                break;
            }
            ClassReader reader = new ClassReader(bytes);
            reader.accept(collect, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            at = reader.getSuperName();
        }
        return found;
    }
}
