package dev.moon.hotwire.fabric.add;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

final class JoinHooks {
    private static final Set<String> JOIN_METHODS = Set.of("handleLogin", "onGameJoin", "method_11120",
            "placeNewPlayer", "onPlayerConnect", "method_14570");
    private static final byte[] MIXIN = "Lorg/spongepowered/asm/mixin/".getBytes(StandardCharsets.UTF_8);

    private static final int DEPTH = 3;

    private JoinHooks() {
    }

    static boolean isIn(Path jar) {
        try (InputStream in = Files.newInputStream(jar)) {
            return isIn(in, DEPTH);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isIn(InputStream jar, int depth) throws IOException {
        ZipInputStream zip = new ZipInputStream(jar);
        for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
            String name = entry.getName();
            if (name.endsWith(".class")) {
                byte[] bytes = zip.readAllBytes();
                if (contains(bytes, MIXIN) && targetsJoin(bytes)) {
                    return true;
                }
            } else if (depth > 0 && name.endsWith(".jar")
                    && isIn(new ByteArrayInputStream(zip.readAllBytes()), depth - 1)) {
                return true;
            }
        }
        return false;
    }

    static boolean targetsJoin(byte[] bytes) {
        boolean[] found = {false};
        try {
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                        String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override
                                public @Nullable AnnotationVisitor visitArray(String part) {
                                    return !"method".equals(part) ? null : new AnnotationVisitor(Opcodes.ASM9) {
                                        @Override
                                        public void visit(String unnamed, Object value) {
                                            if (value instanceof String target && isJoin(target)) {
                                                found[0] = true;
                                            }
                                        }
                                    };
                                }
                            };
                        }
                    };
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (RuntimeException e) {
            return false;
        }
        return found[0];
    }

    private static boolean isJoin(String target) {
        String name = target.contains(";") ? target.substring(target.lastIndexOf(';') + 1) : target;
        int open = name.indexOf('(');
        return JOIN_METHODS.contains(open < 0 ? name : name.substring(0, open));
    }

    private static boolean contains(byte[] bytes, byte[] part) {
        outer:
        for (int at = 0; at <= bytes.length - part.length; at++) {
            for (int i = 0; i < part.length; i++) {
                if (bytes[at + i] != part[i]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
