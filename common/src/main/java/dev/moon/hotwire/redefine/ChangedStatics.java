package dev.moon.hotwire.redefine;

import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ChangedStatics {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChangedStatics.class);
    private static final String PREFIX = "hotwire$passedStatic$";

    private static final Pattern MERGED = Pattern.compile("^[A-Za-z]+\\$(?!lambda\\$)[a-z]{3}[0-9a-f]{3}\\$");

    private ChangedStatics() {
    }

    static String makeKey(String member) {
        return MERGED.matcher(member).replaceFirst("");
    }

    static Map<String, String> add(ClassNode now, Set<String> fresh) {
        Map<String, String> recomputed = new LinkedHashMap<>();
        Set<String> stored = new HashSet<>();
        for (MethodNode method : now.methods) {
            if (!method.name.equals("<clinit>")) {
                continue;
            }
            boolean after = false;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(now.name)
                        && fresh.contains(makeKey(call.name) + call.desc)) {
                    after = true;
                } else if (after && insn instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTSTATIC
                        && put.owner.equals(now.name)) {
                    stored.add(put.name + put.desc);
                    after = false;
                }
            }
        }
        if (stored.isEmpty()) {
            return recomputed;
        }
        FieldInitializers.findStaticSlices(now.name, now, stored).forEach((field, slice) -> {
            if (!callsAny(slice, now.name, fresh)) {
                return;
            }
            AbstractInsnNode store = slice.getLast();
            if (!(store instanceof FieldInsnNode put) || put.getOpcode() != Opcodes.PUTSTATIC) {
                return;
            }
            slice.set(store, new InsnNode(Type.getType(put.desc).getOpcode(Opcodes.IRETURN)));
            MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                    PREFIX + put.name, "()" + put.desc, null, null);
            method.instructions.add(slice);
            now.methods.add(method);
            recomputed.put(put.name, put.desc);
        });
        return recomputed;
    }

    private static boolean callsAny(InsnList slice, String owner, Set<String> fresh) {
        for (AbstractInsnNode insn : slice) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(owner)
                    && fresh.contains(makeKey(call.name) + call.desc)) {
                return true;
            }
        }
        return false;
    }

    static int run(Class<?> type, Map<String, String> statics, InitHookReplay.StaticWriter writer) {
        if (statics.isEmpty()) {
            return 0;
        }
        MethodHandles.Lookup side = Linker.findCurrentLookup(type);
        if (side == null) {
            LOGGER.error("[Hotwire] {} has no sidecar to make {} again", type.getName(), statics.keySet());
            return statics.size();
        }
        int failed = 0;
        for (Map.Entry<String, String> entry : statics.entrySet()) {
            try {
                Field field = type.getDeclaredField(entry.getKey());
                Class<?> valueType = field.getType();
                Supplier<?> make = MethodHandleProxies.asInterfaceInstance(Supplier.class, side.findStatic(
                        side.lookupClass(), PREFIX + entry.getKey(), MethodType.methodType(valueType)));
                Object made = make.get();
                field.setAccessible(true);
                Object old = field.get(null);
                copyElements(old, made);
                writer.write(field, made);
                LOGGER.info("[Hotwire] {}.{} was made again by the setup the mods give it now{}", type.getName(),
                        entry.getKey(), Modifier.isFinal(field.getModifiers()) ? " (a final)" : "");
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                failed++;
                LOGGER.error("[Hotwire] {}.{} could not be made again by its new setup", type.getName(),
                        entry.getKey(), e);
            }
        }
        return failed;
    }

    private static void copyElements(@Nullable Object old, @Nullable Object made) {
        if (old != null && made != null && old.getClass().isArray() && old.getClass() == made.getClass()) {
            System.arraycopy(old, 0, made, 0, Math.min(Array.getLength(old), Array.getLength(made)));
        }
    }
}
