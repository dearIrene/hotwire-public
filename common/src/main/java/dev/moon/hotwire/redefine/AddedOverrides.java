package dev.moon.hotwire.redefine;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AddedOverrides {
    private record Answer(int generation, @Nullable MethodHandle handle) {
    }

    static final String INHERITED = "hotwire$inherited$";
    private static final String SELF = AddedOverrides.class.getName().replace('.', '/');
    private static final Handle BOOTSTRAP = new Handle(Opcodes.H_INVOKESTATIC, SELF, "bootstrap",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                    + "Ljava/lang/String;)Ljava/lang/invoke/CallSite;", false);
    private static final int NEVER_OVERRIDES = Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_ABSTRACT;

    private static final int REMEMBERED = 4;
    private static final Logger LOGGER = LoggerFactory.getLogger(AddedOverrides.class);

    private static final Map<String, Map<String, String>> OWNERS = new ConcurrentHashMap<>();

    private static final Set<String> JUDGED = ConcurrentHashMap.newKeySet();

    private static final Set<String> SPLIT = ConcurrentHashMap.newKeySet();
    private static final Set<String> UNSPLIT = ConcurrentHashMap.newKeySet();

    private static final Set<String> REACHED = ConcurrentHashMap.newKeySet();

    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static final ClassValue<Map<String, Answer>> FOUND = new ClassValue<>() {
        @Override
        protected Map<String, Answer> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };
    private static final Set<Site> SITES = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final MethodHandle CHOOSE;
    private static final MethodHandle IS_CLASS;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            CHOOSE = lookup.findVirtual(Site.class, "choose", MethodType.methodType(MethodHandle.class, Object.class));
            IS_CLASS = lookup.findStatic(AddedOverrides.class, "isClass",
                    MethodType.methodType(boolean.class, Class.class, Object.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private AddedOverrides() {
    }

    private static final class Site extends MutableCallSite {
        private final String key;
        private final MethodHandle own;
        private final MethodHandle asking;
        private final Set<Class<?>> met = new HashSet<>();

        Site(MethodType type, String key, MethodHandle own) {
            super(type);
            this.key = key;
            this.own = own;
            MethodHandle choose = MethodHandles.dropArguments(CHOOSE.bindTo(this).asType(MethodType.methodType(
                    MethodHandle.class, type.parameterType(0))), 1, type.parameterList().subList(1,
                            type.parameterCount()));
            this.asking = MethodHandles.foldArguments(MethodHandles.exactInvoker(type), choose);
            this.setTarget(this.asking);
        }

        @SuppressWarnings("unused")
        MethodHandle choose(Object receiver) {
            Class<?> type = receiver.getClass();
            int before = GENERATION.get();
            MethodHandle found = find(type, this.key);
            MethodHandle answer = found == null ? this.own : MethodHandles.explicitCastArguments(found, this.type());
            synchronized (this) {
                if (GENERATION.get() == before && this.met.size() < REMEMBERED && this.met.add(type)) {
                    MethodHandle test = MethodHandles.dropArguments(IS_CLASS.bindTo(type).asType(
                            MethodType.methodType(boolean.class, this.type().parameterType(0))), 1,
                            this.type().parameterList().subList(1, this.type().parameterCount()));
                    this.setTarget(MethodHandles.guardWithTest(test, answer, this.getTarget()));
                }
            }
            return answer;
        }

        synchronized void forget() {
            this.met.clear();
            this.setTarget(this.asking);
        }
    }

    static void learn(Map<String, byte[]> current, Map<String, byte[]> mixed,
            Function<String, byte @Nullable []> classBytes) {
        mixed.forEach((name, bytes) -> {
            byte[] running = current.get(name);
            if (running == null) {
                return;
            }
            Set<String> had = new HashSet<>();
            new ClassReader(running).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public @Nullable MethodVisitor visitMethod(int access, String method, String descriptor,
                        @Nullable String signature, String @Nullable [] exceptions) {
                    had.add(method + descriptor);
                    return null;
                }
            }, ClassReader.SKIP_CODE);
            ClassReader reader = new ClassReader(bytes);
            if ((reader.getAccess() & Opcodes.ACC_INTERFACE) != 0) {
                return;
            }
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public @Nullable MethodVisitor visitMethod(int access, String method, String descriptor,
                        @Nullable String signature, String @Nullable [] exceptions) {
                    if (!had.contains(method + descriptor) && (access & NEVER_OVERRIDES) == 0
                            && !method.startsWith("<")) {
                        note(name, reader, method, descriptor, classBytes);
                    }
                    return null;
                }
            }, ClassReader.SKIP_CODE);
        });
    }

    private static void note(String name, ClassReader reader, String method, String descriptor,
            Function<String, byte @Nullable []> classBytes) {
        String key = method + descriptor;

        if (!JUDGED.add(name + "." + key)) {
            return;
        }
        String owner = Overrides.findDeclarer(name, reader.getSuperName(), method, descriptor, classBytes);
        if (owner == null) {
            owner = Overrides.findDefaultDeclarer(List.of(reader.getInterfaces()), reader.getSuperName(), method,
                    descriptor, classBytes);
        }
        if (owner == null) {
            return;
        }
        if (isOfJava(owner) || classBytes.apply(owner) == null || UNSPLIT.contains(owner)) {
            LOGGER.info("[Hotwire] {} gains {} over the one it inherits from {}, which cannot be rebuilt:"
                    + " code loaded before the mod calls {}'s until a restart", name, key, owner, owner);
            return;
        }
        if (!owner.equals(OWNERS.computeIfAbsent(name, added -> new ConcurrentHashMap<>()).put(key, owner))) {
            LOGGER.info("[Hotwire] {} gains {} over the one it inherits from {}, which is rebuilt to ask for it",
                    name, key, owner);
        }
    }

    private static boolean isOfJava(String name) {
        return name.startsWith("java/") || name.startsWith("javax/") || name.startsWith("jdk/")
                || name.startsWith("sun/");
    }

    public static Set<String> findOwnersOf(Collection<String> classes) {
        Set<String> owners = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(classes);
        while (!queue.isEmpty()) {
            for (String owner : OWNERS.getOrDefault(queue.poll(), Map.of()).values()) {
                if (owners.add(owner)) {
                    queue.add(owner);
                }
            }
        }
        return owners;
    }

    static byte[] addDispatch(String name, byte[] running, byte[] mixed) {
        Set<String> methods = new HashSet<>();
        OWNERS.values().forEach(added -> added.forEach((method, owner) -> {
            if (owner.equals(name)) {
                methods.add(method);
            }
        }));
        if (methods.isEmpty()) {
            return mixed;
        }
        ClassNode node = new ClassNode();
        new ClassReader(mixed).accept(node, 0);
        if (node.version < Opcodes.V1_7) {
            LOGGER.info("[Hotwire] {} is a class of before Java 7, which cannot ask for the overrides of {}: code"
                    + " loaded before their mods calls its own until a restart", name, methods);
            return mixed;
        }
        for (MethodNode method : node.methods.toArray(MethodNode[]::new)) {
            if (methods.contains(method.name + method.desc) && (method.access & (NEVER_OVERRIDES | Opcodes.ACC_NATIVE
                    | Opcodes.ACC_FINAL)) == 0) {
                moveBody(node, method);
            }
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        byte[] asking = writer.toByteArray();
        if (!SPLIT.contains(name)) {
            try {
                ClassSplit.split(running, asking);
                SPLIT.add(name);
            } catch (SplitException | RuntimeException e) {
                UNSPLIT.add(name);
                OWNERS.values().forEach(added -> added.values().removeIf(name::equals));
                LOGGER.info("[Hotwire] {} cannot be rebuilt to ask for the overrides of {}: code loaded before"
                        + " their mods calls its own until a restart ({})", name, methods, e.toString());
                return mixed;
            }
        }
        return asking;
    }

    private static void moveBody(ClassNode node, MethodNode method) {
        MethodNode own = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC | (method.access
                & (Opcodes.ACC_VARARGS | Opcodes.ACC_STRICT)), INHERITED + method.name, method.desc, null,
                method.exceptions.toArray(String[]::new));
        method.accept(own);
        node.methods.add(own);
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        int slot = 1;
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            code.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), slot));
            slot += argument.getSize();
        }
        code.add(new InvokeDynamicInsnNode(method.name, "(L" + node.name + ";" + method.desc.substring(1), BOOTSTRAP,
                node.name + "." + method.name + method.desc));
        code.add(new InsnNode(Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN)));
        method.instructions = code;
        method.tryCatchBlocks.clear();
        method.localVariables = null;
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
    }

    public static CallSite bootstrap(MethodHandles.Lookup caller, String name, MethodType type, String key)
            throws ReflectiveOperationException {
        Class<?> holder = caller.lookupClass();
        MethodHandles.Lookup side = Linker.findCurrentLookup(holder);
        if (side == null) {
            throw new NoSuchMethodException(key + " has no body beside it");
        }
        String body = INHERITED + name;
        Site site = new Site(type, key, SidecarCallSites.bind(holder, side.findStatic(side.lookupClass(), body, type),
                body, type).dynamicInvoker());
        SITES.add(site);
        return site;
    }

    @SuppressWarnings("unused")
    private static boolean isClass(Class<?> type, Object receiver) {
        return receiver.getClass() == type;
    }

    static @Nullable MethodHandle find(Class<?> type, String key) {
        Map<String, Answer> known = FOUND.get(type);
        Answer answer = known.get(key);
        int now = GENERATION.get();
        if (answer == null || answer.generation() != now) {
            answer = new Answer(now, findOverride(type, key));
            known.put(key, answer);
        }
        return answer.handle();
    }

    private static @Nullable MethodHandle findOverride(Class<?> type, String key) {
        int open = key.indexOf('(');
        int dot = key.lastIndexOf('.', open);
        String owner = key.substring(0, dot);
        String method = key.substring(dot + 1);
        for (Class<?> at = type; at != null; at = at.getSuperclass()) {
            String name = at.getName().replace('.', '/');
            if (name.equals(owner)) {
                return null;
            }
            MethodHandles.Lookup side = Linker.findCurrentLookup(at);

            if (side == null || !owner.equals(OWNERS.getOrDefault(name, Map.of()).get(method))) {
                continue;
            }
            try {
                MethodType shape = MethodType.fromMethodDescriptorString(key.substring(open), at.getClassLoader());
                Class<?> declaring = Class.forName(owner.replace('/', '.'), false, at.getClassLoader());
                MethodHandle added = MethodHandles.explicitCastArguments(side.findStatic(side.lookupClass(),
                        key.substring(dot + 1, open), shape.insertParameterTypes(0, at)),
                        shape.insertParameterTypes(0, declaring));
                if (REACHED.add(name + "." + method)) {
                    LOGGER.info("[Hotwire] {}'s added {} is called through {}'s", name, method, owner);
                }
                return added;
            } catch (NoSuchMethodException off) {
            } catch (ReflectiveOperationException | LinkageError e) {
                LOGGER.error("[Hotwire] the override of {} that {} has was not found; the method's own runs", key,
                        at.getName(), e);
                return null;
            }
        }
        return null;
    }

    static void forget() {
        GENERATION.incrementAndGet();
        List<Site> sites;
        synchronized (SITES) {
            sites = List.copyOf(SITES);
        }
        sites.forEach(Site::forget);
        if (!sites.isEmpty()) {
            MutableCallSite.syncAll(sites.toArray(MutableCallSite[]::new));
        }
    }

    static void clear() {
        OWNERS.clear();
        JUDGED.clear();
        SPLIT.clear();
        UNSPLIT.clear();
        REACHED.clear();
        forget();
    }
}
