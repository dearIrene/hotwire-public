package dev.moon.hotwire.redefine;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

public final class Relocations {
    private static final Map<String, String> MOVED = new ConcurrentHashMap<>();

    private static final Set<String> LEFT = ConcurrentHashMap.newKeySet();

    private static final Set<String> OPENED = ConcurrentHashMap.newKeySet();

    private Relocations() {
    }

    static void clear() {
        MOVED.clear();
        LEFT.clear();
        OPENED.clear();
    }

    public static String findTarget(String moved, String parent) {
        int slash = parent.lastIndexOf('/');
        String parentPackage = slash < 0 ? "" : parent.substring(0, slash + 1);
        return parentPackage + "hotwire$" + moved.replace('/', '_');
    }

    public static void register(String moved, String target, Map<String, byte[]> classes) {
        MOVED.put(moved, target);
        LEFT.add(getPackageOf(moved));
        OPENED.addAll(findCrossings(moved, classes).opened());
    }

    public static byte[] remap(byte[] classBytes) {
        if (MOVED.isEmpty()) {
            return classBytes;
        }
        String className = new ClassReader(classBytes).getClassName();
        boolean opened = MOVED.containsValue(className) || LEFT.contains(getPackageOf(className));
        if (!opened && !namesAny(classBytes)) {
            return classBytes;
        }
        ClassWriter writer = new ClassWriter(0);

        ClassVisitor publicly = new ClassVisitor(Opcodes.ASM9,
                new ClassRemapper(writer, new SimpleRemapper(Map.copyOf(MOVED)))) {
            @Override
            public void visit(int version, int access, String name, @Nullable String signature,
                    @Nullable String superName, String @Nullable [] interfaces) {
                super.visit(version, opened ? open(access) : access, name, signature, superName, interfaces);
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, @Nullable String signature,
                    @Nullable Object value) {
                return super.visitField(openReached(className, name + descriptor, opened ? open(access) : access),
                        name, descriptor, signature, value);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, @Nullable String signature,
                    String @Nullable [] exceptions) {
                return super.visitMethod(openReached(className, name + descriptor, opened ? open(access) : access),
                        name, descriptor, signature, exceptions);
            }
        };
        new ClassReader(classBytes).accept(publicly, 0);
        return writer.toByteArray();
    }

    private static int openReached(String owner, String member, int access) {
        return OPENED.contains(owner + "." + member) ? (access & ~Opcodes.ACC_PRIVATE) | Opcodes.ACC_PUBLIC : access;
    }

    private static int open(int access) {
        return (access & Opcodes.ACC_PRIVATE) != 0 ? access
                : (access | Opcodes.ACC_PUBLIC) & ~Opcodes.ACC_PROTECTED;
    }

    private static boolean namesAny(byte[] classBytes) {
        String text = new String(classBytes, StandardCharsets.ISO_8859_1);
        for (String moved : MOVED.keySet()) {
            if (text.contains(moved)) {
                return true;
            }
        }
        return false;
    }

    public static @Nullable String findBlocker(String moved, Map<String, byte[]> classes, String texts) {
        String dotted = moved.replace('/', '.');
        if (texts.contains(dotted) || texts.contains(moved)) {
            return moved + " is named in the mod's manifest or configs";
        }
        if (!classes.containsKey(moved)) {
            return moved + " is not in the mod's jar";
        }
        return findCrossings(moved, classes).blocker();
    }

    private record Crossings(@Nullable String blocker, Set<String> opened) {
    }

    private record Reach(String owner, String member, int access) {
        boolean isInstanceMethod() {
            return this.member.contains("(") && !this.member.startsWith("<init>")
                    && (this.access & Opcodes.ACC_STATIC) == 0;
        }
    }

    private static Crossings findCrossings(String moved, Map<String, byte[]> classes) {
        ClassNode node = read(Objects.requireNonNull(classes.get(moved)));
        String home = getPackageOf(moved);
        Set<String> opened = new LinkedHashSet<>();
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            ClassNode other = entry.getKey().equals(moved) ? node : read(entry.getValue());
            for (MethodNode method : other.methods) {
                for (AbstractInsnNode insn : method.instructions) {
                    String where = other.name + "." + method.name + " ";
                    if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String text
                            && (text.equals(moved) || text.equals(moved.replace('/', '.')))) {
                        return new Crossings(where + "names it as text", Set.of());
                    }
                    Reach reach = findReach(insn, other, node, home, classes);
                    if (reach != null && reach.isInstanceMethod()) {
                        return new Crossings(where + "reaches " + reach.owner() + "." + reach.member()
                                + ", a private method, which only its nest may call", Set.of());
                    }
                    if (reach != null) {
                        opened.add(reach.owner() + "." + reach.member());
                    }
                }
            }
        }
        return new Crossings(null, opened);
    }

    private static @Nullable Reach findReach(AbstractInsnNode insn, ClassNode at, ClassNode moved, String home,
            Map<String, byte[]> classes) {
        String owner;
        String member;
        if (insn instanceof MethodInsnNode call) {
            owner = call.owner;
            member = call.name + call.desc;
        } else if (insn instanceof FieldInsnNode field) {
            owner = field.owner;
            member = field.name + field.desc;
        } else {
            return null;
        }
        boolean fromMoved = at.name.equals(moved.name);
        ClassNode reached;
        if (!fromMoved && owner.equals(moved.name)) {
            reached = moved;
        } else if (fromMoved && !owner.equals(moved.name) && getPackageOf(owner).equals(home)
                && classes.containsKey(owner)) {
            reached = read(Objects.requireNonNull(classes.get(owner)));
        } else {
            return null;
        }

        OptionalInt access = findAccess(reached, member);
        return access.isPresent() && (access.getAsInt() & Opcodes.ACC_PRIVATE) != 0
                ? new Reach(owner, member, access.getAsInt()) : null;
    }

    private static OptionalInt findAccess(ClassNode node, String member) {
        for (MethodNode method : node.methods) {
            if ((method.name + method.desc).equals(member)) {
                return OptionalInt.of(method.access);
            }
        }
        for (FieldNode field : node.fields) {
            if ((field.name + field.desc).equals(member)) {
                return OptionalInt.of(field.access);
            }
        }
        return OptionalInt.empty();
    }

    private static String getPackageOf(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? "" : internalName.substring(0, slash);
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
    }
}
