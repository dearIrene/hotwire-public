package dev.moon.hotwire.redefine;

import dev.moon.hotwire.cache.CacheKey;
import dev.moon.hotwire.cache.CacheStore;
import dev.moon.hotwire.classfile.ClassNodes;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.RecordComponentNode;

final class ClassPacks {
    private static final int MOST_ORIGINALS = 64;

    private final CacheStore store;

    private final Map<CacheKey, Map<CacheKey, Integer>> originals = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<CacheKey, Map<CacheKey, Integer>> eldest) {
                    return this.size() > MOST_ORIGINALS;
                }
            });

    ClassPacks(CacheStore store) {
        this.store = store;
    }

    CacheKey put(String target, byte[] mixed, byte @Nullable [] original) throws IOException {
        ClassNode node = read(mixed);
        Map<CacheKey, Integer> index = node == null || original == null ? null : this.findOriginal(original);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(body)) {
            if (node == null || index == null) {
                out.writeBoolean(false);
                out.write(mixed);
            } else {
                out.writeBoolean(true);
                List<MethodNode> methods = node.methods;
                List<MethodNode> made = new ArrayList<>();
                out.writeInt(methods.size());
                for (MethodNode method : methods) {
                    Integer before = index.get(findKey(node, method));
                    out.writeBoolean(before != null);
                    if (before != null) {
                        out.writeShort(before);
                    } else {
                        out.writeShort(made.size());
                        made.add(method);
                    }
                }
                node.methods = made;
                out.write(write(node));
                node.methods = methods;
            }
        }
        byte[] bytes = body.toByteArray();
        CacheKey key = CacheKey.of("class body 4").add(target).add(bytes)
                .add(original == null ? new byte[0] : original).build();
        this.store.put(key, bytes, original);
        return key;
    }

    byte @Nullable [] get(CacheKey key, byte[] original) throws IOException {
        byte[] body = this.store.get(key, original);
        if (body == null) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
            if (!in.readBoolean()) {
                return in.readAllBytes();
            }
            int count = in.readInt();
            boolean[] before = new boolean[count];
            int[] places = new int[count];
            boolean anyBefore = false;
            for (int at = 0; at < count; at++) {
                before[at] = in.readBoolean();
                places[at] = in.readUnsignedShort();
                anyBefore |= before[at];
            }
            ClassNode own = read(in.readAllBytes());
            ClassNode plain = anyBefore ? read(original) : own;
            if (own == null || plain == null) {
                return null;
            }
            List<MethodNode> methods = new ArrayList<>(count);
            for (int at = 0; at < count; at++) {
                List<MethodNode> from = before[at] ? plain.methods : own.methods;
                if (places[at] >= from.size()) {
                    return null;
                }
                methods.add(from.get(places[at]));
            }
            own.methods = methods;
            return write(own);
        }
    }

    private @Nullable Map<CacheKey, Integer> findOriginal(byte[] original) {
        CacheKey name = CacheKey.of("class before 1").add(original).build();
        Map<CacheKey, Integer> known = this.originals.get(name);
        if (known != null) {
            return known;
        }
        ClassNode node = read(original);
        if (node == null) {
            return null;
        }
        Map<CacheKey, Integer> index = new HashMap<>();
        for (int at = 0; at < node.methods.size(); at++) {
            index.putIfAbsent(findKey(node, node.methods.get(at)), at);
        }
        this.originals.put(name, index);
        return index;
    }

    private static CacheKey findKey(ClassNode owner, MethodNode method) {
        ClassNode alone = new ClassNode();
        alone.version = owner.version;
        alone.access = owner.access;
        alone.name = owner.name;
        alone.superName = owner.superName;
        alone.methods.add(method);
        return CacheKey.of("method 1").add(write(alone)).build();
    }

    private static @Nullable ClassNode read(byte[] bytes) {
        ClassNode node = ClassNodes.read(bytes, 0);
        return hasRawAttributes(node) ? null : node;
    }

    private static boolean hasRawAttributes(ClassNode node) {
        if (node.attrs != null && !node.attrs.isEmpty()) {
            return true;
        }
        for (FieldNode field : node.fields) {
            if (field.attrs != null && !field.attrs.isEmpty()) {
                return true;
            }
        }
        for (MethodNode method : node.methods) {
            if (method.attrs != null && !method.attrs.isEmpty()) {
                return true;
            }
        }
        if (node.recordComponents != null) {
            for (RecordComponentNode component : node.recordComponents) {
                if (component.attrs != null && !component.attrs.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static byte[] write(ClassNode node) {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }
}
