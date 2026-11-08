package dev.moon.hotwire.classfile;

import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

public final class PlainTypes {
    private static final Set<String> PLAIN = Set.of("java/lang/String", "java/lang/Integer", "java/lang/Long",
            "java/lang/Short", "java/lang/Byte", "java/lang/Character", "java/lang/Boolean", "java/lang/Float",
            "java/lang/Double", "java/util/UUID");
    private static final Set<String> CONTAINERS = Set.of("java/util/Collection", "java/util/List",
            "java/util/ArrayList", "java/util/LinkedList", "java/util/Set", "java/util/HashSet",
            "java/util/LinkedHashSet", "java/util/TreeSet", "java/util/SortedSet", "java/util/NavigableSet",
            "java/util/Map", "java/util/HashMap", "java/util/LinkedHashMap", "java/util/TreeMap", "java/util/SortedMap",
            "java/util/NavigableMap", "java/util/Map$Entry", "java/util/Queue", "java/util/Deque",
            "java/util/ArrayDeque", "java/util/concurrent/ConcurrentMap", "java/util/concurrent/ConcurrentHashMap",
            "java/util/concurrent/CopyOnWriteArrayList", "java/util/concurrent/CopyOnWriteArraySet",
            "java/util/concurrent/ConcurrentLinkedQueue", "java/util/concurrent/ConcurrentLinkedDeque");

    private static final String FASTUTIL = "it.unimi.dsi.fastutil.";
    private static final Set<String> PRIMITIVE_PACKAGES = Set.of("ints", "longs", "shorts", "bytes", "chars",
            "floats", "doubles", "booleans");

    private PlainTypes() {
    }

    public static boolean isPrimitiveCollection(String className) {
        if (!className.startsWith(FASTUTIL)) {
            return false;
        }
        int dot = className.indexOf('.', FASTUTIL.length());
        String simple = className.substring(className.lastIndexOf('.') + 1);
        return dot > 0 && PRIMITIVE_PACKAGES.contains(className.substring(FASTUTIL.length(), dot))
                && !simple.contains("Object") && !simple.contains("Reference");
    }

    public static boolean holdsPlainOnly(String desc, @Nullable String signature) {
        if (signature == null) {
            Type type = Type.getType(desc);
            return type.getSort() == Type.ARRAY && type.getElementType().getSort() == Type.OBJECT
                    && PLAIN.contains(type.getElementType().getInternalName());
        }
        if (signature.isEmpty() || signature.charAt(0) != 'L') {
            return false;
        }
        TypeReader reading = new TypeReader();
        try {
            new SignatureReader(signature).acceptType(reading);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return reading.plain && reading.arguments > 0;
    }

    private static boolean isContainer(String name) {
        return CONTAINERS.contains(name) || name.startsWith("it/unimi/dsi/fastutil/");
    }

    private static final class TypeReader extends SignatureVisitor {
        private boolean plain = true;
        private int arguments;
        private boolean first = true;

        TypeReader() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visitClassType(String name) {
            if (this.first) {
                this.first = false;
                this.plain &= isContainer(name);
            } else {
                this.plain &= PLAIN.contains(name) || isContainer(name);
            }
        }

        @Override
        public void visitInnerClassType(String name) {
            this.plain = false;
        }

        @Override
        public void visitTypeVariable(String name) {
            this.plain = false;
        }

        @Override
        public void visitTypeArgument() {
            this.plain = false;
        }

        @Override
        public SignatureVisitor visitTypeArgument(char wildcard) {
            this.arguments++;
            if (wildcard == SignatureVisitor.SUPER) {
                this.plain = false;
            }
            return this;
        }

        @Override
        public SignatureVisitor visitArrayType() {
            return this;
        }

        @Override
        public void visitBaseType(char descriptor) {
        }
    }
}
