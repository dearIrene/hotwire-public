package dev.moon.hotwire.redefine;

import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaConversionException;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class LambdaSites {
    private static final String JDK = "java/lang/invoke/LambdaMetafactory";
    private static final String OWN = LambdaSites.class.getName().replace('.', '/');
    private static final String JDK_NAME = "metafactory";
    private static final String NAME = "linkLambda";
    private static final String DESCRIPTOR = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
            + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;"
            + "Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;";
    private static final Handle REMEMBERING = new Handle(Opcodes.H_INVOKESTATIC, OWN, NAME, DESCRIPTOR, false);

    private static final ClassValue<Map<String, CallSite>> LINKED = new ClassValue<>() {
        @Override
        protected Map<String, CallSite> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    private LambdaSites() {
    }

    static boolean isFactory(Handle bootstrap) {
        return isJdkFactory(bootstrap) || (bootstrap.getOwner().equals(OWN) && bootstrap.getName().equals(NAME));
    }

    private static boolean isJdkFactory(Handle bootstrap) {
        return bootstrap.getOwner().equals(JDK) && bootstrap.getName().equals(JDK_NAME);
    }

    public static byte[] rewrite(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        boolean[] changed = new boolean[1];
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature,
                        exceptions)) {
                    @Override
                    public void visitInvokeDynamicInsn(String site, String shape, Handle bootstrap, Object... args) {
                        boolean plain = isJdkFactory(bootstrap);
                        changed[0] |= plain;
                        super.visitInvokeDynamicInsn(site, shape, plain ? REMEMBERING : bootstrap, args);
                    }
                };
            }
        }, 0);
        return changed[0] ? writer.toByteArray() : classBytes;
    }

    public static CallSite linkLambda(MethodHandles.Lookup caller, String name, MethodType factory, MethodType erased,
            MethodHandle body, MethodType instantiated) throws LambdaConversionException {
        String site;
        try {
            MethodHandleInfo info = caller.revealDirect(body);
            site = name + factory.toMethodDescriptorString() + erased.toMethodDescriptorString()
                    + instantiated.toMethodDescriptorString() + info.getReferenceKind()
                    + info.getDeclaringClass().getName() + '.' + info.getName()
                    + info.getMethodType().toMethodDescriptorString();
        } catch (IllegalArgumentException | SecurityException adapted) {
            return LambdaMetafactory.metafactory(caller, name, factory, erased, body, instantiated);
        }
        Map<String, CallSite> linked = LINKED.get(Linker.getHostOf(caller.lookupClass()));
        CallSite known = linked.get(site);
        if (known != null) {
            return known;
        }
        CallSite made = LambdaMetafactory.metafactory(caller, name, factory, erased, body, instantiated);
        CallSite first = linked.putIfAbsent(site, made);
        return first != null ? first : made;
    }
}
