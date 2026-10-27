package dev.moon.hotwire.redefine;

import dev.moon.hotwire.classfile.ClassHeader;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;

final class InheritedMethods {
    private InheritedMethods() {
    }

    static Set<String> find(@Nullable String superName, Function<String, byte @Nullable []> mixedOf) {
        Set<String> found = new HashSet<>();
        String at = superName;
        while (at != null && !at.equals("java/lang/Object")) {
            byte[] bytes = mixedOf.apply(at);
            if (bytes == null) {
                break;
            }
            ClassHeader header = ClassHeader.of(bytes);
            for (ClassHeader.Member method : header.methods()) {
                if ((method.access() & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0) {
                    found.add(method.name() + method.desc());
                }
            }
            at = header.superName();
        }
        return found;
    }
}
