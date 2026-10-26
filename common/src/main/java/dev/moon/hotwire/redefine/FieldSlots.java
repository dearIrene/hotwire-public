package dev.moon.hotwire.redefine;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

public final class FieldSlots {
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    private FieldSlots() {
    }

    private static volatile Predicate<Class<?>> setUp = type -> true;

    public static void readStaticsWhen(Predicate<Class<?>> isSetUp) {
        setUp = isSetUp;
    }

    public static final class Slot {
        private volatile @Nullable Field field;
        private final Class<?> owner;
        private final String name;
        private final Class<?> type;
        private final int modifiers;
        private final @Nullable Function<@Nullable Object, @Nullable Object> getter;
        private final @Nullable BiConsumer<@Nullable Object, @Nullable Object> setter;

        public static Slot of(Field field) {
            return new Slot(field);
        }

        private Slot(Field field) {
            this.field = field;
            this.owner = field.getDeclaringClass();
            this.name = field.getName();
            this.type = field.getType();
            this.modifiers = field.getModifiers();
            this.getter = null;
            this.setter = null;
        }

        private Slot(Class<?> owner, String name, Class<?> type, int modifiers,
                Function<@Nullable Object, @Nullable Object> getter,
                @Nullable BiConsumer<@Nullable Object, @Nullable Object> setter) {
            this.field = null;
            this.owner = owner;
            this.name = name;
            this.type = type;
            this.modifiers = modifiers;
            this.getter = getter;
            this.setter = setter;
        }

        public String getName() {
            return this.name;
        }

        public Class<?> getType() {
            return this.type;
        }

        public int getModifiers() {
            return this.modifiers;
        }

        public Class<?> getDeclaringClass() {
            return this.owner;
        }

        public boolean isStatic() {
            return Modifier.isStatic(this.modifiers);
        }

        public @Nullable Object get(@Nullable Object holder) throws IllegalAccessException {
            if (holder == null && this.isStatic() && !setUp.test(this.owner)) {
                return null;
            }
            if (this.field != null) {
                return this.field.get(holder);
            }
            Function<@Nullable Object, @Nullable Object> get = this.getter;
            if (get == null) {
                throw new IllegalAccessException(this.owner.getName() + "." + this.name + " cannot be read");
            }
            try {
                return get.apply(holder);
            } catch (RuntimeException | LinkageError e) {
                throw new IllegalAccessException(this.owner.getName() + "." + this.name + ": " + e);
            }
        }

        public void set(@Nullable Object holder, @Nullable Object value) throws IllegalAccessException {
            if (this.field != null) {
                this.field.set(holder, value);
                return;
            }
            BiConsumer<@Nullable Object, @Nullable Object> set = this.setter;
            if (set == null) {
                throw new IllegalAccessException(this.owner.getName() + "." + this.name + " cannot be set");
            }
            try {
                set.accept(holder, value);
            } catch (RuntimeException | LinkageError e) {
                throw new IllegalAccessException(this.owner.getName() + "." + this.name + ": " + e);
            }
        }

        public Field asField() throws NoSuchFieldException {
            Field made = this.field;
            if (made == null) {
                made = this.owner.getDeclaredField(this.name);
                made.setAccessible(true);
                this.field = made;
            }
            return made;
        }

        @Override
        public String toString() {
            return this.owner.getName() + "." + this.name;
        }
    }

    public record Read(List<Slot> slots, Set<String> unloaded) {
        public Read {
            slots = List.copyOf(slots);
            unloaded = Set.copyOf(unloaded);
        }
    }

    private record Entry(int access, String name, String descriptor) {
    }

    public static Read read(Class<?> type, byte @Nullable [] bytes, Predicate<String> loaded) {
        if (bytes == null) {
            return new Read(readByReflection(type), Set.of());
        }
        List<Entry> declared = listFields(bytes);
        Set<String> unloaded = new LinkedHashSet<>();
        for (Entry field : declared) {
            String binary = findClassName(field.descriptor());
            if (binary != null && !isLoaded(binary, loaded)) {
                unloaded.add(binary);
            }
        }
        if (unloaded.isEmpty()) {
            return new Read(readByReflection(type), Set.of());
        }
        List<Slot> slots = new ArrayList<>();
        MethodHandles.Lookup lookup;
        try {
            lookup = MethodHandles.privateLookupIn(type, LOOKUP);
        } catch (IllegalAccessException | SecurityException e) {
            return new Read(slots, unloaded);
        }
        for (Entry field : declared) {
            String binary = findClassName(field.descriptor());
            if (binary != null && unloaded.contains(binary)) {
                continue;
            }
            Class<?> fieldType = findType(field.descriptor(), type.getClassLoader());
            if (fieldType == null) {
                continue;
            }
            boolean isStatic = (field.access() & Opcodes.ACC_STATIC) != 0;
            boolean isFinal = (field.access() & Opcodes.ACC_FINAL) != 0;
            try {
                MethodHandle getter = isStatic ? lookup.findStaticGetter(type, field.name(), fieldType)
                        : lookup.findGetter(type, field.name(), fieldType);
                MethodHandle setter = null;
                if (!isFinal) {
                    setter = isStatic ? lookup.findStaticSetter(type, field.name(), fieldType)
                            : lookup.findSetter(type, field.name(), fieldType);
                }
                slots.add(new Slot(type, field.name(), fieldType, field.access(), asObjectGetter(getter, isStatic),
                        setter == null ? null : asObjectSetter(setter, isStatic)));
            } catch (ReflectiveOperationException | SecurityException e) {
            }
        }
        return new Read(slots, unloaded);
    }

    private static List<Slot> readByReflection(Class<?> type) {
        List<Slot> out = new ArrayList<>();
        try {
            for (Field field : type.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    out.add(new Slot(field));
                } catch (InaccessibleObjectException | SecurityException e) {
                }
            }
        } catch (LinkageError e) {
        }
        return out;
    }

    private static List<Entry> listFields(byte[] bytes) {
        List<Entry> out = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public @Nullable FieldVisitor visitField(int access, String name, String descriptor,
                    @Nullable String signature, @Nullable Object value) {
                out.add(new Entry(access, name, descriptor));
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
        return out;
    }

    static @Nullable String findClassName(String descriptor) {
        Type type = Type.getType(descriptor);
        if (type.getSort() == Type.ARRAY) {
            type = type.getElementType();
        }
        return type.getSort() == Type.OBJECT ? type.getClassName() : null;
    }

    private static boolean isLoaded(String binary, Predicate<String> loaded) {
        return binary.startsWith("java.") || binary.startsWith("javax.") || binary.startsWith("jdk.")
                || binary.startsWith("sun.") || loaded.test(binary);
    }

    private static @Nullable Class<?> findType(String descriptor, @Nullable ClassLoader loader) {
        Type type = Type.getType(descriptor);
        try {
            return switch (type.getSort()) {
                case Type.BOOLEAN -> boolean.class;
                case Type.BYTE -> byte.class;
                case Type.CHAR -> char.class;
                case Type.SHORT -> short.class;
                case Type.INT -> int.class;
                case Type.LONG -> long.class;
                case Type.FLOAT -> float.class;
                case Type.DOUBLE -> double.class;
                case Type.ARRAY -> Class.forName(type.getDescriptor().replace('/', '.'), false, loader);
                default -> Class.forName(type.getClassName(), false, loader);
            };
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Function<@Nullable Object, @Nullable Object> asObjectGetter(MethodHandle getter,
            boolean isStatic) {
        MethodHandle taking = isStatic ? MethodHandles.dropArguments(getter, 0, Object.class) : getter;
        return MethodHandleProxies.asInterfaceInstance(Function.class,
                taking.asType(MethodType.methodType(Object.class, Object.class)));
    }

    @SuppressWarnings("unchecked")
    private static BiConsumer<@Nullable Object, @Nullable Object> asObjectSetter(MethodHandle setter,
            boolean isStatic) {
        MethodHandle taking = isStatic ? MethodHandles.dropArguments(setter, 0, Object.class) : setter;
        return MethodHandleProxies.asInterfaceInstance(BiConsumer.class,
                taking.asType(MethodType.methodType(void.class, Object.class, Object.class)));
    }
}
