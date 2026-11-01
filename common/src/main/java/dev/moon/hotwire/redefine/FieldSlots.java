package dev.moon.hotwire.redefine;

import dev.moon.hotwire.classfile.ClassHeader;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
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
        private volatile @Nullable VarHandle handle;

        private final MethodHandles.@Nullable Lookup lookup;

        private volatile boolean unhandled;
        private final Class<?> owner;
        private final String name;
        private final Class<?> type;
        private final int modifiers;

        public static Slot of(Field field) {
            return new Slot(field);
        }

        private Slot(Field field) {
            this.field = field;
            this.owner = field.getDeclaringClass();
            this.name = field.getName();
            this.type = field.getType();
            this.modifiers = field.getModifiers();
            this.lookup = null;
        }

        private Slot(Class<?> owner, String name, Class<?> type, int modifiers, @Nullable VarHandle handle,
                MethodHandles.@Nullable Lookup lookup) {
            this.handle = handle;
            this.lookup = lookup;
            this.owner = owner;
            this.name = name;
            this.type = type;
            this.modifiers = modifiers;
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
            VarHandle read = this.findHandle();
            if (read == null) {
                Field reflected = this.field;
                if (reflected == null) {
                    throw new IllegalAccessException(this.owner.getName() + "." + this.name + " cannot be read");
                }
                return reflected.get(holder);
            }
            boolean ordered = Modifier.isVolatile(this.modifiers);
            try {
                if (this.isStatic()) {
                    return ordered ? (Object) read.getVolatile() : (Object) read.get();
                }
                return ordered ? (Object) read.getVolatile(holder) : (Object) read.get(holder);
            } catch (RuntimeException | LinkageError e) {
                throw new IllegalAccessException(this.owner.getName() + "." + this.name + ": " + e);
            }
        }

        public void set(@Nullable Object holder, @Nullable Object value) throws IllegalAccessException {
            VarHandle write = Modifier.isFinal(this.modifiers) ? null
                    : this.field != null ? this.handle : this.findHandle();
            if (write == null) {
                Field reflected = this.field;
                if (reflected == null) {
                    throw new IllegalAccessException(this.owner.getName() + "." + this.name + " cannot be set");
                }
                reflected.set(holder, value);
                return;
            }
            boolean ordered = Modifier.isVolatile(this.modifiers);
            try {
                if (this.isStatic() && ordered) {
                    write.setVolatile(value);
                } else if (this.isStatic()) {
                    write.set(value);
                } else if (ordered) {
                    write.setVolatile(holder, value);
                } else {
                    write.set(holder, value);
                }
            } catch (RuntimeException | LinkageError e) {
                throw new IllegalAccessException(this.owner.getName() + "." + this.name + ": " + e);
            }
        }

        private @Nullable VarHandle findHandle() {
            VarHandle made = this.handle;
            Field reflected = this.field;
            MethodHandles.Lookup found = this.lookup;
            if (made != null || this.unhandled || (reflected == null && found == null)) {
                return made;
            }
            try {
                made = reflected != null
                        ? MethodHandles.privateLookupIn(this.owner, LOOKUP).unreflectVarHandle(reflected)
                        : Objects.requireNonNull(found).findStaticVarHandle(this.owner, this.name, this.type);
                this.handle = made;
            } catch (ReflectiveOperationException | RuntimeException e) {
                this.unhandled = true;
            }
            return made;
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
            try {
                if (isStatic) {
                    lookup.findStaticGetter(type, field.name(), fieldType);
                }
                VarHandle handle = isStatic ? null : lookup.findVarHandle(type, field.name(), fieldType);
                slots.add(new Slot(type, field.name(), fieldType, field.access(), handle, isStatic ? lookup : null));
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
        ClassHeader.of(bytes).fields().forEach(field -> out.add(new Entry(field.access(), field.name(), field.desc())));
        return out;
    }

    static @Nullable String findClassName(String descriptor) {
        Type type = Type.getType(descriptor);
        if (type.getSort() == Type.ARRAY) {
            type = type.getElementType();
        }
        return type.getSort() == Type.OBJECT ? type.getClassName() : null;
    }

    static boolean isLoaded(String binary, Predicate<String> loaded) {
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
}
