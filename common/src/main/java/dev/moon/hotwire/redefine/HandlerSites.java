package dev.moon.hotwire.redefine;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class HandlerSites {
    private static final Logger LOGGER = LoggerFactory.getLogger(HandlerSites.class);

    private HandlerSites() {
    }

    public static boolean makeSites(String internalName, ClassNode node, boolean atLoad) {
        if ((node.version & 0xFFFF) < Opcodes.V1_7) {
            return false;
        }

        Map<String, String> mixinOfHandler = new HashMap<>();

        Set<String> merged = new HashSet<>();
        for (MethodNode method : node.methods) {
            String mixin = HandlerGuards.findMerged(method);
            if (mixin != null) {
                merged.add(mixin);
                if (HandlerGuards.isHandlerName(method.name)) {
                    mixinOfHandler.put(method.name + method.desc, mixin);
                }
            }
        }

        ClassState.of(internalName).setStagedPresent(Set.copyOf(merged));
        Set<String> guarded = new HashSet<>();

        ClassNode[] original = new ClassNode[1];
        boolean[] originalRead = new boolean[1];
        Supplier<@Nullable ClassNode> shipped = () -> {
            if (!originalRead[0]) {
                originalRead[0] = true;
                byte[] bytes = HandlerGuards.originals.apply(internalName);
                if (bytes != null) {
                    original[0] = new ClassNode();
                    new ClassReader(bytes).accept(original[0], ClassReader.SKIP_DEBUG);
                }
            }
            return original[0];
        };
        if (!merged.isEmpty()) {
            markFinalWrites(internalName, node, shipped);
            OverwriteSites.make(internalName, node, mixinOfHandler, shipped, guarded, atLoad);
            FieldSites.make(internalName, node, merged, shipped, guarded,
                    atLoad ? FieldSites.Mode.LOAD : FieldSites.Mode.REBUILD);
        }
        if (mixinOfHandler.isEmpty()) {
            return markStaged(internalName, guarded);
        }
        NullArguments nulls = new NullArguments(internalName);
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode call) || !call.owner.equals(internalName)) {
                    continue;
                }
                String mixin = mixinOfHandler.get(call.name + call.desc);
                if (mixin == null || mixin.equals(mixinOfHandler.get(method.name + method.desc))) {
                    continue;
                }
                Outcome outcome = classify(internalName, node, method, call, mixin, shipped, nulls);
                if (outcome.refusal() != null) {
                    HandlerGuards.markUnflippable(internalName, mixin, outcome.refusal());
                }
                if (outcome.site() != null) {
                    method.instructions.set(call, outcome.site());
                    guarded.add(mixin);
                }
            }
        }
        return markStaged(internalName, guarded);
    }

    private record Outcome(@Nullable String refusal, @Nullable InvokeDynamicInsnNode site) {
        static final Outcome LEFT = new Outcome(null, null);

        static Outcome refuse(String why) {
            return new Outcome(why, null);
        }

        static Outcome put(InvokeDynamicInsnNode site) {
            return new Outcome(null, site);
        }
    }

    private static Outcome classify(String internalName, ClassNode node, MethodNode method, MethodInsnNode call,
            String mixin, Supplier<@Nullable ClassNode> shipped, NullArguments nulls) {
        String handlerMod = HandlerGuards.findModInHandlerName(call.name);
        if (handlerMod != null && HandlerGuards.neverSwitched.contains(handlerMod)) {
            return Outcome.LEFT;
        }
        if (method.name.equals("<clinit>")) {
            return Outcome.refuse("handler in the class's setup");
        }
        if (method.name.equals("<init>")) {
            String gameField = findGameFieldSet(node, call, shipped.get());
            if (gameField != null) {
                return Outcome.refuse("handler in a constructor that sets " + gameField);
            }

            if (!call.name.startsWith("handler$")) {
                if (isArraySize(call)) {
                    return Outcome.refuse("an array's size changed in a constructor");
                }
            } else {
                if (!HandlerGuards.UNFLIPPABLE.contains(mixin)) {
                    LOGGER.info("[Hotwire] {}'s constructor calls a handler of {}: its on rebuilds, its off"
                            + " flips", internalName, mixin);
                }

                HandlerGuards.markUnflippable(internalName, mixin, HandlerGuards.IN_CONSTRUCTOR);
            }
        }
        boolean isStatic = call.getOpcode() == Opcodes.INVOKESTATIC;
        int how = isStatic ? HandlerGuards.STATIC : call.getOpcode() == Opcodes.INVOKESPECIAL
                ? HandlerGuards.OWN : HandlerGuards.VIRTUAL;
        String prefix = call.name.substring(0, call.name.indexOf('$'));
        Integer kind = HandlerGuards.KINDS.get(prefix);
        if (kind == null && !prefix.equals("redirect")) {
            return Outcome.LEFT;
        }
        String desc = isStatic ? call.desc : "(L" + internalName + ";" + call.desc.substring(1);
        String why = "";
        if (kind == null) {
            ClassNode before = shipped.get();
            RedirectStandIns.Target target = before == null ? null
                    : RedirectStandIns.find(before, node, method, call.desc);
            if (target == null && !HandlerGuards.UNFLIPPABLE.contains(mixin)) {
                why = " (" + RedirectStandIns.explainMiss(before, node, method, call.desc) + ")";
            }
            if (target != null) {
                String unset = findUnset(node, call, before);
                return Outcome.put(unset.isEmpty()
                        ? new InvokeDynamicInsnNode(call.name, desc, HandlerGuards.BOOTSTRAP_REDIRECT,
                        mixin, how, target.opcode(), target.owner(), target.name(), target.desc())
                        : new InvokeDynamicInsnNode(call.name, desc,
                        HandlerGuards.BOOTSTRAP_REDIRECT_FORGETTING, mixin, how, target.opcode(),
                        target.owner(), target.name(), target.desc(), unset));
            }
        }
        OptionalInt index = kind == null ? OptionalInt.empty() : switch (kind) {
            case HandlerStandIns.AS_IT_WAS -> HandlerStandIns.findAsItWas(call);
            case HandlerStandIns.OPERATION -> HandlerStandIns.findOperation(call.desc);
            default -> OptionalInt.of(0);
        };
        if (kind != null && kind == HandlerStandIns.OPERATION && index.isPresent()
                && nulls.isNull(method, call, index.getAsInt())) {
            ClassNode was = shipped.get();
            Type[] arguments = Type.getArgumentTypes(call.desc);
            String shape = Type.getMethodDescriptor(Type.getReturnType(call.desc),
                    Arrays.copyOf(arguments, index.getAsInt()));
            RedirectStandIns.Target target = was == null ? null
                    : RedirectStandIns.find(was, node, method, shape);
            if (target == null) {
                if (!HandlerGuards.UNFLIPPABLE.contains(mixin)) {
                    LOGGER.info("[Hotwire] {}.{} runs its operation inside itself and what it wraps is not"
                            + " told: {} is rebuilt, not flipped", internalName, call.name, mixin);
                }
                return Outcome.refuse("operation inside the wrapper");
            }
            return Outcome.put(new InvokeDynamicInsnNode(call.name, desc,
                    HandlerGuards.BOOTSTRAP_REDIRECT, mixin, how, target.opcode(), target.owner(), target.name(),
                    target.desc()));
        }
        if (index.isEmpty()) {
            if (!HandlerGuards.UNFLIPPABLE.contains(mixin)) {
                LOGGER.info("[Hotwire] {}.{} has no stand-in{}: {} is rebuilt, not flipped", internalName,
                        call.name, why, mixin);
            }
            return Outcome.refuse("no stand-in" + why);
        }
        return Outcome.put(method.name.equals("<init>") && !isStatic
                && call.name.startsWith("handler$")
                ? new InvokeDynamicInsnNode(call.name, desc, HandlerGuards.BOOTSTRAP_CONSTRUCTOR, mixin, how)
                : new InvokeDynamicInsnNode(call.name, desc, HandlerGuards.BOOTSTRAP, mixin, how, kind,
                        index.getAsInt() + (isStatic ? 0 : 1)));
    }

    private static boolean isArraySize(MethodInsnNode call) {
        AbstractInsnNode next = call.getNext();
        while (next != null && next.getOpcode() < 0) {
            next = next.getNext();
        }
        return next != null && (next.getOpcode() == Opcodes.NEWARRAY || next.getOpcode() == Opcodes.ANEWARRAY
                || next.getOpcode() == Opcodes.MULTIANEWARRAY);
    }

    private static String findUnset(ClassNode node, MethodInsnNode call, @Nullable ClassNode shipped) {
        if (shipped == null || call.getOpcode() == Opcodes.INVOKESTATIC) {
            return "";
        }
        Set<String> unset = new TreeSet<>();
        Set<String> constructed = new HashSet<>();
        for (MethodNode method : node.methods) {
            boolean handler = method.name.equals(call.name) && method.desc.equals(call.desc);
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                        && field.owner.equals(node.name)) {
                    String named = field.name + " " + field.desc;
                    if (method.name.equals("<init>")) {
                        constructed.add(named);
                    } else if (handler && Type.getType(field.desc).getSort() < Type.ARRAY
                            && !hasField(shipped, field.name)) {
                        unset.add(named);
                    }
                }
            }
        }
        unset.removeAll(constructed);
        return String.join(";", unset);
    }

    private static void markFinalWrites(String internalName, ClassNode node, Supplier<@Nullable ClassNode> shipped) {
        for (MethodNode method : node.methods) {
            String mixin = HandlerGuards.isHandlerName(method.name) ? HandlerGuards.findMerged(method) : null;
            if (mixin == null) {
                continue;
            }
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode store && store.getOpcode() == Opcodes.PUTFIELD
                        && store.owner.equals(internalName) && store.name.indexOf('$') < 0) {
                    ClassNode game = shipped.get();
                    if (game != null && hasFinalField(game, store.name, store.desc)
                            && SwappedFields.isMadePlainly(game, store.name + store.desc)) {
                        HandlerGuards.markUnflippable(internalName, mixin, "writes the game's final " + store.name);
                    }
                }
            }
        }
    }

    private static @Nullable String findGameFieldSet(ClassNode node, MethodInsnNode call,
            @Nullable ClassNode shipped) {
        if (shipped == null) {
            return null;
        }
        for (MethodNode handler : node.methods) {
            if (!handler.name.equals(call.name) || !handler.desc.equals(call.desc)) {
                continue;
            }
            for (AbstractInsnNode insn : handler.instructions) {
                if (insn instanceof FieldInsnNode field && (field.getOpcode() == Opcodes.PUTFIELD
                        || field.getOpcode() == Opcodes.PUTSTATIC) && field.owner.equals(node.name)
                        && hasField(shipped, field.name)) {
                    return field.name;
                }
            }
        }
        return null;
    }

    private static boolean markStaged(String internalName, Set<String> guarded) {
        if (guarded.isEmpty()) {
            return false;
        }
        ClassState.of(internalName).setStaged(Set.copyOf(guarded));
        return true;
    }

    private static boolean hasField(ClassNode node, String name) {
        for (FieldNode own : node.fields) {
            if (own.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasFinalField(ClassNode node, String name, String desc) {
        for (FieldNode own : node.fields) {
            if (own.name.equals(name) && own.desc.equals(desc) && (own.access & Opcodes.ACC_FINAL) != 0) {
                return true;
            }
        }
        return false;
    }
}
