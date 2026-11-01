package dev.moon.hotwire.redefine;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HandlerGuards {
    public static final boolean ENABLED = !"false".equals(System.getProperty("hotwire.guards"));
    private static final Logger LOGGER = LoggerFactory.getLogger(HandlerGuards.class);
    private static final String MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";
    private static final byte[] MERGED_BYTES = MERGED.getBytes(StandardCharsets.UTF_8);

    static final int OWN = 0;
    static final int STATIC = 1;
    static final int VIRTUAL = 2;
    static final Map<String, Integer> KINDS = Map.ofEntries(
            Map.entry("handler", HandlerStandIns.NOTHING),
            Map.entry("args", HandlerStandIns.NOTHING),
            Map.entry("modify", HandlerStandIns.AS_IT_WAS),
            Map.entry("localvar", HandlerStandIns.AS_IT_WAS),
            Map.entry("constant", HandlerStandIns.AS_IT_WAS),
            Map.entry("modifyExpressionValue", HandlerStandIns.AS_IT_WAS),
            Map.entry("modifyReturnValue", HandlerStandIns.AS_IT_WAS),
            Map.entry("modifyReceiver", HandlerStandIns.AS_IT_WAS),
            Map.entry("wrapOperation", HandlerStandIns.OPERATION),
            Map.entry("wrapMethod", HandlerStandIns.OPERATION),
            Map.entry("wrapWithCondition", HandlerStandIns.TRUE));

    static final Set<String> UNFLIPPABLE = ConcurrentHashMap.newKeySet();

    private static final Map<String, Set<String>> UNFLIPPABLE_AT = new ConcurrentHashMap<>();
    static final Handle BOOTSTRAP = new Handle(Opcodes.H_INVOKESTATIC,
            HandlerGuards.class.getName().replace('.', '/'), "bootstrap",
            MethodType.methodType(CallSite.class, MethodHandles.Lookup.class, String.class, MethodType.class,
                    String.class, int.class, int.class, int.class).toMethodDescriptorString(), false);
    static final Handle BOOTSTRAP_REDIRECT = new Handle(Opcodes.H_INVOKESTATIC,
            HandlerGuards.class.getName().replace('.', '/'), "bootstrapRedirect",
            MethodType.methodType(CallSite.class, MethodHandles.Lookup.class, String.class, MethodType.class,
                    String.class, int.class, int.class, String.class, String.class, String.class)
                    .toMethodDescriptorString(), false);

    static volatile Function<String, byte @Nullable []> originals = name -> null;

    private record Guard(MethodHandle handler, MethodHandle standIn) {
    }

    private static final Map<String, Map<MutableCallSite, Guard>> GUARDS = new HashMap<>();
    private static final Set<String> OFF = new HashSet<>();

    private static final Map<String, Set<String>> GUARDED = new ConcurrentHashMap<>();
    static final Map<String, Set<String>> STAGED = new ConcurrentHashMap<>();

    private static final Map<String, Set<String>> PRESENT = new ConcurrentHashMap<>();
    static final Map<String, Set<String>> STAGED_PRESENT = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> DIRECT_MODS = new ConcurrentHashMap<>();

    private static final Map<String, Set<String>> BEHAVING = new ConcurrentHashMap<>();

    private static final AtomicLong FLIPS = new AtomicLong();

    private static final AtomicLong CHUNK_FLIPS = new AtomicLong();
    private static final List<String> CHUNK_HOSTS = List.of("chunk", "renderer/block/", "color/block/", "/model/",
            "terrain", "/mesh");
    private static final List<String> CHUNK_MIXINS = List.of("chunk", "blockmodel", "bakedmodel", "blockrender",
            "blockcolor", "quad", "mesh", "terrain", "fluidrender", "section", "blockstatemodel");
    private static final Object LOCK = new Object();

    static volatile Set<String> neverSwitched = Set.of();

    private HandlerGuards() {
    }

    public static void leaveHandlersOf(Set<String> mods) {
        neverSwitched = Set.copyOf(mods);
    }

    public static void readOriginalsWith(Function<String, byte @Nullable []> reader) {
        originals = reader;
    }

    public static boolean isGuarded(String internalName) {
        return GUARDED.containsKey(internalName);
    }

    public static @Nullable String findModInHandlerName(String name) {
        String[] parts = name.split("[$]", 4);
        return isHandlerName(name) && parts.length == 4 && !parts[2].isEmpty() ? parts[2] : null;
    }

    public static boolean isHandlerName(String name) {
        String[] parts = name.split("[$]", 4);
        return parts.length >= 3 && parts[1].length() == 6
                && (KINDS.containsKey(parts[0]) || parts[0].equals("redirect"));
    }

    private static void noteDirect(String internalName, String handlerName) {
        String mod = findModInHandlerName(handlerName);
        if (mod != null) {
            DIRECT_MODS.computeIfAbsent(internalName, name -> ConcurrentHashMap.newKeySet()).add(mod);
        }
    }

    public static Set<String> findBehavingAt(String internalName) {
        return BEHAVING.getOrDefault(internalName, Set.of());
    }

    private static void noteBehaving(String internalName, ClassNode node) {
        Set<String> behaving = findBehaving(node);
        if (behaving.isEmpty()) {
            BEHAVING.remove(internalName);
        } else {
            BEHAVING.put(internalName, behaving);
        }
    }

    private static Set<String> findBehaving(ClassNode node) {
        Set<String> behaving = new HashSet<>();
        for (MethodNode method : node.methods) {
            String mixin = findMerged(method);
            if (mixin != null && method.name.indexOf('$') < 0 && !method.name.startsWith("<")
                    && (method.access & Opcodes.ACC_SYNTHETIC) == 0 && !isPlainAccess(method)) {
                behaving.add(mixin);
            }
        }
        return Set.copyOf(behaving);
    }

    private static boolean isPlainAccess(MethodNode method) {
        int real = 0;
        int calls = 0;
        for (AbstractInsnNode insn : method.instructions) {
            int opcode = insn.getOpcode();
            if (opcode < 0) {
                continue;
            }
            real++;
            if (insn instanceof MethodInsnNode) {
                calls++;
            } else if (!(insn instanceof FieldInsnNode || insn instanceof VarInsnNode
                    || opcode == Opcodes.CHECKCAST || (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN))) {
                return false;
            }
        }
        return real <= 8 && calls <= 1;
    }

    public static Set<String> findDirectModsAt(String internalName) {
        return DIRECT_MODS.getOrDefault(internalName, Set.of());
    }

    public static long getFlips() {
        return FLIPS.get();
    }

    private static boolean containsAny(String name, List<String> parts) {
        for (String part : parts) {
            if (name.contains(part)) {
                return true;
            }
        }
        return false;
    }

    public static long getChunkFlips() {
        return CHUNK_FLIPS.get();
    }

    private static boolean isOfChunks(String mixin) {
        String simple = mixin.substring(Math.max(mixin.lastIndexOf('/'), mixin.lastIndexOf('.')) + 1)
                .toLowerCase(Locale.ROOT);
        if (containsAny(simple, CHUNK_MIXINS)) {
            return true;
        }
        for (Map.Entry<String, Set<String>> host : GUARDED.entrySet()) {
            if (host.getValue().contains(mixin)) {
                String name = host.getKey().toLowerCase(Locale.ROOT);
                if (containsAny(name, CHUNK_HOSTS)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isSite(Handle bootstrap) {
        return bootstrap.getOwner().equals(BOOTSTRAP.getOwner()) && bootstrap.getName().equals(BOOTSTRAP.getName());
    }

    public static boolean isWritten(String internalName) {
        return PRESENT.containsKey(internalName);
    }

    public static Set<String> getPresentMixins(String internalName) {
        return PRESENT.getOrDefault(internalName, Set.of());
    }

    public static Set<String> getGuardedMixins(String internalName) {
        return GUARDED.getOrDefault(internalName, Set.of());
    }

    public static void forget(String internalName) {
        GUARDED.remove(internalName);
        PRESENT.remove(internalName);
    }

    static void markUnflippable(String internalName, String mixin) {
        UNFLIPPABLE.add(mixin);
        UNFLIPPABLE_AT.computeIfAbsent(internalName, name -> ConcurrentHashMap.newKeySet()).add(mixin);
    }

    public static Set<String> findUnflippableAt(String internalName) {
        return UNFLIPPABLE_AT.getOrDefault(internalName, Set.of());
    }

    public static boolean isFlippable(String mixin) {
        return !UNFLIPPABLE.contains(mixin);
    }

    public static byte @Nullable [] rewrite(String internalName, byte[] bytes) {
        try {
            return rewriteOrThrow(internalName, bytes);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("[Hotwire] {} keeps its callbacks called direct: {}", internalName, e.toString());
            return null;
        }
    }

    private static byte @Nullable [] rewriteOrThrow(String internalName, byte[] bytes) {
        if (!ClassBytes.mentions(bytes, MERGED_BYTES)) {
            if (bytes.length >= 8 && ((bytes[6] & 0xFF) << 8 | (bytes[7] & 0xFF)) >= Opcodes.V1_7) {
                STAGED_PRESENT.put(internalName, Set.of());
            }
            BEHAVING.remove(internalName);
            commitStaged(internalName);
            return null;
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        boolean made = HandlerSites.makeSites(internalName, node);
        noteBehaving(internalName, node);
        commitStaged(internalName);
        return made ? DuckBridge.write(node, 0) : null;
    }

    public static void recordSidecar(String internalName, byte[] sidecar) {
        if (sidecar.length == 0) {
            return;
        }
        ClassNode node = new ClassNode();
        new ClassReader(sidecar).accept(node, ClassReader.SKIP_FRAMES);
        Set<String> behaving = new HashSet<>(findBehavingAt(internalName));
        behaving.addAll(findBehaving(node));
        if (!behaving.isEmpty()) {
            BEHAVING.put(internalName, Set.copyOf(behaving));
        }
        for (MethodNode method : node.methods) {
            String mixin = findMerged(method);
            int mark = method.name.indexOf('$');
            if (mixin != null && mark > 0 && (KINDS.containsKey(method.name.substring(0, mark))
                    || method.name.startsWith("redirect$"))) {
                UNFLIPPABLE_AT.computeIfAbsent(internalName, name -> ConcurrentHashMap.newKeySet()).add(mixin);
            }
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(internalName)) {
                    noteDirect(internalName, call.name);
                }
            }
        }
    }

    public static void recordFrom(String internalName, byte[] bytes) {
        DIRECT_MODS.remove(internalName);
        STAGED.remove(internalName);
        STAGED_PRESENT.remove(internalName);
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
        Set<String> present = new HashSet<>();
        Set<String> guarded = new HashSet<>();
        Map<String, String> handlers = new HashMap<>();
        for (MethodNode method : node.methods) {
            String mixin = findMerged(method);
            if (mixin != null) {
                present.add(mixin);
                int mark = method.name.indexOf('$');
                if (mark > 0 && (KINDS.containsKey(method.name.substring(0, mark))
                        || method.name.startsWith("redirect$"))) {
                    handlers.put(method.name + method.desc, mixin);
                }
            }
        }
        for (MethodNode method : node.methods) {
            String outer = handlers.get(method.name + method.desc);
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof InvokeDynamicInsnNode site && isSite(site.bsm) && site.bsmArgs.length > 0
                        && site.bsmArgs[0] instanceof String of) {
                    guarded.add(of);
                } else if (insn instanceof InvokeDynamicInsnNode site && !isSite(site.bsm)
                        && isHandlerName(site.name)) {
                    for (Map.Entry<String, String> handler : handlers.entrySet()) {
                        if (handler.getKey().startsWith(site.name + "(")) {
                            markUnflippable(internalName, handler.getValue());
                        }
                    }
                    noteDirect(internalName, site.name);
                } else if (insn instanceof MethodInsnNode call) {
                    String direct = call.owner.equals(internalName) ? handlers.get(call.name + call.desc) : null;
                    if (direct != null && direct.equals(outer)) {
                        continue;
                    }
                    if (direct != null) {
                        markUnflippable(internalName, direct);
                    }
                    noteDirect(internalName, call.name);
                }
            }
        }
        noteBehaving(internalName, node);
        PRESENT.put(internalName, Set.copyOf(present));
        if (guarded.isEmpty()) {
            GUARDED.remove(internalName);
        } else {
            GUARDED.put(internalName, Set.copyOf(guarded));
        }
    }

    public static void commitStaged(String internalName) {
        Set<String> staged = STAGED.remove(internalName);
        if (staged != null) {
            GUARDED.put(internalName, staged);
        }
        Set<String> present = STAGED_PRESENT.remove(internalName);
        if (present != null) {
            PRESENT.put(internalName, present);
        }
    }

    static @Nullable String findMerged(MethodNode method) {
        List<AnnotationNode> annotations = new ArrayList<>();
        if (method.visibleAnnotations != null) {
            annotations.addAll(method.visibleAnnotations);
        }
        if (method.invisibleAnnotations != null) {
            annotations.addAll(method.invisibleAnnotations);
        }
        for (AnnotationNode annotation : annotations) {
            if (MERGED.equals(annotation.desc) && annotation.values != null) {
                for (int at = 0; at + 1 < annotation.values.size(); at += 2) {
                    if ("mixin".equals(annotation.values.get(at)) && annotation.values.get(at + 1) instanceof String
                            name) {
                        return name;
                    }
                }
            }
        }
        return null;
    }

    public static CallSite bootstrap(MethodHandles.Lookup caller, String name, MethodType type, String mixin,
            int isStatic, int kind, int index) throws ReflectiveOperationException {
        return link(caller, name, type, mixin, isStatic,
                () -> HandlerStandIns.make(caller, type, kind, index, isStatic == STATIC ? 0 : 1));
    }

    public static CallSite bootstrapRedirect(MethodHandles.Lookup caller, String name, MethodType type, String mixin,
            int isStatic, int opcode, String owner, String target, String desc) throws ReflectiveOperationException {
        return link(caller, name, type, mixin, isStatic, () -> RedirectStandIns.makeStandIn(caller, type,
                isStatic == STATIC ? 0 : 1, new RedirectStandIns.Target(opcode, owner, target, desc)));
    }

    private interface StandInCall {
        MethodHandle make() throws ReflectiveOperationException;
    }

    private static CallSite link(MethodHandles.Lookup caller, String name, MethodType type, String mixin,
            int isStatic, StandInCall standIns) throws ReflectiveOperationException {
        Class<?> owner = caller.lookupClass();
        MethodHandle handler = isStatic == STATIC ? caller.findStatic(owner, name, type)
                : isStatic == VIRTUAL ? caller.findVirtual(owner, name, type.dropParameterTypes(0, 1))
                        : caller.findSpecial(owner, name, type.dropParameterTypes(0, 1), owner);
        MethodHandle standIn;
        try {
            standIn = standIns.make();
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("[Hotwire] {}.{} {} has no stand-in and is called direct: {}", owner.getName(), name, type,
                    e.toString());
            markUnflippable(owner.getName().replace('.', '/'), mixin);
            return new ConstantCallSite(handler);
        }
        synchronized (LOCK) {
            MutableCallSite site = new MutableCallSite(OFF.contains(mixin) ? standIn : handler);
            GUARDS.computeIfAbsent(mixin, m -> new WeakHashMap<>()).put(site, new Guard(handler, standIn));
            return site;
        }
    }

    public static int setOff(Set<String> mixins, boolean off) {
        List<MutableCallSite> changed = new ArrayList<>();
        boolean chunks = false;
        synchronized (LOCK) {
            for (String mixin : mixins) {
                if (off ? !OFF.add(mixin) : !OFF.remove(mixin)) {
                    continue;
                }
                Map<MutableCallSite, Guard> guards = GUARDS.getOrDefault(mixin, Map.of());
                guards.forEach((site, guard) -> {
                    site.setTarget(off ? guard.standIn() : guard.handler());
                    changed.add(site);
                });
                chunks |= !guards.isEmpty() && isOfChunks(mixin);
            }
        }
        if (!changed.isEmpty()) {
            FLIPS.incrementAndGet();
            MutableCallSite.syncAll(changed.toArray(MutableCallSite[]::new));
        }
        if (chunks) {
            CHUNK_FLIPS.incrementAndGet();
        }
        LOGGER.info("[Hotwire] {} handler sites of {} mixins now call {}", changed.size(), mixins.size(),
                off ? "their stand-ins" : "their handlers");
        return changed.size();
    }

    public static boolean isOff(String mixin) {
        synchronized (LOCK) {
            return OFF.contains(mixin);
        }
    }
}
