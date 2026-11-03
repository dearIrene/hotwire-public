package dev.moon.hotwire.redefine;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
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

        HandlerGuards.STAGED_PRESENT.put(internalName, Set.copyOf(merged));
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
            OverwriteSites.make(internalName, node, mixinOfHandler, shipped, guarded, atLoad);
            if (atLoad) {
                FieldSites.make(internalName, node, merged, shipped, guarded);
            }
        }
        if (mixinOfHandler.isEmpty()) {
            return stage(internalName, guarded);
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
                String handlerMod = HandlerGuards.findModInHandlerName(call.name);
                if (handlerMod != null && HandlerGuards.neverSwitched.contains(handlerMod)) {
                    continue;
                }
                if (method.name.equals("<clinit>")) {
                    HandlerGuards.markUnflippable(internalName, mixin, "handler in the class's setup");
                    continue;
                }
                if (method.name.equals("<init>")) {
                    String gameField = findGameFieldSet(node, call, shipped.get());
                    if (gameField != null) {
                        HandlerGuards.markUnflippable(internalName, mixin, "handler in a constructor that sets "
                                + gameField);
                        continue;
                    }

                    if (!call.name.startsWith("handler$")) {
                        HandlerGuards.markUnflippable(internalName, mixin, "a value changed in a constructor");
                        continue;
                    }
                    if (!HandlerGuards.UNFLIPPABLE.contains(mixin)) {
                        LOGGER.info("[Hotwire] {}'s constructor calls a handler of {}: its on rebuilds, its off flips",
                                internalName, mixin);
                    }
                    HandlerGuards.markUnflippable(internalName, mixin, HandlerGuards.IN_CONSTRUCTOR);
                }
                boolean isStatic = call.getOpcode() == Opcodes.INVOKESTATIC;
                int how = isStatic ? HandlerGuards.STATIC : call.getOpcode() == Opcodes.INVOKESPECIAL
                        ? HandlerGuards.OWN : HandlerGuards.VIRTUAL;
                String prefix = call.name.substring(0, call.name.indexOf('$'));
                Integer kind = HandlerGuards.KINDS.get(prefix);
                if (kind == null && !prefix.equals("redirect")) {
                    continue;
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
                        method.instructions.set(call,
                                new InvokeDynamicInsnNode(call.name, desc, HandlerGuards.BOOTSTRAP_REDIRECT,
                                mixin, how, target.opcode(), target.owner(), target.name(),
                                target.desc()));
                        guarded.add(mixin);
                        continue;
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
                        HandlerGuards.markUnflippable(internalName, mixin, "operation inside the wrapper");
                        continue;
                    }
                    method.instructions.set(call,
                            new InvokeDynamicInsnNode(call.name, desc, HandlerGuards.BOOTSTRAP_REDIRECT,
                            mixin, how, target.opcode(), target.owner(), target.name(), target.desc()));
                    guarded.add(mixin);
                    continue;
                }
                if (index.isEmpty()) {
                    if (!HandlerGuards.UNFLIPPABLE.contains(mixin)) {
                        LOGGER.info("[Hotwire] {}.{} has no stand-in{}: {} is rebuilt, not flipped", internalName,
                                call.name, why, mixin);
                    }
                    HandlerGuards.markUnflippable(internalName, mixin, "no stand-in" + why);
                    continue;
                }
                method.instructions.set(call, new InvokeDynamicInsnNode(call.name, desc, HandlerGuards.BOOTSTRAP, mixin,
                        how, kind, index.getAsInt() + (isStatic ? 0 : 1)));
                guarded.add(mixin);
            }
        }
        return stage(internalName, guarded);
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
                        && shipped.fields.stream().anyMatch(own -> own.name.equals(field.name))) {
                    return field.name;
                }
            }
        }
        return null;
    }

    private static boolean stage(String internalName, Set<String> guarded) {
        if (guarded.isEmpty()) {
            return false;
        }
        HandlerGuards.STAGED.put(internalName, Set.copyOf(guarded));
        return true;
    }
}
