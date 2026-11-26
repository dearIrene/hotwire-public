package dev.moon.hotwire.redefine;

import dev.moon.hotwire.classfile.ClassNodes;
import dev.moon.hotwire.classfile.GameNames;
import dev.moon.hotwire.config.Flags;
import dev.moon.hotwire.seam.Games;
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
import java.util.LinkedHashMap;
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
import org.objectweb.asm.Type;
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
    public static final boolean ENABLED = !"false".equals(Flags.findGuards());
    private static final Logger LOGGER = LoggerFactory.getLogger(HandlerGuards.class);
    private static final String MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";
    static final byte[] MERGED_BYTES = MERGED.getBytes(StandardCharsets.UTF_8);

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

    static final Handle BOOTSTRAP = new Handle(Opcodes.H_INVOKESTATIC,
            Live.NAME, "guard",
            MethodType.methodType(CallSite.class, MethodHandles.Lookup.class, String.class, MethodType.class,
                    String.class, int.class, int.class, int.class).toMethodDescriptorString(), false);

    static final Handle BOOTSTRAP_SAVING = new Handle(Opcodes.H_INVOKESTATIC,
            Live.NAME, "guardSaving",
            MethodType.methodType(CallSite.class, MethodHandles.Lookup.class, String.class, MethodType.class,
                    String.class, int.class, int.class, int.class).toMethodDescriptorString(), false);

    static final Handle BOOTSTRAP_CONSTRUCTOR = new Handle(Opcodes.H_INVOKESTATIC,
            Live.NAME, "guardConstructor",
            MethodType.methodType(CallSite.class, MethodHandles.Lookup.class, String.class, MethodType.class,
                    String.class, int.class).toMethodDescriptorString(), false);

    static final Handle BOOTSTRAP_HEAD = new Handle(Opcodes.H_INVOKESTATIC,
            Live.NAME, "guardHead",
            MethodType.methodType(CallSite.class, MethodHandles.Lookup.class, String.class, MethodType.class,
                    String.class, int.class, int.class, int.class).toMethodDescriptorString(), false);

    static final Handle BOOTSTRAP_OFF_FLAG = new Handle(Opcodes.H_INVOKESTATIC,
            Live.NAME, "findOffFlag",
            MethodType.methodType(boolean[].class, MethodHandles.Lookup.class, String.class, Class.class,
                    String.class).toMethodDescriptorString(), false);
    static final Handle BOOTSTRAP_REDIRECT = new Handle(Opcodes.H_INVOKESTATIC,
            Live.NAME, "guardRedirect",
            MethodType.methodType(CallSite.class, MethodHandles.Lookup.class, String.class, MethodType.class,
                    String.class, int.class, int.class, String.class, String.class, String.class)
                    .toMethodDescriptorString(), false);

    static final Handle BOOTSTRAP_REDIRECT_FORGETTING = new Handle(Opcodes.H_INVOKESTATIC,
            Live.NAME, "guardRedirectForgetting",
            MethodType.methodType(CallSite.class, MethodHandles.Lookup.class, String.class, MethodType.class,
                    String.class, int.class, int.class, String.class, String.class, String.class, String.class)
                    .toMethodDescriptorString(), false);

    static final Handle BOOTSTRAP_FIELD = new Handle(Opcodes.H_INVOKESTATIC,
            Live.NAME, "guardField",
            MethodType.methodType(CallSite.class, MethodHandles.Lookup.class, String.class, MethodType.class,
                    String.class).toMethodDescriptorString(), false);
    static final Handle BOOTSTRAP_OVERWRITE = new Handle(Opcodes.H_INVOKESTATIC,
            Live.NAME, "guardOverwrite",
            MethodType.methodType(CallSite.class, MethodHandles.Lookup.class, String.class, MethodType.class,
                    String.class, int.class, String.class).toMethodDescriptorString(), false);

    static volatile Function<String, byte @Nullable []> originals = name -> null;

    static volatile Function<String, Set<String>> overwriters = method -> Set.of();

    private record Guard(MethodHandle handler, MethodHandle standIn, boolean ofChunks) {
    }

    private static final Map<String, Map<MutableCallSite, Guard>> GUARDS = new HashMap<>();
    private static final Set<String> OFF = new HashSet<>();
    private static final HeadFlags HEAD_FLAGS = new HeadFlags();

    private static final class HeadFlags {
        private final Map<String, boolean[]> byMixin = new HashMap<>();
        private final Set<String> ofChunks = new HashSet<>();
    }

    private static final AtomicLong CHUNK_FLIPS = new AtomicLong();
    private static final List<String> CHUNK_HOSTS = List.of("chunk", "renderer/block/", "color/block/", "/model/",
            "terrain", "/mesh");

    private static final List<String> CHUNK_MIXINS = List.of("chunk", "blockmodel", "bakedmodel", "blockrender",
            "blockcolor", "quad", "mesh", "terrain", "fluidrender", "section", "blockstatemodel", "light");
    private static final Object LOCK = new Object();

    static volatile Set<String> neverSwitched = Set.of();

    private static volatile Set<String> neverSwitchedMixins = Set.of();

    private HandlerGuards() {
    }

    public static void leaveHandlersOf(Set<String> mods, Set<String> mixins) {
        neverSwitched = Set.copyOf(mods);
        neverSwitchedMixins = Set.copyOf(mixins);
        SiteInputs.writeNeverSwitched(mods, mixins);
    }

    static boolean isNeverSwitched(String mixin) {
        return neverSwitchedMixins.contains(mixin);
    }

    public static void readOriginalsWith(Function<String, byte @Nullable []> reader) {
        originals = SiteInputs.writeAsked(reader);
    }

    public static void readOverwritersWith(Function<String, Set<String>> reader) {
        overwriters = SiteInputs.writeTold(reader);
    }

    public static boolean isGuarded(String internalName) {
        return getGuardedOrNull(internalName) != null;
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
            ClassState.of(internalName).addDirectMod(mod);
        }
    }

    public static boolean isFieldSitedFor(String internalName, Set<String> owns) {
        return FieldSites.isSitedFor(internalName, owns);
    }

    static Set<String> findBehavingAt(String internalName) {
        ClassState state = ClassState.find(internalName);
        Set<String> behaving = state == null ? null : state.behaving;
        return behaving == null ? Set.of() : behaving;
    }

    public static Set<String> findBehavingAt(String internalName, Set<String> owns) {
        Set<String> reached = OwnMethods.findReachedAt(internalName, owns);
        reached.addAll(findBehavingAt(internalName));
        return reached;
    }

    public static Set<String> findBehavingNamesAt(String internalName, String mixin, Set<String> owns) {
        return OwnMethods.findNamesAt(internalName, mixin, owns);
    }

    private static void noteBehaving(String internalName, ClassNode node, boolean rebuilt) {
        Map<MethodNode, String> working = findWorking(node);

        for (MethodNode method : node.methods) {
            String mixin = (method.access & Opcodes.ACC_BRIDGE) != 0 ? findMerged(method) : null;
            if (mixin != null) {
                working.put(method, mixin);
            }
        }
        Set<String> behaving;
        if (rebuilt) {
            OwnMethods.forget(internalName);
            behaving = Set.copyOf(working.values());
        } else {
            behaving = OwnMethods.note(internalName, node, working);
        }
        ClassState.of(internalName).behaving = behaving.isEmpty() ? null : behaving;
    }

    private static Set<String> findBehaving(ClassNode node) {
        return Set.copyOf(findWorking(node).values());
    }

    private static Map<MethodNode, String> findWorking(ClassNode node) {
        Map<MethodNode, String> working = new LinkedHashMap<>();
        for (MethodNode method : node.methods) {
            String mixin = findMerged(method);

            if (mixin != null && method.name.indexOf('$') < 0 && !method.name.startsWith("<")
                    && (method.access & Opcodes.ACC_SYNTHETIC) == 0 && !isPlainAccess(method)
                    && !OverwriteSites.isTrampoline(method)) {
                working.put(method, mixin);
            }
        }
        return working;
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
        ClassState state = ClassState.find(internalName);
        Set<String> direct = state == null ? Set.of() : state.getDirectMods();
        Set<String> putIn = state == null ? null : state.putIn;
        if (putIn == null) {
            return direct;
        }
        Set<String> all = new HashSet<>(direct);
        for (String owner : putIn) {
            String mod = TripwireSites.findModOfClass(owner);
            if (mod != null) {
                all.add(mod);
            }
        }
        return all;
    }

    public static List<String> findPutInAt(String internalName, String mod) {
        List<String> owners = new ArrayList<>();
        ClassState state = ClassState.find(internalName);
        Set<String> putIn = state == null ? null : state.putIn;
        for (String owner : putIn == null ? Set.<String>of() : putIn) {
            if (mod.equals(TripwireSites.findModOfClass(owner))) {
                owners.add(owner.substring(owner.lastIndexOf('/') + 1));
            }
        }
        return owners;
    }

    static void notePutIn(String internalName, ClassNode node) {
        Set<String> owners = new HashSet<>();
        Set<String> checked = new HashSet<>();
        byte[] shipped = null;
        for (MethodNode method : node.methods) {
            if (findMerged(method) != null || method.name.contains(OverwriteSites.BODY)
                    || method.name.endsWith(FieldSites.INIT)
                    || ((method.access & Opcodes.ACC_SYNTHETIC) != 0 && method.name.contains("lambda$"))) {
                continue;
            }
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode gated && !gated.name.equals("<init>")
                        && Type.getReturnType(gated.desc).getSort() != Type.OBJECT
                        && Type.getReturnType(gated.desc).getSort() != Type.ARRAY) {
                    continue;
                }
                String owner = insn instanceof MethodInsnNode call ? call.owner
                        : insn instanceof FieldInsnNode field ? field.owner : null;

                if (owner == null || owner.equals(internalName) || owner.startsWith("java/")
                        || GameNames.isGameInternal(owner)
                        || owner.contains("/mixinextras/") || !checked.add(owner)) {
                    continue;
                }
                if (shipped == null) {
                    shipped = originals.apply(internalName);
                    if (shipped == null) {
                        return;
                    }
                }
                if (!ClassBytes.mentions(shipped, owner)) {
                    owners.add(owner);
                }
            }
        }
        ClassState.of(internalName).putIn = owners.isEmpty() ? null : Set.copyOf(owners);
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

    private static boolean isOfChunks(String mixin, String host) {
        String simple = mixin.substring(Math.max(mixin.lastIndexOf('/'), mixin.lastIndexOf('.')) + 1)
                .toLowerCase(Locale.ROOT);
        return containsAny(simple, CHUNK_MIXINS)
                || containsAny(host.replace('.', '/').toLowerCase(Locale.ROOT), CHUNK_HOSTS);
    }

    public static boolean isSite(Handle bootstrap) {
        return bootstrap.getOwner().equals(Live.NAME) && bootstrap.getName().startsWith("guard");
    }

    public static boolean isWritten(String internalName) {
        ClassState state = ClassState.find(internalName);
        return state != null && state.present != null;
    }

    public static Set<String> getPresentMixins(String internalName) {
        ClassState state = ClassState.find(internalName);
        Set<String> present = state == null ? null : state.present;
        return present == null ? Set.of() : present;
    }

    public static Set<String> getGuardedMixins(String internalName) {
        Set<String> guarded = getGuardedOrNull(internalName);
        return guarded == null ? Set.of() : guarded;
    }

    private static @Nullable Set<String> getGuardedOrNull(String internalName) {
        ClassState state = ClassState.find(internalName);
        return state == null ? null : state.guarded;
    }

    public static boolean isRebuilt(String internalName) {
        ClassState state = ClassState.find(internalName);
        return state != null && state.rebuilt;
    }

    public static void forget(String internalName) {
        ClassState.forgetUnmixed(internalName);
        ClassState state = ClassState.find(internalName);
        if (state != null) {
            state.guarded = null;
            state.present = null;
            state.putIn = null;
        }
        FieldSites.forgetExamined(internalName);
    }

    public static final String IN_CONSTRUCTOR = "handler in a constructor";

    static void markUnflippable(String internalName, String mixin, String why) {
        UNFLIPPABLE.add(mixin);
        ClassState state = ClassState.of(internalName);
        state.addUnflippable(mixin, why);
    }

    public static Map<String, String> findWhyUnflippableAt(String internalName) {
        ClassState state = ClassState.find(internalName);
        return state == null ? Map.of() : state.getUnflippableWhy();
    }

    public static Set<String> findUnflippableAt(String internalName) {
        ClassState state = ClassState.find(internalName);
        return state == null ? Set.of() : state.getUnflippable();
    }

    public static boolean isFlippable(String mixin) {
        return !UNFLIPPABLE.contains(mixin);
    }

    public static byte @Nullable [] rewrite(String internalName, byte[] bytes) {
        SiteInputs.writeMixed(internalName, bytes);
        try {
            return rewriteOrThrow(internalName, bytes, true);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("[Hotwire] {} keeps its callbacks called direct: {}", internalName, e.toString());
            return null;
        }
    }

    static byte @Nullable [] rewriteOrThrow(String internalName, byte[] bytes, boolean written) {
        if (!ClassBytes.mentions(bytes, MERGED_BYTES)) {
            boolean sited = bytes.length >= 8 && ((bytes[6] & 0xFF) << 8 | (bytes[7] & 0xFF)) >= Opcodes.V1_7;
            if (sited && ClassState.markUnmixed(internalName)) {
                OwnMethods.forget(internalName);
                return null;
            }
            if (sited) {
                ClassState.of(internalName).setStagedPresent(Set.of());
            }
            ClassState unmixed = ClassState.find(internalName);
            if (unmixed != null) {
                unmixed.behaving = null;
            }
            OwnMethods.forget(internalName);
            commitStaged(internalName);
            return null;
        }
        ClassNode node = ClassNodes.read(bytes, 0);
        boolean made = HandlerSites.makeSites(internalName, node, true);
        noteBehaving(internalName, node, false);
        notePutIn(internalName, node);
        commitStaged(internalName);
        return made && written ? DuckBridge.write(node, 0) : null;
    }

    public static void recordSidecar(String internalName, byte[] sidecar) {
        if (sidecar.length == 0) {
            return;
        }
        ClassNode node = ClassNodes.read(sidecar, ClassReader.SKIP_FRAMES);
        Set<String> behaving = new HashSet<>(findBehavingAt(internalName));
        behaving.addAll(findBehaving(node));
        if (!behaving.isEmpty()) {
            ClassState.of(internalName).behaving = Set.copyOf(behaving);
        }
        for (MethodNode method : node.methods) {
            String mixin = findMerged(method);
            int mark = method.name.indexOf('$');
            if (mixin != null && mark > 0 && (KINDS.containsKey(method.name.substring(0, mark))
                    || method.name.startsWith("redirect$"))) {
                ClassState.of(internalName).addUnflippable(mixin, null);
            }
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(internalName)) {
                    noteDirect(internalName, call.name);
                }
            }
        }
    }

    public static void recordFrom(String internalName, byte[] bytes) {
        ClassState state = ClassState.of(internalName);
        state.rebuilt = true;
        state.clearDirectMods();
        state.takeStaged();
        state.takeStagedPresent();
        ClassNode node = ClassNodes.read(bytes, ClassReader.SKIP_FRAMES);
        Set<String> present = new HashSet<>();
        Set<String> guarded = new HashSet<>();
        Map<String, String> handlers = new HashMap<>();
        Set<String> headGuarded = new HashSet<>();
        for (MethodNode method : node.methods) {
            String mixin = findMerged(method);
            if (mixin != null) {
                present.add(mixin);
                int mark = method.name.indexOf('$');
                if (mark > 0 && (KINDS.containsKey(method.name.substring(0, mark))
                        || method.name.startsWith("redirect$"))) {
                    handlers.put(method.name + method.desc, mixin);
                    if (HandlerSites.isHeadGuarded(method)) {
                        headGuarded.add(method.name + method.desc);
                    }
                }
            }
        }

        FieldSites.make(internalName, node, present, () -> {
            byte[] game = originals.apply(internalName);
            return game == null ? null : ClassNodes.read(game, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }, new HashSet<>(), FieldSites.Mode.RECORD);

        ClassNode shipped = null;
        for (MethodNode method : node.methods) {
            String mixin = findMerged(method);
            if (mixin == null || isHandlerName(method.name) || method.name.startsWith("<")) {
                continue;
            }
            if (shipped == null) {
                byte[] bytes0 = originals.apply(internalName);
                if (bytes0 == null) {
                    break;
                }
                shipped = new ClassNode();
                new ClassReader(bytes0).accept(shipped, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
            if (OverwriteSites.isPlainOverwrite(method, shipped)) {
                markUnflippable(internalName, mixin, "an overwrite in a rebuilt class");
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
                            markUnflippable(internalName, handler.getValue(), "handler linked straight");
                        }
                    }
                    noteDirect(internalName, site.name);
                } else if (insn instanceof MethodInsnNode call && !(call.owner.equals(internalName)
                        && headGuarded.contains(call.name + call.desc))) {
                    String direct = call.owner.equals(internalName) ? handlers.get(call.name + call.desc) : null;
                    if (direct != null && direct.equals(outer)) {
                        continue;
                    }
                    if (direct != null) {
                        markUnflippable(internalName, direct, "handler called straight");
                    }
                    noteDirect(internalName, call.name);
                }
            }
        }
        noteBehaving(internalName, node, true);
        notePutIn(internalName, node);
        state.present = Set.copyOf(present);
        state.guarded = guarded.isEmpty() ? null : Set.copyOf(guarded);
    }

    public static void commitStaged(String internalName) {
        ClassState state = ClassState.find(internalName);
        if (state == null) {
            return;
        }
        Set<String> staged = state.takeStaged();
        if (staged != null) {
            state.guarded = staged;
        }
        Set<String> present = state.takeStagedPresent();
        if (present != null) {
            state.present = present;
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

    static CallSite bootstrap(MethodHandles.Lookup caller, String name, MethodType type, String mixin,
            int isStatic, int kind, int index) throws ReflectiveOperationException {
        return link(caller, name, type, mixin, isStatic,
                () -> HandlerStandIns.make(caller, type, kind, index, isStatic == STATIC ? 0 : 1));
    }

    static CallSite bootstrapSaving(MethodHandles.Lookup caller, String name, MethodType type, String mixin,
            int isStatic, int kind, int index) throws ReflectiveOperationException {
        return link(caller, name, type, mixin, isStatic,
                () -> HandlerStandIns.make(caller, type, kind, index, isStatic == STATIC ? 0 : 1), true);
    }

    static CallSite bootstrapConstructor(MethodHandles.Lookup caller, String name, MethodType type,
            String mixin, int isStatic) throws ReflectiveOperationException {
        Class<?> owner = caller.lookupClass();
        MethodHandle handler = isStatic == VIRTUAL ? caller.findVirtual(owner, name, type.dropParameterTypes(0, 1))
                : caller.findSpecial(owner, name, type.dropParameterTypes(0, 1), owner);
        return link(caller, name, type, mixin, isStatic,
                () -> SkippedConstructorCalls.makeStandIn(mixin, handler, type));
    }

    static CallSite bootstrapRedirect(MethodHandles.Lookup caller, String name, MethodType type, String mixin,
            int isStatic, int opcode, String owner, String target, String desc) throws ReflectiveOperationException {
        return link(caller, name, type, mixin, isStatic, () -> RedirectStandIns.makeStandIn(caller, type,
                isStatic == STATIC ? 0 : 1, new RedirectStandIns.Target(opcode, owner, target, desc)));
    }

    static CallSite bootstrapRedirectForgetting(MethodHandles.Lookup caller, String name, MethodType type,
            String mixin, int isStatic, int opcode, String owner, String target, String desc, String unset)
            throws ReflectiveOperationException {
        return link(caller, name, type, mixin, isStatic, () -> {
            MethodHandle standIn = RedirectStandIns.makeStandIn(caller, type, 1,
                    new RedirectStandIns.Target(opcode, owner, target, desc));
            for (String field : unset.split(";", -1)) {
                int space = field.indexOf(' ');
                Class<?> number = MethodType.fromMethodDescriptorString("()" + field.substring(space + 1), null)
                        .returnType();
                try {
                    MethodHandle set = caller.findSetter(caller.lookupClass(), field.substring(0, space), number);
                    standIn = MethodHandles.foldArguments(standIn,
                            MethodHandles.collectArguments(set, 1, MethodHandles.zero(number)));
                } catch (NoSuchFieldException | IllegalAccessException e) {
                    LOGGER.debug("[Hotwire] {} keeps {} through its off: {}", mixin, field, e.toString());
                }
            }
            return standIn;
        });
    }

    static CallSite bootstrapField(MethodHandles.Lookup caller, String name, MethodType type, String mixin)
            throws ReflectiveOperationException {
        Class<?> owner = caller.lookupClass();
        String className = Type.getInternalName(owner);
        MethodHandle setUp = caller.findSpecial(owner, name, MethodType.methodType(void.class), owner);
        FieldSites.note(mixin, className, name, setUp);
        return link(caller, name, type, mixin, OWN, () -> FieldSites.findStandIn(className, mixin, setUp)
                .asType(type));
    }

    static CallSite bootstrapOverwrite(MethodHandles.Lookup caller, String name, MethodType type, String mixin,
            int isStatic, String was) throws ReflectiveOperationException {
        Class<?> owner = caller.lookupClass();
        return link(caller, name, type, mixin, isStatic, () -> was.isEmpty() ? MethodHandles.empty(type)
                : isStatic == STATIC ? caller.findStatic(owner, was, type)
                : caller.findSpecial(owner, was, type.dropParameterTypes(0, 1), owner));
    }

    static CallSite bootstrapHead(MethodHandles.Lookup caller, String name, MethodType type, String mixin,
            int isStatic, int kind, int index) {
        MethodHandle standIn;
        try {
            standIn = HandlerStandIns.make(caller, type, kind, index, isStatic == STATIC ? 0 : 1);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("[Hotwire] {}.{} {} has no stand-in and answers nothing while off: {}",
                    caller.lookupClass().getName(), name, type, e.toString());
            markUnflippable(Type.getInternalName(caller.lookupClass()), mixin, "stand-in failed to link");
            standIn = MethodHandles.empty(type);
        }
        return new ConstantCallSite(standIn);
    }

    static boolean[] findOffFlag(String mixin, Class<?> host) {
        synchronized (LOCK) {
            if (isOfChunks(mixin, host.getName())) {
                HEAD_FLAGS.ofChunks.add(mixin);
            }
            return HEAD_FLAGS.byMixin.computeIfAbsent(mixin, m -> new boolean[] {OFF.contains(m)});
        }
    }

    private interface StandInCall {
        MethodHandle make() throws ReflectiveOperationException;
    }

    private static CallSite link(MethodHandles.Lookup caller, String name, MethodType type, String mixin,
            int isStatic, StandInCall standIns) throws ReflectiveOperationException {
        return link(caller, name, type, mixin, isStatic, standIns, false);
    }

    private static CallSite link(MethodHandles.Lookup caller, String name, MethodType type, String mixin,
            int isStatic, StandInCall standIns, boolean saving) throws ReflectiveOperationException {
        Class<?> owner = caller.lookupClass();
        MethodHandle handler = isStatic == STATIC ? caller.findStatic(owner, name, type)
                : isStatic == VIRTUAL ? caller.findVirtual(owner, name, type.dropParameterTypes(0, 1))
                        : caller.findSpecial(owner, name, type.dropParameterTypes(0, 1), owner);
        MethodHandle standIn;
        try {
            standIn = standIns.make();
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("[Hotwire] {}.{} {} has no stand-in and answers nothing while off: {}", owner.getName(),
                    name, type, e.toString());
            markUnflippable(Type.getInternalName(owner), mixin, "stand-in failed to link");
            standIn = MethodHandles.empty(type);
        }
        if (saving) {
            standIn = SaveHooks.wrap(handler, standIn, mixin);
        }
        synchronized (LOCK) {
            MutableCallSite site = new MutableCallSite(OFF.contains(mixin) ? standIn : handler);
            GUARDS.computeIfAbsent(mixin, m -> new WeakHashMap<>()).put(site, new Guard(handler, standIn,
                    isOfChunks(mixin, owner.getName())));
            return site;
        }
    }

    public static int setOff(Set<String> mixins, boolean off) {
        if (mixins.isEmpty()) {
            return 0;
        }
        List<MutableCallSite> changed = new ArrayList<>();
        int flags = 0;
        boolean chunks = false;
        synchronized (LOCK) {
            for (String mixin : mixins) {
                if (off ? !OFF.add(mixin) : !OFF.remove(mixin)) {
                    continue;
                }
                boolean[] flag = HEAD_FLAGS.byMixin.get(mixin);
                if (flag != null) {
                    flag[0] = off;
                    flags++;
                    chunks |= HEAD_FLAGS.ofChunks.contains(mixin);
                }
                for (Map.Entry<MutableCallSite, Guard> guard : GUARDS.getOrDefault(mixin, Map.of()).entrySet()) {
                    guard.getKey().setTarget(off ? guard.getValue().standIn() : guard.getValue().handler());
                    changed.add(guard.getKey());
                    chunks |= guard.getValue().ofChunks();
                }
            }
        }
        if (!changed.isEmpty()) {
            MutableCallSite.syncAll(changed.toArray(MutableCallSite[]::new));
        }
        if (chunks) {
            CHUNK_FLIPS.incrementAndGet();
        }
        if (!off) {
            Games.getNow().runOnGameAndWait(() -> {
                FieldSites.replay(mixins);
                SkippedConstructorCalls.replay(mixins);
            });
        }
        LOGGER.info("[Hotwire] {} handler sites and {} guarded handlers of {} mixins now call {}", changed.size(),
                flags, mixins.size(), off ? "their stand-ins" : "their handlers");
        return changed.size() + flags;
    }

    public static boolean isOff(String mixin) {
        synchronized (LOCK) {
            return OFF.contains(mixin);
        }
    }
}
