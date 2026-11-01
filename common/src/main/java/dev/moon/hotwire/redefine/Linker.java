package dev.moon.hotwire.redefine;

import dev.moon.hotwire.jar.WidenerUses;
import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
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

    static final Map<Class<?>, MethodHandles.Lookup> SIDECARS = new ConcurrentHashMap<>();

    private static final Map<Class<?>, Class<?>> SIDECAR_TARGETS = Collections.synchronizedMap(new WeakHashMap<>());

    static final Map<Class<?>, Deque<MethodHandles.Lookup>> EARLIER = new ConcurrentHashMap<>();

    private static final Set<Class<?>> PENDING = ConcurrentHashMap.newKeySet();
    private static final int EARLIER_LIMIT = 4;

    private static final Map<Class<?>, Set<String>> INSTANCE_METHODS = Collections.synchronizedMap(new WeakHashMap<>());

    private static final Map<String, Set<Class<?>>> DUCKS = new ConcurrentHashMap<>();

    private static final Set<String> MOVED = ConcurrentHashMap.newKeySet();
    static final Map<Class<?>, Set<String>> MOVED_BY_TARGET = new ConcurrentHashMap<>();

    private static volatile WidenerUses.Openings openings = WidenerUses.Openings.NONE;
    private static final Set<String> ADOPTED = ConcurrentHashMap.newKeySet();
    private static final Set<String> ADDED_CONSTRUCTORS = ConcurrentHashMap.newKeySet();

    private Linker() {
    }

    public static Class<?> install(Class<?> target, byte[] sidecar, Set<String> ducks) throws IllegalAccessException {
        return install(target, sidecar, ducks, null, Set.of(), Set.of());
    }

    public static Class<?> install(Class<?> target, ClassSplit.Result split) throws IllegalAccessException {
        return install(target, split.sidecar(), split.ducks(), split.fieldHolder(), split.movedInstance(),
                Set.copyOf(split.moved()));
    }

    static Class<?> installInBatch(Class<?> target, ClassSplit.Result split) throws IllegalAccessException {
        PENDING.add(target);
        return install(target, split.sidecar(), split.ducks(), split.fieldHolder(), split.movedInstance(),
                Set.copyOf(split.moved()), false);
    }

    static void endBatch(Collection<Class<?>> targets) {
        PENDING.removeAll(targets);
        DuckCalls.forget();
    }

    static boolean isPending(Class<?> target) {
        return PENDING.contains(target);
    }

    static MethodHandles.@Nullable Lookup findLookupForOldCode(Class<?> target) {
        if (PENDING.contains(target)) {
            MethodHandles.Lookup before = EARLIER.getOrDefault(target, new ArrayDeque<>()).peekFirst();
            if (before != null) {
                return before;
            }
        }
        return SIDECARS.get(target);
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
        MethodHandles.Lookup before = SIDECARS.put(target, side);
        SidecarCallSites.rebind(target, side);
        if (fieldHolder != null && startStatics) {
            FieldHolders.startStatics(target);
        }
        if (before != null) {
            Deque<MethodHandles.Lookup> earlier = EARLIER.computeIfAbsent(target, t -> new ConcurrentLinkedDeque<>());
            earlier.addFirst(before);
            while (earlier.size() > EARLIER_LIMIT) {
                earlier.removeLast();
            }
        }
        INSTANCE_METHODS.put(side.lookupClass(), Set.copyOf(instanceMethods));
        MOVED_BY_TARGET.put(target, Set.copyOf(moved));
        MovedSites.reset(MOVED, MOVED_BY_TARGET.values());
        String internal = target.getName().replace('.', '/');
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
        return mixinOwner.apply(mixin.replace('.', '/'));
    }

    public static void onReached(String member, String mixin) {
        if (REACHED.add(member)) {
            LOGGER.info("[Hotwire] {} belongs to a mod that is off and was reached; its old body ran. The caller"
                    + " holds something of that mod, or was already running when the mod went off.", member);
        }
        String mod = mixin.isEmpty() ? null : mixinOwner.apply(mixin.replace('.', '/'));
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
        EARLIER.remove(target);
        MethodHandles.Lookup side = SIDECARS.remove(target);
        if (side != null) {
            INSTANCE_METHODS.remove(side.lookupClass());
        }
        MOVED_BY_TARGET.remove(target);
        MovedSites.reset(MOVED, MOVED_BY_TARGET.values());
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
        return Map.copyOf(MOVED_BY_TARGET);
    }

    static Set<String> getMovedOf(String internalName) {
        for (Map.Entry<Class<?>, Set<String>> entry : MOVED_BY_TARGET.entrySet()) {
            if (entry.getKey().getName().replace('.', '/').equals(internalName)) {
                return entry.getValue();
            }
        }
        return Set.of();
    }

    public static CallSite bootstrapMoved(MethodHandles.Lookup caller, String name, MethodType type, String owner,
            String method, String descriptor, int isStatic) throws ReflectiveOperationException {
        if (isStatic != 0) {
            Class<?> at = Class.forName(owner.replace('.', '/').replace('/', '.'), false,
                    caller.lookupClass().getClassLoader());
            MethodType wanted = MethodType.fromMethodDescriptorString(descriptor, at.getClassLoader());
            for (; at != null; at = at.getSuperclass()) {
                MethodHandles.Lookup side = SIDECARS.get(at);
                if (side != null && MOVED_BY_TARGET.getOrDefault(at, Set.of()).contains(method + descriptor)) {
                    return new ConstantCallSite(MethodHandles.explicitCastArguments(
                            side.findStatic(side.lookupClass(), method, wanted), type));
                }
            }
            Class<?> real = Class.forName(owner.replace('/', '.'), false, caller.lookupClass().getClassLoader());
            return new ConstantCallSite(MethodHandles.explicitCastArguments(MethodHandles.privateLookupIn(real,
                    MethodHandles.lookup()).findStatic(real, method, wanted), type));
        }
        MethodHandle own = MovedSites.findOwnFinal(caller, owner, method, descriptor);
        if (own != null) {
            return new ConstantCallSite(MethodHandles.explicitCastArguments(own, type));
        }
        return MovedSites.make(type, method, descriptor, MovedSites.findPlain(caller, owner, method, descriptor));
    }

    static MethodHandle findMoved(String method, String descriptor, Class<?> receiver) throws Throwable {
        return findMoved(method, descriptor, receiver, null);
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

    private static MethodHandle resolveMoved(String method, String descriptor, Class<?> receiver,
            @Nullable MethodHandle plain) throws ReflectiveOperationException {
        MethodType type = MethodType.fromMethodDescriptorString(descriptor, receiver.getClassLoader());
        for (Class<?> at = receiver; at != null; at = at.getSuperclass()) {
            MethodHandles.Lookup side = SIDECARS.get(at);
            if (side != null && MOVED_BY_TARGET.getOrDefault(at, Set.of()).contains(method + descriptor)) {
                return side.findStatic(side.lookupClass(), method, type.insertParameterTypes(0, at));
            }
        }
        MethodHandle gained = LinkerLookups.findGainedDefault(method, type, receiver);
        if (gained != null) {
            return gained;
        }
        if (plain != null) {
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

    public static CallSite bootstrapIsInstance(MethodHandles.Lookup caller, String name, MethodType type, String duck)
            throws ReflectiveOperationException {
        MethodHandle test = MethodHandles.lookup().findStatic(Linker.class, "carries",
                MethodType.methodType(boolean.class, String.class, Object.class));
        return new ConstantCallSite(MethodHandles.insertArguments(test, 0, duck).asType(type));
    }

    public static CallSite bootstrapCast(MethodHandles.Lookup caller, String name, MethodType type, String duck)
            throws ReflectiveOperationException {
        MethodHandle cast = MethodHandles.lookup().findStatic(Linker.class, "castTo",
                MethodType.methodType(Object.class, String.class, Object.class));
        MethodHandle same = MethodHandles.explicitCastArguments(MethodHandles.identity(Object.class), type);
        return DuckCalls.makeSite(MethodHandles.insertArguments(cast, 0, duck).asType(type),
                kind -> DuckCalls.carries(duck, kind) ? same : null);
    }

    public static CallSite bootstrapDuck(MethodHandles.Lookup caller, String name, MethodType type, String duck,
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
            Set<String> moved, Set<String> ducks, FieldHolders.@Nullable Holder holder) {
    }

    static Snapshot snapshot(Class<?> target) {
        MethodHandles.Lookup side = SIDECARS.get(target);
        Set<String> ducks = new HashSet<>();
        DUCKS.forEach((duck, containers) -> {
            if (containers.contains(target)) {
                ducks.add(duck);
            }
        });
        return new Snapshot(target, side, side == null ? Set.of() : INSTANCE_METHODS.getOrDefault(side.lookupClass(),
                Set.of()), MOVED_BY_TARGET.getOrDefault(target, Set.of()), ducks, FieldHolders.snapshot(target));
    }

    static void restore(Snapshot snapshot) {
        Class<?> target = snapshot.target();
        MethodHandles.Lookup now = SIDECARS.get(target);
        if (now != null) {
            INSTANCE_METHODS.remove(now.lookupClass());
        }
        if (snapshot.sidecar() == null) {
            SIDECARS.remove(target);
            MOVED_BY_TARGET.remove(target);
        } else {
            SIDECARS.put(target, snapshot.sidecar());
            INSTANCE_METHODS.put(snapshot.sidecar().lookupClass(), snapshot.instanceMethods());
            MOVED_BY_TARGET.put(target, snapshot.moved());

            SidecarCallSites.rebind(target, snapshot.sidecar());
        }
        setDucksOf(target, snapshot.ducks());
        MovedSites.reset(MOVED, MOVED_BY_TARGET.values());
        DuckCalls.forget();
        FieldHolders.restore(target, snapshot.holder());
        FieldCallSites.rebind(target);
    }

    static Set<Class<?>> getContainers(String duck) {
        return DUCKS.getOrDefault(duck, Set.of());
    }

    static MethodHandles.@Nullable Lookup findCurrentLookup(Class<?> target) {
        return SIDECARS.get(target);
    }

    public static boolean isInstalled(Class<?> target) {
        return SIDECARS.containsKey(target);
    }

    public static synchronized void open(WidenerUses.Openings more) {
        openings = openings.with(more);
    }

    public static WidenerUses.Openings getOpenings() {
        return openings;
    }

    public static void adopt(String internalName) {
        ADOPTED.add(internalName);
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

    public static Set<String> getAdopted() {
        return Set.copyOf(ADOPTED);
    }

    public static CallSite bootstrapMethod(MethodHandles.Lookup caller, String name, MethodType type)
            throws ReflectiveOperationException {
        return SidecarCallSites.bind(getHostOf(caller.lookupClass()),
                LinkerLookups.findInSidecars(caller.lookupClass(), name, type),
                name, type);
    }

    public static CallSite bootstrapSuperCall(MethodHandles.Lookup caller, String name, MethodType type, Class<?> owner)
            throws ReflectiveOperationException {
        return SidecarCallSites.bindSuper(getHostOf(caller.lookupClass()), name, type, owner);
    }

    public static CallSite bootstrapInherited(MethodHandles.Lookup caller, String name, MethodType type, Class<?> owner,
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

    public static CallSite bootstrapField(MethodHandles.Lookup caller, String name, MethodType type, int kind,
            String descriptor) throws ReflectiveOperationException {
        return bindField(getHostOf(caller.lookupClass()), name, type, kind);
    }

    public static CallSite bootstrapForeignField(MethodHandles.Lookup caller, String name, MethodType type,
            Class<?> owner, int kind) throws ReflectiveOperationException {
        return bindField(owner, name, type, kind);
    }

    private static CallSite bindField(Class<?> target, String name, MethodType type, int kind)
            throws ReflectiveOperationException {
        return kind == PUT_FINAL ? new ConstantCallSite(findCastFieldHandle(target, name, type, kind))
                : FieldCallSites.bind(target, name, type, kind);
    }

    static MethodHandle findCastFieldHandle(Class<?> target, String name, MethodType type, int kind)
            throws ReflectiveOperationException {
        return MethodHandles.explicitCastArguments(LinkerLookups.findFieldHandle(target, name, type, kind), type);
    }

    public static CallSite bootstrapDuckLambda(MethodHandles.Lookup caller, String name, MethodType factory,
            MethodType erased, MethodHandle target, MethodType instantiated) throws ReflectiveOperationException {
        MethodHandleInfo info = caller.revealDirect(target);
        String owner = info.getDeclaringClass().getName().replace('.', '/');
        MethodHandle body = target;
        if (info.getReferenceKind() == MethodHandleInfo.REF_invokeInterface && EVER_DUCKS.contains(owner)) {
            MethodType shape = info.getMethodType();
            body = DuckCalls.bind(owner, info.getName(), shape.toMethodDescriptorString())
                    .asCollector(Object[].class, shape.parameterCount());
        }
        return Lambdas.spin(caller, name, factory, erased, DuckCalls.loosen(body));
    }

    public static CallSite bootstrapMovedLambda(MethodHandles.Lookup caller, String name, MethodType factory,
            MethodType erased, String owner, String method, String descriptor, int isStatic)
            throws ReflectiveOperationException {
        MethodType shape = MethodType.fromMethodDescriptorString(descriptor, caller.lookupClass().getClassLoader());
        MethodHandle body = isStatic != 0
                ? bootstrapMoved(caller, method, shape, owner, method, descriptor, 1).dynamicInvoker()
                : bootstrapMoved(caller, method, shape.insertParameterTypes(0, Object.class), owner, method, descriptor,
                        0).dynamicInvoker();
        return Lambdas.spin(caller, name, factory, erased, DuckCalls.loosen(body));
    }

    public static CallSite bootstrapLambda(MethodHandles.Lookup caller, String name, MethodType type, MethodType erased,
            String implName, String implDescriptor, MethodType instantiated) throws ReflectiveOperationException {
        MethodHandles.Lookup side = getSidecarOf(caller.lookupClass());
        MethodType implType = MethodType.fromMethodDescriptorString(implDescriptor,
                side.lookupClass().getClassLoader());
        MethodHandle impl = LinkerLookups.findInSidecars(caller.lookupClass(), implName, implType);
        return Lambdas.spin(side, name, type, erased, impl);
    }

    static MethodHandles.Lookup getSidecarOf(Class<?> caller) {
        MethodHandles.Lookup side = SIDECARS.get(getHostOf(caller));
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

    static MethodHandles.@Nullable Lookup findSidecar(Class<?> target) {
        return SIDECARS.get(target);
    }

    static boolean standsForInstanceMethod(Class<?> sidecar, String nameAndDescriptor) {
        return INSTANCE_METHODS.getOrDefault(sidecar, Set.of()).contains(nameAndDescriptor);
    }
}
