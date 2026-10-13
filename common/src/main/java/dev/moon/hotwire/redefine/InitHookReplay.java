package dev.moon.hotwire.redefine;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class InitHookReplay {
    private static final Logger LOGGER = LoggerFactory.getLogger(InitHookReplay.class);
    static final String ON_OBJECTS = "hotwire$passedHooks";
    static final String ON_CLASS = "hotwire$passedStaticHooks";
    private static final String CALLBACK = "org/spongepowered/asm/mixin/injection/callback/CallbackInfo";

    private static final Set<String> RUN = ConcurrentHashMap.newKeySet();

    private static volatile Supplier<List<Object>> longLived = List::of;
    private static volatile Function<String, Set<String>> startup = name -> Set.of();

    private static volatile Predicate<Class<?>> rebuilt = type -> false;
    private static volatile StaticWriter statics = (field, value) -> {
        throw new IllegalAccessException("no writer of statics is set");
    };

    public record Due(Set<String> onObjects, Set<String> onClass, Map<String, String> statics, List<String> missed,
            Set<String> unset, Set<String> caughtUp) {
        public Due {
            onObjects = Set.copyOf(onObjects);
            onClass = Set.copyOf(onClass);
            statics = Map.copyOf(statics);
            missed = List.copyOf(missed);
            unset = Set.copyOf(unset);
            caughtUp = Set.copyOf(caughtUp);
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

    public static void answerWith(Supplier<List<Object>> objects, Function<String, Set<String>> methods) {
        longLived = objects;
        startup = methods;
    }

    public static void skipRebuiltWith(Predicate<Class<?>> builtAgain) {
        rebuilt = builtAgain;
    }

    public static void writeStaticsWith(StaticWriter writer) {
        statics = writer;
    }

    static Addition add(String name, byte[] mixed, byte[] running, byte @Nullable [] first) {
        return add(name, mixed, running, first, null);
    }

    static Addition add(String name, byte[] mixed, byte[] running, byte @Nullable [] first,
            byte @Nullable [] previous) {
        Set<String> once = startup.apply(name);
        Set<String> fresh = findKeys(mixed, name, once);
        if (fresh.isEmpty()) {
            return new Addition(mixed, null);
        }
        fresh.removeAll(findKeys(running, name, once));
        if (first != null && !fresh.isEmpty()) {
            fresh.removeAll(findKeys(first, name, once));
        }
        if (previous != null && !fresh.isEmpty()) {
            fresh.removeAll(findKeys(previous, name, once));
        }
        fresh.removeIf(key -> RUN.contains(name + " " + key));
        if (fresh.isEmpty()) {
            return new Addition(mixed, null);
        }
        ClassNode now = new ClassNode();
        new ClassReader(mixed).accept(now, 0);

        Set<String> finals = StoredArguments.findFinals(running);
        InsnList onObjects = new InsnList();
        InsnList onClass = new InsnList();
        Set<String> objectKeys = new LinkedHashSet<>();
        Set<String> classKeys = new LinkedHashSet<>();
        List<String> missed = new ArrayList<>();
        Set<String> unset = new TreeSet<>();
        Set<String> caughtUp = new TreeSet<>();
        findCalls(now, once).forEach((key, site) -> {
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
        Map<String, String> recomputed = values.isEmpty() ? Map.of() : ChangedStatics.add(now, values);
        if (objectKeys.isEmpty() && classKeys.isEmpty() && recomputed.isEmpty() && missed.isEmpty()) {
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
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        now.accept(writer);
        return new Addition(writer.toByteArray(), new Due(objectKeys, classKeys, recomputed, missed, unset, caughtUp));
    }

    private static Set<String> findKeys(byte[] bytes, String owner, Set<String> once) {
        Set<String> keys = new LinkedHashSet<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public @Nullable MethodVisitor visitMethod(int access, String method, String descriptor,
                    @Nullable String signature, String @Nullable [] exceptions) {
                if (!method.equals("<init>") && !method.equals("<clinit>") && !once.contains(method)) {
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
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return keys;
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
        for (int index = 0; index < arguments.length; index++) {
            boolean read = reads(handler, slot);
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
                loads.add(makeDefault(arguments[index]));
            } else if (stored != null) {
                loads.add(new VarInsnNode(Opcodes.ALOAD, 0));
                loads.add(new FieldInsnNode(Opcodes.GETFIELD, stored.owner, stored.name, stored.desc));
            } else if (index > callback && captured != null) {
                int local = captured[index - callback - 1];
                recomputed.add(local);
                loads.add(new VarInsnNode(arguments[index].getOpcode(Opcodes.ILOAD), local));
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

    private static boolean reads(MethodNode handler, int slot) {
        for (AbstractInsnNode insn : handler.instructions) {
            if (insn instanceof VarInsnNode load && load.var == slot && load.getOpcode() >= Opcodes.ILOAD
                    && load.getOpcode() <= Opcodes.ALOAD) {
                return true;
            }
        }
        return false;
    }

    private static AbstractInsnNode makeDefault(Type type) {
        return switch (type.getSort()) {
            case Type.LONG -> new InsnNode(Opcodes.LCONST_0);
            case Type.FLOAT -> new InsnNode(Opcodes.FCONST_0);
            case Type.DOUBLE -> new InsnNode(Opcodes.DCONST_0);
            case Type.OBJECT, Type.ARRAY -> new InsnNode(Opcodes.ACONST_NULL);
            default -> new InsnNode(Opcodes.ICONST_0);
        };
    }

    @SuppressWarnings("unchecked")
    private static Consumer<Object> asConsumer(MethodHandle method) {
        return MethodHandleProxies.asInterfaceInstance(Consumer.class, method);
    }

    public static int run(Map<Class<?>, Due> hooked) {
        List<Object> made = longLived.get();
        List<Class<?>> order = new ArrayList<>(hooked.keySet());
        order.sort(Comparator.comparingInt(type -> {
            for (int at = 0; at < made.size(); at++) {
                if (type.isInstance(made.get(at))) {
                    return at;
                }
            }
            return made.size();
        }));
        int failed = 0;
        for (Class<?> type : order) {
            failed += ChangedStatics.run(type, Objects.requireNonNull(hooked.get(type)).statics(), statics);
        }
        for (Class<?> type : order) {
            List<Object> ofType = new ArrayList<>();

            Object[] constants = type.getEnumConstants();
            if (constants != null) {
                ofType.addAll(List.of(constants));
            }
            for (Object one : made) {
                if (type.isInstance(one) && !ofType.contains(one)) {
                    ofType.add(one);
                }
            }
            failed += run(type, Objects.requireNonNull(hooked.get(type)), ofType, true);
        }
        for (Class<?> type : order) {
            failed += run(type, Objects.requireNonNull(hooked.get(type)), List.of(), false);
        }
        return failed;
    }

    public static ConstructedFields.Unmade findUnset(Map<Class<?>, Due> hooked, Predicate<Class<?>> worldObject,
            boolean inWorld) {
        return findUnset(hooked, worldObject, inWorld, type -> true);
    }

    public static ConstructedFields.Unmade findUnset(Map<Class<?>, Due> hooked, Predicate<Class<?>> worldObject,
            boolean inWorld, Predicate<Class<?>> alive) {
        List<String> lines = new ArrayList<>();
        boolean reopenFixes = true;
        List<Object> made = longLived.get();
        for (Map.Entry<Class<?>, Due> entry : hooked.entrySet()) {
            Class<?> type = entry.getKey();

            boolean world = worldObject.test(type) && !holdsAny(made, type);
            Set<String> unset = entry.getValue().unset();
            boolean lateOnWorld = world && inWorld && !entry.getValue().onObjects().isEmpty();
            if ((unset.isEmpty() && !lateOnWorld) || (world && !inWorld)) {
                continue;
            }
            String name = type.getName().replace('.', '/');
            if (!alive.test(type)) {
                LOGGER.info("[Hotwire] no object of {} is alive: its hooks have none made before them", name);
                continue;
            }
            lines.add(unset.isEmpty() ? "objects of " + name + " made with this world before its hooks would be"
                    + " caught up late, with the state they gathered since: " + entry.getValue().onObjects()
                    : "objects of " + name + " made before its hooks would go without " + new TreeSet<>(unset) + " ("
                    + String.join("; ", entry.getValue().missed()) + ")");
            reopenFixes &= world;
        }
        return new ConstructedFields.Unmade(lines, !lines.isEmpty() && reopenFixes);
    }

    private static boolean holdsAny(List<Object> objects, Class<?> type) {
        for (Object one : objects) {
            if (type.isInstance(one)) {
                return true;
            }
        }
        return false;
    }

    private static int run(Class<?> type, Due due, List<Object> objects, boolean onObjects) {
        String name = type.getName().replace('.', '/');
        Set<String> keys = onObjects ? due.onObjects() : due.onClass();
        if (onObjects && !due.missed().isEmpty() && !objects.isEmpty()) {
            if (rebuilt.test(type)) {
                LOGGER.info("[Hotwire] {} objects of {} were made before hooks that read their moment's arguments,"
                        + " and the switch builds its objects again with them: {}", objects.size(), name,
                        due.missed());
            } else {
                LOGGER.warn("[Hotwire] {} objects of {} made before them go without hooks that read their moment's"
                        + " arguments: {}", objects.size(), name, due.missed());
            }
        }
        if (onObjects && objects.isEmpty() && (!keys.isEmpty() || !due.missed().isEmpty())) {
            LOGGER.info("[Hotwire] {} gained hooks for objects made before them, and none of its objects is among the"
                    + " game's long-lived ones: {}", name, keys.isEmpty() ? due.missed() : keys);
        }
        if (keys.isEmpty()) {
            return 0;
        }
        MethodHandles.Lookup side = Linker.findCurrentLookup(type);
        if (side == null) {
            LOGGER.error("[Hotwire] {} has no sidecar for the hooks it missed: {}", name, keys);
            return 1;
        }
        try {
            if (onObjects) {
                Consumer<Object> each = asConsumer(side.findStatic(side.lookupClass(), ON_OBJECTS,
                        MethodType.methodType(void.class, type)).asType(MethodType.methodType(void.class,
                                Object.class)));

                FieldHolders.catchUpWith(type, each);
                if (objects.isEmpty()) {
                    keys.forEach(key -> RUN.add(name + " " + key));
                    return 0;
                }
                for (Object object : objects) {
                    FieldHolders.markMade(type, object);
                    each.accept(object);
                }
            } else {
                MethodHandleProxies.asInterfaceInstance(Runnable.class, side.findStatic(side.lookupClass(), ON_CLASS,
                        MethodType.methodType(void.class))).run();
            }
            keys.forEach(key -> RUN.add(name + " " + key));
            LOGGER.info("[Hotwire] {} ran the hooks it missed {}: {}", name, onObjects ? "on " + objects.size()
                    + " objects made before them" : "once", keys);
            return 0;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.error("[Hotwire] the hooks {} of {} could not run for the moments they missed", keys, name, e);
            return 1;
        }
    }
}
