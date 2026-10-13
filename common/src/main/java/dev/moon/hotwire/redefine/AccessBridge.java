package dev.moon.hotwire.redefine;

import dev.moon.hotwire.jar.WidenerUses;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AccessBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger(AccessBridge.class);
    private static final String OPENED = OpenedMembers.class.getName().replace('.', '/');
    private static final String BOOTSTRAP = "Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
            + "Ljava/lang/invoke/MethodType;";
    private static final Handle OPEN = new Handle(Opcodes.H_INVOKESTATIC, OPENED, "open",
            "(" + BOOTSTRAP + "ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/invoke/CallSite;",
            false);
    private static final String OBJECT = "Ljava/lang/Object;";

    private static final String ACCESSOR = "$hotwire$access";

    private static final Set<Class<?>> ACCESSIBLE = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

    private AccessBridge() {
    }

    static boolean names(byte[] classBytes, WidenerUses.Openings openings) {
        if (openings.isEmpty()) {
            return false;
        }
        ClassReader reader = new ClassReader(classBytes);
        char[] buffer = new char[reader.getMaxStringLength()];
        for (int i = 1; i < reader.getItemCount(); i++) {
            int offset = reader.getItem(i);
            if (offset > 0 && reader.readByte(offset - 1) == 7) {
                String name = reader.readUTF8(offset, buffer);
                if (openings.classes().contains(name) || ownsMember(openings, name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean ownsMember(WidenerUses.Openings openings, String owner) {
        for (String member : openings.members()) {
            if (member.startsWith(owner + ".")) {
                return true;
            }
        }
        return false;
    }

    public static void open(Class<?> opened) {
        if (!ACCESSIBLE.add(opened)) {
            return;
        }
        try {
            MethodHandles.privateLookupIn(opened, MethodHandles.lookup()).defineClass(
                    buildAccessor(opened.getName().replace('.', '/')));
        } catch (IllegalAccessException | LinkageError e) {
            LOGGER.info("[Hotwire] the accessor of {} was not defined again", opened.getName(), e);
        }
    }

    public static boolean isAccessor(String internalName) {
        return internalName.endsWith(ACCESSOR);
    }

    static byte[] buildAccessor(String opened) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, opened + ACCESSOR,
                null, "java/lang/Object", null);
        String type = "L" + opened + ";";
        MethodVisitor cast = start(writer, "cast", "(" + OBJECT + ")" + type);
        cast.visitVarInsn(Opcodes.ALOAD, 0);
        cast.visitTypeInsn(Opcodes.CHECKCAST, opened);
        end(cast, Opcodes.ARETURN);
        MethodVisitor castArray = start(writer, "castArray", "(" + OBJECT + ")[" + type);
        castArray.visitVarInsn(Opcodes.ALOAD, 0);
        castArray.visitTypeInsn(Opcodes.CHECKCAST, "[" + type);
        end(castArray, Opcodes.ARETURN);
        MethodVisitor isInstance = start(writer, "isInstance", "(" + OBJECT + ")Z");
        isInstance.visitVarInsn(Opcodes.ALOAD, 0);
        isInstance.visitTypeInsn(Opcodes.INSTANCEOF, opened);
        end(isInstance, Opcodes.IRETURN);
        MethodVisitor constant = start(writer, "type", "()Ljava/lang/Class;");
        constant.visitLdcInsn(Type.getObjectType(opened));
        end(constant, Opcodes.ARETURN);
        MethodVisitor newArray = start(writer, "newArray", "(I)[" + type);
        newArray.visitVarInsn(Opcodes.ILOAD, 0);
        newArray.visitTypeInsn(Opcodes.ANEWARRAY, opened);
        end(newArray, Opcodes.ARETURN);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static MethodVisitor start(ClassWriter writer, String name, String desc) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, null, null);
        method.visitCode();
        return method;
    }

    private static void end(MethodVisitor method, int returned) {
        method.visitInsn(returned);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static MethodInsnNode makeAccess(String opened, String name, String desc) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, opened + ACCESSOR, name, desc, false);
    }

    public static byte[] rewrite(byte[] classBytes, WidenerUses.Openings openings) {
        if (!names(classBytes, openings)) {
            return eraseCallSites(classBytes, openings);
        }
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        if (openings.classes().contains(node.name) || !DuckBridge.raiseForInvokeDynamic(node)) {
            return classBytes;
        }

        Set<String> declared = new HashSet<>();
        node.methods.forEach(method -> declared.add(method.name + method.desc));
        node.fields.forEach(field -> declared.add(field.name + field.desc));
        for (MethodNode method : node.methods) {
            rewrite(method, openings, node.name, declared);
        }
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return eraseCallSites(writer.toByteArray(), openings);
    }

    static void rewriteMembers(ClassNode node, String host, WidenerUses.Openings openings) {
        if (openings.members().isEmpty()) {
            return;
        }
        WidenerUses.Openings members = new WidenerUses.Openings(Set.of(), openings.members());
        for (MethodNode method : node.methods) {
            rewrite(method, members, host, null);
        }
    }

    private static boolean isOwn(String owner, String member, String host, @Nullable Set<String> declared) {
        return owner.equals(host) && (declared == null || declared.contains(member));
    }

    private static @Nullable TypeInsnNode findLastNewOf(List<TypeInsnNode> pending, String owner) {
        for (int i = pending.size() - 1; i >= 0; i--) {
            if (pending.get(i).desc.equals(owner)) {
                return pending.remove(i);
            }
        }
        return null;
    }

    private static void rewrite(MethodNode method, WidenerUses.Openings openings, String host,
            @Nullable Set<String> declared) {
        boolean modCode = declared != null;
        List<TypeInsnNode> pendingNews = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            switch (insn) {
                case MethodInsnNode call when !isOwn(call.owner, call.name + call.desc, host, declared) -> {
                    TypeInsnNode made = call.name.equals("<init>") ? findLastNewOf(pendingNews, call.owner) : null;
                    boolean opened = openings.classes().contains(call.owner)
                            || openings.opens(call.owner, call.name, call.desc);
                    boolean reached = modCode ? openings.opensThrough(call.owner, call.name, call.desc) : opened;
                    if (made != null && opened) {
                        AbstractInsnNode dup = made.getNext();
                        method.instructions.set(made, new InsnNode(Opcodes.ACONST_NULL));
                        method.instructions.set(dup, new InsnNode(Opcodes.ACONST_NULL));
                        String shape = "(" + OBJECT + OBJECT + call.desc.substring(1, call.desc.lastIndexOf(')')) + ")L"
                                + call.owner + ";";
                        method.instructions.set(insn, new InvokeDynamicInsnNode("make", shape, OPEN, Opcodes.NEW,
                                call.owner, call.name, call.desc));
                    } else if (!call.name.equals("<init>") && reached) {
                        boolean isStatic = call.getOpcode() == Opcodes.INVOKESTATIC;
                        String withReceiver = isStatic ? call.desc : "(" + OBJECT + call.desc.substring(1);
                        method.instructions.set(insn, new InvokeDynamicInsnNode(call.name.replace('<', '_')
                                .replace('>', '_'), withReceiver, OPEN, call.getOpcode(), call.owner, call.name,
                                call.desc));
                    }
                }
                case FieldInsnNode field when !isOwn(field.owner, field.name + field.desc, host, declared) && (modCode
                        ? openings.opensThrough(field.owner, field.name, field.desc)
                        : openings.opens(field.owner, field.name, field.desc)) -> {
                    String desc = switch (field.getOpcode()) {
                        case Opcodes.GETFIELD -> "(" + OBJECT + ")" + field.desc;
                        case Opcodes.PUTFIELD -> "(" + OBJECT + field.desc + ")V";
                        case Opcodes.GETSTATIC -> "()" + field.desc;
                        case Opcodes.PUTSTATIC -> "(" + field.desc + ")V";
                        default -> throw new IllegalStateException("field opcode " + field.getOpcode());
                    };
                    method.instructions.set(insn, new InvokeDynamicInsnNode(field.name, desc, OPEN, field.getOpcode(),
                            field.owner, field.name, field.desc));
                }
                case TypeInsnNode made when made.getOpcode() == Opcodes.NEW -> {
                    pendingNews.add(made);
                }
                case TypeInsnNode type when openings.classes().contains(type.desc) -> {
                    String typed = "L" + type.desc + ";";
                    switch (type.getOpcode()) {
                        case Opcodes.CHECKCAST -> method.instructions.set(insn, makeAccess(type.desc, "cast",
                                "(" + OBJECT + ")" + typed));
                        case Opcodes.INSTANCEOF -> method.instructions.set(insn, makeAccess(type.desc, "isInstance",
                                "(" + OBJECT + ")Z"));
                        case Opcodes.ANEWARRAY -> method.instructions.set(insn, makeAccess(type.desc, "newArray",
                                "(I)[" + typed));
                        default -> {
                        }
                    }
                }
                case TypeInsnNode cast when cast.getOpcode() == Opcodes.CHECKCAST && cast.desc.startsWith("[L")
                        && openings.classes().contains(cast.desc.substring(2, cast.desc.length() - 1)) -> {
                    String element = cast.desc.substring(2, cast.desc.length() - 1);
                    method.instructions.set(insn, makeAccess(element, "castArray", "(" + OBJECT + ")" + cast.desc));
                }
                case LdcInsnNode ldc when ldc.cst instanceof Type constant && constant.getSort() == Type.OBJECT
                        && openings.classes().contains(constant.getInternalName()) -> {
                    method.instructions.set(insn, makeAccess(constant.getInternalName(), "type",
                            "()Ljava/lang/Class;"));
                }
                default -> {
                }
            }
        }
    }

    public static byte[] eraseCallSites(byte[] classBytes, WidenerUses.Openings openings) {
        if (!mentionsAny(classBytes, openings.classes())) {
            return classBytes;
        }
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, ClassReader.EXPAND_FRAMES);
        Map<String, String> bodies = new HashMap<>();
        Map<String, String> fields = new HashMap<>();
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof InvokeDynamicInsnNode site && names(site, openings)) {
                    Type returned = Type.getReturnType(site.desc);
                    site.desc = erase(site.desc, openings);
                    for (int i = 0; i < site.bsmArgs.length; i++) {
                        site.bsmArgs[i] = eraseArgument(site.bsmArgs[i], node, openings, bodies, fields);
                    }
                    castBack(method.instructions, site, returned, openings);
                    changed = true;
                }
            }
        }
        if (!bodies.isEmpty()) {
            for (MethodNode method : node.methods) {
                for (AbstractInsnNode insn : method.instructions.toArray()) {
                    if (insn instanceof MethodInsnNode call && call.owner.equals(node.name)
                            && bodies.containsKey(call.name + call.desc)) {
                        Type returned = Type.getReturnType(call.desc);
                        call.desc = bodies.get(call.name + call.desc);
                        castBack(method.instructions, call, returned, openings);
                    }
                }
            }
            for (MethodNode method : node.methods) {
                String erased = bodies.get(method.name + method.desc);
                if (erased != null) {
                    castArguments(method, openings);
                    method.desc = erased;
                }
            }
        }
        if (!fields.isEmpty()) {
            eraseFields(node, fields, openings);
        }
        if (!changed) {
            return classBytes;
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean mentionsAny(byte[] classBytes, Set<String> classes) {
        if (classes.isEmpty()) {
            return false;
        }
        String text = new String(classBytes, StandardCharsets.ISO_8859_1);
        for (String opened : classes) {
            if (text.contains("L" + opened + ";")) {
                return true;
            }
        }
        return false;
    }

    private static boolean names(InvokeDynamicInsnNode site, WidenerUses.Openings openings) {
        if (openings.mentions(site.desc)) {
            return true;
        }
        for (Object argument : site.bsmArgs) {
            if ((argument instanceof Type type && openings.mentions(type.getDescriptor()))
                    || (argument instanceof Handle handle && openings.mentions(handle.getDesc()))) {
                return true;
            }
        }
        return false;
    }

    private static Object eraseArgument(Object argument, ClassNode node, WidenerUses.Openings openings,
            Map<String, String> bodies, Map<String, String> fields) {
        if (argument instanceof Type type && type.getSort() == Type.METHOD && openings.mentions(type.getDescriptor())) {
            return Type.getMethodType(erase(type.getDescriptor(), openings));
        }
        if (argument instanceof Handle handle && WidenerUses.isFieldHandle(handle)
                && openings.mentions(handle.getDesc()) && handle.getOwner().equals(node.name)
                && isOwnPrivateField(node, handle)) {
            String erased = erase(handle.getDesc(), openings);
            fields.put(handle.getName() + handle.getDesc(), erased);
            return new Handle(handle.getTag(), handle.getOwner(), handle.getName(), erased, handle.isInterface());
        }
        if (argument instanceof Handle handle && openings.mentions(handle.getDesc())
                && handle.getOwner().equals(node.name) && isOwnPrivate(node, handle)) {
            String erased = erase(handle.getDesc(), openings);
            bodies.put(handle.getName() + handle.getDesc(), erased);
            return new Handle(handle.getTag(), handle.getOwner(), handle.getName(), erased, handle.isInterface());
        }
        return argument;
    }

    private static boolean isOwnPrivateField(ClassNode node, Handle handle) {
        for (FieldNode field : node.fields) {
            if (field.name.equals(handle.getName()) && field.desc.equals(handle.getDesc())) {
                return (field.access & Opcodes.ACC_PRIVATE) != 0;
            }
        }
        return false;
    }

    private static void eraseFields(ClassNode node, Map<String, String> fields, WidenerUses.Openings openings) {
        for (FieldNode field : node.fields) {
            String erased = fields.get(field.name + field.desc);
            if (erased != null) {
                field.desc = erased;
                field.signature = null;
            }
        }
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof FieldInsnNode access && access.owner.equals(node.name)
                        && fields.containsKey(access.name + access.desc)) {
                    Type type = Type.getType(access.desc);
                    access.desc = fields.get(access.name + access.desc);
                    if (access.getOpcode() == Opcodes.GETFIELD || access.getOpcode() == Opcodes.GETSTATIC) {
                        castBack(method.instructions, access, type, openings);
                    }
                }
            }
        }
    }

    private static boolean isOwnPrivate(ClassNode node, Handle handle) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(handle.getName()) && method.desc.equals(handle.getDesc())) {
                return (method.access & Opcodes.ACC_PRIVATE) != 0;
            }
        }
        return false;
    }

    private static void castBack(InsnList instructions, AbstractInsnNode after, Type returned,
            WidenerUses.Openings openings) {
        AbstractInsnNode cast = makeCastBack(returned, openings);
        if (cast != null) {
            instructions.insert(after, cast);
        }
    }

    private static @Nullable AbstractInsnNode makeCastBack(Type type, WidenerUses.Openings openings) {
        if (type.getSort() == Type.OBJECT && openings.classes().contains(type.getInternalName())) {
            return makeAccess(type.getInternalName(), "cast", "(" + OBJECT + ")" + type.getDescriptor());
        }
        if (type.getSort() == Type.ARRAY && type.getDimensions() == 1 && type.getElementType().getSort() == Type.OBJECT
                && openings.classes().contains(type.getElementType().getInternalName())) {
            return makeAccess(type.getElementType().getInternalName(), "castArray",
                    "(" + OBJECT + ")" + type.getDescriptor());
        }
        return null;
    }

    private static void castArguments(MethodNode method, WidenerUses.Openings openings) {
        InsnList start = new InsnList();
        int slot = (method.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            AbstractInsnNode cast = makeCastBack(argument, openings);
            if (cast != null) {
                start.add(new VarInsnNode(Opcodes.ALOAD, slot));
                start.add(cast);
                start.add(new VarInsnNode(Opcodes.ASTORE, slot));
            }
            slot += argument.getSize();
        }
        method.instructions.insert(start);
    }

    private static String erase(String desc, WidenerUses.Openings openings) {
        String out = desc;
        for (String opened : openings.classes()) {
            out = out.replace("L" + opened + ";", OBJECT);
        }
        return out;
    }
}
