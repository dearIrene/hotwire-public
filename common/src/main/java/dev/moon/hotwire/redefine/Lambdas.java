package dev.moon.hotwire.redefine;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

final class Lambdas {
    private static final Handle CLASS_DATA = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/MethodHandles",
            "classData",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;", false);

    private record Body(WeakReference<Class<?>> owner, String name, MethodType type) {
    }

    private static final Map<Class<?>, Body> BODIES = Collections.synchronizedMap(new WeakHashMap<>());

    private Lambdas() {
    }

    static @Nullable Method findBody(Class<?> spun) {
        Body body = BODIES.get(spun);
        Class<?> owner = body == null ? null : body.owner().get();
        if (body == null || owner == null) {
            return null;
        }
        try {
            return owner.getDeclaredMethod(body.name(), body.type().parameterArray());
        } catch (NoSuchMethodException | LinkageError gone) {
            return null;
        }
    }

    static CallSite spin(MethodHandles.Lookup side, String method, MethodType factory, MethodType erased,
            MethodHandle body) throws ReflectiveOperationException {
        Class<?> face = factory.returnType();
        String name = side.lookupClass().getPackageName().replace('.', '/') + "/Lambda$hotwire";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, name, null, "java/lang/Object",
                new String[] {Type.getInternalName(face)});
        Type[] captured = Type.getArgumentTypes(factory.toMethodDescriptorString());
        for (int i = 0; i < captured.length; i++) {
            writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "c" + i, captured[i].getDescriptor(), null,
                    null).visitEnd();
        }

        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PRIVATE, "<init>",
                Type.getMethodDescriptor(Type.VOID_TYPE, captured), null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        int slot = 1;
        for (int i = 0; i < captured.length; i++) {
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitVarInsn(captured[i].getOpcode(Opcodes.ILOAD), slot);
            init.visitFieldInsn(Opcodes.PUTFIELD, name, "c" + i, captured[i].getDescriptor());
            slot += captured[i].getSize();
        }
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        String descriptor = erased.toMethodDescriptorString();
        MethodVisitor call = writer.visitMethod(Opcodes.ACC_PUBLIC, method, descriptor, null, null);
        call.visitCode();
        call.visitLdcInsn(new ConstantDynamic("_", "Ljava/lang/invoke/MethodHandle;", CLASS_DATA));
        for (int i = 0; i < captured.length; i++) {
            call.visitVarInsn(Opcodes.ALOAD, 0);
            call.visitFieldInsn(Opcodes.GETFIELD, name, "c" + i, captured[i].getDescriptor());
        }
        Type[] arguments = Type.getArgumentTypes(descriptor);
        slot = 1;
        for (Type argument : arguments) {
            call.visitVarInsn(argument.getOpcode(Opcodes.ILOAD), slot);
            slot += argument.getSize();
        }
        Type[] all = new Type[captured.length + arguments.length];
        System.arraycopy(captured, 0, all, 0, captured.length);
        System.arraycopy(arguments, 0, all, captured.length, arguments.length);
        Type returned = Type.getReturnType(descriptor);
        call.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "invoke",
                Type.getMethodDescriptor(returned, all), false);
        call.visitInsn(returned.getOpcode(Opcodes.IRETURN));
        call.visitMaxs(0, 0);
        call.visitEnd();
        writer.visitEnd();

        MethodHandles.Lookup made = side.defineHiddenClassWithClassData(writer.toByteArray(), DuckCalls.loosen(body),
                true);
        try {
            Method called = side.revealDirect(body).reflectAs(Method.class, side);
            BODIES.put(made.lookupClass(), new Body(new WeakReference<>(called.getDeclaringClass()),
                    called.getName(), MethodType.methodType(called.getReturnType(), called.getParameterTypes())));
        } catch (IllegalArgumentException | ClassCastException | SecurityException adapted) {
        }
        MethodHandle constructor = made.findConstructor(made.lookupClass(), factory.changeReturnType(void.class));
        if (captured.length == 0) {
            return new ConstantCallSite(MethodHandles.constant(face, invoke(constructor)));
        }
        return new ConstantCallSite(constructor.asType(factory));
    }

    private static Object invoke(MethodHandle constructor) throws ReflectiveOperationException {
        try {
            return MethodHandleProxies.asInterfaceInstance(Supplier.class, constructor).get();
        } catch (UndeclaredThrowableException e) {
            throw new ReflectiveOperationException(e.getCause());
        }
    }
}
