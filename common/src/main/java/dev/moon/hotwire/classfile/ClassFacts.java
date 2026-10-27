package dev.moon.hotwire.classfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public record ClassFacts(String name, @Nullable String superName, List<String> interfaces, int access,
        List<Member> fields, List<Member> methods) {
    private static final Map<byte[], ClassFacts> READ = Collections.synchronizedMap(new WeakHashMap<>());

    public ClassFacts {
        interfaces = List.copyOf(interfaces);
        fields = List.copyOf(fields);
        methods = List.copyOf(methods);
    }

    public record Member(String name, String desc, int access) {
        public boolean isStatic() {
            return (this.access & Opcodes.ACC_STATIC) != 0;
        }
    }

    public static ClassFacts of(byte[] bytes) {
        ClassFacts known = READ.get(bytes);
        if (known != null) {
            return known;
        }
        ClassFacts read = read(bytes);
        READ.put(bytes, read);
        return read;
    }

    public boolean isInterface() {
        return (this.access & Opcodes.ACC_INTERFACE) != 0;
    }

    public List<String> findAbove() {
        List<String> above = new ArrayList<>(this.interfaces.size() + 1);
        if (this.superName != null) {
            above.add(this.superName);
        }
        above.addAll(this.interfaces);
        return above;
    }

    public @Nullable Member findMethod(String name, String desc) {
        for (Member method : this.methods) {
            if (method.name().equals(name) && method.desc().equals(desc)) {
                return method;
            }
        }
        return null;
    }

    public @Nullable Member findField(String name, String desc) {
        for (Member field : this.fields) {
            if (field.name().equals(name) && field.desc().equals(desc)) {
                return field;
            }
        }
        return null;
    }

    private static ClassFacts read(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        List<Member> fields = new ArrayList<>();
        List<Member> methods = new ArrayList<>();
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public @Nullable FieldVisitor visitField(int access, String name, String descriptor,
                    @Nullable String signature, @Nullable Object value) {
                fields.add(new Member(name, descriptor, access));
                return null;
            }

            @Override
            public @Nullable MethodVisitor visitMethod(int access, String name, String descriptor,
                    @Nullable String signature, String @Nullable [] exceptions) {
                methods.add(new Member(name, descriptor, access));
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return new ClassFacts(reader.getClassName(), reader.getSuperName(), List.of(reader.getInterfaces()),
                reader.getAccess(), fields, methods);
    }
}
