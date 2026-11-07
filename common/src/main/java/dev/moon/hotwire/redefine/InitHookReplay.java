package dev.moon.hotwire.redefine;

import dev.moon.hotwire.classfile.ClassNodes;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class InitHookReplay {
    static final String ON_OBJECTS = "hotwire$passedHooks";
    private static final String START_ARGUMENTS = StartArguments.class.getName().replace('.', '/');
    static final String ON_CLASS = "hotwire$passedStaticHooks";

    static final String LATE_ON_OBJECTS = "hotwire$passedLoadHooks";
    static final String LATE_ON_CLASS = "hotwire$passedStaticLoadHooks";
    private static final String CALLBACK = "org/spongepowered/asm/mixin/injection/callback/CallbackInfo";

    static final Set<String> RUN = ConcurrentHashMap.newKeySet();

    private static volatile Supplier<List<Object>> longLived = List::of;
    private static volatile Function<String, Set<String>> startup = name -> Set.of();

    private static volatile Function<String, Set<String>> joining = name -> Set.of();

    private static volatile Function<String, Set<String>> loadFinished = name -> Set.of();
    private static volatile StaticWriter statics = (field, value) -> {
        throw new IllegalAccessException("no writer of statics is set");
    };

    public record Due(Set<String> onObjects, Set<String> onClass, Map<String, String> statics, List<String> missed,
            Set<String> unset, Set<String> caughtUp, Set<String> lateOnObjects, Set<String> lateOnClass) {
        public Due {
            onObjects = Set.copyOf(onObjects);
            onClass = Set.copyOf(onClass);
            statics = Map.copyOf(statics);
            missed = List.copyOf(missed);
            unset = Set.copyOf(unset);
            caughtUp = Set.copyOf(caughtUp);
            lateOnObjects = Set.copyOf(lateOnObjects);
            lateOnClass = Set.copyOf(lateOnClass);
        }

        public Due(Set<String> onObjects, Set<String> onClass, Map<String, String> statics, List<String> missed,
                Set<String> unset, Set<String> caughtUp) {
            this(onObjects, onClass, statics, missed, unset, caughtUp, Set.of(), Set.of());
        }
    }

    public interface StaticWriter {
        void write(Field field, Object value) throws IllegalAccessException;
    }

    static final class Addition {
        private final byte[] bytes;
        private final @Nullable Due due;

        Addition(byte[] bytes, @Nullable Due due) {
            this.bytes = bytes;
            this.due = due;
        }

        byte[] getBytes() {
            return this.bytes;
        }

        @Nullable Due findDue() {
            return this.due;
        }
    }

    private record Site(String moment, MethodInsnNode call) {
    }

    private InitHookReplay() {
    }

    public static void answerWith(Supplier<List<Object>> objects, Function<String, Set<String>> methods,
            Function<String, Set<String>> joins) {
        longLived = objects;
        startup = methods;
        joining = joins;
    }

    static List<Object> getLongLived() {
        return longLived.get();
    }

    static StaticWriter getStatics() {
        return statics;
    }

    public static void answerLoadFinishedWith(Function<String, Set<String>> methods) {
        loadFinished = methods;
    }

    public static void writeStaticsWith(StaticWriter writer) {
        statics = writer;
    }

    static Addition add(String name, byte[] mixed, byte[] running, byte @Nullable [] first) {
        return add(name, mixed, running, first, null, null);
    }

    static Addition add(String name, byte[] mixed, byte[] running, byte @Nullable [] first,
            byte @Nullable [] previous, byte @Nullable [] original) {
        Set<String> once = startup.apply(name);
        Set<String> joins = joining.apply(name);
        Set<String> fresh = findKeys(mixed, name, once);
        if (fresh.isEmpty() && joins.isEmpty()) {
            return addStaticsOnly(mixed, original, running, previous);
        }
        fresh.removeAll(findKeys(running, name, once));
        if (first != null && !fresh.isEmpty()) {
            fresh.removeAll(findKeys(first, name, once));
        }
        if (previous != null && !fresh.isEmpty()) {
            fresh.removeAll(findKeys(previous, name, once));
        }
        fresh.removeIf(key -> RUN.contains(name + " " + key));

        if (!joins.isEmpty()) {
            Set<String> again = findKeysIn(mixed, name, joins);
            again.removeAll(findKeysIn(running, name, joins));
            fresh.addAll(again);
        }
        if (fresh.isEmpty()) {
            return addStaticsOnly(mixed, original, running, previous);
        }
        ClassNode now = ClassNodes.read(mixed, 0);

        Set<String> finals = StoredArguments.findFinals(running);
        InsnList onObjects = new InsnList();
        InsnList onClass = new InsnList();
        InsnList lateOnObjects = new InsnList();
        InsnList lateOnClass = new InsnList();
        Set<String> objectKeys = new LinkedHashSet<>();
        Set<String> classKeys = new LinkedHashSet<>();
        Set<String> lateObjectKeys = new LinkedHashSet<>();
        Set<String> lateClassKeys = new LinkedHashSet<>();
        Set<String> late = loadFinished.apply(name);
        List<String> missed = new ArrayList<>();
        Set<String> unset = new TreeSet<>();
        Set<String> caughtUp = new TreeSet<>();
        Set<String> read = new LinkedHashSet<>(once);
        read.addAll(joins);
        visitInOrder(findCalls(now, read), now, (key, site) -> {
            MethodNode handler = ArgumentInitializers.findMethod(now, site.call().name, site.call().desc);
            if (!fresh.contains(key) || handler == null) {
                return;
            }
            boolean onObject = site.call().getOpcode() != Opcodes.INVOKESTATIC;
            StringBuilder why = new StringBuilder();
            InsnList invocation = buildInvocation(now, site, handler, finals, why);
            if (invocation == null) {
                missed.add(name + "." + site.moment() + " ran before " + key + " was there, which reads that"
                        + " moment's arguments: " + why);
                if (onObject) {
                    unset.addAll(ConstructedFields.findReliedSetBy(mixed, site.call().name, site.call().desc));
                }
            } else if (late.contains(site.moment())) {
                (onObject ? lateOnObjects : lateOnClass).add(invocation);
                (onObject ? lateObjectKeys : lateClassKeys).add(key);
            } else {
                (onObject ? onObjects : onClass).add(invocation);
                (onObject ? objectKeys : classKeys).add(key);
                if (onObject) {
                    caughtUp.addAll(ConstructedFields.findReliedSetBy(mixed, site.call().name, site.call().desc));
                }
            }
        });
        Set<String> values = new LinkedHashSet<>(fresh);
        values.removeIf(InitHookReplay::isHandler);
        Map<String, String> recomputed = new LinkedHashMap<>(values.isEmpty() ? Map.of()
                : ChangedStatics.add(now, values));
        ClassNode runs = RetainedInitializers.read(running);
        recomputed.putAll(ChangedStatics.addSwitched(now, findLastMix(previous, runs)));
        if (original != null) {
            recomputed.putAll(ChangedStatics.addMadeFresh(now, RetainedInitializers.read(original), runs));
        }
        if (objectKeys.isEmpty() && classKeys.isEmpty() && lateObjectKeys.isEmpty() && lateClassKeys.isEmpty()
                && recomputed.isEmpty() && missed.isEmpty()) {
            return new Addition(mixed, null);
        }
        classKeys.addAll(recomputed.isEmpty() ? Set.of() : values);
        if (!objectKeys.isEmpty()) {
            now.methods.add(makeMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC, ON_OBJECTS, onObjects));
        }
        if (!classKeys.isEmpty()) {
            now.methods.add(makeMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, ON_CLASS,
                    onClass));
        }
        if (!lateObjectKeys.isEmpty()) {
            now.methods.add(makeMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC, LATE_ON_OBJECTS, lateOnObjects));
        }
        if (!lateClassKeys.isEmpty()) {
            now.methods.add(makeMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, LATE_ON_CLASS,
                    lateOnClass));
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        now.accept(writer);
        return new Addition(writer.toByteArray(), new Due(objectKeys, classKeys, recomputed, missed, unset, caughtUp,
                lateObjectKeys, lateClassKeys));
    }

    static boolean isGameClass(String name) {
        return name.startsWith("net/minecraft/") || name.startsWith("com/mojang/");
    }

    private static ClassNode findLastMix(byte @Nullable [] previous, ClassNode runs) {
        return previous == null ? runs : RetainedInitializers.read(previous);
    }

    private static Addition addStaticsOnly(byte[] mixed, byte @Nullable [] original, byte[] running,
            byte @Nullable [] previous) {
        ClassNode now = ClassNodes.read(mixed, 0);
        ClassNode runs = RetainedInitializers.read(running);
        Map<String, String> recomputed = new LinkedHashMap<>(ChangedStatics.addSwitched(now,
                findLastMix(previous, runs)));
        if (original != null && ChangedStatics.hasAddedObjectStatics(mixed, original, running)) {
            recomputed.putAll(ChangedStatics.addMadeFresh(now, RetainedInitializers.read(original), runs));
        }
        if (recomputed.isEmpty()) {
            return new Addition(mixed, null);
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        now.accept(writer);
        return new Addition(writer.toByteArray(), new Due(Set.of(), Set.of(), recomputed, List.of(), Set.of(),
                Set.of()));
    }

    private static Set<String> findKeys(byte[] bytes, String owner, Set<String> once) {
        return findKeys(bytes, owner, method -> method.equals("<init>") || method.equals("<clinit>")
                || once.contains(method));
    }

    private static Set<String> findKeysIn(byte[] bytes, String owner, Set<String> methods) {
        return findKeys(bytes, owner, methods::contains);
    }

    private static Set<String> findKeys(byte[] bytes, String owner, Predicate<String> read) {
        Set<String> keys = new LinkedHashSet<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public @Nullable MethodVisitor visitMethod(int access, String method, String descriptor,
                    @Nullable String signature, String @Nullable [] exceptions) {
                if (!read.test(method)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String callOwner, String called, String calledDescriptor,
                            boolean isInterface) {
                        String key = ChangedStatics.makeKey(called);
                        if (callOwner.equals(owner) && !key.equals(called)) {
                            keys.add(key + calledDescriptor);
                        }
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String called, String calledDescriptor, Handle bootstrap,
                            Object... arguments) {
                        String key = ChangedStatics.makeKey(called);
                        if (HandlerGuards.isSite(bootstrap) && !key.equals(called)) {
                            boolean isStatic = arguments.length > 1 && Integer.valueOf(1).equals(arguments[1]);
                            keys.add(key + (isStatic ? calledDescriptor
                                    : "(" + calledDescriptor.substring(calledDescriptor.indexOf(';') + 1)));
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return keys;
    }

    private static void visitInOrder(Map<String, Site> calls, ClassNode node, BiConsumer<String, Site> each) {
        List<Map.Entry<String, Site>> ordered = new ArrayList<>(calls.entrySet());
        ordered.sort(Comparator.comparingInt((Map.Entry<String, Site> entry) ->
                entry.getValue().moment().equals("<init>") ? 0 : 1).thenComparingInt(entry -> indexOf(node,
                entry.getValue().call())));
        ordered.forEach(entry -> each.accept(entry.getKey(), entry.getValue()));
    }

    @SuppressWarnings("ReferenceEquality")
    private static int indexOf(ClassNode node, MethodInsnNode call) {
        for (MethodNode method : node.methods) {
            int at = 0;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn == call) {
                    return at;
                }
                at++;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static Map<String, Site> findCalls(ClassNode node, Set<String> once) {
        Map<String, Site> calls = new LinkedHashMap<>();
        for (MethodNode method : node.methods) {
            if (!method.name.equals("<init>") && !method.name.equals("<clinit>") && !once.contains(method.name)) {
                continue;
            }
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(node.name) && isHandler(call.desc)) {
                    calls.putIfAbsent(ChangedStatics.makeKey(call.name) + call.desc, new Site(method.name, call));
                }
            }
        }
        return calls;
    }

    private static @Nullable InsnList buildInvocation(ClassNode node, Site site, MethodNode handler,
            Set<String> finals, StringBuilder why) {
        MethodInsnNode call = site.call();
        Type[] arguments = Type.getArgumentTypes(call.desc);
        int callback = -1;
        for (int index = 0; index < arguments.length && callback < 0; index++) {
            if (arguments[index].getInternalName().startsWith(CALLBACK)) {
                callback = index;
            }
        }
        if (callback < 0) {
            why.append("it takes no callback");
            return null;
        }
        int[] captured = findCapturedSlots(call, arguments.length - callback - 1);
        MethodNode moment = findMethodOf(node, call);
        InsnList insns = new InsnList();
        InsnList loads = new InsnList();
        Set<Integer> recomputed = new LinkedHashSet<>();
        int slot = (handler.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;

        int momentSlot = 1;

        Map<Integer, FieldInsnNode> storedArguments = moment != null && moment.name.equals("<init>")
                ? StoredArguments.findStoredArguments(node, moment, finals) : Map.of();
        for (int index = 0; index < arguments.length; index++) {
            boolean read = ClassNodes.isSlotRead(handler, slot);
            FieldInsnNode capturedField = index > callback && captured != null
                    ? storedArguments.get(captured[index - callback - 1]) : null;
            FieldInsnNode stored = read && index < callback && moment != null && moment.name.equals("<init>")
                    ? StoredArguments.findStoredField(node, moment, momentSlot, arguments[index], finals) : null;
            if (index == callback) {
                String type = arguments[index].getInternalName();
                loads.add(new TypeInsnNode(Opcodes.NEW, type));
                loads.add(new InsnNode(Opcodes.DUP));
                loads.add(new LdcInsnNode(site.moment()));
                loads.add(new InsnNode(Opcodes.ICONST_0));
                loads.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, type, "<init>", "(Ljava/lang/String;Z)V",
                        false));
            } else if (!read) {
                loads.add(ClassNodes.makeDefault(arguments[index]));
            } else if (stored != null) {
                loads.add(new VarInsnNode(Opcodes.ALOAD, 0));
                loads.add(new FieldInsnNode(Opcodes.GETFIELD, stored.owner, stored.name, stored.desc));
            } else if (capturedField != null) {
                loads.add(new VarInsnNode(Opcodes.ALOAD, 0));
                loads.add(new FieldInsnNode(Opcodes.GETFIELD, capturedField.owner, capturedField.name,
                        capturedField.desc));
            } else if (index > callback && captured != null) {
                int local = captured[index - callback - 1];
                recomputed.add(local);
                loads.add(new VarInsnNode(arguments[index].getOpcode(Opcodes.ILOAD), local));
            } else if (index < callback && moment != null && arguments[index].getSort() >= Type.ARRAY
                    && StartArguments.has(node.name + "." + moment.name + moment.desc, index)) {
                loads.add(new LdcInsnNode(node.name + "." + moment.name + moment.desc));
                loads.add(new LdcInsnNode(index));
                loads.add(new MethodInsnNode(Opcodes.INVOKESTATIC, START_ARGUMENTS, "get",
                        "(Ljava/lang/String;I)Ljava/lang/Object;", false));
                loads.add(new TypeInsnNode(Opcodes.CHECKCAST, arguments[index].getInternalName()));
            } else {
                why.append("argument ").append(index).append(" (").append(arguments[index].getClassName())
                        .append(") is read and no final field of the ")
                        .append(moment == null ? "moment, which was not found" : moment.name).append(" holds it");
                return null;
            }
            slot += arguments[index].getSize();
            if (index < callback) {
                momentSlot += arguments[index].getSize();
            }
        }
        if (!recomputed.isEmpty()) {
            Map<Integer, FieldInsnNode> stored = moment != null && moment.name.equals("<init>")
                    ? StoredArguments.findStoredArguments(node, moment, finals) : Map.of();
            InsnList statements = moment == null ? null : FieldInitializers.copyStoring(node, moment, call, recomputed,
                    stored.keySet());
            if (statements == null) {
                why.append("a local it captured cannot be computed again");
                return null;
            }
            StoredArguments.readStoredArguments(statements, stored);
            insns.add(statements);
        }
        if (call.getOpcode() != Opcodes.INVOKESTATIC) {
            insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        }
        insns.add(loads);
        insns.add(new MethodInsnNode(call.getOpcode(), call.owner, call.name, call.desc, call.itf));
        return insns;
    }

    private static int @Nullable [] findCapturedSlots(MethodInsnNode call, int count) {
        int[] slots = new int[count];
        AbstractInsnNode at = call;
        for (int index = count - 1; index >= 0; index--) {
            at = at.getPrevious();
            while (at instanceof LabelNode || at instanceof LineNumberNode) {
                at = at.getPrevious();
            }
            if (!(at instanceof VarInsnNode load) || load.getOpcode() < Opcodes.ILOAD
                    || load.getOpcode() > Opcodes.ALOAD) {
                return null;
            }
            slots[index] = load.var;
        }
        return slots;
    }

    private static @Nullable MethodNode findMethodOf(ClassNode node, AbstractInsnNode insn) {
        for (MethodNode method : node.methods) {
            if (method.instructions.contains(insn)) {
                return method;
            }
        }
        return null;
    }

    private static boolean isHandler(String descriptor) {
        return descriptor.contains("L" + CALLBACK) && descriptor.endsWith(")V");
    }

    private static MethodNode makeMethod(int access, String name, InsnList body) {
        MethodNode method = new MethodNode(access, name, "()V", null, null);
        method.instructions.add(body);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        return method;
    }
}
