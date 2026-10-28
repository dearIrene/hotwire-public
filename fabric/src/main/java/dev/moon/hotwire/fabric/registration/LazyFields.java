package dev.moon.hotwire.fabric.registration;

import com.google.common.collect.MapMaker;
import dev.moon.hotwire.redefine.FieldSlots;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class LazyFields {
    private static final Logger LOGGER = LoggerFactory.getLogger(LazyFields.class);

    private static final Map<Object, Object> HOLDERS = new MapMaker().weakKeys().weakValues().makeMap();
    private static final Map<Class<?>, Set<String>> LAZY = new ConcurrentHashMap<>();

    private LazyFields() {
    }

    static void noteHolder(Object collection, Object holder) {
        HOLDERS.put(collection, holder);
    }

    @SuppressWarnings("ReferenceEquality")
    static void resetFor(Object collection) {
        Object holder = HOLDERS.get(collection);
        if (holder == null) {
            return;
        }
        for (Class<?> at = holder.getClass(); at != null && at != Object.class; at = at.getSuperclass()) {
            Set<String> lazy = LAZY.computeIfAbsent(at, LazyFields::findLazy);
            for (FieldSlots.Slot field : Fields.getDeclared(at)) {
                if (lazy.contains(field.getName()) && !field.isStatic()
                        && !Modifier.isFinal(field.getModifiers())) {
                    try {
                        if (field.get(holder) != null && field.get(holder) != collection) {
                            field.set(holder, null);
                            LOGGER.info("[Hotwire] {}.{} is made again from what its collection holds now",
                                    at.getSimpleName(), field.getName());
                        }
                    } catch (ReflectiveOperationException | RuntimeException e) {
                    }
                }
            }
        }
    }

    private static Set<String> findLazy(Class<?> type) {
        ClassNode node = read(type);
        Set<String> lazy = new HashSet<>();
        if (node == null) {
            return lazy;
        }
        for (MethodNode method : node.methods) {
            Set<String> tested = new HashSet<>();
            Set<String> set = new HashSet<>();
            for (AbstractInsnNode insn : method.instructions) {
                if (!(insn instanceof FieldInsnNode field) || !field.owner.equals(node.name)) {
                    continue;
                }
                AbstractInsnNode next = field.getNext();
                if (field.getOpcode() == Opcodes.GETFIELD && next != null
                        && (next.getOpcode() == Opcodes.IFNULL || next.getOpcode() == Opcodes.IFNONNULL)) {
                    tested.add(field.name);
                } else if (field.getOpcode() == Opcodes.PUTFIELD) {
                    set.add(field.name);
                }
            }
            tested.retainAll(set);
            lazy.addAll(tested);
        }
        return lazy;
    }

    private static @Nullable ClassNode read(Class<?> type) {
        ClassLoader loader = type.getClassLoader();
        if (loader == null) {
            return null;
        }
        try (InputStream in = loader.getResourceAsStream(type.getName().replace('.', '/') + ".class")) {
            if (in == null) {
                return null;
            }
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
