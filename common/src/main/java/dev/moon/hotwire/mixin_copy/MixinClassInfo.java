package dev.moon.hotwire.mixin_copy;

import java.util.HashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

public record MixinClassInfo(Set<String> targets, Set<String> interfaces, String superName, boolean isInterface) {
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";

    public MixinClassInfo {
        targets = Set.copyOf(targets);
        interfaces = Set.copyOf(interfaces);
    }

    public static MixinClassInfo read(byte[] bytes) {
        Set<String> targets = new HashSet<>();
        ClassReader reader = new ClassReader(bytes);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public @Nullable AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                return MIXIN.equals(descriptor) ? new Targets(targets) : null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        Set<String> interfaces = new HashSet<>();
        for (String face : reader.getInterfaces()) {
            interfaces.add(face.replace('/', '.'));
        }
        return new MixinClassInfo(targets, interfaces, reader.getSuperName().replace('/', '.'),
                (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0);
    }

    public static Set<String> readNamed(byte[] bytes) {
        Set<String> named = new HashSet<>();

        ClassVisitor everything = new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, @Nullable String signature,
                    String @Nullable [] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                        return new AnnotationVisitor(Opcodes.ASM9) { };
                    }
                };
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, @Nullable String signature,
                    @Nullable Object value) {
                return new FieldVisitor(Opcodes.ASM9) { };
            }

            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                return new AnnotationVisitor(Opcodes.ASM9) { };
            }
        };
        new ClassReader(bytes).accept(new ClassRemapper(everything, new Remapper() {
            @Override
            public String map(String internalName) {
                named.add(internalName);
                return internalName;
            }
        }), ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return named;
    }

    private static final class Targets extends AnnotationVisitor {
        private final Set<String> into;

        Targets(Set<String> into) {
            super(Opcodes.ASM9);
            this.into = into;
        }

        @Override
        public @Nullable AnnotationVisitor visitArray(String name) {
            return "value".equals(name) || "targets".equals(name) ? this : null;
        }

        @Override
        public void visit(@Nullable String name, Object value) {
            if (value instanceof Type type) {
                this.into.add(type.getInternalName());
            } else if (value instanceof String target && !target.isBlank()) {
                this.into.add(target.replace('.', '/'));
            }
        }
    }
}
