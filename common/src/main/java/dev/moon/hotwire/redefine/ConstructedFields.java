package dev.moon.hotwire.redefine;

import dev.moon.hotwire.concurrent.Parallel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class ConstructedFields {
    private static final int HANDLER_ARGUMENT_STEPS = 4;

    record ClassFields(List<FieldInfo> fields, Set<String> set, Map<String, String> arraySizes, Set<String> read,
            Set<String> maybeUnset, Set<String> storedAfter) {
        Set<String> keys() {
            Set<String> keys = new HashSet<>();
            this.fields.forEach(field -> keys.add(field.key()));
            return keys;
        }
    }

    record FieldInfo(int access, String name, String desc) {
        boolean isInstance() {
            return (this.access & Opcodes.ACC_STATIC) == 0;
        }

        boolean isReference() {
            return this.desc.startsWith("L") || this.desc.startsWith("[");
        }

        String key() {
            return this.name + this.desc;
        }
    }

    @SuppressWarnings("ArrayAsKeyOfSetOrMap")
    private static final Map<byte[], ClassFields> FIELDS_BY_BYTES = Collections.synchronizedMap(new WeakHashMap<>());

    private ConstructedFields() {
    }

    static ClassFields readFields(byte[] classBytes) {
        ClassFields known = FIELDS_BY_BYTES.get(classBytes);
        if (known == null) {
            ClassNode node = read(classBytes);
            List<FieldInfo> fields = new ArrayList<>();
            node.fields.forEach(field -> fields.add(new FieldInfo(field.access, field.name, field.desc)));
            Set<String> read = new HashSet<>();
            Set<String> maybeUnset = new HashSet<>();
            collectReads(node, read, maybeUnset);
            known = new ClassFields(List.copyOf(fields), findConstructed(node), findArraySizes(node), Set.copyOf(read),
                    Set.copyOf(maybeUnset), findStoredAfter(node));
            FIELDS_BY_BYTES.put(classBytes, known);
        }
        return known;
    }

    private static List<AbstractInsnNode> findConstructing(ClassNode node) {
        Map<String, MethodNode> methods = new HashMap<>();
        node.methods.forEach(method -> methods.put(method.name + method.desc, method));
        List<AbstractInsnNode> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Deque<MethodNode> todo = new ArrayDeque<>();
        for (MethodNode method : node.methods) {
            if (method.name.equals("<init>")) {
                todo.add(method);
            }
        }
        while (!todo.isEmpty()) {
            MethodNode method = todo.pop();
            if (!seen.add(method.name + method.desc)) {
                continue;
            }
            for (AbstractInsnNode insn : method.instructions) {
                all.add(insn);
                if (insn instanceof MethodInsnNode call && call.owner.equals(node.name)
                        && !call.name.equals("<init>")) {
                    MethodNode called = methods.get(call.name + call.desc);
                    if (called != null) {
                        todo.add(called);
                    }
                }
            }
        }
        return all;
    }

    private static Set<String> findStoredAfter(ClassNode node) {
        Map<String, Set<String>> callers = new HashMap<>();
        Set<String> building = new HashSet<>();
        for (MethodNode method : node.methods) {
            String key = method.name + method.desc;
            if ((method.access & Opcodes.ACC_PRIVATE) != 0 && !method.name.startsWith("<")) {
                building.add(key);
            }
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(node.name)) {
                    callers.computeIfAbsent(call.name + call.desc, any -> new HashSet<>()).add(key);
                }
            }
        }
        boolean shrank = true;
        while (shrank) {
            shrank = building.removeIf(key -> isCalledOutsideBuilding(callers.getOrDefault(key, Set.of()), building));
        }
        Set<String> stored = new HashSet<>();
        for (MethodNode method : node.methods) {
            if (method.name.startsWith("<") || building.contains(method.name + method.desc)) {
                continue;
            }
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode store && store.getOpcode() == Opcodes.PUTFIELD
                        && store.owner.equals(node.name)) {
                    stored.add(store.name);
                }
            }
        }
        return Set.copyOf(stored);
    }

    private static boolean isCalledOutsideBuilding(Set<String> callers, Set<String> building) {
        for (String caller : callers) {
            if (!caller.startsWith("<init>") && !building.contains(caller)) {
                return true;
            }
        }
        return callers.isEmpty();
    }

    private static ClassNode read(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, ClassReader.SKIP_FRAMES);
        return node;
    }

    public static Set<String> of(byte[] classBytes) {
        return readFields(classBytes).set();
    }

    private static Set<String> findConstructed(ClassNode node) {
        Map<String, MethodNode> methods = new HashMap<>();
        node.methods.forEach(method -> methods.put(method.name + method.desc, method));
        Set<String> set = new TreeSet<>();
        for (MethodNode constructor : node.methods) {
            if (constructor.name.equals("<init>")) {
                Map<String, Boolean> last = new HashMap<>();
                collectLastStores(node.name, constructor, methods, new HashSet<>(), last);
                last.forEach((field, made) -> {
                    if (made) {
                        set.add(field);
                    }
                });
            }
        }
        return Collections.unmodifiableSet(set);
    }

    private static void collectLastStores(String owner, MethodNode method, Map<String, MethodNode> methods,
            Set<String> onStack, Map<String, Boolean> last) {
        if (!onStack.add(method.name + method.desc)) {
            return;
        }
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof FieldInsnNode store && store.getOpcode() == Opcodes.PUTFIELD
                    && store.owner.equals(owner)) {
                AbstractInsnNode value = store.getPrevious();
                while (value != null && value.getOpcode() < 0) {
                    value = value.getPrevious();
                }
                last.put(store.name, value == null || value.getOpcode() != Opcodes.ACONST_NULL);
            } else if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && !call.name.equals("<init>")) {
                MethodNode called = methods.get(call.name + call.desc);
                if (called != null) {
                    collectLastStores(owner, called, methods, onStack, last);
                }
            }
        }
        onStack.remove(method.name + method.desc);
    }

    static Map<String, String> findArraySizes(byte[] classBytes) {
        return readFields(classBytes).arraySizes();
    }

    private static Map<String, String> findArraySizes(ClassNode node) {
        Map<String, String> sizes = new TreeMap<>();
        for (AbstractInsnNode insn : findConstructing(node)) {
            if (insn.getOpcode() != Opcodes.NEWARRAY && insn.getOpcode() != Opcodes.ANEWARRAY) {
                continue;
            }
            AbstractInsnNode next = insn.getNext();
            while (next != null && next.getOpcode() < 0) {
                next = next.getNext();
            }
            if (next instanceof FieldInsnNode store && store.getOpcode() == Opcodes.PUTFIELD
                    && store.owner.equals(node.name)) {
                Object size = findConstant(insn.getPrevious());
                sizes.put(store.name, size != null ? String.valueOf(size) : findHandledSizes(node, insn.getPrevious()));
            }
        }
        return Collections.unmodifiableMap(sizes);
    }

    private static String findHandledSizes(ClassNode node, @Nullable AbstractInsnNode insn) {
        Set<Integer> sizes = insn instanceof MethodInsnNode call ? findReturned(node, call) : null;
        if (sizes == null) {
            return "?";
        }
        StringJoiner joined = new StringJoiner("|");
        for (int size : sizes) {
            joined.add(String.valueOf(size));
        }
        return joined.toString();
    }

    private static @Nullable Set<Integer> findReturned(ClassNode node, MethodInsnNode call) {
        if (!call.owner.equals(node.name) || !call.desc.startsWith("(I") || !call.desc.endsWith(")I")) {
            return null;
        }
        Integer argument = null;
        AbstractInsnNode back = call.getPrevious();
        for (int step = 0; step < HANDLER_ARGUMENT_STEPS && back != null && argument == null; step++) {
            argument = findConstant(back) instanceof Integer constant ? constant : null;
            back = back.getPrevious();
        }
        MethodNode handler = null;
        for (MethodNode method : node.methods) {
            if (method.name.equals(call.name) && method.desc.equals(call.desc)) {
                handler = method;
            }
        }
        if (handler == null || argument == null) {
            return null;
        }
        int slot = (handler.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        Set<Integer> sizes = new TreeSet<>();
        for (AbstractInsnNode at : handler.instructions) {
            if (at.getOpcode() != Opcodes.IRETURN) {
                continue;
            }
            AbstractInsnNode value = at.getPrevious();
            if (findConstant(value) instanceof Integer constant) {
                sizes.add(constant);
            } else if (value instanceof VarInsnNode load && load.getOpcode() == Opcodes.ILOAD && load.var == slot) {
                sizes.add(argument);
            } else {
                return null;
            }
        }
        return sizes.isEmpty() ? null : sizes;
    }

    static boolean fitsSizes(String running, String target) {
        if (running.equals(target)) {
            return true;
        }
        if (running.contains("?") || target.contains("?")) {
            return false;
        }
        int smallest = Integer.MAX_VALUE;
        for (String one : List.of(running.split("[|]"))) {
            smallest = Math.min(smallest, Integer.parseInt(one));
        }
        for (String one : List.of(target.split("[|]"))) {
            if (Integer.parseInt(one) > smallest) {
                return false;
            }
        }
        return true;
    }

    private static @Nullable Object findConstant(@Nullable AbstractInsnNode insn) {
        if (insn == null) {
            return null;
        }
        int opcode = insn.getOpcode();
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
            return opcode - Opcodes.ICONST_0;
        }
        if (insn instanceof IntInsnNode push && (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH)) {
            return push.operand;
        }
        if (insn instanceof LdcInsnNode ldc) {
            return ldc.cst;
        }
        return null;
    }

    static Set<String> findNewlyRelied(byte[] running, byte[] rebuilt) {
        return findNewlyRelied(running, rebuilt, null);
    }

    private static Set<String> findNewlyRelied(byte[] running, byte[] rebuilt, byte @Nullable [] launch) {
        Set<String> declared = new TreeSet<>();
        for (byte[] shape : launch == null ? List.of(running) : List.of(running, launch)) {
            for (FieldInfo field : readFields(shape).fields()) {
                if (field.isInstance()) {
                    declared.add(field.name());
                }
            }
        }
        Set<String> relied = new TreeSet<>(of(rebuilt));
        relied.removeAll(of(running));
        relied.retainAll(declared);
        return relied;
    }

    public static Set<String> findSetOutsideConstructors(byte[] classBytes, byte[] original) {
        Set<String> own = readFields(original).keys();
        ClassFields catalog = readFields(classBytes);
        Set<String> outside = new TreeSet<>();
        for (FieldInfo field : catalog.fields()) {
            if (field.isInstance() && field.isReference() && !own.contains(field.key())
                    && !catalog.set().contains(field.name()) && catalog.read().contains(field.name())
                    && !catalog.maybeUnset().contains(field.name())) {
                outside.add(field.key());
            }
        }
        return outside;
    }

    static Set<String> findReliedSetBy(byte[] classBytes, String method, String descriptor) {
        ClassNode node = read(classBytes);
        Map<String, MethodNode> methods = new HashMap<>();
        node.methods.forEach(each -> methods.put(each.name + each.desc, each));
        MethodNode start = methods.get(method + descriptor);
        Set<String> relied = new TreeSet<>();
        if (start == null) {
            return relied;
        }
        Map<String, Boolean> last = new HashMap<>();
        collectLastStores(node.name, start, methods, new HashSet<>(), last);
        ClassFields catalog = readFields(classBytes);
        for (FieldInfo field : catalog.fields()) {
            if (field.isInstance() && Boolean.TRUE.equals(last.get(field.name()))
                    && catalog.read().contains(field.name()) && !catalog.maybeUnset().contains(field.name())) {
                relied.add(field.name());
            }
        }
        return relied;
    }

    static boolean readAsMaybeUnset(byte[] classBytes, String field) {
        return readFields(classBytes).maybeUnset().contains(field);
    }

    private static void collectReads(ClassNode node, Set<String> read, Set<String> maybeUnset) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode get && get.getOpcode() == Opcodes.GETFIELD
                        && get.owner.equals(node.name)) {
                    read.add(get.name);
                    AbstractInsnNode next = findNextReal(insn);
                    if (next != null && next.getOpcode() == Opcodes.DUP) {
                        next = findNextReal(next);
                    }
                    if (!method.name.equals("<init>") && next != null
                            && (next.getOpcode() == Opcodes.IFNULL || next.getOpcode() == Opcodes.IFNONNULL)) {
                        maybeUnset.add(get.name);
                    }
                }
            }
        }
    }

    private static @Nullable AbstractInsnNode findNextReal(AbstractInsnNode insn) {
        AbstractInsnNode next = insn.getNext();
        while (next != null && next.getOpcode() < 0) {
            next = next.getNext();
        }
        return next;
    }

    public record Unmade(List<String> lines, boolean reopenFixes) {
        public Unmade {
            lines = List.copyOf(lines);
        }
    }

    public static Unmade findUnmade(Map<Class<?>, byte[]> targets, Map<String, byte[]> current,
            Predicate<Class<?>> liveObjects, Predicate<Class<?>> worldObject,
            Function<String, byte @Nullable []> originalOf, ToLongFunction<String> madeWhileOut,
            Function<String, Set<String>> replayed) {
        return findUnmade(targets, current, liveObjects, worldObject, originalOf, madeWhileOut, replayed,
                type -> true);
    }

    public static Unmade findUnmade(Map<Class<?>, byte[]> targets, Map<String, byte[]> current,
            Predicate<Class<?>> liveObjects, Predicate<Class<?>> worldObject,
            Function<String, byte @Nullable []> originalOf, ToLongFunction<String> madeWhileOut,
            Function<String, Set<String>> replayed, Predicate<Class<?>> alive) {
        return findUnmade(targets, current, liveObjects, worldObject, originalOf, madeWhileOut, replayed, alive,
                name -> null);
    }

    public static Unmade findUnmade(Map<Class<?>, byte[]> targets, Map<String, byte[]> current,
            Predicate<Class<?>> liveObjects, Predicate<Class<?>> worldObject,
            Function<String, byte @Nullable []> originalOf, ToLongFunction<String> madeWhileOut,
            Function<String, Set<String>> replayed, Predicate<Class<?>> alive,
            Function<String, byte @Nullable []> launchOf) {
        Map<Class<?>, List<String>> found = new ConcurrentHashMap<>();
        Parallel.forEach(targets.entrySet(), entry -> {
            String name = entry.getKey().getName().replace('.', '/');
            byte[] running = current.get(name);
            if (running != null && liveObjects.test(entry.getKey())) {
                List<String> lines = new ArrayList<>();
                collectUnmade(name, running, entry.getValue(), originalOf, madeWhileOut, replayed.apply(name),
                        launchOf.apply(name), worldObject.test(entry.getKey()), lines);
                if (!lines.isEmpty() && alive.test(entry.getKey())) {
                    found.put(entry.getKey(), lines);
                }
            }
        });
        List<String> unmade = new ArrayList<>();
        boolean reopenFixes = true;
        for (Class<?> type : targets.keySet()) {
            List<String> lines = found.get(type);
            if (lines != null) {
                unmade.addAll(lines);
                reopenFixes &= worldObject.test(type);
            }
        }
        return new Unmade(unmade, !unmade.isEmpty() && reopenFixes);
    }

    private static void collectUnmade(String name, byte[] running, byte[] target,
            Function<String, byte @Nullable []> originalOf, ToLongFunction<String> madeWhileOut, Set<String> replayed,
            byte @Nullable [] launch, boolean world, List<String> unmade) {
        Map<String, String> before = findArraySizes(running);
        Map<String, String> after = findArraySizes(target);
        List<String> resized = new ArrayList<>();
        before.forEach((field, size) -> {
            String now = after.get(field);

            if (now != null && !fitsSizes(size, now) && !now.equals("0") && !size.equals("0")) {
                resized.add(field + " " + size + " -> " + now);
            }
        });
        if (!resized.isEmpty()) {
            unmade.add(name + " makes its arrays at other sizes (" + String.join(", ", resized) + ")");
        }
        List<byte[]> shapes = world && launch != null ? List.of(running, launch) : List.of(running);
        Set<String> relied = new TreeSet<>(findNewlyRelied(running, target, world ? launch : null));
        relied.removeAll(replayed);

        byte[] original = originalOf.apply(name);
        if (original != null) {
            Set<String> own = new HashSet<>();
            readFields(original).fields().forEach(field -> own.add(field.name()));

            boolean noneMadeWithout = madeWhileOut.applyAsLong(name) == 0;

            Set<String> unsetAtLaunch = launch == null ? null : findNewlyRelied(launch, target);
            Set<String> storedAfter = readFields(target).storedAfter();
            relied.removeIf(field -> own.contains(field)
                    ? (readAsMaybeUnset(target, field) && storedAfter.contains(field))
                            || (noneMadeWithout && unsetAtLaunch != null && !unsetAtLaunch.contains(field))
                    : readAsMaybeUnset(target, field) || noneMadeWithout);
        }

        Set<String> references = new HashSet<>();
        for (byte[] shape : shapes) {
            for (FieldInfo field : readFields(shape).fields()) {
                if (field.isReference()) {
                    references.add(field.name());
                }
            }
        }
        relied.retainAll(references);
        if (!relied.isEmpty()) {
            unmade.add(name + " would rely on " + relied + ", which its live objects never set");
        }
    }
}
