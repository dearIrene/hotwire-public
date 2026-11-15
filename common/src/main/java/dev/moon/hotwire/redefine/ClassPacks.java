package dev.moon.hotwire.redefine;

import dev.moon.hotwire.cache.CacheKey;
import dev.moon.hotwire.cache.CacheStore;
import dev.moon.hotwire.classfile.ClassNodes;
import dev.moon.hotwire.classfile.ClassSlices;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.RecordComponentNode;

final class ClassPacks {
    private static final int MOST_CLASSES = 64;

    private static final CacheKey ROOT = new CacheKey(0, 0);
    private static final byte WHOLE = 0;
    private static final byte PARTS = 1;

    private final CacheStore store;
    private final Map<CacheKey, OriginalClass> originals = Collections.synchronizedMap(makeRecent());

    private final Map<CacheKey, SoftReference<Lineage>> lineages = makeRecent();

    private record OriginalClass(CacheKey name, Map<CacheKey, Integer> index, ClassSlices slices) {
    }

    private record Part(CacheKey pack, int offset, int length) {
    }

    private static final class Lineage {
        private CacheKey extension = ROOT;
        private @Nullable CacheKey last;
        private @Nullable SoftReference<byte[]> seed;
        private final Map<Long, Part> parts = new HashMap<>();
        private final Map<CacheKey, SoftReference<byte[]>> read = new HashMap<>();
    }

    ClassPacks(CacheStore store) {
        this.store = store;
    }

    private static <K, V> Map<K, V> makeRecent() {
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return this.size() > MOST_CLASSES;
            }
        };
    }

    synchronized CacheKey put(String target, byte[] mixed, byte @Nullable [] original, List<CacheKey> used)
            throws IOException {
        ClassNode node = read(mixed);
        OriginalClass before = node == null || original == null ? null : this.findOriginal(original);
        Lineage lineage = null;
        byte[] body = null;
        if (node != null && original != null && before != null) {
            CacheKey note = CacheKey.of("class last 1").add(target).add(before.name().high())
                    .add(before.name().low()).build();
            lineage = this.findLineage(note, original);
            body = this.writeParts(node, original, before, lineage, used);
            if (body == null) {
                lineage = new Lineage();
                this.lineages.put(note, new SoftReference<>(lineage));
                body = this.writeParts(node, original, before, lineage, used);
            }
            if (body != null) {
                CacheKey key = this.putBody(target, body, original, used);
                lineage.last = key;
                this.store.remove(note);
                this.store.put(note, writeNote(lineage, key), null);
                return key;
            }
        }
        byte[] whole = ByteBuffer.allocate(1 + mixed.length).put(WHOLE).put(mixed).array();
        return this.putBody(target, whole, original, used);
    }

    private CacheKey putBody(String target, byte[] body, byte @Nullable [] original, List<CacheKey> used) {
        CacheKey key = CacheKey.of("class body 5").add(target).add(body)
                .add(original == null ? new byte[0] : original).build();
        this.store.put(key, body, original);
        used.add(key);
        return key;
    }

    private byte @Nullable [] writeParts(ClassNode node, byte[] original, OriginalClass before, Lineage lineage,
            List<CacheKey> used) throws IOException {
        SoftReference<byte[]> begun = lineage.seed;
        byte[] seed = begun == null ? null : begun.get();
        if (seed == null) {
            seed = lineage.last == null ? original : this.get(lineage.last, original, new ArrayList<>());
            if (seed == null) {
                return null;
            }
        }
        ClassSlices seedSlices = ClassSlices.read(seed);

        List<MethodNode> methods = node.methods;
        int[] places = new int[methods.size()];
        List<MethodNode> made = new ArrayList<>();
        for (int at = 0; at < places.length; at++) {
            Integer place = before.index().get(findKey(node, methods.get(at)));
            places[at] = place == null ? -1 : place;
            if (place == null) {
                made.add(methods.get(at));
            }
        }
        byte[] out;
        node.methods = made;
        try {
            ClassWriter writer = new ClassWriter(new ClassReader(seed), 0);
            node.accept(writer);
            out = writer.toByteArray();
        } finally {
            node.methods = methods;
        }
        ClassSlices slices = ClassSlices.read(out);
        int seedEnd = seedSlices.getPoolEnd();
        if (slices.getPoolEnd() < seedEnd || slices.countMethods() != made.size() || !Arrays.equals(out,
                slices.getPoolStart(), seedEnd, seed, seedSlices.getPoolStart(), seedEnd)) {
            return null;
        }
        CacheKey extension = lineage.extension;
        if (slices.getPoolEnd() > seedEnd) {
            byte[] grown = ByteBuffer.allocate(2 * Long.BYTES + slices.getPoolEnd() - seedEnd).putLong(extension.high())
                    .putLong(extension.low()).put(out, seedEnd, slices.getPoolEnd() - seedEnd).array();
            extension = CacheKey.of("class pool 1").add(before.name().high()).add(before.name().low()).add(grown)
                    .build();
            this.store.put(extension, grown, null);
            used.add(extension);
        }

        List<CacheKey> sources = new ArrayList<>();
        ByteBuffer refs = ByteBuffer.allocate((places.length + 2) * 10);
        ByteArrayOutputStream fresh = new ByteArrayOutputStream();
        List<int[]> waiting = new ArrayList<>();
        int madeAt = 0;
        for (int part = 0; part < places.length + 2; part++) {
            int from;
            int to;
            if (part == 0) {
                from = slices.getPoolEnd();
                to = slices.getMethodsAt();
            } else if (part == places.length + 1) {
                from = slices.getMethodsEnd();
                to = slices.getEnd();
            } else if (places[part - 1] >= 0) {
                refs.putShort((short) 0).putChar((char) places[part - 1]);
                continue;
            } else {
                from = slices.getMethodStart(madeAt);
                to = slices.getMethodEnd(madeAt);
                madeAt++;
            }
            Part known = this.findPart(lineage, original, out, from, to);
            if (known == null) {
                waiting.add(new int[] {refs.position(), fresh.size(), to - from, from});
                fresh.write(out, from, to - from);
                refs.putShort((short) 0).putInt(0).putInt(to - from);
            } else {
                refs.putShort((short) (indexOf(sources, known.pack()) + 1)).putInt(known.offset())
                        .putInt(known.length());
            }
        }
        if (!waiting.isEmpty()) {
            byte[] pack = fresh.toByteArray();
            CacheKey name = CacheKey.of("class parts 1").add(pack).build();
            this.store.put(name, pack, original);
            used.add(name);
            lineage.read.put(name, new SoftReference<>(pack));
            short source = (short) (indexOf(sources, name) + 1);
            for (int[] one : waiting) {
                refs.putShort(one[0], source).putInt(one[0] + 2, one[1]);
                lineage.parts.putIfAbsent(sum(out, one[3], one[3] + one[2]), new Part(name, one[1], one[2]));
            }
        }
        lineage.extension = extension;
        lineage.seed = new SoftReference<>(out);
        ByteBuffer body = ByteBuffer.allocate(1 + 4 + 2 + 4 + 2 * Long.BYTES + 2 + sources.size() * 2 * Long.BYTES
                + 4 + refs.position());
        body.put(PARTS).put(out, 4, 4).putChar((char) slices.getPoolCount()).putInt(before.slices().getPoolEnd())
                .putLong(extension.high()).putLong(extension.low()).putShort((short) sources.size());
        for (CacheKey source : sources) {
            body.putLong(source.high()).putLong(source.low());
        }
        return body.putInt(places.length + 2).put(refs.array(), 0, refs.position()).array();
    }

    private static int indexOf(List<CacheKey> sources, CacheKey pack) {
        int at = sources.indexOf(pack);
        if (at < 0) {
            sources.add(pack);
            at = sources.size() - 1;
        }
        return at;
    }

    private @Nullable Part findPart(Lineage lineage, byte[] original, byte[] bytes, int from, int to) {
        Part part = lineage.parts.get(sum(bytes, from, to));
        if (part == null || part.length() != to - from) {
            return null;
        }
        SoftReference<byte[]> cached = lineage.read.get(part.pack());
        byte[] pack = cached == null ? null : cached.get();
        if (pack == null) {
            pack = this.store.get(part.pack(), original);
            if (pack == null) {
                return null;
            }
            lineage.read.put(part.pack(), new SoftReference<>(pack));
        }
        return Arrays.equals(pack, part.offset(), part.offset() + part.length(), bytes, from, to) ? part : null;
    }

    private static long sum(byte[] bytes, int from, int to) {
        CRC32C first = new CRC32C();
        CRC32 second = new CRC32();
        first.update(bytes, from, to - from);
        second.update(bytes, from, to - from);
        return first.getValue() << 32 | second.getValue();
    }

    private Lineage findLineage(CacheKey note, byte[] original) {
        SoftReference<Lineage> soft = this.lineages.get(note);
        Lineage known = soft == null ? null : soft.get();
        if (known != null) {
            return known;
        }
        Lineage lineage = new Lineage();
        byte[] noted = this.store.get(note, null);
        if (noted != null && noted.length == 4 * Long.BYTES) {
            ByteBuffer in = ByteBuffer.wrap(noted);
            lineage.last = new CacheKey(in.getLong(), in.getLong());
            lineage.extension = new CacheKey(in.getLong(), in.getLong());
            this.readParts(lineage, original);
        }
        this.lineages.put(note, new SoftReference<>(lineage));
        return lineage;
    }

    private void readParts(Lineage lineage, byte[] original) {
        CacheKey last = lineage.last;
        byte[] body = last == null ? null : this.store.get(last, original);
        if (body == null || body[0] != PARTS) {
            return;
        }
        try {
            ByteBuffer in = ByteBuffer.wrap(body, 1 + 4 + 2 + 4 + 2 * Long.BYTES, body.length - 27);
            CacheKey[] sources = new CacheKey[in.getShort()];
            for (int source = 0; source < sources.length; source++) {
                sources[source] = new CacheKey(in.getLong(), in.getLong());
            }
            for (int count = in.getInt(); count > 0; count--) {
                int source = in.getShort();
                if (source == 0) {
                    in.getChar();
                    continue;
                }
                int offset = in.getInt();
                int length = in.getInt();
                SoftReference<byte[]> cached = lineage.read.get(sources[source - 1]);
                byte[] pack = cached == null ? null : cached.get();
                if (pack == null) {
                    pack = this.store.get(sources[source - 1], original);
                    if (pack == null) {
                        continue;
                    }
                    lineage.read.put(sources[source - 1], new SoftReference<>(pack));
                }
                lineage.parts.putIfAbsent(sum(pack, offset, offset + length),
                        new Part(sources[source - 1], offset, length));
            }
        } catch (BufferUnderflowException | IndexOutOfBoundsException e) {
            lineage.parts.clear();
        }
    }

    private static byte[] writeNote(Lineage lineage, CacheKey last) {
        return ByteBuffer.allocate(4 * Long.BYTES).putLong(last.high()).putLong(last.low())
                .putLong(lineage.extension.high()).putLong(lineage.extension.low()).array();
    }

    byte @Nullable [] get(CacheKey key, byte[] original, List<CacheKey> used) {
        byte[] body = this.store.get(key, original);
        if (body == null || body.length == 0) {
            return null;
        }
        used.add(key);
        if (body[0] == WHOLE) {
            return Arrays.copyOfRange(body, 1, body.length);
        }
        try {
            ByteBuffer in = ByteBuffer.wrap(body, 1, body.length - 1);
            int version = in.getInt();
            char poolCount = in.getChar();
            int ownPoolEnd = in.getInt();
            CacheKey extension = new CacheKey(in.getLong(), in.getLong());
            byte[][] sources = new byte[in.getShort() + 1][];
            sources[0] = original;
            for (int source = 1; source < sources.length; source++) {
                CacheKey pack = new CacheKey(in.getLong(), in.getLong());
                byte[] read = this.store.get(pack, original);
                if (read == null) {
                    return null;
                }
                sources[source] = read;
                used.add(pack);
            }

            List<byte[]> extensions = new ArrayList<>();
            int size = ownPoolEnd + 2;
            while (!extension.equals(ROOT)) {
                byte[] grown = this.store.get(extension, null);
                if (grown == null || grown.length < 2 * Long.BYTES) {
                    return null;
                }
                used.add(extension);
                extensions.add(grown);
                size += grown.length - 2 * Long.BYTES;
                extension = new CacheKey(ByteBuffer.wrap(grown).getLong(), ByteBuffer.wrap(grown).getLong(Long.BYTES));
            }
            int count = in.getInt();

            int[] parts = new int[count * 3];
            ClassSlices own = null;
            for (int part = 0; part < count; part++) {
                int source = in.getShort();
                parts[part * 3] = source;
                if (source == 0) {
                    own = own == null ? ClassSlices.read(original) : own;
                    int place = in.getChar();
                    parts[part * 3 + 1] = own.getMethodStart(place);
                    parts[part * 3 + 2] = own.getMethodEnd(place) - own.getMethodStart(place);
                } else {
                    parts[part * 3 + 1] = in.getInt();
                    parts[part * 3 + 2] = in.getInt();
                }
                size += parts[part * 3 + 2];
            }
            ByteBuffer out = ByteBuffer.allocate(size);
            out.putInt(0xCAFEBABE).putInt(version).putChar(poolCount).put(original, 10, ownPoolEnd - 10);
            for (int at = extensions.size() - 1; at >= 0; at--) {
                out.put(extensions.get(at), 2 * Long.BYTES, extensions.get(at).length - 2 * Long.BYTES);
            }
            for (int part = 0; part < count; part++) {
                if (part == 1) {
                    out.putChar((char) (count - 2));
                }
                out.put(sources[parts[part * 3]], parts[part * 3 + 1], parts[part * 3 + 2]);
            }
            return out.array();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private @Nullable OriginalClass findOriginal(byte[] original) {
        CacheKey name = CacheKey.of("class before 1").add(original).build();
        OriginalClass known = this.originals.get(name);
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
        OriginalClass before = new OriginalClass(name, index, ClassSlices.read(original));
        this.originals.put(name, before);
        return before;
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
