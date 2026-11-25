package dev.moon.hotwire.redefine;

import dev.moon.hotwire.jar.WidenerUses;
import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Linker {
    private static final Logger LOGGER = LoggerFactory.getLogger(Linker.class);
    static final int GET_FIELD = 0;
    static final int PUT_FIELD = 1;
    static final int GET_STATIC = 2;
    static final int PUT_STATIC = 3;
    static final int VIRTUAL = 4;
    static final int PUT_FINAL = 5;

    static final class Link {
        volatile MethodHandles.@Nullable Lookup sidecar;

        final Deque<MethodHandles.Lookup> earlier = new ConcurrentLinkedDeque<>();

        volatile boolean pending;

        volatile @Nullable Set<String> moved;

        volatile Set<String> stubbed = Set.of();

        volatile FieldHolders.@Nullable Holder holder;

        volatile @Nullable Consumer<Object> catchUp;

        volatile @Nullable Map<Object, Boolean> retrying;
        volatile boolean saidFailed;

        final Deque<FieldCallSites.Bound> fieldSites = new ConcurrentLinkedDeque<>();

        private boolean isEmpty() {
            return this.sidecar == null && !this.pending && this.moved == null && this.stubbed.isEmpty()
                    && this.earlier.isEmpty() && this.holder == null && this.catchUp == null
                    && this.retrying == null && this.fieldSites.isEmpty();
        }
    }

    static final Map<Class<?>, Link> LINKS = new ConcurrentHashMap<>();

    static Link link(Class<?> target) {
        return LINKS.computeIfAbsent(target, type -> new Link());
    }

    static void dropIfEmpty(Class<?> target) {
        LINKS.computeIfPresent(target, (type, now) -> now.isEmpty() ? null : now);
    }

    static MethodHandles.@Nullable Lookup findSidecar(Class<?> target) {
        Link linked = LINKS.get(target);
        return linked == null ? null : linked.sidecar;
    }

    static Set<String> findMoved(Class<?> target) {
        Link linked = LINKS.get(target);
        Set<String> moved = linked == null ? null : linked.moved;
        return moved == null ? Set.of() : moved;
    }

    static Collection<MethodHandles.Lookup> findEarlier(Class<?> target) {
        Link linked = LINKS.get(target);
        return linked == null ? List.of() : linked.earlier;
    }

    private static List<Set<String>> findEveryMoved() {
        List<Set<String>> every = new ArrayList<>();
        for (Link linked : LINKS.values()) {
            Set<String> moved = linked.moved;
            if (moved != null) {
                every.add(moved);
            }
        }
        return every;
    }

    private static final Map<Class<?>, Class<?>> SIDECAR_TARGETS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final int EARLIER_LIMIT = 4;

    private static final Map<Class<?>, Set<String>> INSTANCE_METHODS = Collections.synchronizedMap(new WeakHashMap<>());

    private static final Map<String, Set<Class<?>>> DUCKS = new ConcurrentHashMap<>();

    private static final Set<String> MOVED = ConcurrentHashMap.newKeySet();

    private static volatile WidenerUses.Openings openings = WidenerUses.Openings.NONE;
    private static final Set<String> ADDED_CONSTRUCTORS = ConcurrentHashMap.newKeySet();

    private Linker() {
    }

    public static Class<?> install(Class<?> target, byte[] sidecar, Set<String> ducks) throws IllegalAccessException {
        return install(target, sidecar, ducks, null, Set.of(), Set.of());
    }

    public static Class<?> install(Class<?> target, ClassSplit.Result split) throws IllegalAccessException {
        link(target).stubbed = Set.copyOf(split.stubbed());
        return install(target, split.sidecar(), split.ducks(), split.fieldHolder(), split.movedInstance(),
                Set.copyOf(split.moved()));
    }

    static Class<?> installInBatch(Class<?> target, ClassSplit.Result split) throws IllegalAccessException {
        Link linked = link(target);
        linked.pending = true;
        linked.stubbed = Set.copyOf(split.stubbed());
        return install(target, split.sidecar(), split.ducks(), split.fieldHolder(), split.movedInstance(),
                Set.copyOf(split.moved()), false);
    }

    static void endBatch(Collection<Class<?>> targets) {
        for (Class<?> target : targets) {
            Link linked = LINKS.get(target);
            if (linked != null) {
                linked.pending = false;
                dropIfEmpty(target);
            }
        }
        DuckCalls.forget();
    }

    static boolean isPending(Class<?> target) {
        Link linked = LINKS.get(target);
        return linked != null && linked.pending;
    }

    static MethodHandles.@Nullable Lookup findLookupForOldCode(Class<?> target) {
        Link linked = LINKS.get(target);
        if (linked == null) {
            return null;
        }
        if (linked.pending) {
            MethodHandles.Lookup before = linked.earlier.peekFirst();
            if (before != null) {
                return before;
            }
        }
        return linked.sidecar;
    }

    public static Class<?> install(Class<?> target, byte[] sidecar, Set<String> ducks, byte @Nullable [] fieldHolder,
            Set<String> instanceMethods, Set<String> moved) throws IllegalAccessException {
        return install(target, sidecar, ducks, fieldHolder, instanceMethods, moved, true);
    }

    private static Class<?> install(Class<?> target, byte[] sidecar, Set<String> ducks, byte @Nullable [] fieldHolder,
            Set<String> instanceMethods, Set<String> moved, boolean startStatics) throws IllegalAccessException {
        MethodHandles.Lookup host = MethodHandles.privateLookupIn(target, MethodHandles.lookup());

        FieldHolders.install(target, fieldHolder, host);

        MethodHandles.Lookup side = host.defineHiddenClass(sidecar, false, MethodHandles.Lookup.ClassOption.NESTMATE);
        SIDECAR_TARGETS.put(side.lookupClass(), target);
        Link linked = link(target);
        MethodHandles.Lookup before = linked.sidecar;
        linked.sidecar = side;
        SidecarCallSites.rebind(target, side);
        if (fieldHolder != null && startStatics) {
            FieldHolders.startStatics(target);
        }
        if (before != null) {
            linked.earlier.addFirst(before);
            while (linked.earlier.size() > EARLIER_LIMIT) {
                linked.earlier.removeLast();
            }
        }
        INSTANCE_METHODS.put(side.lookupClass(), Set.copyOf(instanceMethods));
        linked.moved = Set.copyOf(moved);
        MovedSites.reset(MOVED, findEveryMoved());
        String internal = Type.getInternalName(target);
        for (String one : moved) {
            if (one.startsWith(Adoption.CONSTRUCT + "(Ljava/lang/Object;")) {
                ADDED_CONSTRUCTORS.add(internal + "<init>(" + one.substring(Adoption.CONSTRUCT.length()
                        + "(Ljava/lang/Object;".length(), one.lastIndexOf(')')) + ")V");
            }
        }
        setDucksOf(target, ducks);
        EVER_DUCKS.addAll(ducks);
        DuckCalls.forget();
        if (startStatics) {
            FieldHolders.carryOver(Set.of(target));
        }
        return side.lookupClass();
    }

    private static void setDucksOf(Class<?> target, Set<String> ducks) {
        for (String duck : ducks) {
            DUCKS.computeIfAbsent(duck, name -> ConcurrentHashMap.newKeySet()).add(target);
        }
        DUCKS.forEach((duck, containers) -> {
            if (!ducks.contains(duck)) {
                containers.remove(target);
            }
        });
    }

    private static final Set<String> REACHED = ConcurrentHashMap.newKeySet();

    private static volatile Function<String, @Nullable String> mixinOwner = mixin -> null;

    public static void setMixinOwners(Function<String, @Nullable String> owner) {
        mixinOwner = owner;
    }

    public static @Nullable String findMixinOwner(String mixin) {
        return mixinOwner.apply(mixin);
    }

    public static void onReached(String member, String mixin) {
        if (REACHED.add(member)) {
            LOGGER.info("[Hotwire] {} belongs to a mod that is off and was reached; its old body ran. The caller"
                    + " holds something of that mod, or was already running when the mod went off.", member);
        }
        String mod = mixin.isEmpty() ? null : findMixinOwner(mixin.replace('.', '/'));
        if (mod != null) {
            Tripwire.onCounted(mod);
        }
    }

    public static void onSkippedCallback(String member) {
        if (REACHED.add(member)) {
            LOGGER.info("[Hotwire] {} is a callback of a mod that is off, called by code that was already running when"
                    + " it went off; it did nothing.", member);
        }
    }

    public static void drop(Class<?> target) {
        Link linked = LINKS.get(target);
        if (linked != null) {
            MethodHandles.Lookup side = linked.sidecar;
            if (side != null) {
                INSTANCE_METHODS.remove(side.lookupClass());
            }

            linked.sidecar = null;
            linked.earlier.clear();
            linked.moved = null;
            linked.stubbed = Set.of();
            dropIfEmpty(target);
        }
        MovedSites.reset(MOVED, findEveryMoved());
        FieldHolders.drop(target);
        DUCKS.values().forEach(containers -> containers.remove(target));
        DuckCalls.forget();
    }

    private static final Set<String> EVER_DUCKS = ConcurrentHashMap.newKeySet();

    public static Set<String> getEverDucks() {
        return Set.copyOf(EVER_DUCKS);
    }

    public static Set<String> getDucks() {
        Set<String> names = new HashSet<>();
        DUCKS.forEach((duck, containers) -> {
            if (!containers.isEmpty()) {
                names.add(duck);
            }
        });
        return names;
    }

    public static Set<String> getMoved() {
        return Set.copyOf(MOVED);
    }

    public static Map<Class<?>, Set<String>> getMovedByTarget() {
        Map<Class<?>, Set<String>> all = new HashMap<>();
        LINKS.forEach((target, linked) -> {
            Set<String> moved = linked.moved;
            if (moved != null) {
                all.put(target, moved);
            }
        });
        return Map.copyOf(all);
    }

    static Set<String> getMovedOf(String internalName) {
        for (Class<?> target : LINKS.keySet()) {
            if (Type.getInternalName(target).equals(internalName)) {
                return findMoved(target);
            }
        }
        return Set.of();
    }

    static CallSite bootstrapMoved(MethodHandles.Lookup caller, String name, MethodType type, String owner,
            String method, String descriptor, int isStatic) throws ReflectiveOperationException {
        if (isStatic != 0) {
            Class<?> at = Class.forName(owner.replace('.', '/').replace('/', '.'), false,
                    caller.lookupClass().getClassLoader());
            MethodType wanted = MethodType.fromMethodDescriptorString(descriptor, at.getClassLoader());
            for (; at != null; at = at.getSuperclass()) {
                MethodHandles.Lookup side = findSidecar(at);
                Set<String> moved = findMoved(at);
                String named = moved.contains(method + descriptor) ? method
                        : findRenumbered(moved, method, descriptor);
                if (side != null && named != null) {
                    return new ConstantCallSite(MethodHandles.explicitCastArguments(
                            side.findStatic(side.lookupClass(), named, wanted), type));
                }
            }
            Class<?> real = Class.forName(owner.replace('/', '.'), false, caller.lookupClass().getClassLoader());
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(real, MethodHandles.lookup());
            try {
                return new ConstantCallSite(MethodHandles.explicitCastArguments(
                        lookup.findStatic(real, method, wanted), type));
            } catch (NoSuchMethodException absent) {
                Set<String> declared = new HashSet<>();
                for (Method one : real.getDeclaredMethods()) {
                    declared.add(one.getName() + MethodType.methodType(one.getReturnType(), one.getParameterTypes())
                            .toMethodDescriptorString());
                }
                String named = findRenumbered(declared, method, descriptor);
                if (named == null) {
                    throw absent;
                }
                return new ConstantCallSite(MethodHandles.explicitCastArguments(
                        lookup.findStatic(real, named, wanted), type));
            }
        }
        MethodHandle own = MovedSites.findOwnFinal(caller, owner, method, descriptor);
        if (own != null) {
            return new ConstantCallSite(MethodHandles.explicitCastArguments(own, type));
        }
        return MovedSites.make(type, method, descriptor, MovedSites.findPlain(caller, owner, method, descriptor));
    }

    static CallSite bootstrapMovedSuper(MethodHandles.Lookup caller, String name, MethodType type, String owner,
            String method, String descriptor) throws ReflectiveOperationException {
        Class<?> from = Class.forName(owner.replace('/', '.'), false, caller.lookupClass().getClassLoader());
        return MovedSites.makeSuper(caller, type, from, method, descriptor);
    }

    static MethodHandle findMovedSuper(MethodHandles.Lookup caller, Class<?> from, String method, String descriptor)
            throws ReflectiveOperationException {
        MethodType type = MethodType.fromMethodDescriptorString(descriptor, from.getClassLoader());
        for (Class<?> at = from; at != null; at = at.getSuperclass()) {
            MethodHandles.Lookup side = findSidecar(at);
            if (side != null && findMoved(at).contains(method + descriptor)) {
                return side.findStatic(side.lookupClass(), method, type.insertParameterTypes(0, at));
            }
            if (hasOwn(at, method, type)) {
                break;
            }
        }
        return caller.findSpecial(from, method, type, caller.lookupClass());
    }

    static @Nullable String findRenumbered(Set<String> members, String method, String descriptor) {
        if (!GeneratedNames.isGenerated(method)) {
            return null;
        }
        String wanted = GeneratedNames.mask(method);
        String found = null;
        for (String member : members) {
            int open = member.indexOf('(');
            if (open > 0 && member.substring(open).equals(descriptor)
                    && GeneratedNames.mask(member.substring(0, open)).equals(wanted)) {
                if (found != null) {
                    return null;
                }
                found = member.substring(0, open);
            }
        }
        return found;
    }

    static MethodHandle findMoved(String method, String descriptor, Class<?> receiver, @Nullable MethodHandle plain)
            throws Throwable {
        try {
            return DuckCalls.loosen(resolveMoved(method, descriptor, receiver, plain));
        } catch (NoSuchMethodException e) {
            if (Tripwire.holdCaller()) {
                return findMoved(method, descriptor, receiver, plain);
            }
            throw e;
        }
    }

    static boolean hasOwn(Class<?> type, String method, MethodType shape) {
        Link linked = LINKS.get(type);
        if (linked != null && linked.stubbed.contains(method + shape.toMethodDescriptorString())) {
            return false;
        }
        try {
            Method own = type.getDeclaredMethod(method, shape.parameterArray());
            return own.getReturnType() == shape.returnType() && !Modifier.isAbstract(own.getModifiers())
                    && !Modifier.isStatic(own.getModifiers());
        } catch (NoSuchMethodException | LinkageError absent) {
            return false;
        }
    }

    private static MethodHandle resolveMoved(String method, String descriptor, Class<?> receiver,
            @Nullable MethodHandle plain) throws ReflectiveOperationException {
        MethodType type = MethodType.fromMethodDescriptorString(descriptor, receiver.getClassLoader());
        Class<?> overrider = null;
        for (Class<?> at = receiver; at != null; at = at.getSuperclass()) {
            MethodHandles.Lookup side = findSidecar(at);
            if (side != null && findMoved(at).contains(method + descriptor)) {
                return overrider == null ? side.findStatic(side.lookupClass(), method,
                        type.insertParameterTypes(0, at))
                        : MethodHandles.privateLookupIn(overrider, MethodHandles.lookup()).findVirtual(overrider,
                                method, type);
            }
            if (overrider == null && hasOwn(at, method, type)) {
                overrider = at;
            }
        }
        MethodHandle gained = LinkerLookups.findGainedDefault(method, type, receiver);
        if (gained != null) {
            return gained;
        }

        if (plain != null && plain.type().parameterType(0).isAssignableFrom(receiver)) {
            return plain;
        }

        try {
            return MethodHandles.publicLookup().findVirtual(receiver, method, type);
        } catch (NoSuchMethodException | IllegalAccessException notPublic) {
            for (Class<?> at = receiver; at != null; at = at.getSuperclass()) {
                try {
                    return MethodHandles.privateLookupIn(at, MethodHandles.lookup()).findVirtual(at, method, type);
                } catch (NoSuchMethodException | IllegalAccessException e) {
                }
            }
            throw notPublic;
        }
    }

    static CallSite bootstrapIsInstance(MethodHandles.Lookup caller, String name, MethodType type, String duck)
            throws ReflectiveOperationException {
        MethodHandle test = MethodHandles.lookup().findStatic(Linker.class, "carries",
                MethodType.methodType(boolean.class, String.class, Object.class));
        return new ConstantCallSite(MethodHandles.insertArguments(test, 0, duck).asType(type));
    }

    static CallSite bootstrapCast(MethodHandles.Lookup caller, String name, MethodType type, String duck)
            throws ReflectiveOperationException {
        MethodHandle cast = MethodHandles.lookup().findStatic(Linker.class, "castTo",
                MethodType.methodType(Object.class, String.class, Object.class));
        MethodHandle same = MethodHandles.explicitCastArguments(MethodHandles.identity(Object.class), type);
        return DuckCalls.makeSite(MethodHandles.insertArguments(cast, 0, duck).asType(type),
                kind -> DuckCalls.carries(duck, kind) ? same : null);
    }

    static CallSite bootstrapDuck(MethodHandles.Lookup caller, String name, MethodType type, String duck,
            String method, String descriptor) {
        MethodHandle bound = DuckCalls.bind(duck, method, descriptor).asCollector(Object[].class,
                type.parameterCount() - 1);

        MethodHandle slow = MethodHandles.explicitCastArguments(bound, type);
        return DuckCalls.makeSite(slow, kind -> DuckCalls.findDirect(duck, method, descriptor, kind, slow));
    }

    static boolean carries(String duck, @Nullable Object object) {
        return object != null && DuckCalls.carries(duck, object.getClass());
    }

    static boolean carriesClass(String duck, Class<?> type) {
        for (Class<?> container : DUCKS.getOrDefault(duck, Set.of())) {
            if (container.isAssignableFrom(type)) {
                return true;
            }
        }
        return false;
    }

    static @Nullable Object castTo(String duck, @Nullable Object object) {
        if (object == null || carries(duck, object) || (Tripwire.holdCaster() && carries(duck, object))) {
            return object;
        }
        throw new ClassCastException(object.getClass().getName() + " cannot be cast to " + duck.replace('/', '.'));
    }

    record Snapshot(Class<?> target, MethodHandles.@Nullable Lookup sidecar, Set<String> instanceMethods,
            Set<String> moved, Set<String> ducks, FieldHolders.@Nullable Holder holder, Set<String> stubbed) {
    }

    static Snapshot snapshot(Class<?> target) {
        Link linked = LINKS.get(target);
        MethodHandles.Lookup side = linked == null ? null : linked.sidecar;
        Set<String> ducks = new HashSet<>();
        DUCKS.forEach((duck, containers) -> {
            if (containers.contains(target)) {
                ducks.add(duck);
            }
        });
        return new Snapshot(target, side, side == null ? Set.of() : INSTANCE_METHODS.getOrDefault(side.lookupClass(),
                Set.of()), findMoved(target), ducks, FieldHolders.snapshot(target),
                linked == null ? Set.of() : linked.stubbed);
    }

    static void restore(Snapshot snapshot) {
        Class<?> target = snapshot.target();
        Link linked = link(target);
        MethodHandles.Lookup now = linked.sidecar;
        if (now != null) {
            INSTANCE_METHODS.remove(now.lookupClass());
        }
        if (snapshot.sidecar() == null) {
            linked.sidecar = null;
            linked.moved = null;
        } else {
            linked.sidecar = snapshot.sidecar();
            INSTANCE_METHODS.put(snapshot.sidecar().lookupClass(), snapshot.instanceMethods());
            linked.moved = snapshot.moved();

            SidecarCallSites.rebind(target, snapshot.sidecar());
        }
        setDucksOf(target, snapshot.ducks());
        linked.stubbed = snapshot.stubbed();
        dropIfEmpty(target);
        MovedSites.reset(MOVED, findEveryMoved());
        DuckCalls.forget();
        FieldHolders.restore(target, snapshot.holder());
        FieldCallSites.rebind(target);
    }

    static Set<Class<?>> getContainers(String duck) {
        return DUCKS.getOrDefault(duck, Set.of());
    }

    static MethodHandles.@Nullable Lookup findCurrentLookup(Class<?> target) {
        return findSidecar(target);
    }

    public static synchronized void open(WidenerUses.Openings more) {
        openings = openings.with(more);
    }

    public static WidenerUses.Openings getOpenings() {
        return openings;
    }

    public static void addAddedConstructors(Set<String> keys) {
        ADDED_CONSTRUCTORS.addAll(keys);
    }

    public static void removeAddedConstructors(Set<String> keys) {
        ADDED_CONSTRUCTORS.removeAll(keys);
    }

    public static Set<String> getAddedConstructors() {
        return Set.copyOf(ADDED_CONSTRUCTORS);
    }

    static CallSite bootstrapMethod(MethodHandles.Lookup caller, String name, MethodType type)
            throws ReflectiveOperationException {
        return SidecarCallSites.bind(getHostOf(caller.lookupClass()),
                LinkerLookups.findInSidecars(caller.lookupClass(), name, type),
                name, type);
    }

    static CallSite bootstrapSuperCall(MethodHandles.Lookup caller, String name, MethodType type, Class<?> owner)
            throws ReflectiveOperationException {
        return SidecarCallSites.bindSuper(getHostOf(caller.lookupClass()), name, type, owner);
    }

    static CallSite bootstrapInherited(MethodHandles.Lookup caller, String name, MethodType type, Class<?> owner,
            int kind) throws ReflectiveOperationException {
        MethodHandle handle;
        try {
            handle = LinkerLookups.findMember(caller, name, type, owner, kind);
        } catch (IllegalAccessException notPublic) {
            MethodHandles.Lookup host = MethodHandles.privateLookupIn(getHostOf(caller.lookupClass()),
                    MethodHandles.lookup());
            try {
                handle = LinkerLookups.findMember(host, name, type, owner, kind);
            } catch (IllegalAccessException notProtected) {
                Class<?> declarer = LinkerLookups.findDeclarer(owner, name, type, kind);
                handle = LinkerLookups.findMember(MethodHandles.privateLookupIn(declarer, MethodHandles.lookup()),
                        name, type,
                        declarer, kind);
            }
        } catch (NoSuchMethodException added) {
            if (kind != VIRTUAL) {
                throw added;
            }
            return bootstrapMoved(caller, name, type, owner.getName(), name,
                    type.dropParameterTypes(0, 1).toMethodDescriptorString(), 0);
        }
        return new ConstantCallSite(MethodHandles.explicitCastArguments(handle, type));
    }

    static CallSite bootstrapField(MethodHandles.Lookup caller, String name, MethodType type, int kind,
            String descriptor) throws ReflectiveOperationException {
        return bindField(getHostOf(caller.lookupClass()), name, type, kind);
    }

    static CallSite bootstrapForeignField(MethodHandles.Lookup caller, String name, MethodType type,
            Class<?> owner, int kind) throws ReflectiveOperationException {
        if (FieldHolders.findHolderClassOf(FieldHolders.findDeclaringOf(owner, name)) == null) {
            return new ConstantCallSite(findPlainField(MethodHandles.privateLookupIn(owner, caller), owner, name,
                    type, kind).asType(type));
        }
        return bindField(owner, name, type, kind);
    }

    private static MethodHandle findPlainField(MethodHandles.Lookup lookup, Class<?> owner, String name,
            MethodType type, int kind) throws ReflectiveOperationException {
        return switch (kind) {
            case GET_FIELD -> lookup.findGetter(owner, name, type.returnType());
            case GET_STATIC -> lookup.findStaticGetter(owner, name, type.returnType());
            case PUT_STATIC -> lookup.findStaticSetter(owner, name, type.parameterType(0));
            default -> lookup.findSetter(owner, name, type.parameterType(1));
        };
    }

    private static CallSite bindField(Class<?> target, String name, MethodType type, int kind)
            throws ReflectiveOperationException {
        return kind == PUT_FINAL ? new ConstantCallSite(findCastFieldHandle(target, name, type, kind))
                : FieldCallSites.bind(FieldHolders.findDeclaringOf(target, name), name, type, kind);
    }

    static MethodHandle findCastFieldHandle(Class<?> target, String name, MethodType type, int kind)
            throws ReflectiveOperationException {
        return MethodHandles.explicitCastArguments(LinkerLookups.findFieldHandle(target, name, type, kind), type);
    }

    static CallSite bootstrapDuckLambda(MethodHandles.Lookup caller, String name, MethodType factory,
            MethodType erased, MethodHandle target, MethodType instantiated) throws ReflectiveOperationException {
        MethodHandleInfo info = caller.revealDirect(target);
        String owner = Type.getInternalName(info.getDeclaringClass());
        MethodHandle body = target;
        if (info.getReferenceKind() == MethodHandleInfo.REF_invokeInterface && EVER_DUCKS.contains(owner)) {
            MethodType shape = info.getMethodType();
            body = DuckCalls.bind(owner, info.getName(), shape.toMethodDescriptorString())
                    .asCollector(Object[].class, shape.parameterCount());
        }
        return Lambdas.spin(caller, name, factory, erased, DuckCalls.loosen(body));
    }

    static CallSite bootstrapMovedLambda(MethodHandles.Lookup caller, String name, MethodType factory,
            MethodType erased, String owner, String method, String descriptor, int isStatic)
            throws ReflectiveOperationException {
        MethodType shape = MethodType.fromMethodDescriptorString(descriptor, caller.lookupClass().getClassLoader());
        MethodHandle body = isStatic != 0
                ? bootstrapMoved(caller, method, shape, owner, method, descriptor, 1).dynamicInvoker()
                : bootstrapMoved(caller, method, shape.insertParameterTypes(0, Object.class), owner, method, descriptor,
                        0).dynamicInvoker();
        return Lambdas.spin(caller, name, factory, erased, DuckCalls.loosen(body));
    }

    static CallSite bootstrapLambda(MethodHandles.Lookup caller, String name, MethodType type, MethodType erased,
            String implName, String implDescriptor, MethodType instantiated) throws ReflectiveOperationException {
        MethodHandles.Lookup side = getSidecarOf(caller.lookupClass());
        MethodType implType = MethodType.fromMethodDescriptorString(implDescriptor,
                side.lookupClass().getClassLoader());
        MethodHandle impl = LinkerLookups.findInSidecars(caller.lookupClass(), implName, implType);
        return Lambdas.spin(side, name, type, erased, impl);
    }

    static MethodHandles.Lookup getSidecarOf(Class<?> caller) {
        MethodHandles.Lookup side = findSidecar(getHostOf(caller));
        if (side == null) {
            throw new IllegalStateException("[Hotwire] no sidecar is installed for " + caller.getName());
        }
        return side;
    }

    static Class<?> getHostOf(Class<?> caller) {
        if (!caller.isHidden()) {
            return caller;
        }
        Class<?> target = SIDECAR_TARGETS.get(caller);
        if (target == null) {
            target = FieldHolders.findTargetOfHolder(caller);
        }
        return target != null ? target : caller.getNestHost();
    }

    public static @Nullable Method findSpunBody(Class<?> type) {
        return Lambdas.findBody(type);
    }

    static boolean standsForInstanceMethod(Class<?> sidecar, String nameAndDescriptor) {
        return INSTANCE_METHODS.getOrDefault(sidecar, Set.of()).contains(nameAndDescriptor);
    }
}
