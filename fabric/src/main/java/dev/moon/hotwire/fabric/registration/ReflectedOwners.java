package dev.moon.hotwire.fabric.registration;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

final class ReflectedOwners {
    private static final Pattern CLASS_NAME = Pattern.compile("[\\w$]+(\\.[\\w$]+)+");

    private static volatile Function<String, byte @Nullable []> bytesOf = name -> null;

    private static volatile Map<String, String> modOfName = Map.of();

    private static volatile Map<Class<?>, String> answers = new ConcurrentHashMap<>();

    private static final ClassValue<Set<String>> NAMED = new ClassValue<>() {
        @Override
        protected Set<String> computeValue(Class<?> type) {
            Set<String> names = new HashSet<>();
            Class<?> own = type.isHidden() ? type.getNestHost() : type;
            String name = own.getName();
            readNames(name, names);
            int inner = name.indexOf('$');
            if (inner > 0) {
                readNames(name.substring(0, inner), names);
            }
            return Set.copyOf(names);
        }
    };

    private ReflectedOwners() {
    }

    static void readBytesWith(Function<String, byte @Nullable []> bytes) {
        bytesOf = bytes;
    }

    static void useNames(Map<String, String> names) {
        modOfName = Map.copyOf(names);
        answers = new ConcurrentHashMap<>();
    }

    static @Nullable String findOwnerOf(Class<?> type) {
        if (modOfName.isEmpty() || type.isArray() || type.isPrimitive()) {
            return null;
        }
        String answer = answers.computeIfAbsent(type, ReflectedOwners::findAfresh);
        return answer.isEmpty() ? null : answer;
    }

    static Set<String> findAllOwnersOf(Class<?> type) {
        if (modOfName.isEmpty() || type.isArray() || type.isPrimitive() || !Wrappers.isOfAnotherMod(type)) {
            return Set.of();
        }
        Set<String> mods = new HashSet<>();
        for (String name : NAMED.get(type)) {
            String mod = modOfName.get(name);
            if (mod != null) {
                mods.add(mod);
            }
        }
        return mods;
    }

    private static String findAfresh(Class<?> type) {
        if (!Wrappers.isOfAnotherMod(type)) {
            return "";
        }
        for (String name : NAMED.get(type)) {
            String mod = modOfName.get(name);
            if (mod != null) {
                return mod;
            }
        }
        return "";
    }

    private static void addName(String name, Set<String> into) {
        if (!name.startsWith("java.") && !name.startsWith("javax.") && !name.startsWith("jdk.")
                && !name.startsWith("sun.") && !name.startsWith("net.minecraft.") && !name.startsWith("com.mojang.")) {
            into.add(name.intern());
        }
    }

    private static void readNames(String binaryName, Set<String> into) {
        byte[] bytes = bytesOf.apply(binaryName.replace('.', '/'));
        if (bytes == null) {
            return;
        }
        try {
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                        String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                boolean isInterface) {
                            addName(owner.replace('/', '.'), into);
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                            addName(owner.replace('/', '.'), into);
                        }

                        @Override
                        public void visitLdcInsn(Object value) {
                            if (value instanceof String text && CLASS_NAME.matcher(text).matches()) {
                                addName(text, into);
                            } else if (value instanceof Type literal && literal.getSort() == Type.OBJECT) {
                                addName(literal.getClassName(), into);
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (RuntimeException e) {
        }
    }
}
