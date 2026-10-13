package dev.moon.hotwire.jar;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;

public final class WidenerUses {
    public interface GameAccess {
        int getModifiers(String owner);

        OptionalInt findModifiers(String owner, String name, String desc);

        @Nullable String findSuperOf(String owner);
    }

    public enum Kind {
        CALL,
        FIELD,
        NEW,
        CAST,
        INSTANCEOF,
        SUBCLASS,
        OVERRIDE,
        ABSTRACT_OVERRIDE,
        CLASS_CONSTANT,
        HANDLE,
        FINAL_FIELD_WRITE,
        FINAL_STATIC_WRITE,
        RECORD_FIELD_WRITE,
        PARENT_CONSTRUCTOR
    }

    public record Use(String inClass, String method, Kind kind, String owner, String name, String desc) {
        public boolean isFixable() {
            return switch (this.kind) {
                case CALL, FIELD, NEW, CAST, INSTANCEOF, FINAL_FIELD_WRITE, FINAL_STATIC_WRITE, RECORD_FIELD_WRITE,
                        OVERRIDE, PARENT_CONSTRUCTOR -> true;
                case SUBCLASS, ABSTRACT_OVERRIDE, CLASS_CONSTANT, HANDLE -> false;
            };
        }

        public String describe() {
            String where = this.inClass.replace('/', '.') + (this.method.isEmpty() ? "" : "." + this.method);
            String what = this.owner.replace('/', '.') + (this.name.isEmpty() ? "" : "." + this.name);
            return switch (this.kind) {
                case CALL -> where + " calls " + what;
                case FIELD -> where + " reads or writes " + what;
                case NEW -> where + " makes a new " + what;
                case CAST -> where + " casts to " + what;
                case INSTANCEOF -> where + " tests for " + what;
                case SUBCLASS -> where + " extends or implements " + what;
                case OVERRIDE -> where + " overrides " + what;
                case ABSTRACT_OVERRIDE -> where + " overrides " + what + ", abstract and shut in the running game";
                case CLASS_CONSTANT -> where + " uses " + what + " as a class constant";
                case HANDLE -> where + " uses a method handle that names " + what;
                case FINAL_FIELD_WRITE -> where + " writes " + what + ", final in the running game";
                case FINAL_STATIC_WRITE -> where + " writes " + what + ", static and final in the running game";
                case RECORD_FIELD_WRITE -> where + " writes " + what + ", a record's field, final in the running game";
                case PARENT_CONSTRUCTOR -> where + " calls " + this.owner.replace('/', '.')
                        + "'s constructor as its parent's, which the game keeps closed";
            };
        }
    }

    public record Openings(Set<String> classes, Set<String> members) {
        public Openings {
            classes = Set.copyOf(classes);
            members = Set.copyOf(members);
        }

        public static final Openings NONE = new Openings(Set.of(), Set.of());

        public boolean isEmpty() {
            return this.classes.isEmpty() && this.members.isEmpty();
        }

        public boolean opens(String owner, String name, String desc) {
            return this.classes.contains(owner) || this.members.contains(owner + "." + name + desc);
        }

        public boolean opensThrough(String owner, String name, String desc) {
            if (this.opens(owner, name, desc)) {
                return true;
            }
            String inherited = "." + name + desc;
            return !name.equals("<init>") && !isPlatform(owner)
                    && this.members.stream().anyMatch(member -> member.endsWith(inherited));
        }

        private static boolean isPlatform(String owner) {
            return owner.startsWith("java/") || owner.startsWith("javax/") || owner.startsWith("jdk/")
                    || owner.startsWith("sun/") || owner.startsWith("com/sun/") || owner.startsWith("[");
        }

        public boolean mentions(String desc) {
            for (String opened : this.classes) {
                if (desc.contains("L" + opened + ";")) {
                    return true;
                }
            }
            return false;
        }

        public Openings with(Openings other) {
            Set<String> classes = new HashSet<>(this.classes);
            classes.addAll(other.classes);
            Set<String> members = new HashSet<>(this.members);
            members.addAll(other.members);
            return new Openings(classes, members);
        }
    }

    public static final Set<String> JUDGED_BY_USE = Set.of("extendable class", "extendable method", "mutable field");

    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";

    private WidenerUses() {
    }

    public static Openings findOpenings(List<AccessWidenerText.Clause> clauses, GameAccess game) {
        Set<String> classes = new HashSet<>();
        Set<String> members = new HashSet<>();
        for (AccessWidenerText.Clause clause : clauses) {
            List<String> tokens = List.of(clause.text().trim().split("\\s+"));
            String kind = clause.kind();
            if ((kind.equals("accessible class") || kind.equals("extendable class"))
                    && !Modifier.isPublic(game.getModifiers(clause.owner()))) {
                classes.add(clause.owner());
            } else if (tokens.size() >= 5 && (kind.equals("mutable field")
                    || ((kind.equals("accessible method") || kind.equals("accessible field"))
                            && !isPublicIn(game, clause.owner(), tokens.get(3), tokens.get(4)))
                    || (kind.equals("extendable method")
                            && isShut(game.findModifiers(clause.owner(), tokens.get(3), tokens.get(4)))))) {
                members.add(clause.owner() + "." + tokens.get(3) + tokens.get(4));
            }
        }
        return new Openings(classes, members);
    }

    private static boolean isPublicIn(GameAccess game, String owner, String name, String desc) {
        OptionalInt member = game.findModifiers(owner, name, desc);
        return member.isPresent() && Modifier.isPublic(member.getAsInt())
                && Modifier.isPublic(game.getModifiers(owner));
    }

    private static boolean isShut(OptionalInt modifiers) {
        return modifiers.isPresent() && (modifiers.getAsInt() & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) == 0;
    }

    public static List<AccessWidenerText.Clause> findUnbridgeable(List<AccessWidenerText.Clause> clauses,
            GameAccess game) {
        List<AccessWidenerText.Clause> out = new ArrayList<>();
        for (AccessWidenerText.Clause clause : clauses) {
            if (!clause.kind().startsWith("accessible ") && !clause.kind().equals("inject-interface")
                    && !(clause.kind().equals("extendable class") && open(game.getModifiers(clause.owner())))) {
                out.add(clause);
            }
        }
        return out;
    }

    public static List<Use> shutUses(List<AccessWidenerText.Clause> clauses, GameAccess game,
            Map<String, byte[]> classes) {
        Set<String> finalClasses = new HashSet<>();
        Set<String> finalFields = new HashSet<>();
        Map<String, String> shutMethods = new HashMap<>();
        Set<String> abstractMethods = new HashSet<>();
        for (AccessWidenerText.Clause clause : clauses) {
            List<String> tokens = List.of(clause.text().trim().split("\\s+"));
            if (clause.kind().equals("extendable class") && Modifier.isFinal(game.getModifiers(clause.owner()))) {
                finalClasses.add(clause.owner());
            } else if (clause.kind().equals("mutable field") && tokens.size() >= 5) {
                finalFields.add(clause.owner() + "." + tokens.get(3) + tokens.get(4));
            } else if (clause.kind().equals("extendable method") && tokens.size() >= 5) {
                OptionalInt method = game.findModifiers(clause.owner(), tokens.get(3), tokens.get(4));
                if (method.isPresent() && (Modifier.isFinal(method.getAsInt()) || isShut(method))) {
                    shutMethods.put(tokens.get(3) + tokens.get(4), clause.owner());
                    if (Modifier.isAbstract(method.getAsInt())) {
                        abstractMethods.add(clause.owner() + "." + tokens.get(3) + tokens.get(4));
                    }
                }
            }
        }
        List<Use> uses = new ArrayList<>();
        if (finalClasses.isEmpty() && finalFields.isEmpty() && shutMethods.isEmpty()) {
            return uses;
        }
        Map<String, ClassNode> nodes = new HashMap<>();
        for (byte[] bytes : classes.values()) {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            nodes.put(node.name, node);
        }
        for (ClassNode node : nodes.values()) {
            if (isMixin(node)) {
                continue;
            }
            if (finalClasses.contains(node.superName)) {
                uses.add(new Use(node.name, "", Kind.SUBCLASS, node.superName, "", ""));
            }
            for (MethodNode method : node.methods) {
                String shut = shutMethods.get(method.name + method.desc);
                if (shut != null && (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0
                        && extendsClass(node, shut, nodes, game)) {
                    uses.add(new Use(node.name, method.name,
                            abstractMethods.contains(shut + "." + method.name + method.desc)
                                    ? Kind.ABSTRACT_OVERRIDE : Kind.OVERRIDE, shut, method.name, method.desc));
                }
            }
            for (MethodNode method : node.methods) {
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof FieldInsnNode field && (field.getOpcode() == Opcodes.PUTFIELD
                            || field.getOpcode() == Opcodes.PUTSTATIC)
                            && finalFields.contains(field.owner + "." + field.name + field.desc)) {
                        Kind kind = field.getOpcode() == Opcodes.PUTSTATIC ? Kind.FINAL_STATIC_WRITE
                                : (game.getModifiers(field.owner) & Opcodes.ACC_RECORD) != 0
                                        ? Kind.RECORD_FIELD_WRITE : Kind.FINAL_FIELD_WRITE;
                        uses.add(new Use(node.name, method.name, kind, field.owner, field.name, field.desc));
                    }
                }
            }
        }
        return uses;
    }

    private static boolean extendsClass(ClassNode node, String parent, Map<String, ClassNode> nodes, GameAccess game) {
        String at = node.superName;
        while (at != null) {
            if (at.equals(parent)) {
                return true;
            }
            ClassNode own = nodes.get(at);
            at = own != null ? own.superName : game.findSuperOf(at);
        }
        return false;
    }

    public static boolean isFieldHandle(Handle handle) {
        return handle.getTag() >= Opcodes.H_GETFIELD && handle.getTag() <= Opcodes.H_PUTSTATIC;
    }

    private static boolean isMixin(ClassNode node) {
        return node.invisibleAnnotations != null
                && node.invisibleAnnotations.stream().anyMatch(annotation -> annotation.desc.equals(MIXIN));
    }

    private static boolean open(int modifiers) {
        return Modifier.isPublic(modifiers) && !Modifier.isFinal(modifiers);
    }

    public static List<Use> scan(Openings openings, GameAccess game, Map<String, byte[]> classes) {
        List<Use> uses = new ArrayList<>();
        if (openings.isEmpty()) {
            return uses;
        }
        List<ClassNode> nodes = new ArrayList<>();
        Set<String> namedElsewhere = new HashSet<>();
        for (byte[] bytes : classes.values()) {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);
            nodes.add(node);
            for (MethodNode method : node.methods) {
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof FieldInsnNode field && !field.owner.equals(node.name)) {
                        namedElsewhere.add(field.owner + "." + field.name + field.desc);
                    }
                }
            }
        }

        Set<String> mixins = new HashSet<>();
        nodes.forEach(node -> {
            if (isMixin(node)) {
                mixins.add(node.name);
            }
        });
        for (ClassNode node : nodes) {
            if (!mixins.contains(node.name) && !mixins.contains(node.outerClass)
                    && !mixins.contains(node.nestHostClass)) {
                scanClass(openings, game, node, uses, namedElsewhere);
            }
        }
        return uses;
    }

    private static void scanClass(Openings openings, GameAccess game, ClassNode node, List<Use> uses,
            Set<String> namedElsewhere) {
        if (node.superName != null && openings.classes.contains(node.superName)) {
            uses.add(new Use(node.name, "", Kind.SUBCLASS, node.superName, "", ""));
        }
        for (String iface : node.interfaces) {
            if (openings.classes.contains(iface)) {
                uses.add(new Use(node.name, "", Kind.SUBCLASS, iface, "", ""));
            }
        }
        for (MethodNode method : node.methods) {
            scanCode(openings, game, node, method, uses, namedElsewhere);
            for (TryCatchBlockNode handler : method.tryCatchBlocks) {
                if (handler.type != null && openings.classes.contains(handler.type)) {
                    uses.add(new Use(node.name, method.name, Kind.CLASS_CONSTANT, handler.type, "", ""));
                }
            }
        }
    }

    private static void scanCode(Openings openings, GameAccess game, ClassNode node, MethodNode method,
            List<Use> uses, Set<String> namedElsewhere) {
        List<TypeInsnNode> pendingNews = new ArrayList<>();
        List<String> newed = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW) {
                newed.add(type.desc);
            }
            switch (insn) {
                case MethodInsnNode call when call.name.equals("<init>")
                        && openings.members.contains(call.owner + ".<init>" + call.desc)
                        && !newed.remove(call.owner) -> {
                    if (isShut(game.findModifiers(call.owner, "<init>", call.desc))) {
                        uses.add(new Use(node.name, method.name, Kind.PARENT_CONSTRUCTOR, call.owner, "<init>",
                                call.desc));
                    }
                }
                case MethodInsnNode call -> {
                    if (call.name.equals("<init>") && openings.classes.contains(call.owner)) {
                        TypeInsnNode made = pendingNews.isEmpty() ? null : pendingNews.removeLast();
                        boolean plain = made != null && made.getNext() != null
                                && made.getNext().getOpcode() == Opcodes.DUP && !hasFrameBetween(made, call);
                        uses.add(new Use(node.name, method.name, plain ? Kind.NEW : Kind.CLASS_CONSTANT, call.owner,
                                "<init>", call.desc));
                    } else if (openings.opensThrough(call.owner, call.name, call.desc)) {
                        String owner = openings.classes.contains(call.owner) ? call.owner
                                : openings.members.contains(call.owner + "." + call.name + call.desc) ? call.owner
                                : findFirstMentioned(openings, call.desc);
                        uses.add(new Use(node.name, method.name, Kind.CALL, owner, call.name, call.desc));
                    }
                }
                case FieldInsnNode field when openings.opensThrough(field.owner, field.name, field.desc)
                        || openings.mentions(field.desc) -> {
                    uses.add(new Use(node.name, method.name, Kind.FIELD, field.owner, field.name, field.desc));
                }
                case TypeInsnNode type when openings.classes.contains(type.desc) -> {
                    switch (type.getOpcode()) {
                        case Opcodes.NEW -> pendingNews.add(type);
                        case Opcodes.CHECKCAST ->
                                uses.add(new Use(node.name, method.name, Kind.CAST, type.desc, "", ""));
                        case Opcodes.INSTANCEOF -> uses.add(new Use(node.name, method.name, Kind.INSTANCEOF, type.desc,
                                "", ""));
                        default -> {
                        }
                    }
                }
                case MultiANewArrayInsnNode array when openings.mentions(array.desc) -> {
                    uses.add(new Use(node.name, method.name, Kind.CLASS_CONSTANT, findFirstMentioned(openings,
                            array.desc), "", ""));
                }
                case InvokeDynamicInsnNode site -> {
                    for (Object argument : site.bsmArgs) {
                        if (argument instanceof Handle handle
                                && isUncarried(handle, node, openings, namedElsewhere)) {
                            uses.add(new Use(node.name, method.name, Kind.HANDLE, findNamed(handle, openings),
                                    handle.getName(), handle.getDesc()));
                        }
                    }
                }
                case LdcInsnNode ldc when ldc.cst instanceof Handle handle
                        && isUncarried(handle, node, openings, namedElsewhere) -> {
                    uses.add(new Use(node.name, method.name, Kind.HANDLE, findNamed(handle, openings), handle.getName(),
                            handle.getDesc()));
                }
                default -> {
                }
            }
        }
    }

    private static boolean isUncarried(Handle handle, ClassNode node, Openings openings,
            Set<String> namedElsewhere) {
        if (openings.classes.contains(handle.getOwner())) {
            return true;
        }
        if (!openings.mentions(handle.getDesc())) {
            return false;
        }
        if (!handle.getOwner().equals(node.name)) {
            return true;
        }
        if (isFieldHandle(handle)) {
            return namedElsewhere.contains(node.name + "." + handle.getName() + handle.getDesc())
                    || node.fields.stream().noneMatch(field -> field.name.equals(handle.getName())
                            && field.desc.equals(handle.getDesc()) && (field.access & Opcodes.ACC_PRIVATE) != 0);
        }
        for (MethodNode method : node.methods) {
            if (method.name.equals(handle.getName()) && method.desc.equals(handle.getDesc())) {
                return (method.access & Opcodes.ACC_PRIVATE) == 0;
            }
        }
        return true;
    }

    private static String findNamed(Handle handle, Openings openings) {
        return openings.classes.contains(handle.getOwner()) ? handle.getOwner()
                : findFirstMentioned(openings, handle.getDesc());
    }

    @SuppressWarnings("ReferenceEquality")
    private static boolean hasFrameBetween(AbstractInsnNode from, AbstractInsnNode to) {
        for (AbstractInsnNode at = from.getNext(); at != null && at != to; at = at.getNext()) {
            if (at instanceof FrameNode frame && (hasLabel(frame.local) || hasLabel(frame.stack))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLabel(List<Object> entries) {
        if (entries == null) {
            return false;
        }
        for (Object entry : entries) {
            if (entry instanceof LabelNode) {
                return true;
            }
        }
        return false;
    }

    private static String findFirstMentioned(Openings openings, String desc) {
        for (String opened : openings.classes) {
            if (desc.contains("L" + opened + ";")) {
                return opened;
            }
        }
        return "";
    }
}
