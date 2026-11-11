package dev.moon.hotwire.redefine;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.TypeDescriptor;
import java.lang.runtime.ObjectMethods;
import java.util.Set;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;

public final class RecordMethods {
    private static final String OBJECT_METHODS = Type.getInternalName(ObjectMethods.class);
    private static final Handle BOOTSTRAP = new Handle(Opcodes.H_INVOKESTATIC,
            Type.getInternalName(RecordMethods.class), "bootstrap", "(Ljava/lang/invoke/MethodHandles$Lookup;"
                    + "Ljava/lang/String;Ljava/lang/invoke/TypeDescriptor;Ljava/lang/Class;Ljava/lang/String;"
                    + "[Ljava/lang/Object;)Ljava/lang/Object;", false);

    private RecordMethods() {
    }

    static void rewrite(InvokeDynamicInsnNode indy, String target, Set<String> addedFields) {
        if (!indy.bsm.getOwner().equals(OBJECT_METHODS)) {
            return;
        }
        Object[] parts = indy.bsmArgs.clone();
        boolean added = false;
        for (int i = 0; i < parts.length; i++) {
            if (parts[i] instanceof Handle getter && getter.getTag() == Opcodes.H_GETFIELD
                    && getter.getOwner().equals(target)
                    && addedFields.contains(getter.getName() + getter.getDesc())) {
                parts[i] = getter.getDesc();
                added = true;
            }
        }
        if (added) {
            indy.bsm = BOOTSTRAP;
            indy.bsmArgs = parts;
        }
    }

    public static Object bootstrap(MethodHandles.Lookup caller, String name, TypeDescriptor type, Class<?> recordClass,
            String names, Object... parts) throws Throwable {
        String[] components = names.split(";", -1);
        MethodHandle[] getters = new MethodHandle[parts.length];
        for (int i = 0; i < parts.length; i++) {
            if (parts[i] instanceof MethodHandle getter) {
                getters[i] = getter;
                continue;
            }
            Class<?> read = MethodType.fromMethodDescriptorString("()" + parts[i], recordClass.getClassLoader())
                    .returnType();
            getters[i] = Linker.bootstrapForeignField(caller, components[i], MethodType.methodType(read, recordClass),
                    recordClass, Linker.GET_FIELD).dynamicInvoker();
        }
        return ObjectMethods.bootstrap(caller, name, type, recordClass, names, getters);
    }
}
