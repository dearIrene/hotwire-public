package dev.moon.hotwire.redefine;

import dev.moon.hotwire.cache.CacheKey;
import dev.moon.hotwire.cache.CacheStore;
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
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.RecordComponentNode;

final class ClassPacks {
    private static final int MOST_BASES = 64;

    private final CacheStore store;

    @SuppressWarnings("ArrayRecordComponent")
    private record Base(byte[] bytes, Map<CacheKey, Integer> index) {
    }

    private final Map<CacheKey, Base> bases = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<CacheKey, Base> eldest) {
                    return this.size() > MOST_BASES;
                }
            });

    ClassPacks(CacheStore store) {
        this.store = store;
    }

    void forget() {
        this.bases.clear();
    }

    record Body(CacheKey key, boolean base) {
    }

    @SuppressWarnings("ArrayRecordComponent")
    record Read(byte[] bytes, boolean base) {
    }

    Body put(String target, byte[] mixed, byte @Nullable [] original, List<CacheKey> used) throws IOException {
        ClassNode node = read(mixed);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        boolean base = true;
        try (DataOutputStream out = new DataOutputStream(body)) {
            if (node == null || original == null) {
                out.writeBoolean(false);
                out.write(mixed);
            } else {
                out.writeBoolean(true);
                base = this.writePacked(target, node, original, out, used);
            }
        }
        byte[] bytes = body.toByteArray();
        CacheKey key = CacheKey.of("class body 2").add(target).add(bytes).build();
        this.store.put(key, bytes, original);
        used.add(key);
        return new Body(key, base);
    }

    @Nullable Read get(CacheKey key, byte[] original, List<CacheKey> used) throws IOException {
        byte[] body = this.store.get(key, original);
        if (body == null) {
            return null;
        }
        used.add(key);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
            if (!in.readBoolean()) {
                return new Read(in.readAllBytes(), true);
            }
            CacheKey ownKey = new CacheKey(in.readLong(), in.readLong());
            CacheKey baseKey = new CacheKey(in.readLong(), in.readLong());
            if (ownKey.equals(baseKey)) {
                byte[] whole = this.store.get(ownKey, original);
                if (whole != null) {
                    used.add(ownKey);
                }
                return whole == null ? null : new Read(whole, true);
            }

            byte[] baseBytes = this.store.get(baseKey, original);
            if (baseBytes == null) {
                return null;
            }
            used.add(baseKey);
            ClassNode own = this.readPack(ownKey, baseBytes, used);
            if (own == null) {
                return null;
            }
            List<MethodNode> ownMethods = own.methods;
            List<MethodNode> baseMethods = List.of();
            boolean baseRead = false;
            List<MethodNode> methods = new ArrayList<>();
            for (int count = in.readInt(); count > 0; count--) {
                boolean fromBase = in.readBoolean();
                int at = in.readUnsignedShort();
                if (fromBase && !baseRead) {
                    ClassNode base = read(baseBytes);
                    if (base == null) {
                        return null;
                    }
                    baseMethods = base.methods;
                    baseRead = true;
                }
                List<MethodNode> from = fromBase ? baseMethods : ownMethods;
                if (at >= from.size()) {
                    return null;
                }
                methods.add(from.get(at));
            }
            own.methods = methods;
            return new Read(write(own), false);
        }
    }

    private boolean writePacked(String target, ClassNode node, byte[] original, DataOutputStream out,
            List<CacheKey> used) throws IOException {
        CacheKey baseName = CacheKey.of("class base 1").add(target).add(original).build();
        byte[] named = this.store.get(baseName, null);
        CacheKey baseKey = named == null || named.length != 2 * Long.BYTES ? null : readKey(named);
        Base base = baseKey == null ? null : this.findBase(baseKey, original);
        if (base == null) {
            baseKey = null;
        }
        Map<CacheKey, Integer> index = base == null ? Map.of() : base.index();
        List<MethodNode> methods = node.methods;
        List<MethodNode> fresh = new ArrayList<>();
        ByteArrayOutputStream places = new ByteArrayOutputStream();
        try (DataOutputStream where = new DataOutputStream(places)) {
            where.writeInt(methods.size());
            for (MethodNode method : methods) {
                Integer inBase = index.get(findKey(node, method));
                where.writeBoolean(inBase != null);
                if (inBase != null) {
                    where.writeShort(inBase);
                } else {
                    where.writeShort(fresh.size());
                    fresh.add(method);
                }
            }
        }
        node.methods = fresh;
        byte[] pack = write(node);
        node.methods = methods;
        CacheKey packKey = CacheKey.of("class pack 1").add(target).add(pack).build();
        this.store.put(packKey, pack, base == null ? original : base.bytes());
        used.add(packKey);
        boolean isBase = baseKey == null;
        if (baseKey == null) {
            baseKey = packKey;
            this.store.put(baseName, writeKey(packKey), null);
        } else {
            used.add(baseKey);
        }
        writeKey(out, packKey);
        writeKey(out, baseKey);
        out.write(places.toByteArray());
        return isBase;
    }

    private @Nullable Base findBase(CacheKey baseKey, byte[] original) {
        Base known = this.bases.get(baseKey);
        if (known != null && this.store.findSize(baseKey) > 0) {
            return known;
        }
        byte[] pack = this.store.get(baseKey, original);
        ClassNode node = pack == null ? null : read(pack);
        if (pack == null || node == null) {
            return null;
        }
        Map<CacheKey, Integer> index = new HashMap<>();
        for (int at = 0; at < node.methods.size(); at++) {
            index.putIfAbsent(findKey(node, node.methods.get(at)), at);
        }
        Base made = new Base(pack, index);
        this.bases.put(baseKey, made);
        return made;
    }

    private @Nullable ClassNode readPack(CacheKey key, byte[] original, List<CacheKey> used) {
        byte[] pack = this.store.get(key, original);
        if (pack == null) {
            return null;
        }
        used.add(key);
        return read(pack);
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
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
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

    private static CacheKey readKey(byte[] bytes) {
        long high = 0;
        long low = 0;
        for (int i = 0; i < Long.BYTES; i++) {
            high = high << 8 | (bytes[i] & 0xFF);
            low = low << 8 | (bytes[Long.BYTES + i] & 0xFF);
        }
        return new CacheKey(high, low);
    }

    private static byte[] writeKey(CacheKey key) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            writeKey(out, key);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    private static void writeKey(DataOutputStream out, CacheKey key) throws IOException {
        out.writeLong(key.high());
        out.writeLong(key.low());
    }
}
