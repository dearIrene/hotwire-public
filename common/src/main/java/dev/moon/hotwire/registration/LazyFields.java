package dev.moon.hotwire.registration;

import com.google.common.collect.MapMaker;
import dev.moon.hotwire.classfile.ClassNodes;
import dev.moon.hotwire.redefine.FieldSlots;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class LazyFields {
    private static final Logger LOGGER = LoggerFactory.getLogger(LazyFields.class);

    private final Map<Object, Object> holders = new MapMaker().weakKeys().weakValues().makeMap();

    private final Map<Class<?>, Map<String, Set<String>>> lazyByClass = new ConcurrentHashMap<>();

    private final Map<Object, Map<FieldSlots.Slot, Object>> nulled = new MapMaker().weakKeys().makeMap();

    void noteHolder(Object collection, Object holder) {
        this.holders.put(collection, holder);
    }

    @SuppressWarnings("ReferenceEquality")
    void resetFor(Object collection) {
        Object holder = this.holders.get(collection);
        if (holder == null) {
            return;
        }
        for (Class<?> at = holder.getClass(); at != null && at != Object.class; at = at.getSuperclass()) {
            Map<String, Set<String>> lazy = this.lazyByClass.computeIfAbsent(at, LazyFields::findLazy);
            String of = findFieldHolding(at, holder, collection);
            if (of == null) {
                continue;
            }
            for (FieldSlots.Slot field : Fields.getDeclared(at)) {
                if (lazy.getOrDefault(field.getName(), Set.of()).contains(of) && !field.isStatic()
                        && !Modifier.isFinal(field.getModifiers())) {
                    try {
                        Object before = field.get(holder);
                        if (before != null && before != collection) {
                            this.nulled.computeIfAbsent(holder, any -> new ConcurrentHashMap<>())
                                    .putIfAbsent(field, before);
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

    @SuppressWarnings("ReferenceEquality")
    void restoreFor(Object collection) {
        Object holder = this.holders.get(collection);
        if (holder == null) {
            return;
        }
        Map<FieldSlots.Slot, Object> fields = this.nulled.remove(holder);
        if (fields == null) {
            return;
        }
        fields.forEach((field, before) -> {
            try {
                Object now = field.get(holder);
                if (now == null) {
                    field.set(holder, before);
                    LOGGER.info("[Hotwire] {}.{} has its value from before its collection's elements left again",
                            holder.getClass().getSimpleName(), field.getName());
                } else if (now != before) {
                    field.set(holder, null);
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
            }
        });
    }

    @SuppressWarnings("ReferenceEquality")
    private static @Nullable String findFieldHolding(Class<?> at, Object holder, Object collection) {
        for (FieldSlots.Slot field : Fields.getDeclared(at)) {
            try {
                if (!field.isStatic() && field.get(holder) == collection) {
                    return field.getName();
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
            }
        }
        return null;
    }

    private static Map<String, Set<String>> findLazy(Class<?> type) {
        ClassNode node = read(type);
        Map<String, Set<String>> lazy = new HashMap<>();
        if (node == null) {
            return lazy;
        }
        Map<String, Set<String>> setBy = new HashMap<>();
        Map<String, Set<String>> readBy = new HashMap<>();
        for (MethodNode method : node.methods) {
            Set<String> set = new HashSet<>();
            Set<String> read = new HashSet<>();
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode field && field.owner.equals(node.name)) {
                    (field.getOpcode() == Opcodes.PUTFIELD ? set : read).add(field.name);
                }
            }
            setBy.put(method.name + method.desc, set);
            readBy.put(method.name + method.desc, read);
        }
        for (MethodNode method : node.methods) {
            Set<String> tested = new HashSet<>();
            Set<String> set = new HashSet<>(setBy.getOrDefault(method.name + method.desc, Set.of()));
            Set<String> read = new HashSet<>(readBy.getOrDefault(method.name + method.desc, Set.of()));
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(node.name)) {
                    set.addAll(setBy.getOrDefault(call.name + call.desc, Set.of()));
                    read.addAll(readBy.getOrDefault(call.name + call.desc, Set.of()));
                } else if (insn instanceof FieldInsnNode field && field.owner.equals(node.name)
                        && field.getOpcode() == Opcodes.GETFIELD && field.getNext() != null
                        && (field.getNext().getOpcode() == Opcodes.IFNULL
                                || field.getNext().getOpcode() == Opcodes.IFNONNULL)) {
                    tested.add(field.name);
                }
            }
            tested.retainAll(set);
            for (String field : tested) {
                Set<String> from = new HashSet<>(read);
                from.remove(field);
                lazy.computeIfAbsent(field, any -> new HashSet<>()).addAll(from);
            }
        }
        return lazy;
    }

    private static @Nullable ClassNode read(Class<?> type) {
        ClassLoader loader = type.getClassLoader();
        if (loader == null) {
            return null;
        }
        try (InputStream in = loader.getResourceAsStream(Type.getInternalName(type) + ".class")) {
            if (in == null) {
                return null;
            }
            return ClassNodes.read(in.readAllBytes(), ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
